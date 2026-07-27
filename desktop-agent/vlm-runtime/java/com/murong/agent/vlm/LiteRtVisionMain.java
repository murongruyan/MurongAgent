package com.murong.agent.vlm;

import com.google.ai.edge.litertlm.Backend;
import com.google.ai.edge.litertlm.Content;
import com.google.ai.edge.litertlm.Contents;
import com.google.ai.edge.litertlm.Conversation;
import com.google.ai.edge.litertlm.ConversationConfig;
import com.google.ai.edge.litertlm.Engine;
import com.google.ai.edge.litertlm.EngineConfig;
import com.google.ai.edge.litertlm.Message;
import com.google.ai.edge.litertlm.MessageCallback;
import com.google.ai.edge.litertlm.SamplerConfig;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

public final class LiteRtVisionMain {
    private static final int PROTOCOL_MAGIC = 0x314C564D;
    private static final int MAX_PROMPT_BYTES = 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 128 * 1024 * 1024;

    private LiteRtVisionMain() {}

    public static void main(String[] args) {
        if (args.length != 2) {
            emit("MURONG_VLM_FATAL_BEGIN", "usage: LiteRtVisionMain <model.litertlm> <cache-dir>");
            System.exit(2);
        }
        Engine engine;
        try {
            engine = createEngine(args[0], args[1]);
        } catch (Throwable error) {
            emit("MURONG_VLM_FATAL_BEGIN", "LiteRT-LM initialization failed: " + message(error));
            return;
        }

        System.out.println("MURONG_VLM_READY");
        System.out.flush();
        try (engine; BufferedInputStream input = new BufferedInputStream(System.in)) {
            while (true) {
                Integer magic = readUint32(input);
                if (magic == null) {
                    return;
                }
                Integer promptSize = readUint32(input);
                Integer width = readUint32(input);
                Integer height = readUint32(input);
                Integer imageSize = readUint32(input);
                Integer maxTokens = readUint32(input);
                Integer enableThinking = readUint32(input);
                if (magic != PROTOCOL_MAGIC ||
                    promptSize == null || width == null || height == null ||
                    imageSize == null || maxTokens == null || enableThinking == null ||
                    promptSize < 0 || promptSize > MAX_PROMPT_BYTES ||
                    imageSize < 0 || imageSize > MAX_IMAGE_BYTES) {
                    emit("MURONG_VLM_FATAL_BEGIN", "invalid binary request");
                    return;
                }
                byte[] promptBytes = input.readNBytes(promptSize);
                byte[] imageBytes = input.readNBytes(imageSize);
                if (promptBytes.length != promptSize || imageBytes.length != imageSize) {
                    emit("MURONG_VLM_FATAL_BEGIN", "truncated binary request");
                    return;
                }
                try {
                    String prompt = new String(promptBytes, StandardCharsets.UTF_8);
                    String result = infer(engine, prompt, imageBytes, enableThinking != 0);
                    emit("MURONG_VLM_RESULT_BEGIN", result);
                } catch (Throwable error) {
                    emit("MURONG_VLM_ERROR_BEGIN", message(error));
                }
            }
        } catch (IOException error) {
            emit("MURONG_VLM_FATAL_BEGIN", message(error));
        }
    }

    private static Engine createEngine(String modelPath, String cacheDir) {
        Throwable lastError = null;
        Backend[][] choices = new Backend[][] {
            {new Backend.GPU(), new Backend.GPU()},
            {new Backend.CPU(), new Backend.CPU()}
        };
        for (Backend[] choice : choices) {
            Engine engine = new Engine(
                new EngineConfig(
                    modelPath,
                    choice[0],
                    choice[1],
                    new Backend.CPU(),
                    4096,
                    1,
                    cacheDir
                )
            );
            try {
                engine.initialize();
                return engine;
            } catch (Throwable error) {
                lastError = error;
                engine.close();
            }
        }
        throw new IllegalStateException(
            "GPU and CPU backends both failed",
            lastError
        );
    }

