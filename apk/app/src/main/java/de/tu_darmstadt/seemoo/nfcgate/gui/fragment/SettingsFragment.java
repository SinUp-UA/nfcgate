package de.tu_darmstadt.seemoo.nfcgate.gui.fragment;

import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.CheckBoxPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import java.io.OutputStream;
import java.util.HashMap;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.List;

import de.tu_darmstadt.seemoo.nfcgate.BuildConfig;
import de.tu_darmstadt.seemoo.nfcgate.NFCGateApp;
import de.tu_darmstadt.seemoo.nfcgate.R;
import de.tu_darmstadt.seemoo.nfcgate.gui.component.ContentShare;
import de.tu_darmstadt.seemoo.nfcgate.network.UserTrustManager;
import de.tu_darmstadt.seemoo.nfcgate.util.ConnectionPresets;
import de.tu_darmstadt.seemoo.nfcgate.util.DiagnosticsStats;
import de.tu_darmstadt.seemoo.nfcgate.util.PrefUtils;
import de.tu_darmstadt.seemoo.nfcgate.util.RecentEvents;
import de.tu_darmstadt.seemoo.nfcgate.util.SettingsLock;

public class SettingsFragment extends PreferenceFragmentCompat implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final String TAG = "SettingsFragment";

    private SharedPreferences mPrefs;
    private boolean mSanitizingPref = false;

    private boolean mSettingsUnlocked = false;

    private final Map<String, CharSequence> mOriginalSummaries = new HashMap<>();
    private final Map<String, Preference.SummaryProvider<?>> mOriginalSummaryProviders = new HashMap<>();

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
        try {
            // PreferenceFragmentCompat-native API (ensures preference manager is initialized).
            setPreferencesFromResource(R.xml.preferences, rootKey);

            // Prefer the preference manager's shared prefs; fall back to default shared prefs.
            try {
                mPrefs = getPreferenceManager().getSharedPreferences();
            } catch (Exception ignored) {
                mPrefs = null;
            }
            if (mPrefs == null && getActivity() != null) {
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
            setupSettingsLock();
            setupConnectionPresets();

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
        } catch (Exception e) {
            Log.e(TAG, "Settings initialization failed", e);
            try {
                // Show an empty preference screen instead of crashing.
                if (getContext() != null) {
                    setPreferenceScreen(getPreferenceManager().createPreferenceScreen(getContext()));
                }
            } catch (Exception ignored) {
                // ignore
            }
            Toast.makeText(getContext(), "Settings crashed. Please send Logcat.", Toast.LENGTH_LONG).show();
        }
    }

    private void setupSettingsLock() {
        if (getActivity() == null || mPrefs == null) {
            return;
        }

        final androidx.fragment.app.FragmentActivity activity = getActivity();
        if (activity == null) {
            return;
        }

        final CheckBoxPreference lockEnabledPref = findPreference("settings_lock_enabled");
        final Preference changePinPref = findPreference("settings_lock_change_pin");

        if (changePinPref != null) {
            changePinPref.setOnPreferenceClickListener(pref -> {
                if (getActivity() == null) {
                    return true;
                }

                // If lock is enabled, require unlock before changing the PIN.
                if (SettingsLock.isEnabled(activity) && SettingsLock.isPinSet(activity) && !mSettingsUnlocked) {
                    promptUnlockThen(this::promptSetNewPin);
                    return true;
                }

                promptSetNewPin();
                return true;
            });
        }

        if (lockEnabledPref != null) {
            lockEnabledPref.setOnPreferenceChangeListener((pref, newValue) -> {
                if (getActivity() == null) {
                    return false;
                }

                boolean enable = Boolean.TRUE.equals(newValue);
                if (!enable) {
                    // Disabling lock never requires PIN.
                    mSettingsUnlocked = false;
                    applyLockedBehavior(false);
                    return true;
                }

                // Enabling lock requires a PIN to be set.
                if (!SettingsLock.isPinSet(activity)) {
                    promptSetNewPin(() -> {
                        // Ensure the toggle stays enabled.
                        if (mPrefs != null) {
                            mPrefs.edit().putBoolean("settings_lock_enabled", true).apply();
                        }
                        mSettingsUnlocked = true; // the user just set the PIN
                        applyLockedBehavior(true);
                    }, () -> {
                        // If user cancels PIN setup, do not enable lock.
                        if (mPrefs != null) {
                            mPrefs.edit().putBoolean("settings_lock_enabled", false).apply();
                        }
                        mSettingsUnlocked = false;
                        applyLockedBehavior(false);
                    });
                    return false; // handled asynchronously
                }

                // Lock is enabled and PIN exists, keep settings locked until user unlocks.
                mSettingsUnlocked = false;
                applyLockedBehavior(true);
                return true;
            });
        }

        // Apply initial locked state.
        applyLockedBehavior(SettingsLock.isEnabled(activity));

        // Guard sensitive preferences.
        guardSensitivePreferenceClick("host");
        guardSensitivePreferenceClick("port");
        guardSensitivePreferenceClick("session");

        guardSensitivePreferenceChange("tls");
        guardSensitivePreferenceChange("tls_pinning");
        guardSensitivePreferenceClick("tls_pinning_mode");
        guardSensitivePreferenceClick("tls_pinning_spki_sha256");
    }

    private void setupConnectionPresets() {
        final Preference presetsPref = findPreference("connection_presets");
        if (presetsPref == null) {
            return;
        }

        presetsPref.setOnPreferenceClickListener(pref -> {
            showConnectionPresetsDialog();
            return true;
        });
    }

    private void showConnectionPresetsDialog() {
        if (getActivity() == null) {
            return;
        }

        final List<ConnectionPresets.Preset> presets = ConnectionPresets.load(requireActivity());
        final AlertDialog.Builder b = new AlertDialog.Builder(requireActivity())
                .setTitle(R.string.settings_connection_presets)
                .setNegativeButton(R.string.button_cancel, null)
                .setNeutralButton(R.string.settings_connection_recents, (dialog, which) -> showConnectionRecentsDialog())
                .setPositiveButton(R.string.settings_connection_presets_save_current, (dialog, which) -> promptSaveCurrentPresetName());

        if (presets.isEmpty()) {
            b.setMessage(R.string.settings_connection_presets_empty);
        } else {
            final String[] names = new String[presets.size()];
            for (int i = 0; i < presets.size(); i++) {
                names[i] = presets.get(i).name;
            }

            b.setItems(names, (dialog, which) -> {
                if (which >= 0 && which < presets.size()) {
                    showPresetActionsDialog(presets.get(which));
                }
            });
        }

        b.show();
    }

    private void showConnectionRecentsDialog() {
        if (getActivity() == null) {
            return;
        }

        final List<ConnectionPresets.Recent> recents = ConnectionPresets.loadRecents(requireActivity());
        final AlertDialog.Builder b = new AlertDialog.Builder(requireActivity())
                .setTitle(R.string.settings_connection_recents)
                .setNegativeButton(R.string.button_cancel, null)
                .setNeutralButton(R.string.settings_connection_recents_clear, (dialog, which) -> {
                    ConnectionPresets.clearRecents(requireActivity());
                    Toast.makeText(getContext(), R.string.settings_connection_recents_cleared_toast, Toast.LENGTH_LONG).show();
                });

        if (recents.isEmpty()) {
            b.setMessage(R.string.settings_connection_recents_empty);
        } else {
            final String[] labels = new String[recents.size()];
            for (int i = 0; i < recents.size(); i++) {
                labels[i] = formatRecentLabel(recents.get(i));
            }

            b.setItems(labels, (dialog, which) -> {
                if (which >= 0 && which < recents.size()) {
                    showRecentActionsDialog(recents.get(which));
                }
            });
        }

        b.show();
    }

    private String formatRecentLabel(ConnectionPresets.Recent recent) {
        if (recent == null) {
            return "";
        }

        String hostPort = (recent.host == null ? "" : recent.host) + ":" + (recent.port == null ? "" : recent.port);
        String sess = (recent.session == null || recent.session.trim().isEmpty()) ? "" : (" s" + recent.session.trim());
        String tls = recent.tls ? " TLS" : "";

        String when;
        try {
            SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());
            when = df.format(new Date(recent.ts));
        } catch (Exception ignored) {
            when = "";
        }

        if (when.isEmpty()) {
            return hostPort + sess + tls;
        }
        return when + "  " + hostPort + sess + tls;
    }

    private void showRecentActionsDialog(ConnectionPresets.Recent recent) {
        if (getActivity() == null || recent == null) {
            return;
        }

        new AlertDialog.Builder(requireActivity())
                .setTitle(formatRecentLabel(recent))
                .setPositiveButton(R.string.settings_connection_presets_apply, (dialog, which) -> {
                    Runnable apply = () -> {
                        ConnectionPresets.applyToSettings(requireActivity(), recent);
                        Toast.makeText(getContext(), R.string.settings_connection_recents_applied_toast, Toast.LENGTH_LONG).show();
                    };

                    if (isLockActiveAndLocked()) {
                        promptUnlockThen(apply);
                    } else {
                        apply.run();
                    }
                })
                .setNeutralButton(R.string.settings_connection_presets_delete, (dialog, which) -> {
                    boolean removed = ConnectionPresets.deleteRecent(requireActivity(), recent.id);
                    if (removed) {
                        Toast.makeText(getContext(), R.string.settings_connection_recents_deleted_toast, Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(R.string.button_cancel, null)
                .show();
    }

    private void promptSaveCurrentPresetName() {
        if (getActivity() == null) {
            return;
        }

        final EditText input = new EditText(requireActivity());
        input.setHint(getString(R.string.settings_connection_presets_name_hint));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout wrapper = new LinearLayout(requireActivity());
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(pad, pad / 2, pad, 0);
        wrapper.addView(input);

        new AlertDialog.Builder(requireActivity())
                .setTitle(R.string.settings_connection_presets_save_current)
                .setView(wrapper)
                .setPositiveButton(R.string.button_ok, (dialog, which) -> {
                    String name = String.valueOf(input.getText()).trim();
                    if (name.isEmpty()) {
                        Toast.makeText(getContext(), R.string.settings_connection_presets_invalid_name, Toast.LENGTH_LONG).show();
                        return;
                    }

                    ConnectionPresets.upsert(requireActivity(), ConnectionPresets.fromCurrentSettings(requireActivity(), name));
                    Toast.makeText(getContext(), R.string.settings_connection_presets_saved_toast, Toast.LENGTH_LONG).show();
                })
                .setNegativeButton(R.string.button_cancel, null)
                .show();
    }

    private void showPresetActionsDialog(ConnectionPresets.Preset preset) {
        if (getActivity() == null) {
            return;
        }

        new AlertDialog.Builder(requireActivity())
                .setTitle(preset.name)
                .setPositiveButton(R.string.settings_connection_presets_apply, (dialog, which) -> {
                    Runnable apply = () -> {
                        ConnectionPresets.applyToSettings(requireActivity(), preset);
                        Toast.makeText(getContext(), R.string.settings_connection_presets_applied_toast, Toast.LENGTH_LONG).show();
                    };

                    if (isLockActiveAndLocked()) {
                        promptUnlockThen(apply);
                    } else {
                        apply.run();
                    }
                })
                .setNeutralButton(R.string.settings_connection_presets_delete, (dialog, which) -> {
                    boolean removed = ConnectionPresets.delete(requireActivity(), preset.name);
                    if (removed) {
                        Toast.makeText(getContext(), R.string.settings_connection_presets_deleted_toast, Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(R.string.button_cancel, null)
                .show();
    }

    private void applyLockedBehavior(boolean lockEnabled) {
        final androidx.fragment.app.FragmentActivity activity = getActivity();
        if (activity == null) {
            return;
        }

        final boolean locked = lockEnabled && SettingsLock.isPinSet(activity) && !mSettingsUnlocked;

        // These are the settings most likely to be abused in the field.
        setSensitivePrefLocked("host", locked);
        setSensitivePrefLocked("port", locked);
        setSensitivePrefLocked("session", locked);
        setSensitivePrefLocked("tls", locked);
        setSensitivePrefLocked("tls_pinning", locked);
        setSensitivePrefLocked("tls_pinning_mode", locked);
        setSensitivePrefLocked("tls_pinning_spki_sha256", locked);
    }

    private void setSensitivePrefLocked(String key, boolean locked) {
        final Preference pref = findPreference(key);
        if (pref == null) {
            return;
        }

        if (!mOriginalSummaries.containsKey(key)) {
            mOriginalSummaries.put(key, pref.getSummary());
        }
        if (!mOriginalSummaryProviders.containsKey(key)) {
            mOriginalSummaryProviders.put(key, pref.getSummaryProvider());
        }

        if (locked) {
            // If a SummaryProvider is set, calling setSummary() can crash (AndroidX throws).
            pref.setSummaryProvider(p -> getString(R.string.settings_lock_locked_summary));
        } else {
            // Restore original SummaryProvider if there was one.
            Preference.SummaryProvider<?> originalProvider = mOriginalSummaryProviders.get(key);
            if (originalProvider != null) {
                //noinspection unchecked
                pref.setSummaryProvider((Preference.SummaryProvider<Preference>) originalProvider);
            } else {
                // Restore original summary (from XML or previously-set value).
                CharSequence original = mOriginalSummaries.get(key);
                pref.setSummary(original);

                // For list prefs, prefer the active selection as summary.
                if (pref instanceof ListPreference) {
                    ((ListPreference) pref).setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
                }
            }
        }
    }

    private boolean isLockActiveAndLocked() {
        final androidx.fragment.app.FragmentActivity activity = getActivity();
        return activity != null
            && SettingsLock.isEnabled(activity)
            && SettingsLock.isPinSet(activity)
                && !mSettingsUnlocked;
    }

    private void guardSensitivePreferenceClick(String key) {
        final Preference pref = findPreference(key);
        if (pref == null) {
            return;
        }
        pref.setOnPreferenceClickListener(p -> {
            if (!isLockActiveAndLocked()) {
                return false; // allow default behavior
            }
            promptUnlockThen(() -> {
                // After unlocking, proceed with the original click.
                try {
                    p.performClick();
                } catch (Exception ignored) {
                    // no-op
                }
            });
            return true;
        });
    }

    private void guardSensitivePreferenceChange(String key) {
        final Preference pref = findPreference(key);
        if (pref == null) {
            return;
        }

        pref.setOnPreferenceChangeListener((p, newValue) -> {
            if (!isLockActiveAndLocked()) {
                return true;
            }
            promptUnlockThen(() -> {
                if (mPrefs == null) {
                    return;
                }

                // Apply the change manually after unlock.
                if (p instanceof CheckBoxPreference) {
                    boolean v = Boolean.TRUE.equals(newValue);
                    mPrefs.edit().putBoolean(p.getKey(), v).apply();
                    ((CheckBoxPreference) p).setChecked(v);
                } else if (newValue instanceof String) {
                    mPrefs.edit().putString(p.getKey(), (String) newValue).apply();
                }
            });
            return false;
        });
    }

    private void promptUnlockThen(Runnable onUnlocked) {
        final androidx.fragment.app.FragmentActivity activity = getActivity();
        if (activity == null) {
            return;
        }
        if (!SettingsLock.isPinSet(activity)) {
            Toast.makeText(getContext(), R.string.settings_lock_pin_not_set, Toast.LENGTH_LONG).show();
            return;
        }

        final EditText pinInput = new EditText(activity);
        pinInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pinInput.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout wrapper = new LinearLayout(activity);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(pad, pad / 2, pad, 0);
        wrapper.addView(pinInput);

        new AlertDialog.Builder(activity)
                .setTitle(R.string.settings_lock_unlock_title)
                .setView(wrapper)
                .setPositiveButton(R.string.button_ok, (dialog, which) -> {
                    String pin = String.valueOf(pinInput.getText()).trim();
                    if (SettingsLock.verifyPin(activity, pin)) {
                        mSettingsUnlocked = true;
                        applyLockedBehavior(SettingsLock.isEnabled(activity));
                        if (onUnlocked != null) {
                            onUnlocked.run();
                        }
                    } else {
                        Toast.makeText(getContext(), R.string.settings_lock_wrong_pin, Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(R.string.button_cancel, null)
                .show();
    }

    private void promptSetNewPin() {
        promptSetNewPin(null, null);
    }

    private void promptSetNewPin(Runnable onSuccess, Runnable onCancel) {
        final androidx.fragment.app.FragmentActivity activity = getActivity();
        if (activity == null) {
            return;
        }

        final EditText pin1 = new EditText(activity);
        pin1.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);

        final EditText pin2 = new EditText(activity);
        pin2.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);

        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout wrapper = new LinearLayout(activity);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(pad, pad / 2, pad, 0);

        pin1.setHint(getString(R.string.settings_lock_pin_hint));
        pin2.setHint(getString(R.string.settings_lock_pin_confirm_hint));
        wrapper.addView(pin1);
        wrapper.addView(pin2);

        new AlertDialog.Builder(activity)
                .setTitle(R.string.settings_lock_set_pin_title)
                .setView(wrapper)
                .setPositiveButton(R.string.button_ok, (dialog, which) -> {
                    String a = String.valueOf(pin1.getText()).trim();
                    String b = String.valueOf(pin2.getText()).trim();
                    if (a.length() < 4) {
                        Toast.makeText(getContext(), R.string.settings_lock_pin_too_short, Toast.LENGTH_LONG).show();
                        if (onCancel != null) {
                            onCancel.run();
                        }
                        return;
                    }
                    if (!a.equals(b)) {
                        Toast.makeText(getContext(), R.string.settings_lock_pin_mismatch, Toast.LENGTH_LONG).show();
                        if (onCancel != null) {
                            onCancel.run();
                        }
                        return;
                    }

                    SettingsLock.setPin(activity, a);
                    Toast.makeText(getContext(), R.string.settings_lock_pin_set_toast, Toast.LENGTH_LONG).show();
                    if (onSuccess != null) {
                        onSuccess.run();
                    }
                })
                .setNegativeButton(R.string.button_cancel, (dialog, which) -> {
                    if (onCancel != null) {
                        onCancel.run();
                    }
                })
                .show();
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
