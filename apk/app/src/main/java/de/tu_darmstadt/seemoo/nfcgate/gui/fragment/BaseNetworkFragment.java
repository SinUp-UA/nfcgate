package de.tu_darmstadt.seemoo.nfcgate.gui.fragment;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import java.util.List;

import de.tu_darmstadt.seemoo.nfcgate.R;
import de.tu_darmstadt.seemoo.nfcgate.db.worker.LogInserter;
import de.tu_darmstadt.seemoo.nfcgate.gui.component.MatrixRainView;
import de.tu_darmstadt.seemoo.nfcgate.gui.component.StatusBanner;
import de.tu_darmstadt.seemoo.nfcgate.gui.dialog.CertificateTrustDialogFragment;
import de.tu_darmstadt.seemoo.nfcgate.gui.log.SessionLogEntryFragment;
import de.tu_darmstadt.seemoo.nfcgate.network.data.NetworkStatus;
import de.tu_darmstadt.seemoo.nfcgate.util.ConnectionPresets;
import de.tu_darmstadt.seemoo.nfcgate.util.SettingsLock;

public abstract class BaseNetworkFragment extends BaseFragment implements LogInserter.SIDChangedListener {
    private static final String TAG = "BaseNetworkFragment";

    private NetworkStatus mLastNetworkStatus = null;

    private boolean canTouchUi() {
        // Do NOT require getView() here: during onCreateView()/reset() it can be null while view refs are already available.
        return isAdded() && getActivity() != null && getContext() != null;
    }

    private final Handler mPrivacyHandler = new Handler(Looper.getMainLooper());
    private final Runnable mPrivacyAutoTimeoutRunnable = this::hideSensitiveContent;

    // UI references
    View mTagWaiting;
    TextView mTagWaitingText;
    LinearLayout mSelector;
    StatusBanner mStatusBanner;

    View mPrivacyOverlay;
    TextView mPrivacySubtitle;
    View mPrivacyToggle;

    View mPrivacyContent;

    ImageView mPrivacyStateIcon;
    View mPrivacyPulse;

    MatrixRainView mMatrixRain;

    private AnimatorSet mPrivacyAnimator;