    private static String infer(
        Engine engine,
        String prompt,
        byte[] imageBytes,
        boolean enableThinking
    ) {
        Map<String, Object> extraContext = new HashMap<>();
        extraContext.put("enable_thinking", enableThinking);
        ConversationConfig config = new ConversationConfig(
            Contents.Companion.of(
                "You are Murong's private on-device assistant. Follow the user's instructions " +
                    "carefully and keep all content on this device."
            ),
            Collections.emptyList(),
            Collections.emptyList(),
            new SamplerConfig(64, 0.95, 1.0, 7),
            false,
            null,
            extraContext,
            null
        );
        try (Conversation conversation = engine.createConversation(config)) {
            Contents contents = imageBytes.length == 0
                ? Contents.Companion.of(new Content.Text(prompt))
                : Contents.Companion.of(
                    new Content.ImageBytes(imageBytes),
                    new Content.Text(prompt)
                );
            StringBuilder output = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();
            CountDownLatch completed = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            conversation.sendMessageAsync(
                Message.Companion.user(contents),
                new MessageCallback() {
                    @Override
                    public void onMessage(Message message) {
                        StringBuilder candidate = new StringBuilder();
                        for (Content content : message.getContents().getContents()) {
                            if (content instanceof Content.Text) {
                                candidate.append(((Content.Text) content).getText());
                            }
                        }
                        String text = candidate.toString();
                        if (!text.isEmpty()) {
                            synchronized (output) {
                                String current = output.toString();
                                String delta = text.startsWith(current)
                                    ? text.substring(current.length())
                                    : text;
                                if (!delta.isEmpty()) {
                                    output.append(delta);
                                    emit("MURONG_VLM_CHUNK_BEGIN", delta);
                                }
                            }
                        }
                        StringBuilder thoughtCandidate = new StringBuilder();
                        for (Map.Entry<String, String> channel : message.getChannels().entrySet()) {
                            String name = channel.getKey().toLowerCase(Locale.ROOT);
                            if (name.contains("thought") || name.contains("thinking") ||
                                name.contains("reasoning") || name.contains("analysis")) {
                                thoughtCandidate.append(channel.getValue());
                            }
                        }
                        String thought = thoughtCandidate.toString();
                        if (!thought.isEmpty()) {
                            synchronized (reasoning) {
                                String current = reasoning.toString();
                                String delta = thought.startsWith(current)
                                    ? thought.substring(current.length())
                                    : thought;
                                if (!delta.isEmpty()) {
                                    reasoning.append(delta);
                                    emit("MURONG_VLM_REASONING_CHUNK_BEGIN", delta);
                                }
                            }
                        }
                    }

                    @Override
                    public void onDone() {
                        completed.countDown();
                    }

                    @Override
                    public void onError(Throwable error) {
                        failure.compareAndSet(null, error);
                        completed.countDown();
                    }
                },
                extraContext
            );
            try {
                completed.await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                conversation.cancelProcess();
                throw new IllegalStateException("LiteRT-LM inference interrupted", error);
            }
            Throwable error = failure.get();
            if (error != null) {
                throw new IllegalStateException("LiteRT-LM inference failed", error);
            }
            synchronized (output) {
                synchronized (reasoning) {
                    if (reasoning.length() == 0) {
                        return output.toString();
                    }
                    return "<think>" + reasoning + "</think>" + output;
                }
            }
        }
    }

    private static Integer readUint32(BufferedInputStream input) throws IOException {
        byte[] bytes = input.readNBytes(4);
        if (bytes.length == 0) {
            return null;
        }
        if (bytes.length != 4) {
            throw new IOException("truncated request header");
        }
        return (bytes[0] & 0xFF) |
            ((bytes[1] & 0xFF) << 8) |
            ((bytes[2] & 0xFF) << 16) |
            ((bytes[3] & 0xFF) << 24);
    }

    private static synchronized void emit(String marker, String payload) {
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        System.out.println(marker + " " + bytes.length);
        System.out.write(bytes, 0, bytes.length);
        System.out.println();
        System.out.println("MURONG_VLM_PAYLOAD_END");
        System.out.flush();
    }

    private static String message(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
            ? error.getClass().getSimpleName()
            : message;
    }
}
