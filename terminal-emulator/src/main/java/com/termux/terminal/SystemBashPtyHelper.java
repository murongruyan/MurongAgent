package com.termux.terminal;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

/**
 * Standalone helper launched through app_process from a root shell. It starts the exact executable,
 * argv and environment selected by the app, then sends the PTY master fd back over a local socket.
 */
public final class SystemBashPtyHelper {
    private static final String LOG_TAG = "MurongSystemBashHelperProc";

    private SystemBashPtyHelper() {
    }

    public static void main(String[] args) throws Exception {
        try {
            if (args.length < 9) {
                throw new IllegalArgumentException(
                    "Expected 9 args: socket cwd executable argv environment rows cols cellW cellH"
                );
            }

            String socketName = args[0];
            String cwd = decodeArg(args[1]);
            String executable = decodeArg(args[2]);
            String[] processArgs = decodeList(args[3]);
            String[] env = decodeList(args[4]);
            int rows = parseInt(args[5], 24);
            int cols = parseInt(args[6], 80);
            int cellWidth = parseInt(args[7], 1);
            int cellHeight = parseInt(args[8], 1);
            if (executable.isEmpty()) {
                throw new IllegalArgumentException("Executable must not be empty");
            }
            if (processArgs.length == 0) {
                processArgs = new String[] { executable };
            }

            int[] processId = new int[1];
            int terminalFd = JNI.createSubprocess(
                executable,
                cwd,
                processArgs,
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

    private static String decodeArg(String value) {
        if (value == null || value.isEmpty() || "-".equals(value)) {
            return "";
        }
        return new String(Base64.decode(value, Base64.NO_WRAP), StandardCharsets.UTF_8);
    }

    private static String[] decodeList(String value) throws IOException {
        if (value == null || value.isEmpty() || "-".equals(value)) {
            return new String[0];
        }
        byte[] payload = Base64.decode(value, Base64.NO_WRAP);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            int count = input.readInt();
            if (count < 0 || count > 16_384) {
                throw new IOException("Invalid encoded list size: " + count);
            }
            String[] result = new String[count];
            for (int index = 0; index < count; index++) {
                int byteCount = input.readInt();
                if (byteCount < 0 || byteCount > 4 * 1024 * 1024) {
                    throw new IOException("Invalid encoded item size: " + byteCount);
                }
                byte[] bytes = new byte[byteCount];
                input.readFully(bytes);
                result[index] = new String(bytes, StandardCharsets.UTF_8);
            }
            return result;
        }
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
