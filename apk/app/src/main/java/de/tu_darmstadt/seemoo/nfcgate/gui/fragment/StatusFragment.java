package de.tu_darmstadt.seemoo.nfcgate.gui.fragment;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;

import java.util.HashMap;
import java.util.Map;

import de.tu_darmstadt.seemoo.nfcgate.R;
import de.tu_darmstadt.seemoo.nfcgate.gui.component.CustomArrayAdapter;
import de.tu_darmstadt.seemoo.nfcgate.gui.component.ContentShare;
import de.tu_darmstadt.seemoo.nfcgate.gui.component.StatusItem;
import de.tu_darmstadt.seemoo.nfcgate.nfc.NfcManager;
import de.tu_darmstadt.seemoo.nfcgate.nfc.chip.NfcChip;
import de.tu_darmstadt.seemoo.nfcgate.network.UserTrustManager;
import de.tu_darmstadt.seemoo.nfcgate.network.transport.PlainTransport;
import de.tu_darmstadt.seemoo.nfcgate.network.transport.TLSTransport;
import de.tu_darmstadt.seemoo.nfcgate.network.transport.Transport;
import de.tu_darmstadt.seemoo.nfcgate.util.DiagnosticsStats;
import de.tu_darmstadt.seemoo.nfcgate.util.DeviceNames;

public class StatusFragment extends BaseFragment {
    // ui references
    private ListView mStatus;
    private StatusListAdapter mStatusAdapter;

