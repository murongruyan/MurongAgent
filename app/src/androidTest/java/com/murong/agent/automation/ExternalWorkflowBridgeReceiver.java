package com.murong.agent.automation;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/** Test-only external app bridge. It is never packaged into Murong's production APK. */
public final class ExternalWorkflowBridgeReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.murong.agent.test.SEND_WORKFLOW";

    @Override
    public void onReceive(Context context, Intent source) {
        if (!ACTION.equals(source.getAction())) return;
        Intent target = new Intent("com.murong.agent.action.RUN_SAVED_WORKFLOW")
                .setComponent(new ComponentName(
                        "com.murong.agent",
                        "com.murong.agent.automation.ExternalSavedWorkflowReceiver"))
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND | Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .putExtra("workflow_id", source.getStringExtra("workflow_id"))
                .putExtra("access_token", source.getStringExtra("access_token"))
                .putExtra("request_id", source.getStringExtra("request_id"))
                .putExtra("callback_package", context.getPackageName());
        String projectPath = source.getStringExtra("project_path");
        if (projectPath != null) target.putExtra("project_path", projectPath);
        String taskText = source.getStringExtra("task_text");
        if (taskText != null) target.putExtra("task_text", taskText);
        context.sendBroadcast(target);
    }
}
