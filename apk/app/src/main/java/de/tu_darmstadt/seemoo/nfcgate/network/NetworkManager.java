package de.tu_darmstadt.seemoo.nfcgate.network;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import androidx.preference.PreferenceManager;
import android.util.Log;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import de.tu_darmstadt.seemoo.nfcgate.gui.MainActivity;
import de.tu_darmstadt.seemoo.nfcgate.network.c2s.C2S;
import de.tu_darmstadt.seemoo.nfcgate.network.data.NetworkStatus;
import de.tu_darmstadt.seemoo.nfcgate.util.DiagnosticsStats;
import de.tu_darmstadt.seemoo.nfcgate.util.ConnectionPresets;
import de.tu_darmstadt.seemoo.nfcgate.util.NfcComm;
import de.tu_darmstadt.seemoo.nfcgate.util.PrefUtils;
import de.tu_darmstadt.seemoo.nfcgate.util.RecentEvents;

import static de.tu_darmstadt.seemoo.nfcgate.network.c2s.C2S.ServerData.Opcode;

public class NetworkManager implements ServerConnection.Callback {
    private static final String TAG = "NetworkManager";

    // User-friendly backoff: 1s → 2s → 5s → 10s → 30s (then stays at 30s)
    private static final long[] RECONNECT_BACKOFF_MS = new long[] { 1_000, 2_000, 5_000, 10_000, 30_000 };

    private static final long WATCHDOG_TICK_MS = 5_000;

    public interface Callback {
        void onReceive(NfcComm data);
        void onNetworkStatus(NetworkStatus status);
    }

    // references
    private final MainActivity mActivity;
    private ServerConnection mConnection;
    private final Callback mCallback;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private int mReconnectAttempt = 0;
    private boolean mManualDisconnect = false;
    private boolean mReconnectScheduled = false;
    private final Runnable mReconnectRunnable = new Runnable() {
        @Override
        public void run() {
            mReconnectScheduled = false;
            if (mManualDisconnect) {
                return;
            }
            Log.w(TAG, "Auto-reconnect attempt");
            connect();
        }
    };

    private long mLastActivityMs = 0;
    private NetworkStatus mLastStatus = null;

