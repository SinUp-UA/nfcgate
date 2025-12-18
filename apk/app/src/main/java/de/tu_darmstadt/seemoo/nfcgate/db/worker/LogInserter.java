package de.tu_darmstadt.seemoo.nfcgate.db.worker;

import android.content.Context;

import java.util.Date;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import de.tu_darmstadt.seemoo.nfcgate.db.AppDatabase;
import de.tu_darmstadt.seemoo.nfcgate.db.NfcCommEntry;
import de.tu_darmstadt.seemoo.nfcgate.db.SessionLog;
import de.tu_darmstadt.seemoo.nfcgate.util.DiagnosticsStats;
import de.tu_darmstadt.seemoo.nfcgate.util.RecentEvents;
import de.tu_darmstadt.seemoo.nfcgate.util.NfcComm;
import de.tu_darmstadt.seemoo.nfcgate.util.PrefUtils;

public class LogInserter {
    public interface SIDChangedListener {
        void onSIDChanged(long sessionID);
    }

    // database
    private final AppDatabase mDatabase;
    private final SessionLog.SessionType mSessionType;
    private static final int DEFAULT_LOG_QUEUE_CAPACITY = 512;
    private static final int DEFAULT_MAX_LOGS_PER_SECOND = 200;
    private final BlockingQueue<LogEntry> mQueue;
    private final int mMaxLogsPerSecond;
    private long mSessionId = -1;

    private int mDroppedLogs = 0;
    private long mRateWindowStartMs = 0;
    private int mRateWindowCount = 0;

    // callback
    private final SIDChangedListener mListener;

    public LogInserter(Context ctx, SessionLog.SessionType sessionType, SIDChangedListener listener) {
        mDatabase = AppDatabase.getDatabase(ctx);
        mSessionType = sessionType;
        mListener = listener;

        int queueCapacity = PrefUtils.readClampedInt(ctx, "log_queue_capacity", DEFAULT_LOG_QUEUE_CAPACITY, 64, 16384);
        mQueue = new LinkedBlockingQueue<>(queueCapacity);

        // 0 disables rate limiting.
        int rate = PrefUtils.readClampedInt(ctx, "log_rate_limit_per_sec", DEFAULT_MAX_LOGS_PER_SECOND, 0, 100_000);
        mMaxLogsPerSecond = rate;

        new LogInserterThread().start();
    }

    private void setSessionId(long sid) {
        mSessionId = sid;

        if (mListener != null)
            mListener.onSIDChanged(sid);
    }

    public void log(NfcComm data) {
        // Lightweight rate limit to avoid overload on bursty APDU streams.
        if (mMaxLogsPerSecond > 0) {
            long now = System.currentTimeMillis();
            if (mRateWindowStartMs == 0 || (now - mRateWindowStartMs) >= 1000L) {
                mRateWindowStartMs = now;
                mRateWindowCount = 0;
            }
            mRateWindowCount++;
            if (mRateWindowCount > mMaxLogsPerSecond) {
                mDroppedLogs++;
                DiagnosticsStats.incDroppedLogEntries();
                if (mDroppedLogs == 1 || (mDroppedLogs % 200) == 0) {
                    RecentEvents.warn("Log rate limited; dropped " + mDroppedLogs + " entries");
                }
                return;
            }
        }

        boolean ok = mQueue.offer(new LogEntry(data));
        if (!ok) {
            mDroppedLogs++;
            DiagnosticsStats.incDroppedLogEntries();
            if (mDroppedLogs == 1 || (mDroppedLogs % 200) == 0) {
                RecentEvents.warn("Log queue full; dropped " + mDroppedLogs + " entries");
            }
        }
    }

    public void reset() {
        // Prioritize reset even under burst: clear queue and enqueue reset marker.
        mQueue.clear();
        mQueue.offer(new LogEntry());
    }

    class LogInserterThread extends Thread {
        LogInserterThread() {
            // ensure JVM stops this thread at the end of app
            setDaemon(true);
        }

        @Override
        public void run() {
            while (true) {
                try {
                    LogEntry entry = mQueue.take();

                    // set session id if none is set or reset it on reset data
                    if (!entry.isValid())
                        setSessionId(-1);
                    else if (mSessionId == -1)
                        setSessionId(mDatabase.sessionLogDao().insert(new SessionLog(new Date(), mSessionType)));

                    if (entry.isValid())
                        mDatabase.nfcCommEntryDao().insert(new NfcCommEntry(entry.getData(), mSessionId));

                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
