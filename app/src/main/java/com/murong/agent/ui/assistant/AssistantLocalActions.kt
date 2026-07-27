package com.murong.agent.ui.assistant

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.murong.agent.common.shell.KeepShellPublic
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.Calendar

internal object AssistantLocalActions {
    fun isCalendarRequest(text: String): Boolean {
        val normalized = text.trim().lowercase()
        return CALENDAR_TERMS.any(normalized::contains)
    }

    fun execute(context: Context, text: String): String? {
        val normalized = text.trim().lowercase()
        if (normalized.removeIntentPunctuation() in GREETINGS) {
            return "你好，我在。"
        }
        if (isCalendarRequest(text)) {
            val draft = parseCalendarEvent(text)
                ?: return "请告诉我日程内容和日期，例如“两天后更新调度”或“明天上午 9 点开会”。"
            return createCalendarEvent(context, draft)
        }
        if (isDateTimeQuery(normalized)) {
            return formatCurrentDateTime()
        }
        if (
            "计时" in normalized ||
            "倒计时" in normalized ||
            "定时器" in normalized ||
            "分钟后" in normalized ||
            "小时后" in normalized ||
            "秒后" in normalized
        ) {
            val seconds = parseDurationSeconds(normalized)
                ?: return "请告诉我要计时多久，例如“计时 10 分钟”。"
            val launched = launchClockActivity(
                context,
                Intent(AlarmClock.ACTION_SET_TIMER)
                    .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, "Murong 语音计时")
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) || launchTimerWithRoot(seconds)
            return if (launched) {
                "好，已设置${formatDuration(seconds)}计时。"
            } else {
                CLOCK_REJECTED_MESSAGE
            }
        }
        if ("闹钟" in normalized || "叫醒我" in normalized || "提醒我起床" in normalized) {
            val time = parseAlarmTime(normalized)
                ?: return "请告诉我闹钟时间，例如“设置明早 7 点半的闹钟”。"
            val launched = launchClockActivity(
                context,
                Intent(AlarmClock.ACTION_SET_ALARM)
                    .putExtra(AlarmClock.EXTRA_HOUR, time.first)
                    .putExtra(AlarmClock.EXTRA_MINUTES, time.second)
                    .putExtra(AlarmClock.EXTRA_MESSAGE, "Murong 语音闹钟")
                    .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) || launchAlarmWithRoot(time.first, time.second)
            return if (launched) {
                "好，已设置 ${"%02d:%02d".format(time.first, time.second)} 的闹钟。"
            } else {
                CLOCK_REJECTED_MESSAGE
            }
        }
        return null
    }
}

internal data class CalendarEventDraft(
    val title: String,
    val beginMillis: Long,
    val endMillis: Long,
    val allDay: Boolean,
    val displayTime: String,
)

private fun launchClockActivity(context: Context, intent: Intent): Boolean =
    runCatching {
        context.startActivity(intent)
        true
    }.getOrDefault(false)

private fun launchTimerWithRoot(seconds: Int): Boolean =
    launchClockWithRoot(
        "am start -W -a android.intent.action.SET_TIMER " +
            "--ei android.intent.extra.alarm.LENGTH ${seconds.coerceIn(1, 86_400)} " +
            "--es android.intent.extra.alarm.MESSAGE 'Murong 语音计时' " +
            "--ez android.intent.extra.alarm.SKIP_UI true",
    )

private fun launchAlarmWithRoot(hour: Int, minute: Int): Boolean =
    launchClockWithRoot(
        "am start -W -a android.intent.action.SET_ALARM " +
            "--ei android.intent.extra.alarm.HOUR ${hour.coerceIn(0, 23)} " +
            "--ei android.intent.extra.alarm.MINUTES ${minute.coerceIn(0, 59)} " +
            "--es android.intent.extra.alarm.MESSAGE 'Murong 语音闹钟' " +
            "--ez android.intent.extra.alarm.SKIP_UI true",
    )

private fun launchClockWithRoot(command: String): Boolean {
    if (!KeepShellPublic.checkRoot()) return false
    val output = KeepShellPublic.doCmdSync(command)
    return listOf("permission denial", "error:", "exception")
        .none { marker -> output.contains(marker, ignoreCase = true) }
}

private fun createCalendarEvent(context: Context, draft: CalendarEventDraft): String {
    val permissionsGranted = listOf(
        Manifest.permission.READ_CALENDAR,
        Manifest.permission.WRITE_CALENDAR,
    ).all { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
    if (!permissionsGranted) {
        return "需要日历读写权限才能静默创建日程，请允许权限后重试。"
    }
    return runCatching {
        val calendarId = findWritableCalendarId(context)
            ?: return@runCatching "没有找到可写入的日历账户，请先在系统日历中启用一个日历。"
        val timezone = if (draft.allDay) "UTC" else ZoneId.systemDefault().id
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, draft.title)
            put(CalendarContract.Events.DTSTART, draft.beginMillis)
            put(CalendarContract.Events.DTEND, draft.endMillis)
            put(CalendarContract.Events.ALL_DAY, if (draft.allDay) 1 else 0)
            put(CalendarContract.Events.EVENT_TIMEZONE, timezone)
        }
        val inserted = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        if (inserted == null) {
            "系统日历拒绝了写入，请确认日历账户可用后重试。"
        } else {
            "已创建日程“${draft.title}”（${draft.displayTime}）。"
        }
    }.getOrElse { error ->
        when (error) {
            is SecurityException -> "日历权限已失效，请重新允许日历读写权限后再试。"
            else -> "创建日程失败：${error.message?.take(120) ?: "系统日历不可用"}"
        }
    }
}

