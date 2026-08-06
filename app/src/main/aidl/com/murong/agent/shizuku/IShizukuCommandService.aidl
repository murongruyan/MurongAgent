package com.murong.agent.shizuku;

import android.os.ParcelFileDescriptor;
import com.murong.agent.shizuku.IRootAgentDisplayClient;

interface IShizukuCommandService {
    String execute(String command, int timeoutSeconds);
    int remoteUid();
    void registerAgentDisplayClient(IRootAgentDisplayClient client);
    int ensureAgentDisplay(int width, int height, int densityDpi);
    int currentAgentDisplayId();
    String currentAgentDisplayPackageName(int displayId);
    boolean launchPackageOnAgentDisplay(String packageName, int displayId);
    boolean launchViewUriOnAgentDisplay(String packageName, String uri, int displayId);
    boolean launchShareTextOnAgentDisplay(String packageName, String text, int displayId);
    byte[] captureAgentDisplayJpeg(int displayId, int maxEdge, int quality);
    ParcelFileDescriptor captureAgentDisplayJpegFile(int displayId, int maxEdge, int quality);
    boolean tapAgentDisplay(int displayId, int x, int y);
    boolean swipeAgentDisplay(int displayId, int x1, int y1, int x2, int y2, int durationMs);
    boolean keyAgentDisplay(int displayId, String keyCode);
    boolean typeAgentDisplay(int displayId, String text);
    boolean handoffAgentDisplayToMain(int displayId);
    void releaseAgentDisplay();
    void destroy();
}