    private AlertDialog mSelfTestRunningDialog;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_status, container, false);

        // setup listview
        mStatus = v.findViewById(R.id.status_list);

        // custom toolbar actions
        setHasOptionsMenu(true);
        // set version as subtitle
        getMainActivity().getSupportActionBar().setSubtitle(getString(R.string.about_version, AboutFragment.getVersionNameGit()));

        // handlers
        mStatus.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @DrawableRes
            private int byState(StatusItem.State state) {
                switch (state) {
                    default:
                    case WARN:
                        return R.drawable.ic_warning_grey_24dp;
                    case ERROR:
                        return R.drawable.ic_error_grey_24dp;
                }
            }

            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int position, long id) {
                if (position >= 0) {
                    final StatusItem item = mStatusAdapter.getItem(position);

                    if (item.getState() != StatusItem.State.OK) {
                        new AlertDialog.Builder(getActivity())
                                .setTitle(getString(item.getState() == StatusItem.State.WARN ?
                                        R.string.status_warning : R.string.status_error))
                                .setPositiveButton(getString(R.string.button_ok), null)
                                .setIcon(byState(item.getState()))
                                .setMessage(item.getMessage())
                                .show();
                    }
                }
            }
        });

        return v;
    }

    @Override
    public void onActivityCreated(@Nullable Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);

        mStatusAdapter = new StatusListAdapter(getActivity(), R.layout.list_status);
        mStatus.setAdapter(mStatusAdapter);

        detect();
    }

    @Override
    public void onDestroyView() {
        if (mSelfTestRunningDialog != null) {
            try {
                mSelfTestRunningDialog.dismiss();
            } catch (Exception ignored) {
                // ignore
            }
            mSelfTestRunningDialog = null;
        }
        super.onDestroyView();
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.toolbar_status, menu);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_selftest) {
            runConnectionSelfTest();
            return true;
        }
        else if (item.getItemId() == R.id.action_copy) {
            shareConfigAsText();
            return true;
        }
        else if (item.getItemId() == R.id.action_export) {
            shareConfigAsFile();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    String buildConfigContent() {
        // add app version
        final StringBuilder str = new StringBuilder("Version: ")
                .append(AboutFragment.getVersionNameGit());

        // add full device info
        for (int i = 0; i < mStatusAdapter.getCount();  i++)
            str.append("\n").append(mStatusAdapter.getItem(i));

        return str.toString();
    }

    void shareConfigAsText() {
        new ContentShare(getActivity())
                .setMimeType("text/plain")
                .setText(buildConfigContent())
                .share();
    }

    void shareConfigAsFile() {
        new ContentShare(getActivity())
                .setPrefix("config")
                .setExtension(".txt")
                .setMimeType("text/plain")
                .setFile(stream -> stream.write(buildConfigContent().getBytes()))
                .share();
    }

    void detect() {
        mStatusAdapter.add(detectDeviceName());
        mStatusAdapter.add(detectAndroidVersion());
        mStatusAdapter.add(detectBuildNumber());
        mStatusAdapter.add(detectNfcEnabled());
        mStatusAdapter.add(detectHceCapability());
        mStatusAdapter.add(detectModuleEnabled());
        mStatusAdapter.add(detectNativeHookEnabled());
        mStatusAdapter.add(detectNfcModel());
        mStatusAdapter.add(detectDroppedSends());
        mStatusAdapter.add(detectDroppedLogs());
        mStatusAdapter.add(detectWatchdogReconnects());

        mStatusAdapter.notifyDataSetChanged();
    }

    StatusItem detectDroppedSends() {
        int count = DiagnosticsStats.getDroppedSendMessages();
        StatusItem item = new StatusItem(getContext(), getString(R.string.status_diag_dropped_sends))
                .setValue(String.valueOf(count));
        if (count > 0) {
            item.setWarn(getString(R.string.status_diag_dropped_sends_warn));
        }
        return item;
    }

    StatusItem detectDroppedLogs() {
        int count = DiagnosticsStats.getDroppedLogEntries();
        StatusItem item = new StatusItem(getContext(), getString(R.string.status_diag_dropped_logs))
                .setValue(String.valueOf(count));
        if (count > 0) {
            item.setWarn(getString(R.string.status_diag_dropped_logs_warn));
        }
        return item;
    }

    StatusItem detectWatchdogReconnects() {
        int count = DiagnosticsStats.getWatchdogReconnects();
        StatusItem item = new StatusItem(getContext(), getString(R.string.status_diag_watchdog_reconnects))
                .setValue(String.valueOf(count));
        if (count > 0) {
            item.setWarn(getString(R.string.status_diag_watchdog_reconnects_warn));
        }
        return item;
    }

    private void runConnectionSelfTest() {
        if (!isAdded()) {
            return;
        }

        if (mSelfTestRunningDialog != null) {
            return;
        }

        // Read settings used by NetworkManager.
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        final String host = prefs.getString("host", "");
        final int port;
        try {
            port = Integer.parseInt(prefs.getString("port", "5567"));
        } catch (Exception ignored) {
            showSelfTestResult(host, -1, prefs.getBoolean("tls", false), prefs.getBoolean("tls_pinning", false), false,
                    getString(R.string.status_selftest_error_bad_port));
            return;
        }
        final boolean tlsEnabled = prefs.getBoolean("tls", false);
        final boolean pinningEnabled = prefs.getBoolean("tls_pinning", false);

        if (host == null || host.trim().isEmpty() || port <= 0) {
            showSelfTestResult(host, port, tlsEnabled, pinningEnabled, false,
                    getString(R.string.status_selftest_error_bad_host_port));
            return;
        }

        mSelfTestRunningDialog = new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.status_selftest_title))
                .setMessage(getString(R.string.status_selftest_running))
                .setCancelable(false)
                .show();

        new Thread(() -> {
            boolean ok = false;
            String errorMessage = null;

            Transport transport = null;
            try {
                transport = tlsEnabled ? new TLSTransport(host, port) : new PlainTransport(host, port);
                transport.connect();
                ok = true;
            } catch (Exception e) {
                Throwable cause = e;
                while (cause.getCause() != null) {
                    cause = cause.getCause();
                }

                if (cause instanceof UserTrustManager.UnknownTrustException) {
                    errorMessage = getString(R.string.status_selftest_error_tls_unknown_cert);
                } else if (cause instanceof UserTrustManager.UntrustedException) {
                    errorMessage = getString(R.string.status_selftest_error_tls_untrusted_cert);
                } else if (cause instanceof SSLException) {
                    errorMessage = getString(R.string.status_selftest_error_tls);
                } else if (cause instanceof UnknownHostException) {
                    errorMessage = getString(R.string.status_selftest_error_unknown_host);
                } else if (cause instanceof SocketTimeoutException) {
                    errorMessage = getString(R.string.status_selftest_error_timeout);
                } else if (cause instanceof ConnectException) {
                    errorMessage = getString(R.string.status_selftest_error_refused);
                } else {
                    String cls = cause.getClass().getSimpleName();
                    String msg = cause.getMessage();
                    errorMessage = cls + (msg == null || msg.trim().isEmpty() ? "" : (": " + msg));
                }
            } finally {
                if (transport != null) {
                    try {
                        transport.close(true);
                    } catch (Exception ignored) {
                        // ignore
                    }
                }
            }

            final boolean okFinal = ok;
            final String errFinal = errorMessage;
            if (isAdded()) {
                requireActivity().runOnUiThread(() -> {
                    if (mSelfTestRunningDialog != null) {
                        try {
                            mSelfTestRunningDialog.dismiss();
                        } catch (Exception ignored) {
                            // ignore
                        }
                        mSelfTestRunningDialog = null;
                    }
                    showSelfTestResult(host, port, tlsEnabled, pinningEnabled, okFinal, errFinal);
                });
            }
        }, "StatusSelfTest").start();
    }

    private void showSelfTestResult(String host, int port, boolean tlsEnabled, boolean pinningEnabled, boolean ok, String errorMessage) {
        if (!isAdded()) {
            return;
        }

        String message = getString(R.string.status_selftest_kv_host, host)
                + "\n" + getString(R.string.status_selftest_kv_port, port)
                + "\n" + getString(R.string.status_selftest_kv_tls, getString(tlsEnabled ? R.string.status_yes : R.string.status_no))
                + "\n" + getString(R.string.status_selftest_kv_pinning, getString(pinningEnabled ? R.string.status_yes : R.string.status_no))
                + "\n\n" + (ok ? getString(R.string.status_selftest_result_ok) : getString(R.string.status_selftest_result_fail));

        if (!ok && errorMessage != null && !errorMessage.trim().isEmpty()) {
            message += "\n" + getString(R.string.status_selftest_kv_error, errorMessage);
        }

        new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.status_selftest_title))
                .setMessage(message)
                .setPositiveButton(getString(R.string.button_ok), null)
                .show();
    }

    StatusItem detectDeviceName() {
        // transform code name into market name
        String deviceString = new DeviceNames(getContext()).formatCurrentDeviceName();

        // device name should be OK for all supported devices
        StatusItem result = new StatusItem(getContext(), getString(R.string.status_devname)).setValue(deviceString);

        // No hist byte on this specific combination
        if ("Nexus 5X".equals(Build.MODEL) && Build.VERSION.RELEASE.equals("6.0.1"))
            result.setWarn(getString(R.string.warn_5X601));

        return result;
    }

    Map<Integer, String> ANDROID_VERSION_CODENAMES_MAP = new HashMap<>() {{
        put(Build.VERSION_CODES.LOLLIPOP, "Lollipop");
        put(Build.VERSION_CODES.LOLLIPOP_MR1, "Lollipop");
        put(Build.VERSION_CODES.M, "Marshmallow");
        put(Build.VERSION_CODES.N, "Nougat");
        put(Build.VERSION_CODES.N_MR1, "Nougat");
        put(Build.VERSION_CODES.O, "Oreo");
        put(Build.VERSION_CODES.O_MR1, "Oreo");
        put(Build.VERSION_CODES.P, "Pie");
        put(Build.VERSION_CODES.Q, "Quince Tart");
        put(Build.VERSION_CODES.R, "Red Velvet Cake");
        put(Build.VERSION_CODES.S, "Snow Cone");
        put(Build.VERSION_CODES.S_V2, "Snow Cone v2");
        put(Build.VERSION_CODES.TIRAMISU, "Tiramisu");
        put(Build.VERSION_CODES.UPSIDE_DOWN_CAKE, "Upside Down Cake");
        put(Build.VERSION_CODES.VANILLA_ICE_CREAM, "Vanilla Ice Cream");
        put(36 /* Build.VERSION_CODES.BAKLAVA */, "Baklava"); // 36 is the latest version as of June 2025
    }};

    StatusItem detectAndroidVersion() {
        // get android version codename for the current SDK_INT or use "Unknown" if not found
        String versionCodeName = ANDROID_VERSION_CODENAMES_MAP.containsKey(Build.VERSION.SDK_INT) ?
                ANDROID_VERSION_CODENAMES_MAP.get(Build.VERSION.SDK_INT) : getString(R.string.status_unknown);
        // assemble the version number, codename and SDK_INT into a single string in the format:
        // "<versionNumber> <codeName> (<SDK_INT>)", e.g. "10 Quince Tart (29)"
        String versionText = getString(R.string.status_version_text, Build.VERSION.RELEASE,
                versionCodeName, Build.VERSION.SDK_INT);

        // android version should be OK for all supported versions
        StatusItem result = new StatusItem(getContext(), getString(R.string.status_version)).setValue(versionText);

        // Android 16 and above is untested
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.VANILLA_ICE_CREAM)
            result.setWarn(getString(R.string.warn_AV));

        return result;
    }

    StatusItem detectBuildNumber() {
        // build number
        StatusItem result = new StatusItem(getContext(), getString(R.string.status_build)).setValue(Build.DISPLAY);

        return result;
    }

    StatusItem detectNfcEnabled() {
        // NFC capability and enabled
        boolean hasNfc = getNfc().isEnabled();
        // NFC Capability should be OK if it is enabled
        StatusItem result = new StatusItem(getContext(), getString(R.string.status_nfc)).setValue(hasNfc);

        if (!hasNfc)
            result.setError(getString(R.string.error_NFCCAP));

        return result;
    }

    StatusItem detectHceCapability() {
        // HCE capability
        boolean hasHCE = getNfc().hasHce();
        // HCE Capability
        StatusItem result = new StatusItem(getContext(), getString(R.string.status_hce)).setValue(hasHCE);

        if (!hasHCE)
            result.setWarn(getString(R.string.warn_HCE));

        return result;
    }

    StatusItem detectModuleEnabled() {
        // xposed module enabled
        boolean hasModule = NfcManager.isModuleLoaded();
        // Xposed module should be OK if it is enabled
        StatusItem result = new StatusItem(getContext(), getString(R.string.status_xposed)).setValue(hasModule);

        if (!hasModule)
            result.setWarn(getString(R.string.warn_XPOMOD));

        return result;
    }

    StatusItem detectNativeHookEnabled() {
        // native hook enabled
        boolean hasNativeHook = getNfc().isHookEnabled();
        // native hook is OK if enabled
        StatusItem result = new StatusItem(getContext(), getString(R.string.status_hook)).setValue(hasNativeHook);

        if (!hasNativeHook)
            result.setWarn(getString(R.string.warn_NATMOD));

        return result;
    }

    StatusItem detectNfcModel() {
        // null or chip model name
        String chipName = NfcChip.detect();
        // Chip model should be OK if it can be detected
        StatusItem result = new StatusItem(getContext(), getString(R.string.status_chip))
                .setValue(chipName != null ? chipName : getString(R.string.status_unknown));

        if (chipName == null)
            result.setWarn(getString(R.string.warn_NFCMOD));

        return result;
    }

    private static class StatusListAdapter extends CustomArrayAdapter<StatusItem> {
        StatusListAdapter(@NonNull Context context, int resource) {
            super(context, resource);
        }

        @DrawableRes
        private int byState(StatusItem.State state) {
            switch (state) {
                default:
                case OK:
                    return R.drawable.ic_check_circle_green_24dp;
                case WARN:
                    return R.drawable.ic_help_orange_24dp;
                case ERROR:
                    return R.drawable.ic_error_red_24dp;
            }
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            View v = super.getView(position, convertView, parent);
            final StatusItem item = getItem(position);

            v.<TextView>findViewById(R.id.status_name).setText(item.getName());
            v.<TextView>findViewById(R.id.status_value).setText(item.getValue());
            v.<ImageView>findViewById(R.id.status_icon).setImageResource(byState(item.getState()));

            return v;
        }
    }
}
