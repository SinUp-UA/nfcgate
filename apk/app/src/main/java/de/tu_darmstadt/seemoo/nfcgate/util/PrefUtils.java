package de.tu_darmstadt.seemoo.nfcgate.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

public final class PrefUtils {
    private PrefUtils() {
    }

    public static int readClampedInt(Context ctx, String key, int defaultValue, int min, int max) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        int v;
        try {
            v = Integer.parseInt(prefs.getString(key, String.valueOf(defaultValue)));
        } catch (Exception ignored) {
            v = defaultValue;
        }
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }
}