    private volatile boolean mPausedSending = false;
    private final Runnable mWatchdogRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                watchdogTick();
            } finally {
                // Always reschedule; watchdogTick decides whether to act.
                mHandler.postDelayed(this, WATCHDOG_TICK_MS);
            }
        }
    };

    // preference data
    private String mHostname;
    private int mPort, mSessionNumber;

    public NetworkManager(MainActivity activity, Callback cb) {
        mActivity = activity;
        mCallback = cb;

        // Start watchdog loop (self-managed, low overhead).
        mHandler.postDelayed(mWatchdogRunnable, WATCHDOG_TICK_MS);
    }

    public void connect() {
        // read fresh preference data
        loadPreferenceData();

        // basic validation to avoid crashes and reconnect loops
        if (mHostname == null || mHostname.trim().isEmpty() || mPort <= 0) {
            Log.e(TAG, "Invalid host/port configuration");
            onNetworkStatus(NetworkStatus.ERROR);
            return;
        }

        mManualDisconnect = false;
        cancelReconnect();
        touchActivity();
        RecentEvents.info("Connect requested");

        // disconnect old connection
        if (mConnection != null) {
            // Do not mark as manual disconnect; this is a reconnect/replacement.
            try {
                mConnection.disconnect();
            } catch (Exception e) {
                Log.w(TAG, "Failed to close previous connection", e);
            }
            mConnection = null;
        }

        // establish connection
        boolean tlsEnabled = PreferenceManager.getDefaultSharedPreferences(mActivity)
                .getBoolean("tls", false);

        int sendQueueCapacity = PrefUtils.readClampedInt(mActivity, "send_queue_capacity", 256, 64, 8192);
        mConnection = new ServerConnection(mHostname, mPort, tlsEnabled, sendQueueCapacity)
                .setCallback(this)
                .connect();

        // queue initial handshake message
        sendServer(Opcode.OP_SYN, null);
    }

    /**
     * Immediate reconnect requested by the user.
     * Unlike the auto-reconnect loop, this resets backoff and forces a new connection attempt now.
     */
    public void reconnectNow() {
        RecentEvents.info("Manual reconnect requested");
        mManualDisconnect = false;
        mReconnectAttempt = 0;
        cancelReconnect();
        connect();
    }

    public void disconnect() {
        mManualDisconnect = true;
        cancelReconnect();
        RecentEvents.info("Disconnect requested");
        if (mConnection != null) {
            sendServer(Opcode.OP_FIN, null);
            mConnection.sync();
            mConnection.disconnect();
            mConnection = null;
        }
    }

    public boolean isPausedSending() {
        return mPausedSending;
    }

    public void setPausedSending(boolean paused) {
        if (mPausedSending == paused) {
            return;
        }
        mPausedSending = paused;
        RecentEvents.warn(paused ? "Sending paused" : "Sending resumed");
    }

    public void send(NfcComm data) {
        if (mPausedSending) {
            DiagnosticsStats.incDroppedSendMessages();
            return;
        }
        // queue data message
        touchActivity();
        sendServer(Opcode.OP_PSH, data.toByteArray());
    }

    @Override
    public void onReceive(byte[] data) {
        touchActivity();
        final C2S.ServerData serverData;
        try {
            serverData = C2S.ServerData.parseFrom(data);
        } catch (InvalidProtocolBufferException e) {
            Log.e(TAG, "Message parsing failed", e);
            RecentEvents.error("Message parsing failed", e);
            return;
        }

        Log.v(TAG, "Got message "+serverData.getOpcode().toString());
        switch (serverData.getOpcode()) {
            case OP_SYN:
                // empty syn message indicates our peer has just connected
                onNetworkStatus(NetworkStatus.PARTNER_CONNECT);
                // return ack
                sendServer(Opcode.OP_ACK, null);

                break;
            case OP_ACK:
                // empty ack message indicates our peer was already connected
                onNetworkStatus(NetworkStatus.PARTNER_CONNECT);

                break;
            case OP_FIN:
                // our peer has disconnected
                onNetworkStatus(NetworkStatus.PARTNER_LEFT);
                mConnection.disconnect();

                break;
            case OP_PSH:
                // pass data to callback
                mCallback.onReceive(new NfcComm(serverData.getData().toByteArray()));

                break;
        }
    }

    @Override
    public void onNetworkStatus(NetworkStatus status) {
        mLastStatus = status;

        // Keep UI updated first.
        mCallback.onNetworkStatus(status);

        // Diagnostics breadcrumbs (neutral, no payloads).
        switch (status) {
            case CONNECTING:
                RecentEvents.info("Connecting");
                break;
            case CONNECTED:
                RecentEvents.info("Connected");
                break;
            case PARTNER_CONNECT:
                RecentEvents.info("Partner connected");
                try {
                    // Auto-capture last successful connection settings.
                    ConnectionPresets.recordRecentFromCurrentSettings(mActivity);
                } catch (Exception ignored) {
                    // best-effort
                }
                break;
            case PARTNER_LEFT:
                RecentEvents.warn("Partner disconnected");
                break;
            case ERROR_TLS_CERT_UNKNOWN:
                RecentEvents.warn("TLS certificate unknown");
                break;
            case ERROR_TLS_CERT_UNTRUSTED:
                RecentEvents.warn("TLS certificate untrusted");
                break;
            case ERROR_TLS:
                RecentEvents.error("TLS error");
                break;
            case ERROR:
            default:
                RecentEvents.error("Connection error");
                break;
        }

        // Stability: try to reconnect on transient errors, but do not loop on
        // certificate-trust errors (those require user action).
        switch (status) {
            case CONNECTED:
            case PARTNER_CONNECT:
                mReconnectAttempt = 0;
                cancelReconnect();
                break;
            case ERROR:
            case ERROR_TLS:
                scheduleReconnect();
                break;
            case ERROR_TLS_CERT_UNKNOWN:
            case ERROR_TLS_CERT_UNTRUSTED:
                cancelReconnect();
                break;
            default:
                // no-op
                break;
        }
    }

    private void loadPreferenceData() {
        // read data from shared prefs
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mActivity);
        mHostname = prefs.getString("host", "193.169.244.23");
        mPort = readIntPref(prefs, "port", 5567);
        mSessionNumber = readIntPref(prefs, "session", 1);
    }

    private static int readIntPref(SharedPreferences prefs, String key, int defaultValue) {
        try {
            return Integer.parseInt(prefs.getString(key, String.valueOf(defaultValue)));
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private void sendServer(Opcode opcode, byte[] data) {
        if (mConnection == null) {
            Log.w(TAG, "sendServer called without connection");
            return;
        }
        touchActivity();
        mConnection.send(mSessionNumber,
                C2S.ServerData.newBuilder()
                    .setOpcode(opcode)
                    .setData(data == null ? ByteString.EMPTY : ByteString.copyFrom(data))
                    .build()
                    .toByteArray());
    }

    private void touchActivity() {
        mLastActivityMs = System.currentTimeMillis();
    }

    private void watchdogTick() {
        if (mManualDisconnect) {
            return;
        }

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mActivity);
        boolean enabled = prefs.getBoolean("network_watchdog", true);
        if (!enabled) {
            return;
        }

        // Avoid fighting with certificate trust flows (requires user action).
        if (mLastStatus == NetworkStatus.ERROR_TLS_CERT_UNKNOWN || mLastStatus == NetworkStatus.ERROR_TLS_CERT_UNTRUSTED) {
            return;
        }

        if (mConnection == null) {
            return;
        }

        int timeoutSec;
        try {
            timeoutSec = Integer.parseInt(prefs.getString("network_watchdog_timeout_sec", "90"));
        } catch (NumberFormatException ignored) {
            timeoutSec = 90;
        }

        if (timeoutSec <= 0) {
            return;
        }

        long now = System.currentTimeMillis();
        long last = mLastActivityMs;
        if (last <= 0) {
            return;
        }

        long idleMs = now - last;
        if (idleMs < (long) timeoutSec * 1000L) {
            return;
        }

        // Soft restart: close the current connection and let the existing backoff take over.
        RecentEvents.warn("Watchdog: no activity for " + (idleMs / 1000L) + "s; reconnecting");
        DiagnosticsStats.incWatchdogReconnects();
        // Keep the privacy overlay/status banner understandable.
        mLastStatus = NetworkStatus.CONNECTING;
        mCallback.onNetworkStatus(NetworkStatus.CONNECTING);
        try {
            mConnection.disconnect();
        } catch (Exception e) {
            Log.w(TAG, "Watchdog disconnect failed", e);
            RecentEvents.error("Watchdog disconnect failed", e);
        }
        mConnection = null;
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (mManualDisconnect) {
            return;
        }
        if (mReconnectScheduled) {
            return;
        }
        mReconnectScheduled = true;

        int idx = Math.min(mReconnectAttempt, RECONNECT_BACKOFF_MS.length - 1);
        long delay = RECONNECT_BACKOFF_MS[idx];
        if (mReconnectAttempt < Integer.MAX_VALUE) {
            mReconnectAttempt++;
        }
        Log.w(TAG, "Scheduling reconnect in " + delay + "ms");
        mHandler.postDelayed(mReconnectRunnable, delay);
    }

    private void cancelReconnect() {
        if (!mReconnectScheduled) {
            return;
        }
        mReconnectScheduled = false;
        mHandler.removeCallbacks(mReconnectRunnable);
    }

}