    // database log reference
    LogInserter mLogInserter;
    SessionLogEntryFragment mLogFragment;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_network, container, false);

        // setup
        mTagWaiting = v.findViewById(R.id.tag_wait);
        mTagWaitingText = v.findViewById(R.id.tag_wait_text);
        mSelector = v.findViewById(R.id.selector);
        mStatusBanner = new StatusBanner(getMainActivity());

        mPrivacyOverlay = v.findViewById(R.id.lay_privacy_overlay);
        mPrivacySubtitle = v.findViewById(R.id.txt_privacy_subtitle);
        mPrivacyToggle = v.findViewById(R.id.btn_privacy_toggle);

        mPrivacyContent = v.findViewById(R.id.lay_privacy_content);

        mPrivacyStateIcon = v.findViewById(R.id.img_privacy_state);
        mPrivacyPulse = v.findViewById(R.id.prg_privacy_pulse);
        mMatrixRain = v.findViewById(R.id.matrix_rain);

        // Tap overlay to reveal logs/details; toggle button brings the overlay back.
        mPrivacyOverlay.setOnClickListener(view -> revealSensitiveContent());
        mPrivacyToggle.setOnClickListener(view -> hideSensitiveContent());

        // selector setup
        v.<LinearLayout>findViewById(R.id.select_reader).setOnClickListener(view -> onSelect(true));
        v.<LinearLayout>findViewById(R.id.select_tag).setOnClickListener(view -> onSelect(false));

        setHasOptionsMenu(true);
        reset();
        return v;
    }

    private int getPrivacyAutoTimeoutSec() {
        if (getActivity() == null) {
            return 0;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getActivity());
        String raw = prefs.getString("privacy_auto_timeout_sec", "30");
        int value;
        try {
            value = Integer.parseInt(raw);
        } catch (Exception ignored) {
            value = 30;
        }
        // 0 disables; clamp to a sane upper bound.
        if (value < 0) {
            value = 0;
        } else if (value > 3600) {
            value = 3600;
        }
        return value;
    }

    private void schedulePrivacyAutoTimeout() {
        cancelPrivacyAutoTimeout();
        int sec = getPrivacyAutoTimeoutSec();
        if (sec <= 0) {
            return;
        }
        mPrivacyHandler.postDelayed(mPrivacyAutoTimeoutRunnable, sec * 1000L);
    }

    private void cancelPrivacyAutoTimeout() {
        mPrivacyHandler.removeCallbacks(mPrivacyAutoTimeoutRunnable);
    }

    private void revealSensitiveContent() {
        setPrivacyOverlayVisible(false);
        schedulePrivacyAutoTimeout();
    }

    private void hideSensitiveContent() {
        cancelPrivacyAutoTimeout();
        setPrivacyOverlayVisible(true);
    }

    private boolean isTlsEnabledInSettings() {
        if (getActivity() == null) {
            return false;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getActivity());
        return prefs.getBoolean("tls", false);
    }

    private void setPrivacyOverlayVisible(boolean visible) {
        if (mPrivacyOverlay == null || mPrivacyToggle == null)
            return;

        mPrivacyOverlay.setVisibility(visible ? View.VISIBLE : View.GONE);
        mPrivacyToggle.setVisibility(visible ? View.GONE : View.VISIBLE);

        updatePrivacyAnimationState();
    }

    private boolean isPrivacyOverlayVisible() {
        return mPrivacyOverlay != null && mPrivacyOverlay.getVisibility() == View.VISIBLE;
    }

    private String neutralBannerMessageForStatus(NetworkStatus status) {
        if (!canTouchUi()) {
            return "";
        }
        switch (status) {
            case CONNECTING:
                return getString(R.string.network_privacy_status_connecting);
            case CONNECTED:
                return getString(R.string.network_privacy_status_connected_wait);
            case PARTNER_CONNECT:
                return getString(R.string.network_privacy_status_connected);
            case PARTNER_LEFT:
                return getString(R.string.network_privacy_status_disconnected);
            case ERROR_TLS_CERT_UNKNOWN:
            case ERROR_TLS_CERT_UNTRUSTED:
                return getString(R.string.network_privacy_status_attention);
            case ERROR:
            case ERROR_TLS:
            default:
                return getString(R.string.network_privacy_status_error);
        }
    }

    private void setPrivacySubtitle(int resId) {
        if (!canTouchUi()) {
            return;
        }
        if (mPrivacySubtitle != null) {
            mPrivacySubtitle.setText(getString(resId));
        }
    }

    private void setPrivacyStateIcon(int resId) {
        if (mPrivacyStateIcon != null) {
            mPrivacyStateIcon.setImageResource(resId);
        }
    }

    private void setPrivacyPulseVisible(boolean visible) {
        if (mPrivacyPulse != null) {
            mPrivacyPulse.setVisibility(visible ? View.VISIBLE : View.GONE);
        }

        updatePrivacyAnimationState();
    }

    private void updatePrivacyAnimationState() {
        final boolean overlayVisible = isPrivacyOverlayVisible();
        final boolean pulseVisible = mPrivacyPulse != null && mPrivacyPulse.getVisibility() == View.VISIBLE;

        applyPrivacyStyle(overlayVisible);

        // Matrix mode: no icon/spinner/text, so also disable the pulse animation.
        if (overlayVisible && isPrivacyStyleMatrixEnabled()) {
            stopPrivacyAnimation();
            return;
        }

        if (overlayVisible && pulseVisible) {
            startPrivacyAnimation();
        } else {
            stopPrivacyAnimation();
        }
    }

    private boolean isPrivacyStyleMatrixEnabled() {
        if (getActivity() == null)
            return false;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getActivity());
        return "matrix".equals(prefs.getString("privacy_style", "text"));
    }

    private void applyPrivacyStyle(boolean overlayVisible) {
        if (mMatrixRain == null)
            return;

        final boolean matrix = isPrivacyStyleMatrixEnabled();
        mMatrixRain.setVisibility(matrix ? View.VISIBLE : View.GONE);
        mMatrixRain.setRunning(matrix && overlayVisible);

        if (mPrivacyContent != null) {
            mPrivacyContent.setVisibility(matrix ? View.GONE : View.VISIBLE);
        }
    }

    private void startPrivacyAnimation() {
        if (mPrivacyAnimator != null && mPrivacyAnimator.isRunning()) {
            return;
        }

        if (mPrivacyStateIcon == null || mPrivacyPulse == null) {
            return;
        }

        // Subtle pulse to convey activity without revealing technical details.
        PropertyValuesHolder sx = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.08f, 1.0f);
        PropertyValuesHolder sy = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.08f, 1.0f);
        PropertyValuesHolder a = PropertyValuesHolder.ofFloat(View.ALPHA, 1.0f, 0.85f, 1.0f);
        ObjectAnimator iconPulse = ObjectAnimator.ofPropertyValuesHolder(mPrivacyStateIcon, sx, sy, a);
        iconPulse.setDuration(1200);
        iconPulse.setRepeatCount(ObjectAnimator.INFINITE);
        iconPulse.setInterpolator(new AccelerateDecelerateInterpolator());

        ObjectAnimator spinnerFade = ObjectAnimator.ofFloat(mPrivacyPulse, View.ALPHA, 0.55f, 1.0f, 0.55f);
        spinnerFade.setDuration(900);
        spinnerFade.setRepeatCount(ObjectAnimator.INFINITE);
        spinnerFade.setInterpolator(new AccelerateDecelerateInterpolator());

        mPrivacyAnimator = new AnimatorSet();
        mPrivacyAnimator.playTogether(iconPulse, spinnerFade);
        mPrivacyAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                // Reset to defaults.
                if (mPrivacyStateIcon != null) {
                    mPrivacyStateIcon.setScaleX(1.0f);
                    mPrivacyStateIcon.setScaleY(1.0f);
                    mPrivacyStateIcon.setAlpha(1.0f);
                }
                if (mPrivacyPulse != null) {
                    mPrivacyPulse.setAlpha(1.0f);
                }
            }
        });
        mPrivacyAnimator.start();
    }

    private void stopPrivacyAnimation() {
        if (mPrivacyAnimator != null) {
            mPrivacyAnimator.cancel();
            mPrivacyAnimator = null;
        }
    }

    private void updatePrivacyVisualsForStatus(NetworkStatus status) {
        switch (status) {
            case CONNECTING:
            case CONNECTED:
                setPrivacyStateIcon(R.drawable.ic_nfc_grey_42dp);
                setPrivacyPulseVisible(true);
                break;
            case PARTNER_CONNECT:
                setPrivacyStateIcon(R.drawable.ic_check_circle_green_24dp);
                setPrivacyPulseVisible(false);
                break;
            case PARTNER_LEFT:
                setPrivacyStateIcon(R.drawable.ic_warning_grey_24dp);
                setPrivacyPulseVisible(false);
                break;
            case ERROR:
            case ERROR_TLS:
            case ERROR_TLS_CERT_UNKNOWN:
            case ERROR_TLS_CERT_UNTRUSTED:
            default:
                setPrivacyStateIcon(R.drawable.ic_error_red_24dp);
                setPrivacyPulseVisible(false);
                break;
        }
    }

    protected void stopAndLock() {
        if (!canTouchUi()) {
            return;
        }
        // Defensive: users can trigger this while the fragment/activity is in a transient state.
        // Never crash the app; best-effort stop the mode and show privacy overlay.
        try {
            reset();
        } catch (Exception e) {
            Log.e(TAG, "stopAndLock(): reset failed", e);
        }

        setPrivacyOverlayVisible(true);
        setPrivacySubtitle(R.string.network_privacy_status_paused);
        setPrivacyStateIcon(R.drawable.ic_stop_grey_60dp);
        setPrivacyPulseVisible(false);
    }

    private boolean isNeutralModeEnabled() {
        if (getActivity() == null)
            return true;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getActivity());
        return prefs.getBoolean("privacy_neutral_mode", true);
    }

    private void updatePrivacySubtitleForStatus(NetworkStatus status) {
        if (!canTouchUi()) {
            return;
        }
        if (!isNeutralModeEnabled()) {
            // Keep technical status strings.
            switch (status) {
                case ERROR:
                    setPrivacySubtitle(R.string.network_error);
                    break;
                case ERROR_TLS:
                    setPrivacySubtitle(R.string.network_tls_error);
                    break;
                case ERROR_TLS_CERT_UNKNOWN:
                    setPrivacySubtitle(R.string.network_tls_unknown);
                    break;
                case ERROR_TLS_CERT_UNTRUSTED:
                    setPrivacySubtitle(R.string.network_tls_untrusted);
                    break;
                case CONNECTING:
                    setPrivacySubtitle(R.string.network_connecting);
                    break;
                case CONNECTED:
                    setPrivacySubtitle(R.string.network_connected_wait);
                    break;
                case PARTNER_CONNECT:
                    setPrivacySubtitle(R.string.network_connected);
                    break;
                case PARTNER_LEFT:
                    setPrivacySubtitle(R.string.network_disconnected);
                    break;
            }
            return;
        }

        // Neutral, non-technical phrases.
        switch (status) {
            case CONNECTING:
                setPrivacySubtitle(R.string.network_privacy_status_connecting);
                break;
            case CONNECTED:
                setPrivacySubtitle(R.string.network_privacy_status_connected_wait);
                break;
            case PARTNER_CONNECT:
                setPrivacySubtitle(R.string.network_privacy_status_connected);
                break;
            case PARTNER_LEFT:
                setPrivacySubtitle(R.string.network_privacy_status_disconnected);
                break;
            case ERROR:
            case ERROR_TLS:
            case ERROR_TLS_CERT_UNKNOWN:
            case ERROR_TLS_CERT_UNTRUSTED:
            default:
                setPrivacySubtitle(R.string.network_privacy_status_error);
                break;
        }
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.toolbar_relay, menu);

        try {
            MenuItem pause = menu.findItem(R.id.action_pause_sending);
            if (pause != null) {
                boolean paused = getNfc().getNetwork().isPausedSending();
                pause.setChecked(paused);
                pause.setTitle(paused ? R.string.relay_resume_sending : R.string.relay_pause_sending);

                // Keep the privacy overlay consistent if the menu is recreated.
                applyPausedOverlayState(paused);
            }
        } catch (Exception ignored) {
            // best-effort
        }

        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_stop_lock) {
            stopAndLock();
            return true;
        }
        if (item.getItemId() == R.id.action_refresh) {
            reset();
            return true;
        }
        if (item.getItemId() == R.id.action_panic) {
            hideSensitiveContent();
            return true;
        }
        if (item.getItemId() == R.id.action_reconnect) {
            if (!checkNetwork()) {
                return true;
            }
            try {
                getNfc().getNetwork().reconnectNow();
            } catch (Exception e) {
                Log.w(TAG, "Manual reconnect failed", e);
            }
            return true;
        }
        if (item.getItemId() == R.id.action_pause_sending) {
            if (getActivity() == null) {
                return true;
            }

            boolean currentlyPaused;
            try {
                currentlyPaused = getNfc().getNetwork().isPausedSending();
            } catch (Exception e) {
                Log.w(TAG, "Pause state read failed", e);
                return true;
            }

            final boolean targetPaused = !currentlyPaused;
            Runnable apply = () -> {
                try {
                    getNfc().getNetwork().setPausedSending(targetPaused);
                    item.setChecked(targetPaused);
                    item.setTitle(targetPaused ? R.string.relay_resume_sending : R.string.relay_pause_sending);

                    // Reflect pause in privacy overlay (subtitle/icon) immediately.
                    applyPausedOverlayState(targetPaused);

                    // Keep UI understandable even in neutral mode.
                    if (targetPaused) {
                        mStatusBanner.setWarning(getString(R.string.network_privacy_status_paused));
                    } else {
                        // Restore visuals from last known network state.
                        if (mLastNetworkStatus != null) {
                            try {
                                handleStatus(mLastNetworkStatus);
                            } catch (Exception ignored) {
                                // no-op
                            }
                        } else {
                            mStatusBanner.setVisibility(false);
                        }
                    }

                    Toast.makeText(getContext(), targetPaused ? R.string.relay_pause_sending_toast : R.string.relay_resume_sending_toast, Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Log.w(TAG, "Pause toggle failed", e);
                }
            };

            if (SettingsLock.isEnabled(requireActivity()) && SettingsLock.isPinSet(requireActivity())) {
                promptPinThen(apply);
            } else {
                apply.run();
            }
            return true;
        }
        if (item.getItemId() == R.id.action_apply_recent) {
            if (getActivity() == null) {
                return true;
            }

            Runnable apply = this::applyLastRecentAndReconnect;
            if (SettingsLock.isEnabled(requireActivity()) && SettingsLock.isPinSet(requireActivity())) {
                promptPinThen(apply);
            } else {
                apply.run();
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private boolean isPausedSendingSafe() {
        try {
            return getActivity() != null && getNfc() != null && getNfc().getNetwork() != null && getNfc().getNetwork().isPausedSending();
        } catch (Exception ignored) {
            return false;
        }
    }

    private void applyPausedOverlayState(boolean paused) {
        if (!canTouchUi()) {
            return;
        }
        if (!isPrivacyOverlayVisible()) {
            return;
        }

        if (paused) {
            setPrivacySubtitle(R.string.network_privacy_status_paused);
            setPrivacyStateIcon(R.drawable.ic_stop_grey_60dp);
            setPrivacyPulseVisible(false);
        } else {
            if (mLastNetworkStatus != null) {
                updatePrivacySubtitleForStatus(mLastNetworkStatus);
                updatePrivacyVisualsForStatus(mLastNetworkStatus);
            }
        }
    }

    private void applyLastRecentAndReconnect() {
        if (getActivity() == null) {
            return;
        }

        final List<ConnectionPresets.Recent> recents = ConnectionPresets.loadRecents(requireActivity());
        if (recents.isEmpty() || recents.get(0) == null) {
            Toast.makeText(getContext(), R.string.relay_apply_recent_empty, Toast.LENGTH_LONG).show();
            return;
        }

        ConnectionPresets.applyToSettings(requireActivity(), recents.get(0));

        if (!checkNetwork()) {
            return;
        }

        try {
            getNfc().getNetwork().reconnectNow();
            Toast.makeText(getContext(), R.string.relay_apply_recent_toast, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.w(TAG, "Apply recent + reconnect failed", e);
        }
    }

    private void promptPinThen(Runnable onVerified) {
        if (getActivity() == null) {
            return;
        }
        if (!SettingsLock.isPinSet(requireActivity())) {
            Toast.makeText(getContext(), R.string.settings_lock_pin_not_set, Toast.LENGTH_LONG).show();
            return;
        }

        final EditText pinInput = new EditText(requireActivity());
        pinInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);

        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout wrapper = new LinearLayout(requireActivity());
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(pad, pad / 2, pad, 0);
        wrapper.addView(pinInput);

        new AlertDialog.Builder(requireActivity())
                .setTitle(R.string.settings_lock_unlock_title)
                .setView(wrapper)
                .setPositiveButton(R.string.button_ok, (dialog, which) -> {
                    String pin = String.valueOf(pinInput.getText()).trim();
                    if (SettingsLock.verifyPin(requireActivity(), pin)) {
                        if (onVerified != null) {
                            onVerified.run();
                        }
                    } else {
                        Toast.makeText(getContext(), R.string.settings_lock_wrong_pin, Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton(R.string.button_cancel, null)
                .show();
    }

    @Override
    public void onSIDChanged(long sessionID) {
        if (!canTouchUi()) {
            return;
        }

        // Avoid fragment transactions after state is saved (can happen during navigation).
        if (getMainActivity() == null || getMainActivity().isFinishing() || getMainActivity().getSupportFragmentManager().isStateSaved()) {
            return;
        }

        // first, close old fragment if exists
        if (mLogFragment != null) {
            getMainActivity().getSupportFragmentManager().beginTransaction()
                    .remove(mLogFragment)
                    .commitAllowingStateLoss();
        }

        // if new session exists, show log fragment
        if (sessionID > -1) {
            mLogFragment = SessionLogEntryFragment.newInstance(sessionID, SessionLogEntryFragment.Type.LIVE, null);
            getMainActivity().getSupportFragmentManager().beginTransaction()
                    .replace(R.id.lay_content, mLogFragment)
                    .commitAllowingStateLoss();

            // Hide technical details by default while an active session is running.
            hideSensitiveContent();
            setPrivacySubtitle(R.string.network_privacy_subtitle);
        } else {
            cancelPrivacyAutoTimeout();
            setPrivacyOverlayVisible(false);
        }
    }

    protected void setSelectorVisible(boolean visible) {
        if (!canTouchUi() || mSelector == null) {
            return;
        }
        mSelector.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    protected void setTagWaitVisible(boolean visible, boolean reader) {
        if (!canTouchUi() || mTagWaitingText == null || mTagWaiting == null) {
            return;
        }
        mTagWaitingText.setText(getString(R.string.network_waiting_for,
                getString(reader ? R.string.network_reader : R.string.network_tag)));
        mTagWaiting.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    protected void handleStatus(NetworkStatus status) {
        if (!canTouchUi()) {
            return;
        }

        mLastNetworkStatus = status;

        // If sending is paused, keep the privacy overlay in a consistent paused state.
        if (isPausedSendingSafe()) {
            setPrivacySubtitle(R.string.network_privacy_status_paused);
            setPrivacyStateIcon(R.drawable.ic_stop_grey_60dp);
            setPrivacyPulseVisible(false);
            // Banner should still be visible to avoid confusion when overlay is hidden.
            mStatusBanner.setWarning(getString(R.string.network_privacy_status_paused));
            return;
        }

        final boolean neutralUi = isNeutralModeEnabled() && isPrivacyOverlayVisible();
        final String neutralMsg = neutralUi ? neutralBannerMessageForStatus(status) : null;

        switch (status) {
            case ERROR:
                mStatusBanner.setError(neutralUi ? neutralMsg : getString(R.string.network_error));
                updatePrivacySubtitleForStatus(status);
                updatePrivacyVisualsForStatus(status);
                break;
            case ERROR_TLS:
                if (!neutralUi && isTlsEnabledInSettings()) {
                    mStatusBanner.setError(getString(R.string.network_tls_error_hint_plain));
                } else {
                    mStatusBanner.setError(neutralUi ? neutralMsg : getString(R.string.network_tls_error));
                }
                updatePrivacySubtitleForStatus(status);
                updatePrivacyVisualsForStatus(status);
                break;
            case ERROR_TLS_CERT_UNKNOWN: {
                mStatusBanner.setWarning(neutralUi ? neutralMsg : getString(R.string.network_tls_unknown));
                updatePrivacySubtitleForStatus(status);
                updatePrivacyVisualsForStatus(status);
                new CertificateTrustDialogFragment().show(getMainActivity().getSupportFragmentManager(), "trust");
                break;
            }
            case ERROR_TLS_CERT_UNTRUSTED:
                mStatusBanner.setError(neutralUi ? neutralMsg : getString(R.string.network_tls_untrusted));
                updatePrivacySubtitleForStatus(status);
                updatePrivacyVisualsForStatus(status);
                break;
            case CONNECTING:
                mStatusBanner.setError(neutralUi ? neutralMsg : getString(R.string.network_connecting));
                updatePrivacySubtitleForStatus(status);
                updatePrivacyVisualsForStatus(status);
                break;
            case CONNECTED:
                mStatusBanner.setWarning(neutralUi ? neutralMsg : getString(R.string.network_connected_wait));
                updatePrivacySubtitleForStatus(status);
                updatePrivacyVisualsForStatus(status);
                break;
            case PARTNER_CONNECT:
                mStatusBanner.setSuccess(neutralUi ? neutralMsg : getString(R.string.network_connected));
                updatePrivacySubtitleForStatus(status);
                updatePrivacyVisualsForStatus(status);
                break;
            case PARTNER_LEFT:
                mStatusBanner.setError(neutralUi ? neutralMsg : getString(R.string.network_disconnected));
                updatePrivacySubtitleForStatus(status);
                updatePrivacyVisualsForStatus(status);
                break;
        }
    }

    /**
     * Returns true if any network connection appears to be online
     */
    private boolean isNetworkAvailable() {
        ConnectivityManager cm =
                (ConnectivityManager) getActivity().getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo netInfo = cm.getActiveNetworkInfo();
        return netInfo != null && netInfo.isConnected();
    }

    /**
     * Returns true if any server hostname was configured in settings
     */
    private boolean isServerConfigured() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getActivity());
        return !prefs.getString("host", "").isEmpty();
    }

    /**
     * Checks if any network is available and the server connection is properly configured
     */
    protected boolean checkNetwork() {
        // check if any network connection is available
        if (!isNetworkAvailable()) {
            getMainActivity().showWarning(getString(R.string.error_no_connection));
            return false;
        }

        // check if the server connection is properly configured
        if (!isServerConfigured()) {
            getMainActivity().showWarning(getString(R.string.error_no_hostname));
            return false;
        }

        if (!getNfc().isEnabled()) {
            getMainActivity().showWarning(getString(R.string.error_nfc_disabled));
            return false;
        }

        return true;
    }

    /**
     * Reset method called initially and when user presses reset button
     */
    protected void reset() {
        try {
            getNfc().stopMode();
        } catch (Exception e) {
            Log.e(TAG, "reset(): stopMode failed", e);
        }

        if (mStatusBanner != null) {
            try {
                mStatusBanner.set(StatusBanner.State.IDLE, getString(R.string.network_idle));
            } catch (Exception e) {
                Log.e(TAG, "reset(): status banner update failed", e);
            }
        }

        cancelPrivacyAutoTimeout();
        setPrivacyOverlayVisible(false);
        setPrivacySubtitle(R.string.network_privacy_subtitle);
        setPrivacyStateIcon(R.drawable.ic_nfc_grey_42dp);
        setPrivacyPulseVisible(false);

        if (mLogInserter != null)
            mLogInserter.reset();
    }

    @Override
    public void onResume() {
        super.onResume();
        updatePrivacyAnimationState();
    }

    @Override
    public void onPause() {
        cancelPrivacyAutoTimeout();
        stopPrivacyAnimation();
        if (mMatrixRain != null) {
            mMatrixRain.setRunning(false);
        }
        super.onPause();
    }

    /**
     * Setup method called when user selects reader or tag
     */
    protected abstract void onSelect(boolean reader);
}
