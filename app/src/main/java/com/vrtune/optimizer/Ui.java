package com.vrtune.optimizer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small helpers so screens can be built in code with a consistent look. */
final class Ui {
    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static int color(Context c, int res) {
        return c.getColor(res);
    }

    static TextView text(Context c, String s, float sp, int colorRes, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color(c, colorRes));
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    static Button button(Context c, String label, boolean primary) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(color(c, primary ? R.color.bg : R.color.text));
        b.setBackgroundResource(primary ? R.drawable.btn : R.drawable.btn_ghost);
        b.setStateListAnimator(null);
        int p = dp(c, 18);
        b.setPadding(p, 0, p, 0);
        b.setMinHeight(dp(c, 52));
        b.setMinimumHeight(dp(c, 52));
        return b;
    }

    static LinearLayout.LayoutParams wrap(Context c, int marginEndDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(c, marginEndDp));
        return lp;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundResource(R.drawable.card);
        int p = dp(c, 18);
        l.setPadding(p, p, p, p);
        return l;
    }

    static LinearLayout.LayoutParams matchWidth(Context c, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, topDp);
        return lp;
    }

    static void info(Activity a, String title, String body) {
        new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title).setMessage(body).setPositiveButton("OK", null).show();
    }

    static void showSetupHelp(Activity a) {
        info(a, "Setup",
                "VRTune changes Meta's hidden performance settings through the headset's own debugging service. "
                        + "Developer Mode must be on (Meta Horizon phone app → Devices → Headset settings).\n\n"
                        + "OPTION A: NO PC (try this first)\n"
                        + "1. Connect the headset to Wi-Fi.\n"
                        + "2. Tap \"Pair (no PC)\", then \"Open Android Wireless debugging\". "
                        + "Meta hides this screen in its own Settings → Developer, so VRTune opens Android's built-in one.\n"
                        + "3. Turn Wireless debugging on, tap \"Pair device with pairing code\" and type the code into VRTune.\n\n"
                        + "OPTION B: ONE-TIME PC OR PHONE (if option A is blocked on your headset)\n"
                        + "1. Plug the Quest into a PC and run 'adb tcpip 5555' (or enable-wireless-adb.bat).\n"
                        + "2. Unplug, open VRTune, tap Connect, and in the headset prompt tick \"Always allow\".\n"
                        + "VRTune then switches on Wireless debugging itself, so after reboots it reconnects without the PC.\n\n"
                        + "Settings take effect when a game starts, so use \"Apply & Launch\". "
                        + "They are global and reset on reboot. \"Reset all\" clears them.");
    }

    /**
     * Opens Android's own (hidden on Quest) Settings app at Wireless debugging, bypassing Meta's
     * Settings which redirects the standard shortcuts. Returns an error message, or null on success.
     */
    static String openHiddenWirelessDebugging(Activity a) {
        String pkg = "com.android.settings";
        String[] screens = {
                "com.android.settings.Settings$WirelessDebuggingActivity",
                "com.android.settings.development.WirelessDebuggingActivity",
                "com.android.settings.Settings$DevelopmentSettingsDashboardActivity",
                "com.android.settings.Settings$DevelopmentSettingsActivity",
                "com.android.settings.DevelopmentSettings",
                "com.android.settings.Settings",
        };
        android.content.pm.PackageManager pm = a.getPackageManager();
        for (String cls : screens) {
            android.content.Intent i = new android.content.Intent()
                    .setClassName(pkg, cls)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            if (pm.resolveActivity(i, 0) == null) continue;
            try {
                a.startActivity(i);
                return null;
            } catch (Exception ignored) {
                // not exported or blocked by Meta; try the next one
            }
        }
        // Last resort: Android's main Settings via its launcher entry.
        try {
            android.content.Intent i = pm.getLaunchIntentForPackage(pkg);
            if (i != null) {
                a.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                return "Opened Android Settings. Go to System → Developer options → Wireless debugging.";
            }
        } catch (Exception ignored) {
        }
        return "This headset's software blocks Android's Wireless debugging screen. "
                + "Use \"Setup help\" → one-time PC/phone setup instead; after that VRTune works without a PC.";
    }

    /** Wireless-debugging pairing: detects the pairing port automatically, the user types the code. */
    static void showPairDialog(Activity a) {
        Adb adb = Adb.get(a);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(a, 20);
        box.setPadding(p, dp(a, 8), p, 0);

        box.addView(text(a,
                "Meta hides Wireless debugging from its own Settings, but it is in Android's built-in settings.\n"
                        + "1. Make sure Wi-Fi is connected.\n"
                        + "2. Tap the button below. In the screen that opens, turn Wireless debugging on "
                        + "(if you land on Developer options, scroll to Wireless debugging and tap its name).\n"
                        + "3. Tap \"Pair device with pairing code\" and keep that window open next to VRTune.\n"
                        + "4. Type the 6-digit code below. The port fills in by itself.", 14, R.color.text_dim, false));

        TextView openResult = text(a, "", 13, R.color.warn, false);
        Button openSettings = button(a, "Open Android Wireless debugging", false);
        openSettings.setOnClickListener(v -> {
            String r = openHiddenWirelessDebugging(a);
            openResult.setText(r == null ? "" : r);
        });
        box.addView(openSettings, matchWidth(a, 12));
        box.addView(openResult, matchWidth(a, 4));

        EditText code = new EditText(a);
        code.setHint("6-digit pairing code");
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setTextColor(color(a, R.color.text));
        code.setHintTextColor(color(a, R.color.text_dim));
        code.setTextSize(22);
        code.setGravity(Gravity.CENTER);
        code.setBackgroundResource(R.drawable.field);
        box.addView(code, matchWidth(a, 14));

        EditText port = new EditText(a);
        port.setHint("Pairing port (auto)");
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        port.setTextColor(color(a, R.color.text));
        port.setHintTextColor(color(a, R.color.text_dim));
        port.setGravity(Gravity.CENTER);
        port.setBackgroundResource(R.drawable.field);
        box.addView(port, matchWidth(a, 10));

        TextView status = text(a, "Looking for the pairing window…", 13, R.color.text_dim, false);
        box.addView(status, matchWidth(a, 8));

        AlertDialog d = new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Pair with Wireless debugging")
                .setView(box)
                .setPositiveButton("Pair", null)
                .setNegativeButton("Close", null)
                .create();

        boolean[] open = {true};
        Runnable[] scan = new Runnable[1];
        scan[0] = () -> adb.findPairingPort((found, err) -> {
            if (!open[0]) return;
            if (found != null && found > 0) {
                if (!String.valueOf(found).equals(port.getText().toString())) port.setText(String.valueOf(found));
                status.setText("Pairing window found (port " + found + "). Enter the code and tap Pair.");
                status.setTextColor(color(a, R.color.good));
            } else if (port.getText().length() == 0) {
                status.setText("Pairing window not found yet. Open it in Settings, or type the port "
                        + "(the number after ':' under \"IP address & Port\").");
                status.setTextColor(color(a, R.color.warn));
            }
            port.postDelayed(scan[0], 2500);
        });

        d.setOnShowListener(x -> {
            scan[0].run();
            Button pair = d.getButton(AlertDialog.BUTTON_POSITIVE);
            pair.setOnClickListener(v -> {
                String c = code.getText().toString().trim();
                int pp;
                try {
                    pp = Integer.parseInt(port.getText().toString().trim());
                } catch (NumberFormatException e) {
                    pp = -1;
                }
                if (c.length() < 6) {
                    status.setText("Enter the 6-digit code shown in the pairing window.");
                    status.setTextColor(color(a, R.color.bad));
                    return;
                }
                if (pp <= 0 || pp > 65535) {
                    status.setText("Port not found yet. Keep the pairing window open, or type the port.");
                    status.setTextColor(color(a, R.color.bad));
                    return;
                }
                pair.setEnabled(false);
                status.setText("Pairing…");
                status.setTextColor(color(a, R.color.text_dim));
                adb.pair(pp, c, (ok, err) -> {
                    pair.setEnabled(true);
                    if (Boolean.TRUE.equals(ok)) {
                        open[0] = false;
                        d.dismiss();
                        info(a, "Paired!", "VRTune is connected without a PC. After a reboot it reconnects by itself, "
                                + "or just turn Wireless debugging back on and tap Connect.");
                    } else {
                        status.setText(err == null ? "Pairing failed." : Adb.friendly(err)
                                + "\nCodes expire: tap \"Pair device with pairing code\" again for a fresh one.");
                        status.setTextColor(color(a, R.color.bad));
                    }
                });
            });
        });
        d.setOnDismissListener(x -> open[0] = false);
        d.show();
    }

    static void askPort(Activity a, Adb adb) {
        EditText e = new EditText(a);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(adb.port()));
        e.setTextColor(color(a, R.color.text));
        e.setGravity(Gravity.CENTER);
        new AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("ADB port")
                .setMessage("Normally 5555 (from 'adb tcpip 5555').")
                .setView(e)
                .setPositiveButton("Save", (d, w) -> {
                    try {
                        int p = Integer.parseInt(e.getText().toString().trim());
                        if (p > 0 && p < 65536) adb.setPort(p);
                    } catch (NumberFormatException ignored) {
                    }
                })
                .setNegativeButton("Cancel", null).show();
    }

    /** Status pill + Connect button shown at the top of each screen. */
    static final class StatusBar implements Adb.StatusListener {
        final LinearLayout view;
        private final TextView dot, label;
        private final Button connect;
        private final Context ctx;

        StatusBar(Activity a) {
            ctx = a;
            view = new LinearLayout(a);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setBackgroundResource(R.drawable.card);
            int p = dp(a, 12);
            view.setPadding(dp(a, 16), p, p, p);
            dot = text(a, "●", 18, R.color.bad, true);
            label = text(a, "", 15, R.color.text, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            lp.setMarginStart(dp(a, 10));
            lp.setMarginEnd(dp(a, 10));
            connect = button(a, "Connect", true);
            connect.setOnClickListener(v -> Adb.get(a).connect((ok, err) -> { }));
            pair = button(a, "Pair (no PC)", true);
            pair.setOnClickListener(v -> showPairDialog(a));
            Button help = button(a, "Setup help", false);
            help.setOnClickListener(v -> showSetupHelp(a));
            view.addView(dot);
            view.addView(label, lp);
            view.addView(pair, wrap(a, 10));
            view.addView(connect, wrap(a, 10));
            view.addView(help);
        }

        private final Button pair;

        @Override
        public void onStatus(Adb.State state, String message) {
            label.setText(message);
            int col;
            switch (state) {
                case CONNECTED: col = R.color.good; break;
                case CONNECTING: col = R.color.text_dim; break;
                case WAITING_APPROVAL: col = R.color.warn; break;
                default: col = R.color.bad;
            }
            dot.setTextColor(color(ctx, col));
            boolean connected = state == Adb.State.CONNECTED;
            connect.setVisibility(connected ? View.GONE : View.VISIBLE);
            connect.setEnabled(state == Adb.State.DISCONNECTED);
            pair.setVisibility(connected ? View.GONE : View.VISIBLE);
            pair.setEnabled(state == Adb.State.DISCONNECTED);
        }
    }
}
