package com.vrtune.optimizer;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Saved per-game settings: tweakId -> optionId, plus free-form custom properties. */
final class Profile {
    final Map<String, String> selections = new LinkedHashMap<>();
    String custom = "";

    boolean isEmpty() {
        for (String v : selections.values()) if (v != null && !v.isEmpty()) return false;
        return customProps().isEmpty();
    }

    /** Parses lines of "key=value" (or "setprop key value"). Invalid lines are skipped. */
    List<String[]> customProps() {
        List<String[]> out = new ArrayList<>();
        for (String raw : custom.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.startsWith("setprop ")) line = line.substring(8).trim().replaceFirst("\\s+", "=");
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String k = line.substring(0, eq).trim();
            String v = line.substring(eq + 1).trim();
            if (v.length() >= 2 && (v.startsWith("'") && v.endsWith("'") || v.startsWith("\"") && v.endsWith("\"")))
                v = v.substring(1, v.length() - 1);
            if (Tweaks.safeKey(k) && Tweaks.safeValue(v)) out.add(new String[]{k, v});
        }
        return out;
    }

    /** Lines in the custom box that will be ignored, so the UI can warn. */
    List<String> invalidCustomLines() {
        List<String> bad = new ArrayList<>();
        for (String raw : custom.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            Profile one = new Profile();
            one.custom = line;
            if (one.customProps().isEmpty()) bad.add(line);
        }
        return bad;
    }

    String summary() {
        List<String> parts = new ArrayList<>();
        for (Tweaks.Tweak t : Tweaks.ALL) {
            int i = t.indexOf(selections.get(t.id));
            if (i == 0) continue;
            String label = t.options.get(i).label;
            if (t.id.equals("resolution")) label = label.substring(0, label.indexOf('·')).trim();
            if (t.id.equals("cpu")) label = "CPU " + label.split(" ")[0];
            if (t.id.equals("gpu")) label = "GPU " + label.split(" ")[0];
            if (t.id.equals("ffr")) label = "FFR " + label;
            if (t.group.equals(Tweaks.G_CAPTURE)) continue;
            parts.add(label);
        }
        int c = customProps().size();
        if (c > 0) parts.add(c + " custom");
        return parts.isEmpty() ? "" : String.join(" · ", parts);
    }

    // ---- storage ----

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("profiles", Context.MODE_PRIVATE);
    }

    static Profile load(Context c, String pkg) {
        Profile p = new Profile();
        String json = prefs(c).getString(pkg, null);
        if (json == null) return p;
        try {
            JSONObject o = new JSONObject(json);
            JSONObject sel = o.optJSONObject("sel");
            if (sel != null) {
                Iterator<String> it = sel.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    p.selections.put(k, sel.getString(k));
                }
            }
            p.custom = o.optString("custom", "");
        } catch (JSONException ignored) {
        }
        return p;
    }

    void save(Context c, String pkg) {
        try {
            JSONObject o = new JSONObject();
            JSONObject sel = new JSONObject();
            for (Map.Entry<String, String> e : selections.entrySet())
                if (e.getValue() != null && !e.getValue().isEmpty()) sel.put(e.getKey(), e.getValue());
            o.put("sel", sel);
            o.put("custom", custom);
            prefs(c).edit().putString(pkg, o.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    // Custom keys we have set before, so "Reset" can clear them too.
    static Set<String> knownCustomKeys(Context c) {
        return new HashSet<>(c.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getStringSet("custom_keys", new HashSet<>()));
    }

    static void rememberCustomKeys(Context c, Profile p) {
        Set<String> s = knownCustomKeys(c);
        for (String[] kv : p.customProps()) s.add(kv[0]);
        c.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putStringSet("custom_keys", s).apply();
    }
}
