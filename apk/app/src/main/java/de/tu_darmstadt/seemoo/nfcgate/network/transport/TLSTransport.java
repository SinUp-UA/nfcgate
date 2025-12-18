package de.tu_darmstadt.seemoo.nfcgate.network.transport;

import android.annotation.SuppressLint;
import android.util.Log;

import java.io.IOException;
import java.net.Socket;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import de.tu_darmstadt.seemoo.nfcgate.network.UserTrustManager;

public class TLSTransport extends Transport {
    private static final String TAG = "TLSTransport";
    protected SSLContext mSslContext;

    public TLSTransport(String hostname, int port) {
        super(hostname, port);

        createSslContext();
    }

    protected void createSslContext() {
        try {
            mSslContext = SSLContext.getInstance("TLS");
            mSslContext.init(null, buildTrustManagers(), null);
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            Log.wtf(TAG, "Cannot instantiate SSLContext");
            throw new RuntimeException(e);
        }
    }

    @SuppressLint("CustomX509TrustManager")
    protected TrustManager[] buildTrustManagers() {
        try {
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            // we want to use the default TrustManager for verification purposes later
            X509TrustManager defaultManager = ((X509TrustManager) factory.getTrustManagers()[0]);

            // create our own TrustManager
            return new X509TrustManager[] { new UserX509TrustManager(defaultManager) };
        } catch (NoSuchAlgorithmException | KeyStoreException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected Socket createSocket() {
        SSLSocketFactory sslSocketFactory = mSslContext.getSocketFactory();
        try {
            return sslSocketFactory.createSocket();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    protected void connectSocket() throws IOException {
        mSocket.connect(mAddress, CONNECT_TIMEOUT_MS);

        SSLSocket sslSocket = (SSLSocket) mSocket;

        // Enforce modern TLS versions where available.
        // (Android 5+ supports TLSv1.2; TLSv1.3 depends on provider.)
        String[] supported = sslSocket.getSupportedProtocols();
        if (supported != null) {
            boolean has12 = false;
            boolean has13 = false;
            for (String p : supported) {
                if ("TLSv1.2".equals(p)) has12 = true;
                else if ("TLSv1.3".equals(p)) has13 = true;
            }
            if (has12 || has13) {
                if (has13) {
                    sslSocket.setEnabledProtocols(has12 ? new String[] {"TLSv1.3", "TLSv1.2"} : new String[] {"TLSv1.3"});
                } else {
                    sslSocket.setEnabledProtocols(new String[] {"TLSv1.2"});
                }
            }
        }

        // Enable endpoint identification (hostname verification) at the TLS layer.
        // We additionally verify below for consistency.
        SSLParameters params = sslSocket.getSSLParameters();
        params.setEndpointIdentificationAlgorithm("HTTPS");
        sslSocket.setSSLParameters(params);

        sslSocket.startHandshake();

        // verify the hostname, even though we do not use HTTPS, we can borrow the hostname verifier
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(mAddress.getHostName(), sslSocket.getSession()))
            throw new SSLHandshakeException("Hostname in certificate does not match");
    }

    protected static class UserX509TrustManager implements X509TrustManager {
        protected X509TrustManager mDefaultManager;

        public UserX509TrustManager(X509TrustManager defaultManager) {
            this.mDefaultManager = defaultManager;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            mDefaultManager.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            boolean trusted = false;
            try {
                mDefaultManager.checkServerTrusted(chain, authType);
                trusted = true;
            } catch (CertificateException e) {
                // If this is server authentication and the certificate path verification
                // fails, we check if the user explicitly trusted the certificate.
                // If verification fails due to other errors, e.g. expiry, fail immediately
                if (!(e.getCause() instanceof CertPathValidatorException))
                    throw e;

                UserTrustManager.Trust trust = UserTrustManager.getInstance().checkCertificate(chain);
                switch (trust) {
                    case TRUSTED:
                        trusted = true;
                        break;
                    case UNKNOWN:
                        UserTrustManager.getInstance().setCachedCertificateChain(chain);
                        throw new UserTrustManager.UnknownTrustException();
                    case UNTRUSTED:
                    default:
                        throw new UserTrustManager.UntrustedException();
                }
            }

            // Optional security hardening: pinning modes.
            UserTrustManager utm = UserTrustManager.getInstance();
            if (trusted && utm != null && utm.isPinningEnabled()) {
                // Strict pinning requires a configured pin.
                if (utm.isStrictPinningEnabled() && !utm.hasConfiguredPin()) {
                    throw new CertificateException("Strict pinning enabled but no pin configured");
                }
                if (!utm.matchesConfiguredPin(chain)) {
                    throw new CertificateException("Pinned key mismatch");
                }
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return mDefaultManager.getAcceptedIssuers();
        }
    }
}
