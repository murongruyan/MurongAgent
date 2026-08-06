package com.murong.agent.ui.assistant

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantInvocationTest {
    @Test
    fun `wake phrase matching ignores spaces punctuation and case`() {
        assertTrue(containsWakePhrase("慕容，慕容！", "慕容慕容"))
        assertTrue(containsWakePhrase("慕容慕蓉", "慕容慕容"))
        assertTrue(containsWakePhrase("Hey MURONG agent", "murong"))
        assertFalse(containsWakePhrase("慕容你好", "慕容慕容"))
        assertFalse(containsWakePhrase("a", "a"))
    }

    @Test
    fun `voice interaction metadata declares a real recognition service`() {
        val xml = File("src/main/res/xml/murong_voice_interaction_service.xml").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(xml.contains("android:recognitionService="))
        assertTrue(xml.contains("MurongSpeechRecognitionService"))
        assertTrue(manifest.contains("android.speech.RecognitionService"))
        assertTrue(manifest.contains("FOREGROUND_SERVICE_MICROPHONE"))
        assertTrue(manifest.contains("com.android.alarm.permission.SET_ALARM"))
        assertTrue(manifest.contains("android.permission.WRITE_CALENDAR"))
        assertTrue(manifest.contains("AssistantTaskForegroundService"))
        assertTrue(manifest.contains("BuiltinVisionModelDownloadService"))
        assertTrue(manifest.contains("FOREGROUND_SERVICE_DATA_SYNC"))
    }

    @Test
    fun `android 16 invocation effect is guarded for incomplete OEM frameworks`() {
        val source = File(
            "src/main/java/com/murong/agent/ui/assistant/MurongVoiceInteractionService.kt"
        ).readText()

        assertTrue(source.contains("getMethod("))
        assertTrue(source.contains("\"setInvocationEffectEnabled\""))
        assertFalse(source.contains("setInvocationEffectEnabled(true)"))
    }

    @Test
    fun `assistant overlay cannot reuse the normal Murong task`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        val source = File(
            "src/main/java/com/murong/agent/ui/assistant/MurongAssistActivity.kt"
        ).readText()
        val activityStart = manifest.indexOf(
            """android:name="com.murong.agent.ui.assistant.MurongAssistActivity""""
        )
        val activityTag = manifest.substring(
            manifest.lastIndexOf("<activity", activityStart),
            manifest.indexOf('>', activityStart) + 1,
        )
        val createIntent = source.substring(
            source.indexOf("fun createIntent"),
            source.indexOf("\n    }\n}", source.indexOf("fun createIntent")),
        )

        assertTrue(activityTag.contains("""android:taskAffinity="${'$'}{applicationId}.assistant""""))
        assertTrue(activityTag.contains("""android:launchMode="singleTask""""))
        assertTrue(activityTag.contains("""android:excludeFromRecents="true""""))
        assertTrue(createIntent.contains("Intent.FLAG_ACTIVITY_NEW_TASK"))
        assertTrue(createIntent.contains("Intent.FLAG_ACTIVITY_SINGLE_TOP"))
        assertTrue(createIntent.contains("Intent.FLAG_ACTIVITY_NO_ANIMATION"))
        assertFalse(createIntent.contains("Intent.FLAG_ACTIVITY_CLEAR_TOP"))
        assertFalse(source.contains("Intent(this, MainActivity::class.java)"))
        assertFalse(source.contains("fun expandToFullChat"))
    }

    @Test
    fun `assistant popup keeps transcript and full screen inside the overlay`() {
        val source = File(
            "src/main/java/com/murong/agent/ui/assistant/MurongAssistActivity.kt"
        ).readText()

        assertTrue(source.contains("mutableStateListOf<AssistantOverlayEntry>()"))
        assertTrue(source.contains("AssistantMessageBubble(entry)"))
        assertTrue(source.contains("OutlinedTextField("))
        assertTrue(source.contains("expanded = !expanded"))
        assertTrue(source.contains("HOLD_TO_TALK_MILLIS"))
        assertTrue(source.contains("暂停录音"))
        assertTrue(source.contains("conversationRunner.cancelCurrent()"))
    }

    @Test
    fun `assistant never exposes raw local action exceptions in the transcript`() {
        val runnerSource = File(
            "src/main/java/com/murong/agent/ui/assistant/AssistantConversationRunner.kt"
        ).readText()

        assertTrue(runnerSource.contains("系统指令执行失败，请重试。"))
        assertFalse(runnerSource.contains("error.message?.take(200)"))
    }

    @Test
    fun `simple app launch is deterministic but compound phone work uses phone agent`() {
        assertEquals("抖音", simpleLaunchAppLabel("打开抖音"))
        assertEquals("微信", simpleLaunchAppLabel("请打开微信应用"))
        assertNull(simpleLaunchAppLabel("打开微信给老爸发送你好"))
        assertEquals("微信", leadingLaunchAppLabel("打开微信给老爸发送你好"))
        assertEquals("QQ", simpleLaunchAppLabel("打开QQ"))
        assertNull(simpleLaunchAppLabel("打开设置查看当前电池电量并告诉我"))
        assertNull(leadingLaunchAppLabel("打开设置查看当前电池电量并告诉我"))
        assertNull(simpleLaunchAppLabel("打开一个不存在的应用"))
        assertNull(leadingLaunchAppLabel("打开一个不存在的应用后继续操作"))
        assertEquals("com.example.demo", simpleLaunchAppLabel("打开com.example.demo"))

        val serviceSource = File(
            "src/main/java/com/murong/agent/ui/assistant/AssistantTaskForegroundService.kt"
        ).readText()
        assertTrue(serviceSource.contains("conversationRunner.runPhoneAndAwait(taskText)"))
        assertTrue(serviceSource.contains("conversationRunner.runCodeAndAwait(modelInput)"))
        assertTrue(serviceSource.contains("showTaskProgressOverlay"))
    }

    @Test
    fun `phone cancellation releases a broker display even after session reference is lost`() {
        val runnerSource = File(
            "src/main/java/com/murong/agent/ui/assistant/AssistantConversationRunner.kt"
        ).readText()
        val serviceSource = File(
            "src/main/java/com/murong/agent/ui/assistant/AssistantTaskForegroundService.kt"
        ).readText()

        assertTrue(runnerSource.contains("releasePhoneDisplayAndBroker(activePhoneDisplay)"))
        assertTrue(runnerSource.contains("connectedAgentDisplayService()"))
        assertTrue(runnerSource.contains("service?.releaseAgentDisplay()"))
        assertTrue(serviceSource.contains("if (running) {"))
        assertTrue(serviceSource.contains("conversationRunner.cancelCurrent()"))
    }

    @Test
    fun `interactive voice waits for wake word recorder to release microphone`() {
        val wakeSource = File(
            "src/main/java/com/murong/agent/ui/assistant/VoiceWakeWordService.kt"
        ).readText()
        val controllerSource = File(
            "src/main/java/com/murong/agent/voice/VoiceChatController.kt"
        ).readText()

        assertTrue(wakeSource.contains("pauseAndAwaitMicrophoneRelease"))
        assertTrue(wakeSource.contains("acknowledgePause(requestId)"))
        assertTrue(controllerSource.contains("pauseAndAwaitMicrophoneRelease(appContext)"))
        assertTrue(controllerSource.contains("releaseWakeWordMicrophone()"))
        assertFalse(controllerSource.contains("这条回复没有可朗读的自然语言内容"))
    }
}
