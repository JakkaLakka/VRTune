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

/** App-wide connection to the headset's own adbd on 127.0.0.1. All work runs on one background thread. */
final class Adb {
    enum State { DISCONNECTED, CONNECTING, WAITING_APPROVAL, CONNECTED }

    interface Callback<T> {
        void done(T result, Exception error);
    }

    interface StatusListener {
        void onStatus(State state, String message);
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
    private AdbClient client;
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
        setState(State.CONNECTING, "Connecting to headset ADB on port " + port() + "…");
        try {
            AdbKeys keys = AdbKeys.loadOrCreate(ctx.getFilesDir());
            client = AdbClient.connect("127.0.0.1", port(), keys, () -> setState(State.WAITING_APPROVAL,
                    "Look in the headset: allow USB debugging for VRTune (tick \"Always allow\")."));
            setState(State.CONNECTED, "Connected to headset");
        } catch (Exception e) {
            closeClient();
            setState(State.DISCONNECTED, friendly(e));
            throw e;
        }
    }

    private void closeClient() {
        if (client != null) client.close();
        client = null;
        if (state == State.CONNECTED) setState(State.DISCONNECTED, "Not connected");
    }

    static String friendly(Exception e) {
        if (e instanceof ConnectException)
            return "Wireless ADB is off. Connect the headset to a PC and run enable-wireless-adb (needed after every reboot).";
        if (e instanceof SocketTimeoutException)
            return "Timed out. If a debugging prompt appeared in the headset, allow it and tap Connect again.";
        if (e instanceof AdbClient.TlsRequiredException || e instanceof AdbClient.RejectedException)
            return e.getMessage();
        String m = e.getMessage();
        return "Connection failed: " + (m == null ? e.getClass().getSimpleName() : m);
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
