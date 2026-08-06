package com.murong.agent.ui.assistant

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Base64
import android.util.Log
import com.murong.agent.core.tool.AndroidGuiAccessibilityService
import com.murong.agent.shizuku.ShizukuPhoneAgentIsolatedDisplaySession
import com.murong.agent.shizuku.ShizukuSystemAccess
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** ADB-only test entry point. This class and its exported receiver do not exist in release APKs. */
class PhoneAgentDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val task = intent.getStringExtra(EXTRA_TASK)?.trim().orEmpty()
        val inputText = intent.getStringExtra(EXTRA_INPUT_TEXT).orEmpty()
        val inputDisplay = intent.getIntExtra(EXTRA_INPUT_DISPLAY, 0)
        val inputX = intent.getIntExtra(EXTRA_INPUT_X, -1)
        val inputY = intent.getIntExtra(EXTRA_INPUT_Y, -1)
        val probePackage = intent.getStringExtra(EXTRA_PROBE_PACKAGE)?.trim().orEmpty()
        val inspectDisplay = intent.getIntExtra(EXTRA_INSPECT_DISPLAY, -1)
        val keepAliveMillis = intent.getLongExtra(EXTRA_KEEP_ALIVE_MILLIS, DEFAULT_KEEP_ALIVE_MILLIS)
        when {
            intent.hasExtra(EXTRA_INPUT_TEXT) -> inputUnicodeText(context, inputDisplay, inputText)
            inputX >= 0 && inputY >= 0 -> tapDisplay(inputDisplay, inputX, inputY)
            inspectDisplay >= 0 -> inspectDisplay(context, inspectDisplay)
            probePackage.isNotBlank() -> probeIsolatedDisplay(context, probePackage, keepAliveMillis)
            task.isBlank() -> Log.e(TAG, "Missing task/probe_package/inspect_display")
            else -> runAssistantTask(context, task)
        }
    }

    private fun tapDisplay(displayId: Int, x: Int, y: Int) {
        runCatching {
            val service = ShizukuSystemAccess.agentDisplayService()
                ?: error("Root/Shizuku Agent Display service is unavailable")
            check(service.tapAgentDisplay(displayId, x, y)) {
                "Unable to tap display $displayId at $x,$y"
            }
            Log.i(TAG, "DISPLAY_TAP_OK display=$displayId point=$x,$y")
        }.onFailure { error ->
            Log.e(TAG, "DISPLAY_TAP_FAILED display=$displayId point=$x,$y ${error.message}", error)
        }
    }

    private fun inputUnicodeText(context: Context, displayId: Int, text: String) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                val service = ShizukuSystemAccess.agentDisplayService()
                    ?: error("Root/Shizuku Agent Display service is unavailable")
                val accepted = if (displayId == 0) {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("Murong ADB debug input", text))
                    AndroidGuiAccessibilityService.connectedInstance()
                        ?.setFocusedText(text, displayId = 0)
                        ?.takeIf { it }
                        ?: run {
                            service.execute("input keyevent KEYCODE_PASTE", 5).trim().let { output ->
                                output.isBlank() || !output.startsWith("Error", ignoreCase = true)
                            }
                        }
                } else {
                    service.typeAgentDisplay(displayId, text)
                }
                check(accepted) {
                    "Unable to input Unicode text on display $displayId"
                }
                Log.i(TAG, "UNICODE_INPUT_OK display=$displayId chars=${text.length}")
            }.onFailure { error ->
                Log.e(TAG, "UNICODE_INPUT_FAILED display=$displayId ${error.message}", error)
            }
            pending.finish()
        }
    }

    private fun inspectDisplay(context: Context, displayId: Int) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val output = File(context.externalCacheDir ?: context.cacheDir, INSPECT_SCREENSHOT)
            // A failed capture must never leave a previous display's frame available to an ADB
            // test (or a human tester) as if it were the newly requested screenshot.
            runCatching { output.delete() }
            runCatching {
                val observation = AndroidGuiAccessibilityService.connectedInstance()
                    ?.observe(maxNodes = 180, includeText = true, displayId = displayId)
                val semantics = observation?.nodes.orEmpty().joinToString("\n") { node ->
                    "${node.role} text=${node.text.orEmpty()} desc=${node.contentDescription.orEmpty()} " +
                        "res=${node.resourceId.orEmpty()} bounds=${node.bounds} " +
                        "click=${node.clickable} edit=${node.editable} visible=${node.visible}"
                }
                Log.i(
                    TAG,
                    "DISPLAY_SEMANTICS id=$displayId success=${observation?.success} " +
                        "app=${observation?.application} nodes=${observation?.nodes?.size ?: 0} " +
                        "error=${observation?.error.orEmpty()}\n$semantics",
                )

                val bytes = ShizukuSystemAccess.agentDisplayService()
                    ?.captureAgentDisplayJpeg(displayId, 1_000, 72)
                    ?: ByteArray(0)
                check(bytes.isNotEmpty()) { "Display capture was empty" }
                output.writeBytes(bytes)
                Log.i(TAG, "DISPLAY_CAPTURE id=$displayId bytes=${bytes.size} path=${output.absolutePath}")
            }.onFailure { error ->
                Log.e(TAG, "DISPLAY_INSPECT_FAILED id=$displayId ${error.message}", error)
            }
            pending.finish()
        }
    }

    private fun runAssistantTask(context: Context, task: String) {
        val route = AssistantRequestRouter.classify(task)
        if (route.kind == AssistantTaskKind.INSTANT_LOCAL) {
            val result = AssistantLocalActions.execute(context.applicationContext, task)
            Log.i(TAG, "LOCAL_RESULT task=$task result=${result.orEmpty()}")
            return
        }
        if (!route.runWithNotification) {
            Log.e(TAG, "Unsupported debug route ${route.kind} for task=$task")
            return
        }
        AssistantTaskForegroundService.enqueue(context, task, route.kind)
        Log.i(TAG, "ENQUEUED kind=${route.kind} task=$task")
    }

    private fun probeIsolatedDisplay(
        context: Context,
        packageName: String,
        keepAliveMillis: Long,
    ) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val session = ShizukuPhoneAgentIsolatedDisplaySession(context)
            runCatching {
                check(session.isAvailable()) { "Root/Shizuku Agent Display is unavailable" }
                val info = session.start()
                check(session.launchPackage(packageName)) { "Unable to launch $packageName" }
                delay(2_000)
                val screenshot = session.captureScreenshot()
                val bytes = Base64.decode(screenshot.base64Data, Base64.DEFAULT)
                File(context.cacheDir, PROBE_SCREENSHOT).writeBytes(bytes)
                val hash = MessageDigest.getInstance("SHA-256")
                    .digest(bytes)
                    .take(8)
                    .joinToString("") { "%02x".format(it) }
                Log.i(
                    TAG,
                    "DISPLAY_PROBE_OK id=${info.displayId} display=${info.width}x${info.height} " +
                        "capture=${screenshot.width}x${screenshot.height} bytes=${bytes.size} sha256=$hash",
                )
                delay(keepAliveMillis.coerceIn(0L, MAX_KEEP_ALIVE_MILLIS))
            }.onFailure { error ->
                Log.e(TAG, "DISPLAY_PROBE_FAILED ${error.javaClass.simpleName}: ${error.message}", error)
            }
            runCatching { session.close() }
            pending.finish()
        }
    }

    private companion object {
        const val TAG = "MurongPhoneDebug"
        const val EXTRA_TASK = "task"
        const val EXTRA_INPUT_TEXT = "input_text"
        const val EXTRA_INPUT_DISPLAY = "input_display"
        const val EXTRA_INPUT_X = "input_x"
        const val EXTRA_INPUT_Y = "input_y"
        const val EXTRA_PROBE_PACKAGE = "probe_package"
        const val EXTRA_INSPECT_DISPLAY = "inspect_display"
        const val EXTRA_KEEP_ALIVE_MILLIS = "keep_alive_ms"
        const val PROBE_SCREENSHOT = "isolated-display-probe.jpg"
        const val INSPECT_SCREENSHOT = "isolated-display-inspect.jpg"
        const val DEFAULT_KEEP_ALIVE_MILLIS = 3_000L
        const val MAX_KEEP_ALIVE_MILLIS = 120_000L
    }
}
