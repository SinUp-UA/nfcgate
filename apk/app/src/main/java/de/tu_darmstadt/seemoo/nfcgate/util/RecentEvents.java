package de.tu_darmstadt.seemoo.nfcgate.util;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Small in-memory ring buffer for diagnostics only.
 * Keep messages short and avoid storing payloads.
 */
public final class RecentEvents {
    private static final int DEFAULT_CAPACITY = 200;

    private static final Object LOCK = new Object();
    private static final ArrayDeque<String> EVENTS = new ArrayDeque<>(DEFAULT_CAPACITY);

    private RecentEvents() {}

    public static void info(String message) {
        add("INFO", message);
    }

    public static void warn(String message) {
        add("WARN", message);
    }

    public static void error(String message) {
        add("ERROR", message);
    }

    public static void error(String message, Throwable t) {
        if (t == null) {
            error(message);
            return;
        }
        String suffix = t.getClass().getSimpleName();
        String details = t.getMessage();
        if (details != null && !details.trim().isEmpty()) {
            suffix += ": " + details.trim();
        }
        add("ERROR", message + " (" + suffix + ")");
    }

    public static List<String> snapshot(int maxLines) {
        int limit = Math.max(0, maxLines);
        synchronized (LOCK) {
            ArrayList<String> out = new ArrayList<>(Math.min(limit, EVENTS.size()));
            int skip = Math.max(0, EVENTS.size() - limit);
            int i = 0;
            for (String e : EVENTS) {
                if (i++ < skip) {
                    continue;
                }
                out.add(e);
            }
            return out;
        }
    }

    public static String dump(int maxLines) {
        List<String> lines = snapshot(maxLines);
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append("- ").append(line).append("\n");
        }
        return sb.toString();
    }

    private static void add(String level, String message) {
        String m = message == null ? "" : message.trim();
        if (m.length() > 400) {
            m = m.substring(0, 400) + "…";
        }

        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        String line = ts + " [" + level + "] " + m;

        synchronized (LOCK) {
            while (EVENTS.size() >= DEFAULT_CAPACITY) {
                EVENTS.removeFirst();
            }
            EVENTS.addLast(line);
        }
    }
}
