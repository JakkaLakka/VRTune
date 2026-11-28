package com.vrtune.optimizer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Optional, lightweight performance recorder (off by default).
 * The headset already logs one stats line per second while a VR app runs; every 30 s we read only the
 * lines logged since the last read, then stop as soon as the game exits. Nothing runs inside the game.
 */
public final class PerfMonitor extends Service {
    private static final String CHANNEL = "perf";
    private static final long POLL_MS = 30_000;
    private static final long FIRST_POLL_MS = 15_000;
    private static final long MAX_SESSION_MS = 4 * 3600_000L;
    private static final int MAX_SAMPLES = 4 * 3600;

    static boolean isEnabled(Context c) {
        return prefs(c).getBoolean("perf_report", false);
    }

    static void setEnabled(Context c, boolean on) {
        prefs(c).edit().putBoolean("perf_report", on).apply();
        if (!on) c.stopService(new Intent(c, PerfMonitor.class));
    }

    static void startFor(Context c, String pkg, String name) {
        if (!isEnabled(c)) return;
        Intent i = new Intent(c, PerfMonitor.class).putExtra("pkg", pkg).putExtra("name", name);
        try {
            c.startForegroundService(i);
        } catch (Exception ignored) {
            // e.g. background-start restrictions; just skip recording this time
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    // ---- service ----

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<PerfStats.Sample> samples = new ArrayList<>();
    private String pkg, name;
    private double lastEpoch;
    private long started;
    private boolean seenRunning;
    private int missing;
    private int polls;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String newPkg = intent == null ? null : intent.getStringExtra("pkg");
        if (newPkg == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (pkg != null) finish(false); // a new game replaced the previous session
        pkg = newPkg;
        name = intent.getStringExtra("name");
        samples.clear();
        lastEpoch = System.currentTimeMillis() / 1000.0;
        started = System.currentTimeMillis();
        seenRunning = false;
        missing = 0;
        polls = 0;
        goForeground();
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::poll, FIRST_POLL_MS);
        return START_NOT_STICKY;
    }

    private void goForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null)
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Performance report", NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_fg)
                .setContentTitle("VRTune")
                .setContentText("Recording performance for " + name)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else
            startForeground(1, n);
    }

    private void poll() {
        if (pkg == null) return;
        String since = String.format(Locale.US, "%.3f", lastEpoch);
        // Read every log line since the last poll that carries the runtime's "FPS=x/y" stats, whatever
        // tag it uses (older software logs it as VrApi; newer builds may use another tag). The grep runs
        // on the headset, so only the few matching lines come back.
        String cmd = "pidof " + pkg + " ; echo VRTUNE_SEP ; logcat -d -v epoch -T '" + since + "' | grep -F 'FPS='";
        Adb.get(this).run(cmd, (out, err) -> {
            if (pkg == null) return;
            if (err == null && out != null) {
                int sep = out.indexOf("VRTUNE_SEP");
                String pid = sep >= 0 ? out.substring(0, sep).trim() : "";
                String log = sep >= 0 ? out.substring(sep + 10) : out;
                for (String line : log.split("\n")) {
                    PerfStats.Sample s = PerfStats.parse(line);
                    if (s == null || (s.epoch > 0 && s.epoch <= lastEpoch)) continue;
                    if (samples.size() < MAX_SAMPLES) samples.add(s);
                    if (s.epoch > lastEpoch) lastEpoch = s.epoch;
                }
                boolean running = !pid.isEmpty() && pid.matches("[\\d\\s]+");
                if (running) {
                    seenRunning = true;
                    missing = 0;
                } else {
                    missing++;
                }
            } else {
                missing++;
            }
            polls++;
            // Save a live snapshot every poll. On Quest a game usually stays resident (suspended) after
            // you take the headset off or return to VRTune, so we can't wait for it to exit to save.
            saveSnapshot();

            boolean timeUp = System.currentTimeMillis() - started > MAX_SESSION_MS;
            boolean gameGone = seenRunning ? missing >= 1 : missing >= 4;
            if (timeUp || gameGone) finish(true);
            else handler.postDelayed(this::poll, POLL_MS);
        });
    }

    /**
     * Writes the session so far, so the game screen shows it even while the game is still resident.
     * Enough data: a real report. The game has been running a while but logged no stats at all:
     * a "no data" note, so the user learns this game doesn't report rather than waiting forever.
     */
    private void saveSnapshot() {
        if (pkg == null) return;
        PerfStats.Report r = PerfStats.report(samples);
        if (r != null) saveReport(this, pkg, r);
        else if (seenRunning && polls >= 2 && samples.isEmpty()) saveReport(this, pkg, null);
        else saveRecording(this, pkg, samples.size());
    }

    /** "Still collecting" marker, so the card shows the recorder is running rather than "No report yet". */
    private static void saveRecording(Context c, String pkg, int seconds) {
        try {
            JSONObject o = new JSONObject();
            o.put("recording", true);
            o.put("samples", seconds);
            o.put("at", System.currentTimeMillis());
            c.getSharedPreferences("perf_reports", MODE_PRIVATE).edit().putString(pkg, o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private void finish(boolean stop) {
        handler.removeCallbacksAndMessages(null);
        PerfStats.Report r = PerfStats.report(samples);
        if (pkg != null) saveReport(this, pkg, r);
        pkg = null;
        samples.clear();
        if (stop) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (pkg != null) {
            PerfStats.Report r = PerfStats.report(samples);
            saveReport(this, pkg, r);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- stored reports ----

    private static void saveReport(Context c, String pkg, PerfStats.Report r) {
        SharedPreferences p = c.getSharedPreferences("perf_reports", MODE_PRIVATE);
        if (r == null) {
            p.edit().putString(pkg, "{\"none\":true,\"at\":" + System.currentTimeMillis() + "}").apply();
            return;
        }
        try {
            JSONObject o = new JSONObject();
            o.put("at", System.currentTimeMillis());
            o.put("headline", r.headline());
            o.put("details", r.details());
            o.put("advice", r.advice());
            o.put("verdict", r.verdict);
            o.put("profile", Profile.load(c, pkg).summary());
            p.edit().putString(pkg, o.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    /** Last saved report JSON for a game, or null. */
    static JSONObject lastReport(Context c, String pkg) {
        String s = c.getSharedPreferences("perf_reports", MODE_PRIVATE).getString(pkg, null);
        if (s == null) return null;
        try {
            return new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }
}
