package de.tu_darmstadt.seemoo.nfcgate.network;

import android.util.Log;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import de.tu_darmstadt.seemoo.nfcgate.network.data.NetworkStatus;
import de.tu_darmstadt.seemoo.nfcgate.network.data.SendRecord;
import de.tu_darmstadt.seemoo.nfcgate.network.threading.ReceiveThread;
import de.tu_darmstadt.seemoo.nfcgate.network.threading.SendThread;
import de.tu_darmstadt.seemoo.nfcgate.network.transport.PlainTransport;
import de.tu_darmstadt.seemoo.nfcgate.network.transport.TLSTransport;
import de.tu_darmstadt.seemoo.nfcgate.network.transport.Transport;
import de.tu_darmstadt.seemoo.nfcgate.util.DiagnosticsStats;
import de.tu_darmstadt.seemoo.nfcgate.util.RecentEvents;

public class ServerConnection {
    private static final String TAG = "ServerConnection";

    private static final int SOCKET_READ_TIMEOUT_MS = 30_000;

    private static final int DEFAULT_SEND_QUEUE_CAPACITY = 256;

    public interface Callback {
        void onReceive(byte[] data);
        void onNetworkStatus(NetworkStatus status);
    }

    // connection objects
    private Transport mTransport;
    private final Object mSocketLock = new Object();

    // threading
    private SendThread mSendThread;
    private ReceiveThread mReceiveThread;
    private final BlockingQueue<SendRecord> mSendQueue;

    private int mDroppedSends = 0;

    // metadata
    private Callback mCallback;

    public ServerConnection(String hostname, int port, boolean tls) {
        this(hostname, port, tls, DEFAULT_SEND_QUEUE_CAPACITY);
    }

    public ServerConnection(String hostname, int port, boolean tls, int sendQueueCapacity) {
        this(tls ? new TLSTransport(hostname, port) : new PlainTransport(hostname, port), sendQueueCapacity);
    }

    ServerConnection(Transport transport) {
        this(transport, DEFAULT_SEND_QUEUE_CAPACITY);
    }

    private ServerConnection(Transport transport, int sendQueueCapacity) {
        mTransport = transport;
        int capacity = clamp(sendQueueCapacity, 64, 8192);
        mSendQueue = new LinkedBlockingQueue<>(capacity);
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    ServerConnection setCallback(Callback cb) {
        mCallback = cb;
        return this;
    }

    /**
     * Connects to the socket, enables async I/O
     */
    ServerConnection connect() {
        // reset transport to close sockets and allow re-initialization
        mTransport.close(true);

        // I/O threads
        mSendThread = new SendThread(this);
        mReceiveThread = new ReceiveThread(this);
        mSendThread.start();
        mReceiveThread.start();
        return this;
    }

    /**
     * Closes the connection and releases all resources
     */
    void disconnect() {
        if (mSendThread != null)
            mSendThread.interrupt();

        if (mReceiveThread != null)
            mReceiveThread.interrupt();
    }

    /**
     * Wait some time to allow sendQueue to be processed
     */
    void sync() {
        if (mSendQueue.peek() != null) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException ignored) { }
        }
    }

    /**
     * Schedules the data to be sent
     */
    public void send(int session, byte[] data) {
        Log.v(TAG, "Enqueuing message of " + data.length + " bytes");
        boolean ok = mSendQueue.offer(new SendRecord(session, data));
        if (!ok) {
            mDroppedSends++;
            DiagnosticsStats.incDroppedSendMessages();
            if (mDroppedSends == 1 || (mDroppedSends % 50) == 0) {
                RecentEvents.warn("Send queue full; dropped " + mDroppedSends + " messages");
            }
        }
    }

    /**
     * Called by threads to open socket
     */
    public Socket openSocket() throws IOException {
        synchronized (mSocketLock) {
            // do not try to establish transport if init was already called
            Log.d(TAG, "ServerConnection.openSocket(): " + Thread.currentThread().getId());
            try {
                reportStatus(NetworkStatus.CONNECTING);
                if (mTransport.connect()) {
                    // transport is newly connected
                    reportStatus(NetworkStatus.CONNECTED);
                    Socket socket = mTransport.socket();
                    if (socket != null) {
                        socket.setTcpNoDelay(true);
                        socket.setKeepAlive(true);
                        // Make blocking reads interrupt-friendly (ReceiveThread handles timeouts).
                        socket.setSoTimeout(SOCKET_READ_TIMEOUT_MS);
                    }
                }
            } catch (Exception e) {
                Log.d(TAG, "mTransport.init exception: " + Thread.currentThread().getId());
                Log.e(TAG, "Transport cannot connect", e);
                mTransport.close(false);

                // rethrow so that caller is also notified about exception
                throw e;
            }

            return mTransport.socket();
        }
    }

    /**
     * Called by threads to close socket
     */
    public void closeSocket() {
        synchronized (mSocketLock) {
            mTransport.close(false);
        }
    }

    /**
     * ReceiveThread delivers data
     */
    public void onReceive(byte[] data) {
        mCallback.onReceive(data);
    }

    /**
     * SendThread accesses sendQueue
     */
    public BlockingQueue<SendRecord> getSendQueue() {
        return mSendQueue;
    }

    /**
     * Reports a status to the callback if set
     */
    public void reportStatus(NetworkStatus status) {
        mCallback.onNetworkStatus(status);
    }
}
