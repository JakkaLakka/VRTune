package com.vrtune.optimizer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import javax.crypto.Cipher;

/**
 * The RSA identity this app uses to authenticate with adbd, stored in the app's private files.
 * The headset shows an "Allow debugging?" prompt the first time it sees this key.
 */
final class AdbKeys {
    private static final int KEY_BITS = 2048;
    private static final int KEY_BYTES = KEY_BITS / 8;
    private static final int KEY_WORDS = KEY_BYTES / 4;

    /** ASN.1 DigestInfo prefix for SHA-1; adbd signs its 20-byte token as if it were a SHA-1 digest. */
    private static final byte[] SHA1_DIGEST_INFO = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };

    final PrivateKey privateKey;
    final RSAPublicKey publicKey;

    AdbKeys(PrivateKey priv, RSAPublicKey pub) {
        this.privateKey = priv;
        this.publicKey = pub;
    }

    static synchronized AdbKeys loadOrCreate(File dir) throws IOException, GeneralSecurityException {
        File privFile = new File(dir, "adbkey");
        File pubFile = new File(dir, "adbkey.pub.der");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (privFile.exists() && pubFile.exists()) {
            try {
                PrivateKey priv = kf.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(privFile.toPath())));
                RSAPublicKey pub = (RSAPublicKey) kf.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(pubFile.toPath())));
                return new AdbKeys(priv, pub);
            } catch (Exception corrupt) {
                // fall through and regenerate
            }
        }
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(KEY_BITS);
        KeyPair kp = gen.generateKeyPair();
        write(privFile, kp.getPrivate().getEncoded());
        write(pubFile, kp.getPublic().getEncoded());
        return new AdbKeys(kp.getPrivate(), (RSAPublicKey) kp.getPublic());
    }

    private static void write(File f, byte[] data) throws IOException {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }

    /** PKCS#1 v1.5 signature of the adbd token (RSA_sign(NID_sha1, token) semantics). */
    byte[] sign(byte[] token) throws GeneralSecurityException {
        byte[] block = new byte[KEY_BYTES];
        int sepIndex = KEY_BYTES - token.length - SHA1_DIGEST_INFO.length - 1;
        block[0] = 0x00;
        block[1] = 0x01;
        for (int i = 2; i < sepIndex; i++) block[i] = (byte) 0xff;
        block[sepIndex] = 0x00;
        System.arraycopy(SHA1_DIGEST_INFO, 0, block, sepIndex + 1, SHA1_DIGEST_INFO.length);
        System.arraycopy(token, 0, block, sepIndex + 1 + SHA1_DIGEST_INFO.length, token.length);
        Cipher c = Cipher.getInstance("RSA/ECB/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, privateKey);
        return c.doFinal(block);
    }

    /** Public key in adbd's custom format: base64(RSAPublicKey struct) + " user@host" + NUL. */
    byte[] adbPublicKey() {
        BigInteger n = publicKey.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));
        BigInteger rr = BigInteger.ONE.shiftLeft(KEY_BITS * 2).mod(n);

        ByteBuffer bb = ByteBuffer.allocate(4 + 4 + KEY_BYTES + KEY_BYTES + 4).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(KEY_WORDS);
        bb.putInt(n0inv.intValue());
        putWords(bb, n);
        putWords(bb, rr);
        bb.putInt(publicKey.getPublicExponent().intValue());

        String s = Base64.getEncoder().encodeToString(bb.array()) + " vrtune@quest";
        byte[] str = s.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[str.length + 1];
        System.arraycopy(str, 0, out, 0, str.length);
        return out;
    }

    private static void putWords(ByteBuffer bb, BigInteger v) {
        for (int i = 0; i < KEY_WORDS; i++) bb.putInt(v.shiftRight(32 * i).intValue());
    }
}
