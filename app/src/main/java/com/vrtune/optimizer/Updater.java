package com.vrtune.optimizer;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Checks this project's GitHub Releases for a newer VRTune and installs it. */
final class Updater {
    static final String REPO = "JakkaLakka/VRTune";
    static final String RELEASES_PAGE = "https://github.com/" + REPO + "/releases/latest";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final long AUTO_CHECK_EVERY_MS = 12 * 3600_000L;

    static final class Release {
        String tag, notes, apkUrl;
    }

    interface Callback {
        void done(Release newer, String error);
    }

    static String currentVersion(Context c) {
        try {
            PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    /** True if it's time for the automatic background check. */
    static boolean autoCheckDue(Context c) {
        SharedPreferences p = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
        return System.currentTimeMillis() - p.getLong("last_update_check", 0) > AUTO_CHECK_EVERY_MS;
    }

    /** Checks GitHub; calls back on the main thread with the newer release, or null if up to date. */
    static void check(Context ctx, Callback cb) {
        Context app = ctx.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            Release newer = null;
            String error = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "VRTune-updater");
                if (c.getResponseCode() != 200) throw new Exception("GitHub returned " + c.getResponseCode());
                JSONObject o;
                try (InputStream in = c.getInputStream()) {
                    o = new JSONObject(readAll(in));
                }
                app.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
                        .putLong("last_update_check", System.currentTimeMillis()).apply();
                String tag = o.optString("tag_name");
                if (isNewer(tag, currentVersion(app))) {
                    Release r = new Release();
                    r.tag = tag;
                    r.notes = o.optString("body", "");
                    JSONArray assets = o.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject a = assets.getJSONObject(i);
                            if (a.optString("name").toLowerCase().endsWith(".apk"))
                                r.apkUrl = a.optString("browser_download_url");
                        }
                    }
                    newer = r;
                }
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            }
            Release fr = newer;
            String fe = error;
            main.post(() -> cb.done(fr, fe));
        }, "update-check").start();
    }

    /** "v1.10.0" vs "1.9.2" -> true. Non-numeric parts are ignored. */
    static boolean isNewer(String remote, String local) {
        int[] a = parse(remote), b = parse(local);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0, y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int[] parse(String v) {
        if (v == null) return new int[0];
        String[] parts = v.replaceAll("[^0-9.]", "").split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) b.write(buf, 0, n);
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Downloads the APK with the system downloader, then opens the installer. Falls back to the browser. */
    static void downloadAndInstall(Activity a, Release r) {
        if (r.apkUrl == null || r.apkUrl.isEmpty()) {
            openReleasePage(a);
            return;
        }
        try {
            DownloadManager dm = (DownloadManager) a.getSystemService(Context.DOWNLOAD_SERVICE);
            String file = "VRTune-" + r.tag + ".apk";
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(r.apkUrl))
                    .setTitle("VRTune " + r.tag)
                    .setMimeType("application/vnd.android.package-archive")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalFilesDir(a, Environment.DIRECTORY_DOWNLOADS, file);
            long id = dm.enqueue(req);
            Toast.makeText(a, "Downloading VRTune " + r.tag + "…", Toast.LENGTH_SHORT).show();

            Context app = a.getApplicationContext();
            BroadcastReceiver done = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return;
                    try {
                        app.unregisterReceiver(this);
                    } catch (Exception ignored) {
                    }
                    Uri uri = dm.getUriForDownloadedFile(id);
                    if (uri == null) {
                        Toast.makeText(app, "Download failed. Opening the release page instead.", Toast.LENGTH_LONG).show();
                        openReleasePage(a);
                        return;
                    }
                    try {
                        Intent install = new Intent(Intent.ACTION_VIEW)
                                .setDataAndType(uri, "application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                        a.startActivity(install);
                    } catch (Exception e) {
                        openReleasePage(a);
                    }
                }
            };
            IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
            if (android.os.Build.VERSION.SDK_INT >= 33)
                app.registerReceiver(done, f, Context.RECEIVER_EXPORTED);
            else
                app.registerReceiver(done, f);
        } catch (Exception e) {
            openReleasePage(a);
        }
    }

    static void openReleasePage(Activity a) {
        try {
            a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(a, "Open " + RELEASES_PAGE + " in the browser.", Toast.LENGTH_LONG).show();
        }
    }
}
