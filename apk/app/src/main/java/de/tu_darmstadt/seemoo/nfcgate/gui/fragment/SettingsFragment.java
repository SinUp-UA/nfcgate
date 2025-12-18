package de.tu_darmstadt.seemoo.nfcgate.gui.fragment;

import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import de.tu_darmstadt.seemoo.nfcgate.BuildConfig;
import de.tu_darmstadt.seemoo.nfcgate.NFCGateApp;
import de.tu_darmstadt.seemoo.nfcgate.R;
import de.tu_darmstadt.seemoo.nfcgate.gui.component.ContentShare;
import de.tu_darmstadt.seemoo.nfcgate.network.UserTrustManager;
import de.tu_darmstadt.seemoo.nfcgate.util.DiagnosticsStats;
import de.tu_darmstadt.seemoo.nfcgate.util.PrefUtils;
import de.tu_darmstadt.seemoo.nfcgate.util.RecentEvents;

public class SettingsFragment extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener {
    private SharedPreferences mPrefs;
    private boolean mSanitizingPref = false;

    private static final String PREF_SHOW_ADVANCED = "show_advanced_settings";
    private static final String KEY_ADVANCED_TOGGLE = "advanced_toggle";
    private static final String KEY_ADVANCED_CATEGORY = "advanced";

    private String boolLabel(boolean value) {
        return getString(value ? R.string.diag_bool_true : R.string.diag_bool_false);
    }

    private String lookupEntryName(String value, int valuesArrayRes, int namesArrayRes) {
        if (value == null) {
            return "";
        }

        try {
            String[] values = getResources().getStringArray(valuesArrayRes);
            String[] names = getResources().getStringArray(namesArrayRes);
            int n = Math.min(values.length, names.length);
            for (int i = 0; i < n; i++) {
                if (value.equals(values[i])) {
                    return names[i];
                }
            }
        } catch (Exception ignored) {
            // fall through
        }

        return value;
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        addPreferencesFromResource(R.xml.preferences);

        if (getActivity() != null) {
            mPrefs = PreferenceManager.getDefaultSharedPreferences(getActivity());
        }

        ListPreference languagePref = findPreference(NFCGateApp.PREF_APP_LANGUAGE);
        if (languagePref != null) {
            languagePref.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        }

        ListPreference themePref = findPreference(NFCGateApp.PREF_APP_THEME);
        if (themePref != null) {
            themePref.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        }

        ListPreference pinningModePref = findPreference("tls_pinning_mode");
        if (pinningModePref != null) {
            pinningModePref.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        }

        setupAdvancedAccordion();

        Preference resetTrustPref = findPreference("reset_usertrust");
        if (resetTrustPref != null) {
            resetTrustPref.setOnPreferenceClickListener((preference) -> {
            UserTrustManager.getInstance().clearTrust();
            Toast.makeText(getContext(), R.string.settings_reset_usertrust_toast, Toast.LENGTH_LONG).show();
            return true;
            });
        }

        Preference shareDiagnosticsPref = findPreference("share_diagnostics");
        if (shareDiagnosticsPref != null) {
            shareDiagnosticsPref.setOnPreferenceClickListener((preference) -> {
            shareDiagnostics();
            return true;
            });
        }
    }

    private void setupAdvancedAccordion() {
        if (mPrefs == null) {
            return;
        }

        final Preference toggle = findPreference(KEY_ADVANCED_TOGGLE);
        final PreferenceCategory advancedCategory = findPreference(KEY_ADVANCED_CATEGORY);
        if (toggle == null || advancedCategory == null) {
            return;
        }

        boolean expanded = mPrefs.getBoolean(PREF_SHOW_ADVANCED, false);
        applyAdvancedAccordionState(toggle, advancedCategory, expanded);

        toggle.setOnPreferenceClickListener(pref -> {
            boolean newExpanded = !mPrefs.getBoolean(PREF_SHOW_ADVANCED, false);
            mPrefs.edit().putBoolean(PREF_SHOW_ADVANCED, newExpanded).apply();
            applyAdvancedAccordionState(toggle, advancedCategory, newExpanded);
            return true;
        });
    }

