package com.jts.gjcxfzksh.data.read;

import com.jts.gjcxfzksh.data.ModelProcessingPool;
import lombok.extern.slf4j.Slf4j;
import org.matsim.core.utils.io.IOUtils;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

@Slf4j
public final class FastEventReader {

    private static final int DEFAULT_BUFFER_BYTES = 4 * 1024 * 1024;
    private static final String PIGZ_THREADS_PROPERTY = "gjcxfzksh.events.pigz.threads";
    private static final String PIGZ_THREADS_ENV = "GJCXFZKSH_EVENTS_PIGZ_THREADS";
    private static final String PIGZ_ENABLED_PROPERTY = "gjcxfzksh.events.pigz.enabled";
    private static final String PIGZ_ENABLED_ENV = "GJCXFZKSH_EVENTS_PIGZ_ENABLED";
    private static final OutputStream DISCARD = OutputStream.nullOutputStream();

    private FastEventReader() {
    }

    public interface Handler {
        void handle(String eventType, double time, Attributes attributes) throws Exception;
    }

    public static void read(String eventsFile, Handler handler) throws Exception {
        long startedAt = System.currentTimeMillis();
        long eventCount = 0L;
        try (Source source = open(eventsFile)) {
            Attributes attributes = new Attributes();
            String line;
            long lineNumber = 0L;
            while ((line = source.reader.readLine()) != null) {
                lineNumber++;
                int tagStart = eventTagStart(line);
                if (tagStart < 0) {
                    continue;
                }
                attributes.reset(line, tagStart);
                String eventType = attributes.value("type");
                if (eventType == null) {
                    throw new IOException("events line " + lineNumber + " is missing type attribute");
                }
                String timeText = attributes.value("time");
                if (timeText == null) {
                    throw new IOException("events line " + lineNumber + " is missing time attribute");
                }
                double time;
                try {
                    time = Double.parseDouble(timeText);
                } catch (NumberFormatException e) {
                    throw new IOException("events line " + lineNumber + " has invalid time: " + timeText, e);
                }
                handler.handle(eventType, time, attributes);
                eventCount++;
            }
        }
        log.info("快速解析events完成: file={}, events={}, elapsed={}ms",
                eventsFile, eventCount, System.currentTimeMillis() - startedAt);
    }

    private static Source open(String eventsFile) throws IOException {
        Source pigz = tryOpenPigz(eventsFile);
        if (pigz != null) {
            return pigz;
        }
        return new Source(IOUtils.getBufferedReader(eventsFile), null, null);
    }