private fun findWritableCalendarId(context: Context): Long? {
    val projection = arrayOf(
        CalendarContract.Calendars._ID,
        CalendarContract.Calendars.IS_PRIMARY,
        CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
        CalendarContract.Calendars.VISIBLE,
    )
    val candidates = mutableListOf<Pair<Long, Boolean>>()
    context.contentResolver.query(
        CalendarContract.Calendars.CONTENT_URI,
        projection,
        "${CalendarContract.Calendars.VISIBLE}=1 AND " +
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=?",
        arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()),
        null,
    )?.use { cursor ->
        val idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
        val primaryIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.IS_PRIMARY)
        while (cursor.moveToNext()) {
            candidates += cursor.getLong(idIndex) to (cursor.getInt(primaryIndex) == 1)
        }
    }
    return candidates.firstOrNull { it.second }?.first ?: candidates.firstOrNull()?.first
}

internal fun parseCalendarEvent(
    text: String,
    now: ZonedDateTime = ZonedDateTime.now(),
): CalendarEventDraft? {
    val normalized = replaceChineseNumbers(text.trim().lowercase())
    val targetDate = parseCalendarDate(normalized, now.toLocalDate()) ?: now.toLocalDate()
    val parsedTime = parseAlarmTime(normalized)
    val title = extractCalendarTitle(normalized)
        .ifBlank { return null }

    if (parsedTime == null) {
        // CalendarContract requires all-day boundaries to be UTC midnight, with an exclusive end.
        val begin = targetDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val end = targetDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return CalendarEventDraft(
            title = title,
            beginMillis = begin,
            endMillis = end,
            allDay = true,
            displayTime = "${targetDate.format(CHINESE_DATE_FORMAT)}全天",
        )
    }

    val zone = now.zone.takeUnless { it == ZoneOffset.UTC } ?: ZoneId.systemDefault()
    val begin = targetDate
        .atTime(parsedTime.first, parsedTime.second)
        .atZone(zone)
    return CalendarEventDraft(
        title = title,
        beginMillis = begin.toInstant().toEpochMilli(),
        endMillis = begin.plusHours(1).toInstant().toEpochMilli(),
        allDay = false,
        displayTime = begin.format(CHINESE_DATE_TIME_FORMAT),
    )
}

private fun parseCalendarDate(text: String, today: LocalDate): LocalDate? {
    val explicit = Regex("""(?:(\d{4})\s*年\s*)?(\d{1,2})\s*月\s*(\d{1,2})\s*[日号]""")
        .find(text)
    if (explicit != null) {
        val year = explicit.groupValues[1].toIntOrNull() ?: today.year
        val month = explicit.groupValues[2].toIntOrNull() ?: return null
        val day = explicit.groupValues[3].toIntOrNull() ?: return null
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }
    val offset = when {
        "大后天" in text -> 3L
        "后天" in text -> 2L
        "明天" in text || "明日" in text -> 1L
        "今天" in text || "今日" in text -> 0L
        else -> Regex("""(\d+)\s*天后""").find(text)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
    } ?: return null
    return today.plusDays(offset.coerceIn(0L, 3_650L))
}

