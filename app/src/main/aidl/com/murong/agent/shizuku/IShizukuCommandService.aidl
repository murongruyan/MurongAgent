package com.murong.agent.shizuku;

interface IShizukuCommandService {
    String execute(String command, int timeoutSeconds);
    int remoteUid();
    void destroy();
}
