package com.vrtune.optimizer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Library screen: pick a game to optimize. */
public class MainActivity extends Activity {
    private final List<Games.Game> all = new ArrayList<>();
    private final List<Games.Game> shown = new ArrayList<>();
    private GameAdapter adapter;
    private EditText search;
    private CheckBox vrOnly;
    private TextView empty;
    private Ui.StatusBar status;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 24);
        root.setPadding(pad, pad, pad, Ui.dp(this, 12));

        // Header
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(Ui.text(this, "VRTune", 30, R.color.text, true));
        titles.addView(Ui.text(this, "Per-game resolution, refresh rate, foveation and clock tuning for Quest", 14, R.color.text_dim, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button reset = Ui.button(this, "Reset all", false);
        reset.setOnClickListener(v -> confirmResetAll());
        Button port = Ui.button(this, "Port", false);
        port.setOnClickListener(v -> Ui.askPort(this, Adb.get(this)));
        header.addView(reset, Ui.wrap(this, 10));
        header.addView(port);
        root.addView(header);

        status = new Ui.StatusBar(this);
        root.addView(status.view, Ui.matchWidth(this, 16));

        // Search + filter
        LinearLayout filters = new LinearLayout(this);
        filters.setGravity(Gravity.CENTER_VERTICAL);
        search = new EditText(this);
        search.setHint("Search games…");
        search.setSingleLine(true);
        search.setTextColor(Ui.color(this, R.color.text));
        search.setHintTextColor(Ui.color(this, R.color.text_dim));
        search.setBackgroundResource(R.drawable.field);
        int fp = Ui.dp(this, 14);
        search.setPadding(fp, fp, fp, fp);
        filters.addView(search, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        vrOnly = new CheckBox(this);
        vrOnly.setText("VR games only");
        vrOnly.setTextColor(Ui.color(this, R.color.text));
        vrOnly.setChecked(prefs.getBoolean("vr_only", true));
        LinearLayout.LayoutParams cbp = Ui.wrap(this, 0);
        cbp.setMarginStart(Ui.dp(this, 16));
        filters.addView(vrOnly, cbp);
        root.addView(filters, Ui.matchWidth(this, 16));

        // Grid
        GridView grid = new GridView(this);
        grid.setColumnWidth(Ui.dp(this, 190));
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(Ui.dp(this, 14));
        grid.setVerticalSpacing(Ui.dp(this, 14));
        grid.setSelector(android.R.color.transparent);
        grid.setClipToPadding(false);
        grid.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 12));
        adapter = new GameAdapter();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((p, v, pos, id) -> {
            Games.Game g = shown.get(pos);
            startActivity(new Intent(this, GameActivity.class).putExtra("pkg", g.pkg));
        });
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        gp.topMargin = Ui.dp(this, 16);
        empty = Ui.text(this, "Loading your library…", 16, R.color.text_dim, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, Ui.dp(this, 40), 0, 0);
        root.addView(empty, Ui.matchWidth(this, 0));
        root.addView(grid, gp);

        setContentView(root);

        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b2, int c) { }
            public void onTextChanged(CharSequence s, int a, int b2, int c) { }
            public void afterTextChanged(Editable s) { filter(); }
        });
        vrOnly.setOnCheckedChangeListener((v, c) -> {
            prefs.edit().putBoolean("vr_only", c).apply();
            filter();
        });

        loadGames();
        if (!prefs.getBoolean("seen_help", false)) {
            prefs.edit().putBoolean("seen_help", true).apply();
            Ui.showSetupHelp(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Adb adb = Adb.get(this);
        adb.addListener(status);
        if (adb.state() == Adb.State.DISCONNECTED) adb.connect((ok, e) -> { });
        adapter.notifyDataSetChanged(); // refresh "tuned" summaries
    }

    @Override
    protected void onPause() {
        super.onPause();
        Adb.get(this).removeListener(status);
    }

    private void loadGames() {
        Context app = getApplicationContext();
        new Thread(() -> {
            List<Games.Game> list = Games.scan(app);
            runOnUiThread(() -> {
                all.clear();
                all.addAll(list);
                filter();
            });
        }, "scan").start();
    }

    private void filter() {
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for (Games.Game g : all) {
            if (vrOnly.isChecked() && !g.vr) continue;
            if (!q.isEmpty() && !g.name.toLowerCase(Locale.ROOT).contains(q) && !g.pkg.contains(q)) continue;
            shown.add(g);
        }
        adapter.notifyDataSetChanged();
        if (shown.isEmpty()) {
            empty.setVisibility(View.VISIBLE);
            empty.setText(all.isEmpty() ? "No games found yet." :
                    vrOnly.isChecked() ? "No VR games match. Untick \"VR games only\" to see every app." : "Nothing matches your search.");
        } else {
            empty.setVisibility(View.GONE);
        }
    }

    private void confirmResetAll() {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Reset all tweaks?")
                .setMessage("Clears every setting VRTune has applied, so all games use their own defaults again. Your saved profiles are kept.")
                .setPositiveButton("Reset", (d, w) -> Adb.get(this).run(Tweaks.resetCommands(Profile.knownCustomKeys(this)),
                        (out, e) -> Toast.makeText(this, e == null ? "All tweaks cleared" : Adb.friendly(e), Toast.LENGTH_LONG).show()))
                .setNegativeButton("Cancel", null).show();
    }

    private final class GameAdapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            Holder h;
            if (convert == null) {
                Context c = MainActivity.this;
                LinearLayout cell = Ui.card(c);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                h = new Holder();
                h.icon = new ImageView(c);
                int s = Ui.dp(c, 84);
                cell.addView(h.icon, new LinearLayout.LayoutParams(s, s));
                h.name = Ui.text(c, "", 15, R.color.text, true);
                h.name.setGravity(Gravity.CENTER);
                h.name.setMaxLines(2);
                cell.addView(h.name, Ui.matchWidth(c, 10));
                h.sub = Ui.text(c, "", 12, R.color.text_dim, false);
                h.sub.setGravity(Gravity.CENTER);
                h.sub.setMaxLines(2);
                cell.addView(h.sub, Ui.matchWidth(c, 4));
                cell.setTag(h);
                convert = cell;
            } else {
                h = (Holder) convert.getTag();
            }
            Games.Game g = shown.get(pos);
            if (g.icon != null) h.icon.setImageDrawable(g.icon);
            else h.icon.setImageResource(R.mipmap.ic_launcher);
            h.name.setText(g.name);
            String summary = Profile.load(MainActivity.this, g.pkg).summary();
            if (summary.isEmpty()) {
                h.sub.setText(g.vr ? "VR · not tuned" : "2D app · not tuned");
                h.sub.setTextColor(Ui.color(MainActivity.this, R.color.text_dim));
            } else {
                h.sub.setText(summary);
                h.sub.setTextColor(Ui.color(MainActivity.this, R.color.accent));
            }
            return convert;
        }
    }

    private static final class Holder {
        ImageView icon;
        TextView name, sub;
    }
}
