package com.murong.agent.ui.assistant

import java.util.Calendar
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AssistantLocalActionsTest {
    @Test
    fun `duration parser handles mixed chinese units`() {
        assertEquals(5 * 60, parseDurationSeconds("五分钟后提醒我"))
        assertEquals(3_660, parseDurationSeconds("计时一小时一分钟"))
        assertEquals(30, parseDurationSeconds("倒计时30秒"))
        assertNull(parseDurationSeconds("计时多久"))
    }

    @Test
    fun `alarm parser handles chinese periods and half hour`() {
        val now = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 1)
            set(Calendar.MINUTE, 0)
        }

        assertEquals(7 to 30, parseAlarmTime("明早七点半叫醒我", now))
        assertEquals(19 to 5, parseAlarmTime("设置晚上7点05分闹钟", now))
        assertEquals(12 to 0, parseAlarmTime("中午十二点的闹钟", now))
    }

    @Test
    fun `calendar parser keeps schedule out of the alarm path`() {
        val now = ZonedDateTime.of(
            2026,
            7,
            27,
            7,
            0,
            0,
            0,
            ZoneId.of("Asia/Shanghai"),
        )

        val draft = assertNotNull(
            parseCalendarEvent("设置一个日程，在两天后更新调度", now),
        )

        assertEquals("更新调度", draft.title)
        assertTrue(draft.allDay)
        assertEquals("2026年7月29日全天", draft.displayTime)
        assertEquals(24 * 60 * 60 * 1_000L, draft.endMillis - draft.beginMillis)
    }

    @Test
    fun `calendar parser supports a timed event`() {
        val now = ZonedDateTime.of(
            2026,
            7,
            27,
            7,
            0,
            0,
            0,
            ZoneId.of("Asia/Shanghai"),
        )

        val draft = assertNotNull(parseCalendarEvent("明天上午九点开会", now))

        assertEquals("开会", draft.title)
        assertFalse(draft.allDay)
        assertEquals("7月28日 09:00", draft.displayTime)
        assertEquals(60 * 60 * 1_000L, draft.endMillis - draft.beginMillis)
    }

    @Test
    fun `timekeeping command parser distinguishes controls before start`() {
        assertEquals(TimekeepingCommand.START, parseTimekeepingCommand("开启五分钟倒计时"))
        assertEquals(TimekeepingCommand.PAUSE, parseTimekeepingCommand("暂停倒计时"))
        assertEquals(TimekeepingCommand.RESUME, parseTimekeepingCommand("继续倒计时"))
        assertEquals(TimekeepingCommand.STOP, parseTimekeepingCommand("关闭倒计时"))
        assertEquals(TimekeepingCommand.RESET, parseTimekeepingCommand("秒表清零"))
        assertEquals(TimekeepingCommand.STATUS, parseTimekeepingCommand("秒表走了多久"))
    }

    @Test
    fun `stopwatch duration formatting keeps tenths`() {
        assertEquals("00:00.0", formatStopwatchDuration(0L))
        assertEquals("01:02.3", formatStopwatchDuration(62_345L))
        assertEquals("1:01:01.2", formatStopwatchDuration(3_661_234L))
    }
}
