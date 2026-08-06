package com.murong.agent.core.tool

import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.inputmethod.EditorInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Headless, short-lived IME used only when a target app hides its editable node from
 * accessibility and Unicode cannot be injected by Android key events.
 *
 * Phone Agent enables/selects this service through an already-authorized Root session, commits
 * one exact string through the focused InputConnection, then restores the user's previous IME.
 */
class MurongAutomationInputMethodService : InputMethodService() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onEvaluateInputViewShown(): Boolean = false

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        Log.i(TAG, "Input connection package=${attribute?.packageName.orEmpty()}")
    }

    companion object {
        @Volatile
        private var instance: MurongAutomationInputMethodService? = null

        suspend fun commitExactText(
            text: String,
            expectedPackage: String,
        ): Boolean = withContext(Dispatchers.Main.immediate) {
            if (text.isEmpty()) return@withContext false
            val service = instance ?: return@withContext false
            val editorPackage = service.currentInputEditorInfo?.packageName.orEmpty()
            if (editorPackage != expectedPackage) return@withContext false
            val connection = service.currentInputConnection ?: return@withContext false
            val committed = runCatching {
                connection.performContextMenuAction(android.R.id.selectAll)
                connection.commitText(text, 1)
            }.getOrDefault(false)
            Log.i(TAG, "Commit package=$editorPackage chars=${text.length} accepted=$committed")
            committed
        }

        private const val TAG = "MurongAutomationIme"
    }
}
