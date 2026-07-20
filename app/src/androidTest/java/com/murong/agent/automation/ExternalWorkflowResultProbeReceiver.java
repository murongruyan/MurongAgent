package com.murong.agent.automation;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Test-only callback sink. The host reads this file with run-as and then uninstalls the test APK. */
public final class ExternalWorkflowResultProbeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!"com.murong.agent.action.SAVED_WORKFLOW_RESULT".equals(intent.getAction())) return;
        String value = safe(intent.getStringExtra("status")) + "|"
                + safe(intent.getStringExtra("request_id")) + "|"
                + safe(intent.getStringExtra("workflow_id")) + "|"
                + safe(intent.getStringExtra("message"));
        File output = new File(context.getFilesDir(), "external_workflow_result.txt");
        try (FileOutputStream stream = new FileOutputStream(output, false)) {
            stream.write(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // The host treats a missing file as a failed acceptance test.
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ');
    }
}
