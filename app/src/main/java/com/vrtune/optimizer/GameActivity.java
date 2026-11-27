package com.vrtune.optimizer;

import android.app.Activity;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Optimizer screen for one game. */
public class GameActivity extends Activity {
    private static final String OK_MARK = "VRTUNE_APPLIED_OK";

    private String pkg;
    private String name;
    private ComponentName launch;
    private Profile profile;
    private final Map<String, Spinner> spinners = new HashMap<>();
    private EditText custom;
    private TextView customWarn, comboWarn, result;
    private Ui.StatusBar status;
    private final List<Button> actionButtons = new ArrayList<>();
    private boolean binding;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        pkg = getIntent().getStringExtra("pkg");
        PackageManager pm = getPackageManager();
        ApplicationInfo ai;
        try {
            ai = pm.getApplicationInfo(pkg, 0);
        } catch (Exception e) {
            Toast.makeText(this, "Game not installed", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        name = String.valueOf(ai.loadLabel(pm));
        for (Games.Game g : Games.scan(this)) if (g.pkg.equals(pkg)) launch = g.launch;
        profile = Profile.load(this, pkg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 24);
        body.setPadding(pad, pad, pad, pad);
        scroll.addView(body);

        // Header
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = Ui.button(this, "‹ Library", false);
        back.setOnClickListener(v -> finish());
        header.addView(back, Ui.wrap(this, 18));
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(ai.loadIcon(pm));
        int is = Ui.dp(this, 64);
        header.addView(icon, new LinearLayout.LayoutParams(is, is));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(Ui.dp(this, 16), 0, 0, 0);
        titles.addView(Ui.text(this, name, 24, R.color.text, true));
        titles.addView(Ui.text(this, pkg, 13, R.color.text_dim, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        body.addView(header);

        status = new Ui.StatusBar(this);
        body.addView(status.view, Ui.matchWidth(this, 16));

        body.addView(buildReportCard(), Ui.matchWidth(this, 16));
        body.addView(buildPresets(), Ui.matchWidth(this, 16));
        for (String group : Tweaks.groups()) body.addView(buildGroup(group), Ui.matchWidth(this, 16));
        body.addView(buildCustom(), Ui.matchWidth(this, 16));
        body.addView(buildNotes(), Ui.matchWidth(this, 16));

        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(buildActionBar());
        setContentView(root);
        bindProfile();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Adb adb = Adb.get(this);
        adb.addListener(status);
        if (adb.state() == Adb.State.DISCONNECTED) adb.connect((ok, e) -> { });
        refreshReport();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Adb.get(this).removeListener(status);
        if (profile != null) profile.save(this, pkg);
    }

    // ---- performance report ----

    private TextView reportHead, reportDetails, reportAdvice;
    private Button reportApply;

    private View buildReportCard() {
        LinearLayout card = Ui.card(this);
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(Ui.text(this, "Performance report", 18, R.color.text, true));
        titles.addView(Ui.text(this,
                "Optional. When on, VRTune reads the FPS stats the headset already logs, every 30 s while you play, "
                        + "and stops when the game closes. Nothing runs inside the game, so the cost is negligible.",
                13, R.color.text_dim, false));
        top.addView(titles, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        android.widget.Switch sw = new android.widget.Switch(this);
        sw.setChecked(PerfMonitor.isEnabled(this));
        sw.setOnCheckedChangeListener((v, on) -> {
            PerfMonitor.setEnabled(this, on);
            refreshReport();
        });
        top.addView(sw);
        card.addView(top);

        reportHead = Ui.text(this, "", 16, R.color.accent, true);
        reportDetails = Ui.text(this, "", 13, R.color.text_dim, false);
        reportAdvice = Ui.text(this, "", 14, R.color.text, false);
        card.addView(reportHead, Ui.matchWidth(this, 12));
        card.addView(reportDetails, Ui.matchWidth(this, 4));
        card.addView(reportAdvice, Ui.matchWidth(this, 8));
        reportApply = Ui.button(this, "Apply suggestion", false);
        LinearLayout.LayoutParams bp = Ui.wrap(this, 0);
        bp.topMargin = Ui.dp(this, 10);
        card.addView(reportApply, bp);
        return card;
    }

    private void refreshReport() {
        if (reportHead == null) return;
        org.json.JSONObject r = PerfMonitor.lastReport(this, pkg);
        reportApply.setVisibility(View.GONE);
        if (r == null) {
            reportHead.setText(PerfMonitor.isEnabled(this)
                    ? "No report yet. Play using Apply & Launch, then come back here."
                    : "Turn on to see FPS, dropped frames and tuning tips after you play.");
            reportHead.setTextColor(Ui.color(this, R.color.text_dim));
            reportDetails.setText("");
            reportAdvice.setText("");
            return;
        }
        if (r.optBoolean("none")) {
            reportHead.setText("Last session: no performance data. Played too briefly, or this game doesn't report stats.");
            reportHead.setTextColor(Ui.color(this, R.color.warn));
            reportDetails.setText("");
            reportAdvice.setText("");
            return;
        }
        String when = android.text.format.DateUtils.getRelativeTimeSpanString(r.optLong("at")).toString();
        reportHead.setText("Last session (" + when + "): " + r.optString("headline"));
        String verdict = r.optString("verdict");
        reportHead.setTextColor(Ui.color(this,
                "heavy".equals(verdict) ? R.color.bad : "headroom".equals(verdict) ? R.color.good : R.color.accent));
        String prof = r.optString("profile");
        reportDetails.setText(r.optString("details") + (prof.isEmpty() ? "" : "\nSettings used: " + prof));
        reportAdvice.setText(r.optString("advice"));
        int step = "heavy".equals(verdict) ? -1 : "headroom".equals(verdict) ? 1 : 0;
        if (step != 0) {
            reportApply.setVisibility(View.VISIBLE);
            reportApply.setText(step < 0 ? "Apply suggestion: lower resolution one step" : "Apply suggestion: raise resolution one step");
            reportApply.setOnClickListener(v -> {
                shiftResolution(step);
                setResult("Resolution changed. Tap Apply & Launch to try it.", R.color.text_dim);
            });
        }
    }

    private void shiftResolution(int delta) {
        Tweaks.Tweak t = Tweaks.byId("resolution");
        int idx = t.indexOf(profile.selections.get("resolution"));
        if (idx == 0) { // "Game default" = Quest 3 default size
            for (int i = 1; i < t.options.size(); i++)
                if (t.options.get(i).values[0].equals(String.valueOf(Tweaks.BASE_W))) idx = i;
        }
        idx = Math.max(1, Math.min(t.options.size() - 1, idx + delta));
        profile.selections.put("resolution", t.options.get(idx).id());
        profile.save(this, pkg);
        bindProfile();
    }

    // ---- sections ----

    private View buildPresets() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, "Quick presets", 18, R.color.text, true));
        card.addView(Ui.text(this, "Start from a preset, then fine-tune below. Every game reacts differently, so test in-game.", 13, R.color.text_dim, false));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        for (Tweaks.Preset p : Tweaks.PRESETS) {
            Button btn = Ui.button(this, p.name + "\n" + p.hint, false);
            btn.setTextSize(14);
            btn.setTypeface(Typeface.DEFAULT);
            int bp = Ui.dp(this, 14);
            btn.setPadding(bp, bp, bp, bp);
            btn.setOnClickListener(v -> {
                p.applyTo(profile);
                bindProfile();
                profile.save(this, pkg);
                setResult("Preset \"" + p.name + "\" loaded. Tap Apply & Launch to use it.", R.color.text_dim);
            });
            row.addView(btn, Ui.wrap(this, 10));
        }
        hs.addView(row);
        card.addView(hs, Ui.matchWidth(this, 12));
        return card;
    }

    private View buildGroup(String group) {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, group, 18, R.color.text, true));
        for (Tweaks.Tweak t : Tweaks.ALL) {
            if (!t.group.equals(group)) continue;
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout texts = new LinearLayout(this);
            texts.setOrientation(LinearLayout.VERTICAL);
            texts.addView(Ui.text(this, t.title, 16, R.color.text, true));
            TextView d = Ui.text(this, t.description, 13, R.color.text_dim, false);
            texts.addView(d);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            tp.setMarginEnd(Ui.dp(this, 16));
            row.addView(texts, tp);

            Spinner sp = new Spinner(this, Spinner.MODE_DROPDOWN);
            List<String> labels = new ArrayList<>();
            for (Tweaks.Option o : t.options) labels.add(o.label);
            ArrayAdapter<String> ad = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, labels) {
                @Override
                public View getView(int position, View convertView, ViewGroup parent) {
                    TextView v = (TextView) super.getView(position, convertView, parent);
                    v.setTextColor(Ui.color(getContext(), position == 0 ? R.color.text_dim : R.color.accent));
                    v.setTextSize(15);
                    return v;
                }
            };
            ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            sp.setAdapter(ad);
            sp.setBackgroundResource(R.drawable.field);
            sp.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
            sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (binding) return;
                    String optId = t.options.get(position).id();
                    if (optId.isEmpty()) profile.selections.remove(t.id);
                    else profile.selections.put(t.id, optId);
                    profile.save(GameActivity.this, pkg);
                    updateWarnings();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) { }
            });
            spinners.put(t.id, sp);
            row.addView(sp, new LinearLayout.LayoutParams(Ui.dp(this, 320), LinearLayout.LayoutParams.WRAP_CONTENT));
            card.addView(row, Ui.matchWidth(this, 16));
        }
        if (group.equals(Tweaks.G_DISPLAY)) {
            comboWarn = Ui.text(this, "", 13, R.color.warn, false);
            comboWarn.setVisibility(View.GONE);
            card.addView(comboWarn, Ui.matchWidth(this, 12));
        }
        return card;
    }

    private View buildCustom() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, "Advanced: custom properties", 18, R.color.text, true));
        card.addView(Ui.text(this,
                "One per line as key=value, e.g. debug.oculus.gpuLevel=3. These are applied after the settings above. "
                        + "Only letters, digits and . _ - : , + are allowed. Lines starting with # are ignored.",
                13, R.color.text_dim, false));
        custom = new EditText(this);
        custom.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        custom.setMinLines(3);
        custom.setGravity(Gravity.TOP | Gravity.START);
        custom.setTypeface(Typeface.MONOSPACE);
        custom.setTextColor(Ui.color(this, R.color.text));
        custom.setHintTextColor(Ui.color(this, R.color.text_dim));
        custom.setHint("# debug.oculus.example=1");
        custom.setBackgroundResource(R.drawable.field);
        int p = Ui.dp(this, 12);
        custom.setPadding(p, p, p, p);
        custom.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) {
                if (binding) return;
                profile.custom = s.toString();
                profile.save(GameActivity.this, pkg);
                updateWarnings();
            }
        });
        card.addView(custom, Ui.matchWidth(this, 12));
        customWarn = Ui.text(this, "", 13, R.color.bad, false);
        customWarn.setVisibility(View.GONE);
        card.addView(customWarn, Ui.matchWidth(this, 8));
        return card;
    }

    private View buildNotes() {
        LinearLayout card = Ui.card(this);
        card.addView(Ui.text(this, "How to get the best result", 18, R.color.text, true));
        card.addView(Ui.text(this,
                "• Sharper image / anti-aliasing: raise Render resolution (1.2x–1.5x) and lower foveation. Quest has no documented system-wide sharpen or MSAA switch, so supersampling is the real lever.\n"
                        + "• More FPS: lower resolution, raise foveation, keep 72 Hz, and raise CPU/GPU level.\n"
                        + "• Smoother: 90 or 120 Hz only helps if the game can hold that frame rate. Otherwise you get reprojection judder.\n"
                        + "• Settings are read when the game starts. Apply & Launch force-closes the game first.\n"
                        + "• Settings are global until reboot or Reset. Launch other games through VRTune, or Reset first.\n"
                        + "• Some games ignore some settings (e.g. they set their own resolution or Hz).",
                13, R.color.text_dim, false), Ui.matchWidth(this, 8));
        return card;
    }

    private View buildActionBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(Ui.color(this, R.color.card));
        int p = Ui.dp(this, 16);
        bar.setPadding(Ui.dp(this, 24), p, Ui.dp(this, 24), p);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Button applyLaunch = Ui.button(this, "Apply & Launch", true);
        applyLaunch.setOnClickListener(v -> apply(true));
        Button applyOnly = Ui.button(this, "Apply only", false);
        applyOnly.setOnClickListener(v -> apply(false));
        Button live = Ui.button(this, "Check live values", false);
        live.setOnClickListener(v -> checkLive());
        Button reset = Ui.button(this, "Reset all", false);
        reset.setOnClickListener(v -> resetAll());
        row.addView(applyLaunch, Ui.wrap(this, 10));
        row.addView(applyOnly, Ui.wrap(this, 10));
        row.addView(live, Ui.wrap(this, 10));
        row.addView(reset, Ui.wrap(this, 10));
        actionButtons.add(applyLaunch);
        actionButtons.add(applyOnly);
        actionButtons.add(live);
        actionButtons.add(reset);
        bar.addView(row);

        result = Ui.text(this, "Changes save automatically.", 13, R.color.text_dim, false);
        bar.addView(result, Ui.matchWidth(this, 8));
        return bar;
    }

    // ---- state ----

    private void bindProfile() {
        binding = true;
        for (Tweaks.Tweak t : Tweaks.ALL) {
            Spinner s = spinners.get(t.id);
            if (s != null) s.setSelection(t.indexOf(profile.selections.get(t.id)), false);
        }
        if (custom != null && !custom.getText().toString().equals(profile.custom)) custom.setText(profile.custom);
        binding = false;
        updateWarnings();
    }

    private void updateWarnings() {
        List<String> bad = profile.invalidCustomLines();
        if (customWarn != null) {
            customWarn.setVisibility(bad.isEmpty() ? View.GONE : View.VISIBLE);
            customWarn.setText("Ignored (not key=value or has unsafe characters): " + String.join("  |  ", bad));
        }
        if (comboWarn != null) {
            String hz = profile.selections.get("refresh");
            String res = profile.selections.get("resolution");
            int w = 0;
            if (res != null && !res.isEmpty()) w = Integer.parseInt(res.split(",")[0]);
            int rate = hz == null || hz.isEmpty() ? 72 : Integer.parseInt(hz);
            float load = (w == 0 ? 1f : (float) w / Tweaks.BASE_W);
            load = load * load * rate / 72f; // pixels per second relative to stock
            String msg = null;
            if (load > 2.6f) msg = "Very heavy (≈" + Math.round(load * 100) + "% of stock GPU work). Expect dropped frames in most games. Lower resolution or Hz, or raise foveation.";
            else if (load > 1.8f) msg = "Heavy (≈" + Math.round(load * 100) + "% of stock GPU work). Fine for light games; consider GPU level 4–5.";
            comboWarn.setVisibility(msg == null ? View.GONE : View.VISIBLE);
            if (msg != null) comboWarn.setText(msg);
        }
    }

    private void setBusy(boolean busy) {
        for (Button b : actionButtons) b.setEnabled(!busy);
    }

    private void setResult(String msg, int colorRes) {
        result.setText(msg);
        result.setTextColor(Ui.color(this, colorRes));
    }

    // ---- actions ----

    private void apply(boolean andLaunch) {
        profile.custom = custom.getText().toString();
        profile.save(this, pkg);
        Set<String> clearKeys = new LinkedHashSet<>(Profile.knownCustomKeys(this));
        for (String[] kv : profile.customProps()) clearKeys.add(kv[0]);

        List<String> cmds = new ArrayList<>();
        cmds.add("am force-stop " + pkg);
        cmds.addAll(Tweaks.resetCommands(clearKeys));
        List<String> sets = Tweaks.applyCommands(profile);
        cmds.addAll(sets);
        cmds.add("echo " + OK_MARK);

        setBusy(true);
        setResult(andLaunch ? "Applying and restarting " + name + "…" : "Applying…", R.color.text_dim);
        Adb.get(this).run(cmds, (out, err) -> {
            setBusy(false);
            if (err != null) {
                setResult(Adb.friendly(err), R.color.bad);
                return;
            }
            if (out == null || !out.contains(OK_MARK)) {
                setResult("The headset did not confirm the change. Output: " + (out == null ? "" : out.trim()), R.color.bad);
                return;
            }
            Profile.rememberCustomKeys(this, profile);
            String what = sets.isEmpty() ? "Cleared all tweaks (game defaults)" : "Applied " + sets.size() + " setting" + (sets.size() == 1 ? "" : "s");
            if (!andLaunch) {
                setResult(what + ". Start " + name + " to use them.", R.color.good);
                return;
            }
            setResult(what + ". Launching " + name + "…", R.color.good);
            launchGame();
        });
    }

    private void launchGame() {
        if (launch == null) {
            setResult("Couldn't find how to start " + name + ". Open it from your library.", R.color.warn);
            return;
        }
        try {
            Games.launch(this, launch);
            PerfMonitor.startFor(this, pkg, name);
        } catch (Exception e) {
            // Fall back to launching through the shell.
            Adb.get(this).run("am start -n " + launch.flattenToShortString(), (out, err) -> {
                if (err != null) setResult("Settings applied, but launch failed: " + Adb.friendly(err), R.color.warn);
            });
        }
    }

    private void checkLive() {
        Set<String> keys = Tweaks.managedKeys();
        for (String[] kv : profile.customProps()) keys.add(kv[0]);
        List<String> cmds = new ArrayList<>();
        for (String k : keys) cmds.add("echo \"" + k + "=$(getprop " + k + ")\"");
        setBusy(true);
        Adb.get(this).run(cmds, (out, err) -> {
            setBusy(false);
            if (err != null) {
                setResult(Adb.friendly(err), R.color.bad);
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (String line : out.split("\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                int eq = line.indexOf('=');
                String v = eq >= 0 ? line.substring(eq + 1) : "";
                sb.append(line.substring(0, eq >= 0 ? eq : line.length()).replace("debug.oculus.", ""))
                        .append(":  ").append(v.isEmpty() ? "default" : v).append('\n');
            }
            Ui.info(this, "Live values on the headset", sb.toString().trim());
        });
    }

    private void resetAll() {
        setBusy(true);
        Adb.get(this).run(Tweaks.resetCommands(Profile.knownCustomKeys(this)), (out, err) -> {
            setBusy(false);
            if (err != null) setResult(Adb.friendly(err), R.color.bad);
            else setResult("All tweaks cleared on the headset. Your saved profile is kept.", R.color.good);
        });
    }
}
