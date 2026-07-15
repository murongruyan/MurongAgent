package com.termux.terminal;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Base64;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Standalone helper launched through app_process from a root shell.
 * It creates the PTY/session outside the current app process tree and sends the PTY master fd back
 * to the app through a local socket.
 */
public final class SystemBashPtyHelper {
    private static final String LOG_TAG = "MurongSystemBashHelperProc";
    private static final String SHELL_PATH = "/system/bin/bash";

    private SystemBashPtyHelper() {
    }

    public static void main(String[] args) throws Exception {
        try {
            if (args.length < 11) {
                throw new IllegalArgumentException("Expected 11 args: socket cwd path ld home rc tmp rows cols cellW cellH");
            }

            String socketName = args[0];
            String cwd = decodeArg(args[1]);
            String path = decodeArg(args[2]);
            String libraryPath = decodeArg(args[3]);
            String home = decodeArg(args[4]);
            String rcFilePath = decodeArg(args[5]);
            String tmpDir = decodeArg(args[6]);
            int rows = parseInt(args[7], 24);
            int cols = parseInt(args[8], 80);
            int cellWidth = parseInt(args[9], 1);
            int cellHeight = parseInt(args[10], 1);

            String[] shellArgs = (rcFilePath == null || rcFilePath.isEmpty())
                ? new String[] {
                    SHELL_PATH,
                    "--noprofile",
                    "--norc",
                    "--noediting"
                }
                : new String[] {
                    SHELL_PATH,
                    "--noprofile",
                    "--rcfile",
                    rcFilePath,
                    "-i"
                };
            String[] env = buildEnv(path, libraryPath, home, tmpDir);

            int[] processId = new int[1];
            int terminalFd = JNI.createSubprocess(
                SHELL_PATH,
                cwd,
                shellArgs,
                env,
                processId,
                rows,
                cols,
                cellWidth,
                cellHeight
            );

            LocalSocket socket = new LocalSocket();
            try {
                socket.connect(new LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT));
                OutputStream output = socket.getOutputStream();
                socket.setFileDescriptorsForSend(new FileDescriptor[] { wrapFileDescriptor(terminalFd) });
                output.write(("PID:" + processId[0] + "\n").getBytes(StandardCharsets.UTF_8));
                output.flush();
                socket.setFileDescriptorsForSend(null);

                int exitCode = JNI.waitFor(processId[0]);
                output.write(("EXIT:" + exitCode + "\n").getBytes(StandardCharsets.UTF_8));
                output.flush();
            } finally {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
                JNI.close(terminalFd);
            }
        } catch (Throwable error) {
            Log.e(LOG_TAG, "helper failed before socket handoff", error);
            throw error;
        }
    }

    private static String[] buildEnv(String path, String libraryPath, String home, String tmpDir) {
        List<String> values = new ArrayList<>();
        values.add("TERM=xterm-256color");
        values.add("HOME=" + home);
        values.add("TMPDIR=" + tmpDir);
        values.add("PATH=" + path);
        values.add("SHELL=" + SHELL_PATH);
        if (libraryPath != null && !libraryPath.isEmpty()) {
            values.add("LD_LIBRARY_PATH=" + libraryPath);
        }
        return values.toArray(new String[0]);
    }

    private static String decodeArg(String value) {
        if (value == null || value.isEmpty() || "-".equals(value)) {
            return "";
        }
        return new String(Base64.decode(value, Base64.NO_WRAP), StandardCharsets.UTF_8);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static FileDescriptor wrapFileDescriptor(int fileDescriptor) throws Exception {
        FileDescriptor result = new FileDescriptor();
        Field descriptorField;
        try {
            descriptorField = FileDescriptor.class.getDeclaredField("descriptor");
        } catch (NoSuchFieldException error) {
            descriptorField = FileDescriptor.class.getDeclaredField("fd");
        }
        descriptorField.setAccessible(true);
        descriptorField.set(result, fileDescriptor);
        return result;
    }
}
