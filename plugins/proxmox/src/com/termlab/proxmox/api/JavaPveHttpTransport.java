package com.termlab.proxmox.api;

import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.HexFormat;

public final class JavaPveHttpTransport implements PveHttpTransport {
    private static final Logger LOG = Logger.getInstance(JavaPveHttpTransport.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Override
    public @NotNull PveHttpResponse send(
        @NotNull PveHttpRequest request,
        String trustedCertificateSha256
    ) throws IOException, InterruptedException, PveCertificateException {
        try {
            long startedNanos = System.nanoTime();
            HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                .timeout(TIMEOUT)
                .header("Authorization", request.authorization())
                .header("Accept", "application/json");
            if ("POST".equals(request.method())) {
                builder.header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(request.body() == null ? "" : request.body()));
            } else {
                builder.GET();
            }
            HttpResponse<String> response = client(trustedCertificateSha256).send(
                builder.build(),
                HttpResponse.BodyHandlers.ofString()
            );
            long elapsedMs = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
            LOG.info("TermLab Proxmox: HTTP " + request.method() + " " + request.uri()
                + " completed status=" + response.statusCode() + " elapsedMs=" + elapsedMs);
            return new PveHttpResponse(response.statusCode(), response.body() == null ? "" : response.body());
        } catch (SSLHandshakeException e) {
            PveCertificateException cert = findCertificateException(e);
            if (cert != null) {
                LOG.warn("TermLab Proxmox: TLS certificate check failed for " + request.uri()
                    + " fingerprint=" + cert.fingerprint() + " message=" + cert.getMessage());
                throw cert;
            }
            throw e;
        }
    }

    private static @NotNull HttpClient client(String trustedCertificateSha256) {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new PinningTrustManager(trustedCertificateSha256)}, new SecureRandom());
            return HttpClient.newBuilder().connectTimeout(TIMEOUT).sslContext(context).build();
        } catch (Exception e) {
            throw new IllegalStateException("Could not initialize Proxmox TLS context", e);
        }
    }

    private static PveCertificateException findCertificateException(Throwable error) {
        Throwable cursor = error;
        while (cursor != null) {
            if (cursor instanceof CertificateBridgeException bridge) return bridge.cause;
            cursor = cursor.getCause();
        }
        return null;
    }

    private static final class PinningTrustManager implements X509TrustManager {
        private final X509TrustManager delegate;
        private final String trustedFingerprint;

        private PinningTrustManager(String trustedFingerprint) throws Exception {
            this.delegate = defaultTrustManager();
            this.trustedFingerprint = normalize(trustedFingerprint);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            delegate.checkClientTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            try {
                delegate.checkServerTrusted(chain, authType);
                if (trustedFingerprint != null && !trustedFingerprint.isBlank()) {
                    String fingerprint = fingerprint(chain);
                    if (!trustedFingerprint.equals(normalize(fingerprint))) {
                        throw new CertificateBridgeException(new PveCertificateMismatchException(fingerprint));
                    }
                }
            } catch (CertificateException defaultRejected) {
                if (defaultRejected instanceof CertificateBridgeException) throw defaultRejected;
                String fingerprint = fingerprint(chain);
                if (trustedFingerprint == null || trustedFingerprint.isBlank()) {
                    LOG.info("TermLab Proxmox: server certificate rejected by default trust and no pin is stored; fingerprint="
                        + fingerprint);
                    throw new CertificateBridgeException(new PveUnknownCertificateException(fingerprint));
                }
                if (trustedFingerprint.equals(normalize(fingerprint))) {
                    LOG.info("TermLab Proxmox: accepted pinned server certificate fingerprint=" + fingerprint);
                    return;
                }
                throw new CertificateBridgeException(new PveCertificateMismatchException(fingerprint));
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return delegate.getAcceptedIssuers();
        }
    }

    private static final class CertificateBridgeException extends CertificateException {
        private final PveCertificateException cause;

        private CertificateBridgeException(PveCertificateException cause) {
            super(cause.getMessage());
            this.cause = cause;
        }
    }

    private static X509TrustManager defaultTrustManager() throws Exception {
        javax.net.ssl.TrustManagerFactory factory = javax.net.ssl.TrustManagerFactory.getInstance(
            javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        factory.init((java.security.KeyStore) null);
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager x509) return x509;
        }
        throw new IllegalStateException("No default X509 trust manager available");
    }

    private static @NotNull String fingerprint(X509Certificate[] chain) throws CertificateException {
        if (chain == null || chain.length == 0) throw new CertificateException("No server certificate presented");
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
            return HexFormat.ofDelimiter(":").withUpperCase().formatHex(digest);
        } catch (Exception e) {
            throw new CertificateException("Could not fingerprint server certificate", e);
        }
    }

    private static String normalize(String value) {
        if (value == null) return null;
        return value.replace(":", "").replace(" ", "").trim().toUpperCase();
    }
}
