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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import de.tu_darmstadt.seemoo.nfcgate.R;
import de.tu_darmstadt.seemoo.nfcgate.db.worker.LogInserter;
import de.tu_darmstadt.seemoo.nfcgate.gui.component.StatusBanner;
import de.tu_darmstadt.seemoo.nfcgate.gui.dialog.CertificateTrustDialogFragment;
import de.tu_darmstadt.seemoo.nfcgate.gui.log.SessionLogEntryFragment;
import de.tu_darmstadt.seemoo.nfcgate.network.data.NetworkStatus;

public abstract class BaseNetworkFragment extends BaseFragment implements LogInserter.SIDChangedListener {
    private static final String TAG = "BaseNetworkFragment";

    // UI references
    View mTagWaiting;
    TextView mTagWaitingText;
    LinearLayout mSelector;
    StatusBanner mStatusBanner;

    View mPrivacyOverlay;
    TextView mPrivacySubtitle;
    View mPrivacyToggle;

    ImageView mPrivacyStateIcon;
    View mPrivacyPulse;

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

        mPrivacyStateIcon = v.findViewById(R.id.img_privacy_state);
        mPrivacyPulse = v.findViewById(R.id.prg_privacy_pulse);

        // Tap overlay to reveal logs/details; toggle button brings the overlay back.
        mPrivacyOverlay.setOnClickListener(view -> setPrivacyOverlayVisible(false));
        mPrivacyToggle.setOnClickListener(view -> setPrivacyOverlayVisible(true));

        // selector setup
        v.<LinearLayout>findViewById(R.id.select_reader).setOnClickListener(view -> onSelect(true));
        v.<LinearLayout>findViewById(R.id.select_tag).setOnClickListener(view -> onSelect(false));

        setHasOptionsMenu(true);
        reset();
        return v;
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

        if (overlayVisible && pulseVisible) {
            startPrivacyAnimation();
        } else {
            stopPrivacyAnimation();
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
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onSIDChanged(long sessionID) {
        // first, close old fragment if exists
        if (mLogFragment != null) {
            getMainActivity().getSupportFragmentManager().beginTransaction()
                    .remove(mLogFragment)
                    .commit();
        }

        // if new session exists, show log fragment
        if (sessionID > -1) {
            mLogFragment = SessionLogEntryFragment.newInstance(sessionID, SessionLogEntryFragment.Type.LIVE, null);
            getMainActivity().getSupportFragmentManager().beginTransaction()
                    .replace(R.id.lay_content, mLogFragment)
                    .commit();

            // Hide technical details by default while an active session is running.
            setPrivacyOverlayVisible(true);
            setPrivacySubtitle(R.string.network_privacy_subtitle);
        } else {
            setPrivacyOverlayVisible(false);
        }
    }

    protected void setSelectorVisible(boolean visible) {
        mSelector.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    protected void setTagWaitVisible(boolean visible, boolean reader) {
        mTagWaitingText.setText(getString(R.string.network_waiting_for,
                getString(reader ? R.string.network_reader : R.string.network_tag)));
        mTagWaiting.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    protected void handleStatus(NetworkStatus status) {
        final boolean neutralUi = isNeutralModeEnabled() && isPrivacyOverlayVisible();
        final String neutralMsg = neutralUi ? neutralBannerMessageForStatus(status) : null;

        switch (status) {
            case ERROR:
                mStatusBanner.setError(neutralUi ? neutralMsg : getString(R.string.network_error));
                updatePrivacySubtitleForStatus(status);
                updatePrivacyVisualsForStatus(status);
                break;
            case ERROR_TLS:
                mStatusBanner.setError(neutralUi ? neutralMsg : getString(R.string.network_tls_error));
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
        stopPrivacyAnimation();
        super.onPause();
    }

    /**
     * Setup method called when user selects reader or tag
     */
    protected abstract void onSelect(boolean reader);
}
