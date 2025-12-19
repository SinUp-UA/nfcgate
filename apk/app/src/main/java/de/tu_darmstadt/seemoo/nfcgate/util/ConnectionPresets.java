package de.tu_darmstadt.seemoo.nfcgate.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ConnectionPresets {
    private static final String KEY_PRESETS_JSON = "connection_presets_json";
    private static final String KEY_RECENTS_JSON = "connection_recents_json";

    // Keep this small: presets are a convenience feature.
    private static final int MAX_PRESETS = 10;

    // Recents should be small as well; this is not history/logging.
    private static final int MAX_RECENTS = 10;

    private ConnectionPresets() {
    }

    public static final class Preset {
        public final String name;
        public final String host;
        public final String port;
        public final String session;
        public final boolean tls;
        public final boolean tlsPinning;
        public final String tlsPinningMode;
        public final String tlsPinningSpkiSha256;

        public Preset(
                @NonNull String name,
                @NonNull String host,
                @NonNull String port,
                @NonNull String session,
                boolean tls,
                boolean tlsPinning,
                @NonNull String tlsPinningMode,
                @NonNull String tlsPinningSpkiSha256
        ) {
            this.name = name;
            this.host = host;
            this.port = port;
            this.session = session;
            this.tls = tls;
            this.tlsPinning = tlsPinning;
            this.tlsPinningMode = tlsPinningMode;
            this.tlsPinningSpkiSha256 = tlsPinningSpkiSha256;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("name", name);
                o.put("host", host);
                o.put("port", port);
                o.put("session", session);
                o.put("tls", tls);
                o.put("tls_pinning", tlsPinning);
                o.put("tls_pinning_mode", tlsPinningMode);
                o.put("tls_pinning_spki_sha256", tlsPinningSpkiSha256);
            } catch (Exception ignored) {
                // best-effort
            }
            return o;
        }

        public static Preset fromJson(JSONObject o) {
            if (o == null) {
                return null;
            }

            try {
                String name = o.optString("name", "").trim();
                if (name.isEmpty()) {
                    return null;
                }

                String host = o.optString("host", "");
                String port = o.optString("port", "");
                String session = o.optString("session", "");
                boolean tls = o.optBoolean("tls", false);
                boolean pinning = o.optBoolean("tls_pinning", false);
                String pinningMode = o.optString("tls_pinning_mode", "normal_ca");
                String pinValue = o.optString("tls_pinning_spki_sha256", "");

                return new Preset(name, host, port, session, tls, pinning, pinningMode, pinValue);
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    public static final class Recent {
        public final long id;
        public final long ts;
        public final String host;
        public final String port;
        public final String session;
        public final boolean tls;
        public final boolean tlsPinning;
        public final String tlsPinningMode;
        public final String tlsPinningSpkiSha256;

        public Recent(
                long id,
                long ts,
                @NonNull String host,
                @NonNull String port,
                @NonNull String session,
                boolean tls,
                boolean tlsPinning,
                @NonNull String tlsPinningMode,
                @NonNull String tlsPinningSpkiSha256
        ) {
            this.id = id;
            this.ts = ts;
            this.host = host;
            this.port = port;
            this.session = session;
            this.tls = tls;
            this.tlsPinning = tlsPinning;
            this.tlsPinningMode = tlsPinningMode;
            this.tlsPinningSpkiSha256 = tlsPinningSpkiSha256;
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("ts", ts);
                o.put("host", host);
                o.put("port", port);
                o.put("session", session);
                o.put("tls", tls);
                o.put("tls_pinning", tlsPinning);
                o.put("tls_pinning_mode", tlsPinningMode);
                o.put("tls_pinning_spki_sha256", tlsPinningSpkiSha256);
            } catch (Exception ignored) {
                // best-effort
            }
            return o;
        }

        public static Recent fromJson(JSONObject o) {
            if (o == null) {
                return null;
            }

            try {
                long id = o.optLong("id", 0);
                long ts = o.optLong("ts", 0);
                String host = o.optString("host", "");
                String port = o.optString("port", "");
                String session = o.optString("session", "");
                boolean tls = o.optBoolean("tls", false);
                boolean pinning = o.optBoolean("tls_pinning", false);
                String pinningMode = o.optString("tls_pinning_mode", "normal_ca");
                String pinValue = o.optString("tls_pinning_spki_sha256", "");

                if (host.trim().isEmpty() || port.trim().isEmpty()) {
                    return null;
                }

                return new Recent(id, ts, host, port, session, tls, pinning, pinningMode, pinValue);
            } catch (Exception ignored) {
                return null;
            }
        }

        public boolean sameConfig(@NonNull Recent other) {
            return host.equals(other.host)
                    && port.equals(other.port)
                    && session.equals(other.session)
                    && tls == other.tls
                    && tlsPinning == other.tlsPinning
                    && tlsPinningMode.equals(other.tlsPinningMode)
                    && tlsPinningSpkiSha256.equals(other.tlsPinningSpkiSha256);
        }
    }

    @NonNull
    public static List<Preset> load(@NonNull Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String raw = prefs.getString(KEY_PRESETS_JSON, "");
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptyList();
        }

        try {
            JSONArray arr = new JSONArray(raw);
            List<Preset> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                Preset p = Preset.fromJson(o);
                if (p != null) {
                    out.add(p);
                }
            }
            return out;
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    public static void save(@NonNull Context context, @NonNull List<Preset> presets) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        JSONArray arr = new JSONArray();
        int n = Math.min(presets.size(), MAX_PRESETS);
        for (int i = 0; i < n; i++) {
            Preset p = presets.get(i);
            if (p != null) {
                arr.put(p.toJson());
            }
        }

        prefs.edit().putString(KEY_PRESETS_JSON, arr.toString()).apply();
    }

    public static void upsert(@NonNull Context context, @NonNull Preset preset) {
        String name = preset.name == null ? "" : preset.name.trim();
        if (name.isEmpty()) {
            return;
        }

        List<Preset> old = load(context);
        List<Preset> next = new ArrayList<>();

        // newest first
        next.add(preset);

        for (Preset p : old) {
            if (p == null) {
                continue;
            }
            if (name.equals(p.name)) {
                continue;
            }
            next.add(p);
            if (next.size() >= MAX_PRESETS) {
                break;
            }
        }

        save(context, next);
    }

    public static boolean delete(@NonNull Context context, @NonNull String name) {
        String n = name.trim();
        if (n.isEmpty()) {
            return false;
        }

        List<Preset> old = load(context);
        if (old.isEmpty()) {
            return false;
        }

        List<Preset> next = new ArrayList<>();
        boolean removed = false;
        for (Preset p : old) {
            if (p == null) {
                continue;
            }
            if (n.equals(p.name)) {
                removed = true;
                continue;
            }
            next.add(p);
        }

        if (removed) {
            save(context, next);
        }

        return removed;
    }

    @NonNull
    public static Preset fromCurrentSettings(@NonNull Context context, @NonNull String name) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        String host = prefs.getString("host", "");
        String port = prefs.getString("port", "5567");
        String session = prefs.getString("session", "1");
        boolean tls = prefs.getBoolean("tls", false);
        boolean tlsPinning = prefs.getBoolean("tls_pinning", false);
        String tlsPinningMode = prefs.getString("tls_pinning_mode", "normal_ca");
        String tlsPinningValue = prefs.getString("tls_pinning_spki_sha256", "");

        return new Preset(
                name.trim(),
                host == null ? "" : host,
                port == null ? "" : port,
                session == null ? "" : session,
                tls,
                tlsPinning,
                tlsPinningMode == null ? "normal_ca" : tlsPinningMode,
                tlsPinningValue == null ? "" : tlsPinningValue
        );
    }

    public static void applyToSettings(@NonNull Context context, @NonNull Preset preset) {
        applyToSettingsInternal(
                context,
                preset.host,
                preset.port,
                preset.session,
                preset.tls,
                preset.tlsPinning,
                preset.tlsPinningMode,
                preset.tlsPinningSpkiSha256
        );
    }

    public static void applyToSettings(@NonNull Context context, @NonNull Recent recent) {
        applyToSettingsInternal(
                context,
                recent.host,
                recent.port,
                recent.session,
                recent.tls,
                recent.tlsPinning,
                recent.tlsPinningMode,
                recent.tlsPinningSpkiSha256
        );
    }

    private static void applyToSettingsInternal(
            @NonNull Context context,
            @NonNull String host,
            @NonNull String port,
            @NonNull String session,
            boolean tls,
            boolean tlsPinning,
            @NonNull String tlsPinningMode,
            @NonNull String tlsPinningSpkiSha256
    ) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit()
                .putString("host", host)
                .putString("port", port)
                .putString("session", session)
                .putBoolean("tls", tls)
                .putBoolean("tls_pinning", tlsPinning)
                .putString("tls_pinning_mode", tlsPinningMode)
                .putString("tls_pinning_spki_sha256", tlsPinningSpkiSha256)
                .apply();
    }

    @NonNull
    public static List<Recent> loadRecents(@NonNull Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String raw = prefs.getString(KEY_RECENTS_JSON, "");
        if (raw == null || raw.trim().isEmpty()) {
            return Collections.emptyList();
        }

        try {
            JSONArray arr = new JSONArray(raw);
            List<Recent> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                Recent r = Recent.fromJson(o);
                if (r != null) {
                    out.add(r);
                }
            }
            return out;
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    public static void clearRecents(@NonNull Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().remove(KEY_RECENTS_JSON).apply();
    }

    public static boolean deleteRecent(@NonNull Context context, long id) {
        if (id <= 0) {
            return false;
        }

        List<Recent> old = loadRecents(context);
        if (old.isEmpty()) {
            return false;
        }

        List<Recent> next = new ArrayList<>();
        boolean removed = false;
        for (Recent r : old) {
            if (r == null) {
                continue;
            }
            if (r.id == id) {
                removed = true;
                continue;
            }
            next.add(r);
        }

        if (removed) {
            saveRecents(context, next);
        }

        return removed;
    }

    public static void recordRecentFromCurrentSettings(@NonNull Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        String host = prefs.getString("host", "");
        String port = prefs.getString("port", "5567");
        String session = prefs.getString("session", "1");
        boolean tls = prefs.getBoolean("tls", false);
        boolean tlsPinning = prefs.getBoolean("tls_pinning", false);
        String tlsPinningMode = prefs.getString("tls_pinning_mode", "normal_ca");
        String tlsPinningValue = prefs.getString("tls_pinning_spki_sha256", "");

        if (host == null || host.trim().isEmpty() || port == null || port.trim().isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        Recent candidate = new Recent(
                now,
                now,
                host,
                port,
                session == null ? "" : session,
                tls,
                tlsPinning,
                tlsPinningMode == null ? "normal_ca" : tlsPinningMode,
                tlsPinningValue == null ? "" : tlsPinningValue
        );

        List<Recent> old = loadRecents(context);
        if (!old.isEmpty()) {
            Recent head = old.get(0);
            if (head != null && candidate.sameConfig(head)) {
                return; // avoid duplicates during reconnect loops
            }
        }

        List<Recent> next = new ArrayList<>();
        next.add(candidate);
        for (Recent r : old) {
            if (r == null) {
                continue;
            }
            next.add(r);
            if (next.size() >= MAX_RECENTS) {
                break;
            }
        }

        saveRecents(context, next);
    }

    private static void saveRecents(@NonNull Context context, @NonNull List<Recent> recents) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        JSONArray arr = new JSONArray();
        int n = Math.min(recents.size(), MAX_RECENTS);
        for (int i = 0; i < n; i++) {
            Recent r = recents.get(i);
            if (r != null) {
                arr.put(r.toJson());
            }
        }

        prefs.edit().putString(KEY_RECENTS_JSON, arr.toString()).apply();
    }
}