    private void applyAdvancedAccordionState(Preference toggle, PreferenceCategory advancedCategory, boolean expanded) {
        advancedCategory.setVisible(expanded);
        toggle.setSummary(expanded ? R.string.settings_advanced_hide_summary : R.string.settings_advanced_show_summary);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mPrefs != null) {
            mPrefs.registerOnSharedPreferenceChangeListener(this);
        }
    }

    @Override
    public void onPause() {
        if (mPrefs != null) {
            mPrefs.unregisterOnSharedPreferenceChangeListener(this);
        }
        super.onPause();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (getContext() == null || key == null) {
            return;
        }

        if ("app_language".equals(key)) {
            NFCGateApp.applyPreferredLocale(getContext().getApplicationContext());
            if (getActivity() != null) {
                getActivity().recreate();
            }
            return;
        }

        if ("app_theme".equals(key)) {
            NFCGateApp.applyPreferredTheme(getContext().getApplicationContext());
            if (getActivity() != null) {
                getActivity().recreate();
            }
            return;
        }

        // Keep numeric tuning prefs sane and consistent with runtime behavior.
        // Write back the effective clamped value to avoid confusion.
        if (!mSanitizingPref) {
            if ("send_queue_capacity".equals(key)) {
                sanitizeIntPref(sharedPreferences, key, 256, 64, 8192);
            } else if ("log_queue_capacity".equals(key)) {
                sanitizeIntPref(sharedPreferences, key, 512, 64, 16384);
            } else if ("log_rate_limit_per_sec".equals(key)) {
                sanitizeIntPref(sharedPreferences, key, 200, 0, 100_000);
            }
        }

        if ("log_queue_capacity".equals(key) || "log_rate_limit_per_sec".equals(key)) {
            Toast.makeText(getContext(), R.string.settings_restart_required_toast, Toast.LENGTH_LONG).show();
        } else if ("send_queue_capacity".equals(key)) {
            Toast.makeText(getContext(), R.string.settings_reconnect_required_toast, Toast.LENGTH_LONG).show();
        }
    }

    private void sanitizeIntPref(SharedPreferences prefs, String key, int defaultValue, int min, int max) {
        if (getActivity() == null) {
            return;
        }

        String raw = prefs.getString(key, String.valueOf(defaultValue));
        int effective = PrefUtils.readClampedInt(getActivity(), key, defaultValue, min, max);
        String effectiveString = String.valueOf(effective);
        if (raw == null || !raw.trim().equals(effectiveString)) {
            mSanitizingPref = true;
            try {
                prefs.edit().putString(key, effectiveString).apply();
            } finally {
                mSanitizingPref = false;
            }
        }
    }

    private void shareDiagnostics() {
        if (getActivity() == null)
            return;

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getActivity());

        final String timestamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
        final StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.diag_title)).append("\n");
        sb.append(getString(R.string.diag_generated, timestamp)).append("\n\n");

        sb.append(getString(R.string.diag_section_app)).append("\n");
        sb.append(getString(R.string.diag_kv_application_id, BuildConfig.APPLICATION_ID)).append("\n");
        sb.append(getString(R.string.diag_kv_version_name, BuildConfig.VERSION_NAME)).append("\n");
        sb.append(getString(R.string.diag_kv_version_code, BuildConfig.VERSION_CODE)).append("\n");
        sb.append(getString(R.string.diag_kv_build_type, BuildConfig.BUILD_TYPE)).append("\n\n");

        sb.append(getString(R.string.diag_section_device)).append("\n");
        sb.append(getString(R.string.diag_kv_manufacturer, Build.MANUFACTURER)).append("\n");
        sb.append(getString(R.string.diag_kv_brand, Build.BRAND)).append("\n");
        sb.append(getString(R.string.diag_kv_model, Build.MODEL)).append("\n");
        sb.append(getString(R.string.diag_kv_android, Build.VERSION.RELEASE, Build.VERSION.SDK_INT)).append("\n\n");

        sb.append(getString(R.string.diag_section_settings)).append("\n");
        String langValue = prefs.getString(NFCGateApp.PREF_APP_LANGUAGE, "");
        String themeValue = prefs.getString(NFCGateApp.PREF_APP_THEME, "");
        sb.append(getString(R.string.diag_kv_app_language, lookupEntryName(langValue, R.array.language_values, R.array.language_names))).append("\n");
        sb.append(getString(R.string.diag_kv_app_theme, lookupEntryName(themeValue, R.array.theme_values, R.array.theme_names))).append("\n");
        sb.append(getString(R.string.diag_kv_host, prefs.getString("host", ""))).append("\n");
        sb.append(getString(R.string.diag_kv_port, prefs.getString("port", ""))).append("\n");
        sb.append(getString(R.string.diag_kv_tls, boolLabel(prefs.getBoolean("tls", false)))).append("\n");
        sb.append(getString(R.string.diag_kv_session, prefs.getString("session", ""))).append("\n");
        sb.append(getString(R.string.diag_kv_neutral_mode, boolLabel(prefs.getBoolean("privacy_neutral_mode", true)))).append("\n");
        sb.append(getString(R.string.diag_kv_watchdog, boolLabel(prefs.getBoolean("network_watchdog", true)))).append("\n");
        sb.append(getString(R.string.diag_kv_watchdog_timeout_sec, prefs.getString("network_watchdog_timeout_sec", "90"))).append("\n");
        sb.append(getString(R.string.diag_kv_tls_pinning_enabled, boolLabel(prefs.getBoolean("tls_pinning", false)))).append("\n");
        sb.append(getString(R.string.diag_kv_tls_pinning_mode, prefs.getString("tls_pinning_mode", "normal_ca"))).append("\n");
        sb.append(getString(R.string.diag_kv_tls_pin_configured, boolLabel(!prefs.getString("tls_pinning_spki_sha256", "").trim().isEmpty()))).append("\n");

        String sendQueueRaw = prefs.getString("send_queue_capacity", "256");
        String logQueueRaw = prefs.getString("log_queue_capacity", "512");
        String logRateRaw = prefs.getString("log_rate_limit_per_sec", "200");
        int sendQueueEffective = PrefUtils.readClampedInt(getActivity(), "send_queue_capacity", 256, 64, 8192);
        int logQueueEffective = PrefUtils.readClampedInt(getActivity(), "log_queue_capacity", 512, 64, 16384);
        int logRateEffective = PrefUtils.readClampedInt(getActivity(), "log_rate_limit_per_sec", 200, 0, 100_000);

        sb.append(getString(R.string.diag_kv_send_queue_capacity, sendQueueRaw, sendQueueEffective)).append("\n");
        sb.append(getString(R.string.diag_kv_log_queue_capacity, logQueueRaw, logQueueEffective)).append("\n");
        sb.append(getString(R.string.diag_kv_log_rate_limit_per_sec, logRateRaw, logRateEffective)).append("\n");

        if (logRateEffective == 0) {
            sb.append("  ").append(getString(R.string.diag_note_log_rate_disabled)).append("\n");
        }

        sb.append("\n").append(getString(R.string.diag_section_health_counters)).append("\n");
        sb.append(getString(R.string.diag_kv_dropped_send_messages, DiagnosticsStats.getDroppedSendMessages())).append("\n");
        sb.append(getString(R.string.diag_kv_dropped_log_entries, DiagnosticsStats.getDroppedLogEntries())).append("\n");
        sb.append(getString(R.string.diag_kv_watchdog_reconnects, DiagnosticsStats.getWatchdogReconnects())).append("\n");

        sb.append("\n").append(getString(R.string.diag_section_recent_events, 80)).append("\n");
        sb.append(RecentEvents.dump(80));

        new ContentShare(getActivity())
                .setPrefix("nfcgate_diagnostics_" + timestamp)
                .setExtension(".txt")
                .setMimeType("text/plain")
                .setFile((OutputStream stream) -> stream.write(sb.toString().getBytes(StandardCharsets.UTF_8)))
                .share();
    }
}
