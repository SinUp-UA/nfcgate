package de.tu_darmstadt.seemoo.nfcgate.gui.log;

import androidx.lifecycle.ViewModelProviders;
import androidx.fragment.app.Fragment;

import android.content.Context;
import android.widget.Toast;

import java.util.Collections;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import de.tu_darmstadt.seemoo.nfcgate.db.AppDatabase;
import de.tu_darmstadt.seemoo.nfcgate.db.NfcCommEntry;
import de.tu_darmstadt.seemoo.nfcgate.db.SessionLog;
import de.tu_darmstadt.seemoo.nfcgate.db.model.SessionLogEntryViewModel;
import de.tu_darmstadt.seemoo.nfcgate.db.model.SessionLogEntryViewModelFactory;
import de.tu_darmstadt.seemoo.nfcgate.db.pcapng.ISO14443Stream;
import de.tu_darmstadt.seemoo.nfcgate.gui.component.ContentShare;
import de.tu_darmstadt.seemoo.nfcgate.util.NfcComm;
import de.tu_darmstadt.seemoo.nfcgate.util.PrefUtils;

public class LogAction {
    private final Fragment mFragment;
    private final List<NfcComm> mLogItems = new ArrayList<>();

    public LogAction(Fragment fragment) {
        mFragment = fragment;
    }

    public void delete(final SessionLog session) {
        new Thread() {
            @Override
            public void run() {
                AppDatabase.getDatabase(mFragment.getActivity()).sessionLogDao().delete(session);
            }
        }.start();
    }

    public void share(final SessionLog session) {
        // clear previous items
        mLogItems.clear();

        // setup db model
        final SessionLogEntryViewModel mLogEntryModel = ViewModelProviders.of(mFragment, new SessionLogEntryViewModelFactory(
                        mFragment.getActivity().getApplication(), session.getId()))
                .get(SessionLogEntryViewModel.class);

        mLogEntryModel.getSession().observe(mFragment, sessionLogJoin -> {
            if (sessionLogJoin != null && mLogItems.isEmpty()) {
                for (NfcCommEntry nfcCommEntry : sessionLogJoin.getNfcCommEntries())
                    mLogItems.add(nfcCommEntry.getNfcComm());

                share(session, mLogItems);
            }
        });
    }

    public void share(SessionLog sessionLog, List<NfcComm> logItems) {
        // share pcap
        new ContentShare(mFragment.getActivity())
                .setPrefix(sessionLog.toString())
                .setExtension(".pcapng")
                .setMimeType("application/*")
                .setFile(new ISO14443Stream().append(logItems))
                .share();
    }

    /**
     * Exports all log entries whose internal timestamps are within the last {@code seconds}.
     * This does not require a selected session; it scans the most recent database entries.
     */
    public void shareLastSeconds(int seconds) {
        if (seconds <= 0) {
            return;
        }

        final Context ctx = mFragment.getActivity();
        if (ctx == null) {
            return;
        }

        new Thread(() -> {
            long now = System.currentTimeMillis();
            long cutoff = now - (seconds * 1000L);

            // Best-effort sizing: use configured rate limit as an estimate.
            int perSec = PrefUtils.readClampedInt(ctx, "log_rate_limit_per_sec", 200, 0, 100_000);
            if (perSec <= 0) {
                perSec = 500;
            }
            int limit = clamp(seconds * perSec + 200, 1000, 20000);

            List<NfcCommEntry> recent = AppDatabase.getDatabase(ctx).nfcCommEntryDao().getRecent(limit);
            if (recent == null || recent.isEmpty()) {
                if (mFragment.getActivity() != null) {
                    mFragment.getActivity().runOnUiThread(() -> Toast.makeText(ctx, ctx.getString(de.tu_darmstadt.seemoo.nfcgate.R.string.log_export_recent_empty), Toast.LENGTH_LONG).show());
                }
                return;
            }

            List<NfcComm> selected = new ArrayList<>();
            // recent is DESC by entryId; keep only those within cutoff.
            for (NfcCommEntry e : recent) {
                if (e == null) continue;
                NfcComm comm = e.getNfcComm();
                if (comm == null) continue;
                if (comm.getTimestamp() >= cutoff) {
                    selected.add(comm);
                } else {
                    // As soon as we drop below cutoff, older entries will likely also be below.
                    // Because entryId correlates with time, this is a good early exit.
                    break;
                }
            }

            if (selected.isEmpty()) {
                if (mFragment.getActivity() != null) {
                    mFragment.getActivity().runOnUiThread(() -> Toast.makeText(ctx, ctx.getString(de.tu_darmstadt.seemoo.nfcgate.R.string.log_export_recent_empty), Toast.LENGTH_LONG).show());
                }
                return;
            }

            // reverse into chronological order
            Collections.reverse(selected);

            String prefix = "recent-" + seconds + "s-" + SessionLog.isoDateFormatter().format(new Date());
            if (mFragment.getActivity() != null) {
                mFragment.getActivity().runOnUiThread(() -> new ContentShare(ctx)
                        .setPrefix(prefix)
                        .setExtension(".pcapng")
                        .setMimeType("application/*")
                        .setFile(new ISO14443Stream().append(selected))
                        .share());
            }
        }, "LogExportRecent").start();
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }
}
