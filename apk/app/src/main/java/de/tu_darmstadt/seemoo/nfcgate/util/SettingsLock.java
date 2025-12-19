package de.tu_darmstadt.seemoo.nfcgate.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

public final class SettingsLock {
    private static final String KEY_ENABLED = "settings_lock_enabled";

    private static final String KEY_SALT_B64 = "settings_lock_salt_b64";
    private static final String KEY_HASH_B64 = "settings_lock_hash_b64";

    private static final int SALT_BYTES = 16;

    private SettingsLock() {
    }

    public static boolean isEnabled(@NonNull Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_ENABLED, false);
    }

    public static boolean isPinSet(@NonNull Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String salt = prefs.getString(KEY_SALT_B64, "");
        String hash = prefs.getString(KEY_HASH_B64, "");
        return salt != null && !salt.trim().isEmpty() && hash != null && !hash.trim().isEmpty();
    }

    public static void setPin(@NonNull Context context, @NonNull String pin) {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);

        byte[] hash = hashPin(salt, pin);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit()
                .putString(KEY_SALT_B64, Base64.encodeToString(salt, Base64.NO_WRAP))
                .putString(KEY_HASH_B64, Base64.encodeToString(hash, Base64.NO_WRAP))
                .apply();
    }

    public static boolean verifyPin(@NonNull Context context, @NonNull String pin) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String saltB64 = prefs.getString(KEY_SALT_B64, "");
        String hashB64 = prefs.getString(KEY_HASH_B64, "");
        if (saltB64 == null || hashB64 == null || saltB64.trim().isEmpty() || hashB64.trim().isEmpty()) {
            return false;
        }

        byte[] salt;
        byte[] expected;
        try {
            salt = Base64.decode(saltB64, Base64.DEFAULT);
            expected = Base64.decode(hashB64, Base64.DEFAULT);
        } catch (Exception ignored) {
            return false;
        }

        byte[] actual = hashPin(salt, pin);
        return MessageDigest.isEqual(expected, actual);
    }

    private static byte[] hashPin(byte[] salt, String pin) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            digest.update(pin.getBytes(StandardCharsets.UTF_8));
            return digest.digest();
        } catch (Exception e) {
            // Should not happen on Android; fall back to something deterministic.
            return (new String(salt, StandardCharsets.ISO_8859_1) + pin).getBytes(StandardCharsets.UTF_8);
        }
    }
}