private fun extractCalendarTitle(text: String): String {
    var result = text
    val removals = listOf(
        Regex("""(?:请|麻烦)?(?:帮我|给我)?(?:设置|创建|添加|安排|新建)"""),
        Regex("""(?:一个|1个|一条|1条)?(?:日历事件|日程|行程|事件)"""),
        Regex("""(?:在)?(?:大后天|后天|明天|明日|今天|今日)"""),
        Regex("""(?:在)?\d+\s*天后"""),
        Regex("""(?:(?:\d{4})\s*年\s*)?\d{1,2}\s*月\s*\d{1,2}\s*[日号]"""),
        Regex("""(?:早上|上午|中午|下午|晚上|今晚|明早)?\s*\d{1,2}\s*(?::|：|点|时)\s*\d{0,2}\s*(?:分|半)?"""),
    )
    removals.forEach { result = result.replace(it, " ") }
    return result
        .replace(Regex("""^[\s，,。；;：:的在]+|[\s，,。；;：:]+$"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

private fun isDateTimeQuery(text: String): Boolean =
    DATE_TIME_TERMS.any(text::contains)

private fun formatCurrentDateTime(now: ZonedDateTime = ZonedDateTime.now()): String {
    val weekday = when (now.dayOfWeek.value) {
        1 -> "星期一"
        2 -> "星期二"
        3 -> "星期三"
        4 -> "星期四"
        5 -> "星期五"
        6 -> "星期六"
        else -> "星期日"
    }
    return "现在是 ${now.format(CHINESE_DATE_FORMAT)} $weekday ${now.format(TIME_FORMAT)}。"
}

internal fun parseDurationSeconds(text: String): Int? {
    var totalSeconds = 0L
    val normalized = replaceChineseNumbers(text.lowercase())
    Regex("""(\d+)\s*(小时|钟头|分钟|分|秒)""")
        .findAll(normalized)
        .forEach { match ->
            val value = match.groupValues[1].toLongOrNull() ?: return@forEach
            totalSeconds += when (match.groupValues[2]) {
                "小时", "钟头" -> value * 3_600L
                "分钟", "分" -> value * 60L
                else -> value
            }
        }
    return totalSeconds
        .takeIf { it in 1..86_400L }
        ?.toInt()
}

internal fun parseAlarmTime(text: String, now: Calendar = Calendar.getInstance()): Pair<Int, Int>? {
    val normalized = replaceChineseNumbers(text.lowercase())
    val match = Regex("""(\d{1,2})(?:\s*[:：点时]\s*(\d{1,2})?\s*(分)?)?""")
        .findAll(normalized)
        .firstOrNull { candidate ->
            val prefix = normalized.substring(
                maxOf(0, candidate.range.first - 5),
                candidate.range.first,
            )
            candidate.value.contains(':') ||
                candidate.value.contains('：') ||
                candidate.value.contains('点') ||
                candidate.value.contains('时') ||
                listOf("早上", "上午", "中午", "下午", "晚上", "明早", "今晚")
                    .any(prefix::contains)
        }
        ?: return null
    var hour = match.groupValues[1].toIntOrNull() ?: return null
    var minute = match.groupValues[2].toIntOrNull() ?: if (
        normalized.substring(match.range.first).take(8).contains("半")
    ) {
        30
    } else {
        0
    }
    val nearby = normalized.substring(maxOf(0, match.range.first - 6), match.range.last + 1)
    if (listOf("下午", "晚上", "今晚").any(nearby::contains) && hour in 1..11) hour += 12
    if ("中午" in nearby && hour in 1..10) hour += 12
    if (hour !in 0..23 || minute !in 0..59) return null

    // A bare "7 点" means the next occurrence. The system AlarmClock app handles the day;
    // this calculation is only retained to keep parsing deterministic in tests.
    @Suppress("UNUSED_VARIABLE")
    val nextOccurrenceIsTomorrow =
        hour < now.get(Calendar.HOUR_OF_DAY) ||
            (hour == now.get(Calendar.HOUR_OF_DAY) && minute <= now.get(Calendar.MINUTE))
    return hour to minute
}

private fun replaceChineseNumbers(text: String): String =
    Regex("""[零〇一二两三四五六七八九十]{1,3}""").replace(text) { match ->
        chineseNumber(match.value)?.toString() ?: match.value
    }

private fun chineseNumber(value: String): Int? {
    val digits = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3,
        '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )
    if ('十' !in value) {
        return value.fold(0) { total, char ->
            total * 10 + (digits[char] ?: return null)
        }
    }
    val parts = value.split('十', limit = 2)
    val tens = parts[0].takeIf(String::isNotEmpty)?.lastOrNull()?.let(digits::get) ?: 1
    val ones = parts.getOrNull(1)?.takeIf(String::isNotEmpty)?.lastOrNull()?.let(digits::get) ?: 0
    return tens * 10 + ones
}

private fun formatDuration(seconds: Int): String = buildString {
    val hours = seconds / 3_600
    val minutes = seconds % 3_600 / 60
    val remainingSeconds = seconds % 60
    if (hours > 0) append(hours).append("小时")
    if (minutes > 0) append(minutes).append("分钟")
    if (remainingSeconds > 0) append(remainingSeconds).append("秒")
}

private fun String.removeIntentPunctuation(): String = filter(Char::isLetterOrDigit)

private val GREETINGS = setOf(
    "你好", "您好", "嗨", "hi", "hello", "哈喽", "在吗", "慕容你好", "你好慕容",
)

private val CALENDAR_TERMS = listOf(
    "日程", "日历事件", "添加事件", "创建事件", "新建事件", "安排会议", "行程",
)

private val DATE_TIME_TERMS = listOf(
    "几点了", "现在几点", "当前时间", "现在时间", "今天时间", "今天几号",
    "今天日期", "当前日期", "今天星期", "星期几", "日期和时间", "查时间",
    "查询时间", "时间是多少", "什么时间",
)

private val CHINESE_DATE_FORMAT =
    DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA)
private val CHINESE_DATE_TIME_FORMAT =
    DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA)
private val TIME_FORMAT =
    DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)

private const val CLOCK_REJECTED_MESSAGE =
    "系统闹钟没有接受请求，请确认系统闹钟已启用后再试。"
