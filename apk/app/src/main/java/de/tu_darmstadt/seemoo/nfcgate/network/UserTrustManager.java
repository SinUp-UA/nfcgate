package de.tu_darmstadt.seemoo.nfcgate.network;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import android.util.Base64;
import android.util.Log;

import java.security.MessageDigest;
import java.security.cert.X509Certificate;

public class UserTrustManager {
    public enum Trust {
        TRUSTED,
        UNTRUSTED,
        UNKNOWN;

        public static Trust from(int value) {
            if (value >= Trust.UNKNOWN.ordinal())
                return Trust.UNKNOWN;
            return Trust.values()[value];
        }

    }
    public static class UnknownTrustException extends RuntimeException {}
    public static class UntrustedException extends RuntimeException {}

    private static final String TAG = "UserTrustManager";

    // singleton
    private static UserTrustManager mInstance;
    public static UserTrustManager getInstance() {
        return mInstance;
    }
    public static void init(Context context) {
        mInstance = new UserTrustManager(context);
    }

    protected SharedPreferences mPreferences;
    protected SharedPreferences mAppPreferences;
    protected X509Certificate[] cachedCertificateChain = null;

    public static String certificateChainHash(X509Certificate[] chain) {
        try {
            return Base64.encodeToString(certificateChainFingerprint(chain, "SHA512"), Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "Cannot calculate certificate hash", e);
            return null;
        }
    }

    public static byte[] certificateChainFingerprint(X509Certificate[] certificateChain, String algorithm) {
        try {
            MessageDigest hash = MessageDigest.getInstance(algorithm);
            for (X509Certificate certificate : certificateChain)
                hash.update(certificate.getEncoded());
            return hash.digest();
        } catch (Exception e) {
            Log.e(TAG, "Cannot calculate certificate hash", e);
            return new byte[]{};
        }
    }

    private UserTrustManager(Context context) {
        // use a dedicated certificate trust preferences file to avoid cluttering application
        // settings and allow easy clearing
        mPreferences = context.getSharedPreferences("certificate_trust", Context.MODE_PRIVATE);

        // normal app settings (TLS pinning, etc.)
        mAppPreferences = PreferenceManager.getDefaultSharedPreferences(context.getApplicationContext());
    }

    /**
     * Returns true if pinning is enabled.
     */
    public boolean isPinningEnabled() {
        if (mAppPreferences == null)
            return false;
        return mAppPreferences.getBoolean("tls_pinning", false);
    }

    public boolean isStrictPinningEnabled() {
        if (!isPinningEnabled())
            return false;
        String mode = mAppPreferences.getString("tls_pinning_mode", "normal_ca");
        return "strict_pinning".equals(mode);
    }

    public boolean hasConfiguredPin() {
        if (mAppPreferences == null)
            return false;
        String pin = mAppPreferences.getString("tls_pinning_spki_sha256", "");
        return pin != null && !pin.trim().isEmpty();
    }

    /**
     * Checks whether the provided certificate chain matches the configured SPKI SHA-256 pin.
     * Pin formats accepted:
     * - "sha256/BASE64"
     * - "BASE64"
     * - hex (64 chars) of SHA-256
     */
    public boolean matchesConfiguredPin(X509Certificate[] chain) {
        if (!isPinningEnabled())
            return true;

        // Strict mode requires a configured pin.
        if (isStrictPinningEnabled() && !hasConfiguredPin())
            return false;

        // Normal mode: if no pin configured, do nothing.
        if (!isStrictPinningEnabled() && !hasConfiguredPin())
            return true;
        if (chain == null || chain.length == 0 || chain[0] == null)
            return false;

        String configured = mAppPreferences.getString("tls_pinning_spki_sha256", "");
        byte[] expected = parsePinToBytes(configured);
        if (expected == null || expected.length == 0)
            return false;

        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            byte[] spki = chain[0].getPublicKey().getEncoded();
            byte[] actual = hash.digest(spki);
            return constantTimeEquals(expected, actual);
        } catch (Exception e) {
            Log.e(TAG, "Cannot validate certificate pin", e);
            return false;
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length)
            return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }

    private static byte[] parsePinToBytes(String pin) {
        if (pin == null)
            return null;
        String p = pin.trim();
        if (p.isEmpty())
            return null;

        // Accept the common prefix "sha256/".
        if (p.startsWith("sha256/")) {
            p = p.substring("sha256/".length()).trim();
        } else if (p.startsWith("sha256:")) {
            p = p.substring("sha256:".length()).trim();
        }

        // Hex form (64 hex chars for SHA-256)
        if (p.matches("(?i)^[0-9a-f]{64}$")) {
            int len = p.length();
            byte[] out = new byte[len / 2];
            for (int i = 0; i < len; i += 2) {
                out[i / 2] = (byte) Integer.parseInt(p.substring(i, i + 2), 16);
            }
            return out;
        }

        try {
            return Base64.decode(p, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Invalid pin format", e);
            return null;
        }
    }

    public Trust checkCertificate(X509Certificate[] chain) {
        // get user trust value for certificate chain, defaulting to UNKNOWN trust
        int trustValue = mPreferences.getInt(certificateChainHash(chain), Trust.UNKNOWN.ordinal());
        return Trust.from(trustValue);
    }

    public void setCertificateTrust(X509Certificate[] chain, Trust trust) {
        mPreferences.edit().putInt(certificateChainHash(chain), trust.ordinal()).apply();
    }

    public void clearTrust() {
        // clears all saved certificate trust
        mPreferences.edit().clear().apply();
    }

    public X509Certificate[] getCachedCertificateChain() {
        return cachedCertificateChain;
    }

    public void setCachedCertificateChain(X509Certificate[] cachedCertificate) {
        this.cachedCertificateChain = cachedCertificate;
    }
}
