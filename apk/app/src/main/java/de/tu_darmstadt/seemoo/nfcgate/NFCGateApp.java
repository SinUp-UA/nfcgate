package de.tu_darmstadt.seemoo.nfcgate;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.preference.PreferenceManager;

public class NFCGateApp extends Application {
    public static final String PREF_APP_LANGUAGE = "app_language";
    public static final String PREF_APP_THEME = "app_theme";

    @Override
    public void onCreate() {
        super.onCreate();
        applyPreferredLocale(this);
        applyPreferredTheme(this);
    }

    public static void applyPreferredLocale(Context context) {
        if (context == null) {
            return;
        }

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String lang = prefs.getString(PREF_APP_LANGUAGE, null);

        // Default language: Russian (ru)
        if (lang == null || lang.trim().isEmpty()) {
            lang = "ru";
            prefs.edit().putString(PREF_APP_LANGUAGE, lang).apply();
        }

        final LocaleListCompat locales = LocaleListCompat.forLanguageTags(lang);
        AppCompatDelegate.setApplicationLocales(locales);
    }

    public static void applyPreferredTheme(Context context) {
        if (context == null) {
            return;
        }

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String theme = prefs.getString(PREF_APP_THEME, null);

        // Default theme: Dark
        if (theme == null || theme.trim().isEmpty()) {
            theme = "dark";
            prefs.edit().putString(PREF_APP_THEME, theme).apply();
        }

        if ("dark".equals(theme)) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        }
    }
}