    private static Source tryOpenPigz(String eventsFile) {
        if (!pigzEnabled()) {
            return null;
        }
        if (eventsFile == null || !eventsFile.toLowerCase(Locale.ROOT).endsWith(".gz")) {
            return null;
        }
        Path path;
        try {
            path = Path.of(eventsFile);
        } catch (Exception e) {
            return null;
        }
        if (!Files.isRegularFile(path)) {
            return null;
        }

        int threads = pigzThreads();
        try {
            Process process = new ProcessBuilder(
                    "pigz",
                    "-dc",
                    "-p",
                    String.valueOf(threads),
                    path.toString()
            ).start();
            ErrorCollector errors = new ErrorCollector(process.getErrorStream());
            Thread errorThread = new Thread(errors, "events-pigz-stderr");
            errorThread.setDaemon(true);
            errorThread.start();
            log.info("使用pigz并行解压events: file={}, threads={}", eventsFile, threads);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8),
                    bufferBytes()
            );
            return new Source(reader, process, errorThread);
        } catch (IOException e) {
            log.info("pigz不可用，退回Java流式解压events: {}", e.getMessage());
            return null;
        }
    }

    private static int eventTagStart(String line) {
        int index = line.indexOf("<event");
        while (index >= 0) {
            int after = index + "<event".length();
            if (after >= line.length()) {
                return index;
            }
            char c = line.charAt(after);
            if (Character.isWhitespace(c) || c == '>' || c == '/') {
                return index;
            }
            index = line.indexOf("<event", after);
        }
        return -1;
    }

    private static int pigzThreads() {
        int fallback = Math.max(1, Math.min(16, ModelProcessingPool.parallelism()));
        return positiveIntSetting(PIGZ_THREADS_PROPERTY, PIGZ_THREADS_ENV, fallback);
    }

    private static boolean pigzEnabled() {
        String value = setting(PIGZ_ENABLED_PROPERTY, PIGZ_ENABLED_ENV);
        if (value == null || value.isBlank()) {
            return true;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return !normalized.equals("0") && !normalized.equals("false") && !normalized.equals("no");
    }

    private static int bufferBytes() {
        return positiveIntSetting("gjcxfzksh.events.reader.buffer.bytes",
                "GJCXFZKSH_EVENTS_READER_BUFFER_BYTES", DEFAULT_BUFFER_BYTES);
    }

    private static int positiveIntSetting(String property, String env, int fallback) {
        String value = setting(property, env);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Math.max(1, Integer.parseInt(value.trim()));
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String setting(String property, String env) {
        String value = System.getProperty(property);
        if (value != null) {
            return value;
        }
        return System.getenv(env);
    }

    public static final class Attributes {
        private String line;
        private int tagStart;

        private void reset(String line, int tagStart) {
            this.line = line;
            this.tagStart = tagStart;
        }

        public String value(String name) {
            if (line == null || name == null || name.isBlank()) {
                return null;
            }
            int index = tagStart;
            while ((index = line.indexOf(name, index)) >= 0) {
                if (!isAttributeNameAt(index, name)) {
                    index += name.length();
                    continue;
                }
                int cursor = index + name.length();
                while (cursor < line.length() && Character.isWhitespace(line.charAt(cursor))) {
                    cursor++;
                }
                if (cursor >= line.length() || line.charAt(cursor) != '=') {
                    index += name.length();
                    continue;
                }
                cursor++;
                while (cursor < line.length() && Character.isWhitespace(line.charAt(cursor))) {
                    cursor++;
                }
                if (cursor >= line.length()) {
                    return null;
                }
                char quote = line.charAt(cursor);
                if (quote != '"' && quote != '\'') {
                    return null;
                }
                int valueStart = cursor + 1;
                int valueEnd = line.indexOf(quote, valueStart);
                if (valueEnd < 0) {
                    return null;
                }
                String value = line.substring(valueStart, valueEnd);
                return value.indexOf('&') < 0 ? value : unescapeXml(value);
            }
            return null;
        }

        private boolean isAttributeNameAt(int index, String name) {
            if (index > tagStart) {
                char before = line.charAt(index - 1);
                if (!Character.isWhitespace(before) && before != '<') {
                    return false;
                }
            }
            int after = index + name.length();
            return after >= line.length() || Character.isWhitespace(line.charAt(after)) || line.charAt(after) == '=';
        }
    }

    private static String unescapeXml(String value) {
        String result = value
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&");
        int entityStart = result.indexOf("&#");
        while (entityStart >= 0) {
            int entityEnd = result.indexOf(';', entityStart + 2);
            if (entityEnd < 0) {
                break;
            }
            String entity = result.substring(entityStart + 2, entityEnd);
            try {
                int codePoint = entity.startsWith("x") || entity.startsWith("X")
                        ? Integer.parseInt(entity.substring(1), 16)
                        : Integer.parseInt(entity);
                result = result.substring(0, entityStart)
                        + new String(Character.toChars(codePoint))
                        + result.substring(entityEnd + 1);
                entityStart = result.indexOf("&#", entityStart + 1);
            } catch (Exception e) {
                entityStart = result.indexOf("&#", entityEnd + 1);
            }
        }
        return result;
    }

    private static final class Source implements Closeable {
        private final BufferedReader reader;
        private final Process process;
        private final Thread errorThread;

        private Source(BufferedReader reader, Process process, Thread errorThread) {
            this.reader = reader;
            this.process = process;
            this.errorThread = errorThread;
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            try {
                reader.close();
            } catch (IOException e) {
                failure = e;
            }
            if (process != null) {
                try {
                    int exit = process.waitFor();
                    if (errorThread != null) {
                        errorThread.join(TimeUnit.SECONDS.toMillis(2));
                    }
                    if (exit != 0 && failure == null) {
                        failure = new IOException("pigz exited with code " + exit);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (failure == null) {
                        failure = new IOException("Interrupted while waiting for pigz", e);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static final class ErrorCollector implements Runnable {
        private final InputStream input;

        private ErrorCollector(InputStream input) {
            this.input = input;
        }

        @Override
        public void run() {
            try {
                input.transferTo(DISCARD);
            } catch (IOException ignored) {
            }
        }
    }
}
