package com.vrtune.optimizer;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * Minimal ADB wire-protocol client (legacy TCP transport, i.e. after `adb tcpip 5555`).
 * Only what we need: connect, RSA auth, and running "shell:" commands one at a time.
 */
final class AdbClient implements Closeable {
    interface ApprovalListener {
        void onWaitingForApproval();
    }

    static final class TlsRequiredException extends IOException {
        TlsRequiredException() {
            super("This port needs TLS (a Wireless-debugging port). Use port 5555 after running 'adb tcpip 5555'.");
        }
    }

    static final class RejectedException extends IOException {
        RejectedException(String m) {
            super(m);
        }
    }

    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;
    private static final int A_STLS = 0x534c5453;

    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;

    private static final int VERSION = 0x01000000;
    private static final int MAX_PAYLOAD = 256 * 1024;
    private static final int MAX_ACCEPT = 16 * 1024 * 1024;

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int nextLocalId = 1;
    private int deviceMaxData = 4096;
    private volatile boolean closed;

    private AdbClient(Socket s) throws IOException {
        socket = s;
        in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
        out = s.getOutputStream();
    }

    static AdbClient connect(String host, int port, AdbKeys keys, ApprovalListener listener)
            throws IOException, GeneralSecurityException {
        Socket s = new Socket();
        s.setTcpNoDelay(true);
        s.connect(new InetSocketAddress(host, port), 4000);
        s.setSoTimeout(10_000);
        AdbClient c = new AdbClient(s);
        try {
            c.handshake(keys, listener);
            return c;
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            c.close();
            throw e;
        }
    }

    private void handshake(AdbKeys keys, ApprovalListener listener) throws IOException, GeneralSecurityException {
        send(A_CNXN, VERSION, MAX_PAYLOAD, "host::\0".getBytes(StandardCharsets.UTF_8));
        boolean sentSignature = false;
        boolean sentKey = false;
        while (true) {
            Msg m;
            try {
                m = read();
            } catch (EOFException eof) {
                throw new RejectedException(sentKey
                        ? "Debugging was not allowed in the headset prompt."
                        : "ADB closed the connection.");
            }
            switch (m.cmd) {
                case A_CNXN:
                    deviceMaxData = Math.max(1024, m.arg1);
                    socket.setSoTimeout(30_000);
                    return;
                case A_STLS:
                    throw new TlsRequiredException();
                case A_AUTH:
                    if (m.arg0 != AUTH_TOKEN) throw new IOException("Unexpected AUTH type " + m.arg0);
                    if (!sentSignature) {
                        send(A_AUTH, AUTH_SIGNATURE, 0, keys.sign(m.data));
                        sentSignature = true;
                    } else if (!sentKey) {
                        send(A_AUTH, AUTH_RSAPUBLICKEY, 0, keys.adbPublicKey());
                        sentKey = true;
                        socket.setSoTimeout(90_000); // the user has to tap "Allow" in the headset
                        if (listener != null) listener.onWaitingForApproval();
                    } else {
                        throw new RejectedException("The headset rejected this app's key.");
                    }
                    break;
                default:
                    // ignore anything else during the handshake
            }
        }
    }

    /** Longest command that fits in one OPEN packet. */
    int maxCommandLength() {
        return deviceMaxData - 16;
    }

    /** Runs a command via "shell:" and returns its combined output. */
    synchronized String shell(String command) throws IOException {
        if (closed) throw new IOException("Connection closed");
        int local = nextLocalId++;
        send(A_OPEN, local, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int remote = 0;
        while (true) {
            Msg m = read();
            if (m.arg1 != local) continue; // late message for an older stream
            switch (m.cmd) {
                case A_OKAY:
                    remote = m.arg0;
                    break;
                case A_WRTE:
                    remote = m.arg0;
                    buf.write(m.data, 0, m.data.length);
                    send(A_OKAY, local, remote, null);
                    break;
                case A_CLSE:
                    if (remote == 0) throw new IOException("ADB refused to open a shell");
                    send(A_CLSE, local, remote, null);
                    return new String(buf.toByteArray(), StandardCharsets.UTF_8);
                default:
                    break;
            }
        }
    }

    boolean isOpen() {
        return !closed && socket.isConnected() && !socket.isClosed();
    }

    @Override
    public void close() {
        closed = true;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    // ---- framing ----

    private static final class Msg {
        final int cmd, arg0, arg1;
        final byte[] data;

        Msg(int cmd, int arg0, int arg1, byte[] data) {
            this.cmd = cmd;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.data = data;
        }
    }

    private Msg read() throws IOException {
        byte[] header = new byte[24];
        in.readFully(header);
        ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int cmd = b.getInt();
        int arg0 = b.getInt();
        int arg1 = b.getInt();
        int len = b.getInt();
        b.getInt(); // checksum (ignored)
        int magic = b.getInt();
        if (magic != ~cmd) throw new IOException("Bad ADB packet (is this really an ADB port?)");
        if (len < 0 || len > MAX_ACCEPT) throw new IOException("Bad ADB payload size " + len);
        byte[] data = new byte[len];
        in.readFully(data);
        return new Msg(cmd, arg0, arg1, data);
    }

    private synchronized void send(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length;
        int sum = 0;
        for (int i = 0; i < len; i++) sum += data[i] & 0xff;
        ByteBuffer b = ByteBuffer.allocate(24 + len).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd).putInt(arg0).putInt(arg1).putInt(len).putInt(sum).putInt(~cmd);
        if (len > 0) b.put(data);
        out.write(b.array());
        out.flush();
    }
}
