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
        info(a, "One-time setup (repeat after each reboot)",
                "VRTune changes Meta's hidden performance settings through the headset's own ADB service. "
                        + "Android only lets ADB turn that service on, so after each reboot it must be woken up once:\n\n"
                        + "1. Enable Developer Mode for your headset in the Meta Horizon phone app.\n"
                        + "2. Connect the Quest to a PC with a USB-C cable and allow USB debugging in the headset.\n"
                        + "3. On the PC, run enable-wireless-adb.bat (from the GitHub release), or:\n"
                        + "      adb tcpip 5555\n"
                        + "4. Unplug the cable, open VRTune, tap Connect.\n"
                        + "5. The first time, the headset asks to allow debugging for VRTune. Tick \"Always allow\" and Allow.\n\n"
                        + "Settings take effect when the game starts, so use \"Apply & Launch\". "
                        + "They are global and reset on reboot; \"Reset all\" clears them.");
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
            Button help = button(a, "Setup help", false);
            help.setOnClickListener(v -> showSetupHelp(a));
            view.addView(dot);
            view.addView(label, lp);
            view.addView(connect, wrap(a, 10));
            view.addView(help);
        }

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
            connect.setVisibility(state == Adb.State.CONNECTED ? View.GONE : View.VISIBLE);
            connect.setEnabled(state == Adb.State.DISCONNECTED);
        }
    }
}
