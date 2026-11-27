package com.vrtune.optimizer;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.muntashirakon.adb.AdbPairingRequiredException;

/**
 * App-wide connection to the headset's own adbd. Tries, in order:
 *  1. Wireless debugging (TLS, Android 11+). Needs a one-time pairing code, no PC.
 *  2. Legacy ADB over TCP on 127.0.0.1:5555 (after `adb tcpip 5555` from a PC).
 * All work runs on one background thread; callbacks arrive on the main thread.
 */
final class Adb {
    enum State { DISCONNECTED, CONNECTING, WAITING_APPROVAL, CONNECTED }

    interface Callback<T> {
        void done(T result, Exception error);
    }

    interface StatusListener {
        void onStatus(State state, String message);
    }

    static final class NeedsSetupException extends IOException {
        NeedsSetupException(String m) {
            super(m);
        }
    }

    private static Adb instance;

    static synchronized Adb get(Context c) {
        if (instance == null) instance = new Adb(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final SharedPreferences prefs;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<StatusListener> listeners = new CopyOnWriteArrayList<>();
    private AdbShell client;
    private String via = "";
    private volatile State state = State.DISCONNECTED;
    private volatile String message = "Not connected";

    private Adb(Context ctx) {
        this.ctx = ctx;
        this.prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    int port() {
        return prefs.getInt("adb_port", 5555);
    }

    void setPort(int port) {
        prefs.edit().putInt("adb_port", port).apply();
        worker.execute(this::closeClient);
    }

    boolean isPaired() {
        return prefs.getBoolean("paired", false);
    }

    State state() {
        return state;
    }

    String message() {
        return message;
    }

    void addListener(StatusListener l) {
        listeners.add(l);
        l.onStatus(state, message);
    }

    void removeListener(StatusListener l) {
        listeners.remove(l);
    }

    void connect(Callback<Boolean> cb) {
        worker.execute(() -> {
            try {
                ensureConnected();
                post(cb, true, null);
            } catch (Exception e) {
                post(cb, false, e);
            }
        });
    }

    /** Finds the pairing port advertised while the headset's "Pair with code" dialog is open. */
    void findPairingPort(Callback<Integer> cb) {
        new Thread(() -> {
            int p = AdbDiscovery.findPort(ctx, AdbDiscovery.PAIRING, 4000);
            post(cb, p, null);
        }, "pair-discovery").start();
    }

    /** Pairs with Wireless debugging using the 6-digit code, then connects. */
    void pair(int pairingPort, String code, Callback<Boolean> cb) {
        worker.execute(() -> {
            setState(State.CONNECTING, "Pairing with the headset…");
            Exception last = null;
            for (String host : pairHosts()) {
                try (TlsAdb tls = TlsAdb.create(ctx)) {
                    if (tls.pair(host, pairingPort, code.trim())) {
                        prefs.edit().putBoolean("paired", true).apply();
                        last = null;
                        break;
                    }
                    last = new IOException("Pairing was refused. Check the code and try again.");
                } catch (Exception e) {
                    last = e;
                }
            }
            if (last != null) {
                setState(State.DISCONNECTED, "Pairing failed: " + friendly(last));
                post(cb, false, last);
                return;
            }
            closeClient();
            try {
                ensureConnected();
                post(cb, true, null);
            } catch (Exception e) {
                post(cb, false, e);
            }
        });
    }

    private List<String> pairHosts() {
        List<String> hosts = new ArrayList<>();
        hosts.add("127.0.0.1");
        for (String a : AdbDiscovery.localAddresses())
            if (a.matches("\\d+\\.\\d+\\.\\d+\\.\\d+") && !a.startsWith("127.")) hosts.add(a);
        return hosts;
    }

    /** Runs commands in as few shell sessions as possible, joined with ';'. Returns combined output. */
    void run(List<String> commands, Callback<String> cb) {
        worker.execute(() -> {
            try {
                post(cb, runSync(commands), null);
            } catch (Exception e) {
                post(cb, null, e);
            }
        });
    }

    void run(String command, Callback<String> cb) {
        List<String> l = new ArrayList<>();
        l.add(command);
        run(l, cb);
    }

    private String runSync(List<String> commands) throws Exception {
        ensureConnected();
        int max = Math.min(3500, client.maxCommandLength());
        StringBuilder all = new StringBuilder();
        StringBuilder batch = new StringBuilder();
        for (String c : commands) {
            if (batch.length() > 0 && batch.length() + c.length() + 3 > max) {
                all.append(shellWithRetry(batch.toString()));
                batch.setLength(0);
            }
            if (batch.length() > 0) batch.append(" ; ");
            batch.append(c);
        }
        if (batch.length() > 0) all.append(shellWithRetry(batch.toString()));
        return all.toString();
    }

    private String shellWithRetry(String cmd) throws Exception {
        try {
            return client.shell(cmd);
        } catch (IOException first) {
            closeClient();
            ensureConnected();
            return client.shell(cmd);
        }
    }

    private void ensureConnected() throws Exception {
        if (client != null && client.isOpen()) return;
        closeClient();
        Exception tlsError = null;

        // 1) Wireless debugging (no PC needed once paired)
        if (isPaired()) {
            setState(State.CONNECTING, "Connecting via Wireless debugging…");
            try {
                if (tryTls()) return;
            } catch (Exception e) {
                tlsError = e;
            }
        }

        // 2) Legacy port 5555 (set up from a PC)
        setState(State.CONNECTING, "Connecting to headset ADB on port " + port() + "…");
        try {
            AdbKeys keys = AdbKeys.loadOrCreate(ctx.getFilesDir());
            client = AdbClient.connect("127.0.0.1", port(), keys, () -> setState(State.WAITING_APPROVAL,
                    "Look in the headset: allow debugging for VRTune (tick \"Always allow\")."));
            onConnected("ADB port " + port());
        } catch (ConnectException legacyOff) {
            closeClient();
            String msg;
            if (tlsError != null) msg = friendly(tlsError);
            else if (isPaired()) msg = "Wireless debugging is off. Turn it on in Settings → System → Developer, then tap Connect.";
            else msg = "Not set up yet. Tap \"Pair (no PC)\" to connect using Wireless debugging.";
            setState(State.DISCONNECTED, msg);
            throw new NeedsSetupException(msg);
        } catch (Exception e) {
            closeClient();
            setState(State.DISCONNECTED, friendly(e));
            throw e;
        }
    }

    private boolean tryTls() throws Exception {
        // Turn Wireless debugging on ourselves if we have permission (after a reboot it is off).
        boolean wasOn = WirelessDebug.isOn(ctx);
        if (!wasOn && prefs.getBoolean("auto_wireless", true)) WirelessDebug.enable(ctx);

        int port = AdbDiscovery.findPort(ctx, AdbDiscovery.CONNECT, wasOn ? 3000 : 6000);
        if (port <= 0 && !wasOn) port = AdbDiscovery.findPort(ctx, AdbDiscovery.CONNECT, 4000);
        if (port <= 0) {
            if (!WirelessDebug.isOn(ctx))
                throw new NeedsSetupException("Wireless debugging is off. Turn it on in Settings → System → Developer → Wireless debugging (Wi-Fi must be connected).");
            throw new NeedsSetupException("Wireless debugging is on but its port wasn't found. Make sure Wi-Fi is connected, then tap Connect.");
        }
        TlsAdb tls = TlsAdb.create(ctx);
        try {
            if (tls.connect("127.0.0.1", port)) {
                client = tls;
                onConnected("Wireless debugging");
                return true;
            }
            tls.close();
            return false;
        } catch (AdbPairingRequiredException e) {
            tls.close();
            prefs.edit().putBoolean("paired", false).apply();
            throw new NeedsSetupException("The headset forgot VRTune's pairing. Tap \"Pair (no PC)\" and pair again.");
        } catch (Exception e) {
            tls.close();
            throw e;
        }
    }

    private void onConnected(String how) {
        via = how;
        setState(State.CONNECTED, "Connected (" + how + ")");
        // One-time: let VRTune switch Wireless debugging back on after reboots, so no PC is ever needed again.
        if (!WirelessDebug.canToggle(ctx)) {
            try {
                client.shell("pm grant " + ctx.getPackageName() + " android.permission.WRITE_SECURE_SETTINGS");
            } catch (Exception ignored) {
            }
        }
    }

    String via() {
        return via;
    }

    private void closeClient() {
        if (client != null) client.close();
        client = null;
        if (state == State.CONNECTED) setState(State.DISCONNECTED, "Not connected");
    }

    static String friendly(Exception e) {
        if (e instanceof NeedsSetupException) return e.getMessage();
        if (e instanceof SocketTimeoutException)
            return "Timed out. If a debugging prompt appeared in the headset, allow it and tap Connect again.";
        if (e instanceof AdbClient.TlsRequiredException || e instanceof AdbClient.RejectedException)
            return e.getMessage();
        if (e instanceof ConnectException)
            return "Couldn't reach the pairing port. Keep the \"Pair with pairing code\" window open and check the port.";
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }

    private void setState(State s, String msg) {
        state = s;
        message = msg;
        main.post(() -> {
            for (StatusListener l : listeners) l.onStatus(s, msg);
        });
    }

    private <T> void post(Callback<T> cb, T result, Exception e) {
        if (cb != null) main.post(() -> cb.done(result, e));
    }
}
