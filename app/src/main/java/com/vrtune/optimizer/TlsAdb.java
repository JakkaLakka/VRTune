package com.vrtune.optimizer;

import android.content.Context;
import android.os.Build;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import io.github.muntashirakon.adb.AdbStream;

/**
 * Android 11+ "Wireless debugging" transport (TLS), plus pairing with a 6-digit code.
 * Uses the same RSA key as the legacy transport, wrapped in a self-signed certificate.
 */
final class TlsAdb extends AbsAdbConnectionManager implements AdbShell {
    private final PrivateKey privateKey;
    private final X509Certificate certificate;

    private TlsAdb(PrivateKey key, X509Certificate cert) {
        privateKey = key;
        certificate = cert;
        setApi(Build.VERSION.SDK_INT);
        setTimeout(15, TimeUnit.SECONDS);
        setThrowOnUnauthorised(true);
    }

    static TlsAdb create(Context ctx) throws Exception {
        AdbKeys keys = AdbKeys.loadOrCreate(ctx.getFilesDir());
        return new TlsAdb(keys.privateKey, loadOrCreateCert(ctx.getFilesDir(), keys));
    }

    private static X509Certificate loadOrCreateCert(File dir, AdbKeys keys) throws Exception {
        File f = new File(dir, "adbkey.cert.der");
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        if (f.exists()) {
            try {
                X509Certificate c = (X509Certificate) cf.generateCertificate(
                        new ByteArrayInputStream(Files.readAllBytes(f.toPath())));
                if (c.getPublicKey().equals(keys.publicKey)) return c;
            } catch (Exception ignored) {
                // regenerate below
            }
        }
        X500Name name = new X500Name("CN=VRTune");
        Date from = new Date(System.currentTimeMillis() - 86_400_000L);
        Date to = new Date(System.currentTimeMillis() + 3650L * 86_400_000L);
        X509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(System.currentTimeMillis()), from, to, name, keys.publicKey);
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(keys.privateKey);
        X509Certificate cert = new JcaX509CertificateConverter().getCertificate(b.build(signer));
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(cert.getEncoded());
        }
        return cert;
    }

    @Override protected PrivateKey getPrivateKey() { return privateKey; }
    @Override protected Certificate getCertificate() { return certificate; }
    @Override protected String getDeviceName() { return "VRTune"; }

    // ---- AdbShell ----

    @Override
    public String shell(String command) throws IOException {
        try (AdbStream stream = openStream("shell:" + command);
             InputStream in = stream.openInputStream()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) != -1) buf.write(b, 0, n);
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        }
    }

    @Override
    public boolean isOpen() {
        return isConnected();
    }

    @Override
    public int maxCommandLength() {
        return 3500;
    }

    @Override
    public void close() {
        try {
            super.close();
        } catch (IOException ignored) {
        }
    }
}
