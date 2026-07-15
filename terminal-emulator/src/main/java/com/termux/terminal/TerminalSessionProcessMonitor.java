package com.termux.terminal;

/**
 * Optional external process monitor used when the shell process is not a direct child of the
 * current app process.
 */
public interface TerminalSessionProcessMonitor {

    /**
     * Blocks until the monitored process exits and returns the same exit code semantics as JNI.waitFor():
     * >= 0 for normal exit status, < 0 for signal number negated.
     */
    int waitForProcessExit();

    /**
     * Releases any underlying transport resources.
     */
    void close();
}
