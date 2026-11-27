package com.vrtune.optimizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Every setting VRTune can change. Each maps to Meta's documented debug.oculus.* system properties
 * (see developers.meta.com "Configure Android System Properties"). They are global, reset on reboot,
 * and are read by a game when it starts, so we always force-stop the game before applying.
 */
final class Tweaks {
    static final class Option {
        final String label;
        /** One value per key of the tweak; null means "leave at game/system default". */
        final String[] values;

        Option(String label, String... values) {
            this.label = label;
            this.values = values;
        }

        boolean isDefault() {
            return values == null;
        }

        String id() {
            return values == null ? "" : String.join(",", values);
        }
    }

    static final class Tweak {
        final String id;
        final String group;
        final String title;
        final String description;
        final String[] keys;
        final List<Option> options = new ArrayList<>();

        Tweak(String id, String group, String title, String description, String... keys) {
            this.id = id;
            this.group = group;
            this.title = title;
            this.description = description;
            this.keys = keys;
            options.add(new Option("Game default", (String[]) null));
        }

        Tweak opt(String label, String... values) {
            options.add(new Option(label, values));
            return this;
        }

        int indexOf(String optionId) {
            if (optionId == null) return 0;
            for (int i = 0; i < options.size(); i++) if (options.get(i).id().equals(optionId)) return i;
            return 0;
        }
    }

    static final String G_DISPLAY = "Display & clarity";
    static final String G_PERF = "Performance";
    static final String G_FOV = "Foveated rendering";
    static final String G_CAPTURE = "Recording & casting";

    /** Quest 3 default eye-buffer size, per Meta's docs. */
    static final int BASE_W = 1680, BASE_H = 1760;

    static final List<Tweak> ALL = build();

    private static List<Tweak> build() {
        List<Tweak> l = new ArrayList<>();

        l.add(new Tweak("refresh", G_DISPLAY, "Refresh rate",
                "Higher Hz is smoother and reduces motion blur but costs GPU time. 72 Hz gives the most headroom for resolution.",
                "debug.oculus.refreshRate")
                .opt("72 Hz", "72").opt("80 Hz", "80").opt("90 Hz", "90").opt("120 Hz", "120"));

        Tweak res = new Tweak("resolution", G_DISPLAY, "Render resolution (per eye)",
                "Supersampling: rendering above 1680×1760 and downscaling is the most effective anti-aliasing and sharpening on Quest. Lowering it gives big FPS gains.",
                "debug.oculus.textureWidth", "debug.oculus.textureHeight");
        int[] widths = {1216, 1344, 1440, 1536, 1680, 1824, 1920, 2064, 2208, 2352, 2496, 2640, 2784, 2880, 3072};
        for (int w : widths) {
            int h = Math.round(w * (float) BASE_H / BASE_W / 8f) * 8;
            float mult = (float) w / BASE_W;
            String tag = w == BASE_W ? "  (Quest 3 default)" : w == 2064 ? "  (panel native)" : "";
            res.opt(String.format(Locale.US, "%d × %d  ·  %.2fx%s", w, h, mult, tag), String.valueOf(w), String.valueOf(h));
        }
        l.add(res);

        l.add(new Tweak("cpu", G_PERF, "CPU level",
                "Minimum CPU clock level. Raise it for CPU-bound games (physics, many NPCs, stutters). Higher = more heat and battery drain.",
                "debug.oculus.cpuLevel")
                .opt("0 · lowest", "0").opt("1", "1").opt("2", "2").opt("3", "3").opt("4", "4").opt("5 · max", "5"));

        l.add(new Tweak("gpu", G_PERF, "GPU level",
                "Minimum GPU clock level. Raise it when you increase resolution or refresh rate. Higher = more heat and battery drain.",
                "debug.oculus.gpuLevel")
                .opt("0 · lowest", "0").opt("1", "1").opt("2", "2").opt("3", "3").opt("4", "4").opt("5 · max", "5"));

        l.add(new Tweak("ffr", G_FOV, "Foveation level",
                "Renders the edges of the lens at lower detail, where you barely see it. Higher = more FPS, softer edges.",
                "debug.oculus.foveation.level")
                .opt("Off", "0").opt("Low", "1").opt("Medium", "2").opt("High", "3").opt("High top", "4"));

        l.add(new Tweak("ffr_dyn", G_FOV, "Dynamic foveation",
                "When on, the game raises foveation only under load and the level above acts as a maximum. Turn off to force the level above.",
                "debug.oculus.foveation.dynamic")
                .opt("On", "1").opt("Off (force level)", "0"));

        l.add(new Tweak("cap_rate", G_CAPTURE, "Full-rate recording",
                "Record and cast at the full refresh rate instead of half rate.",
                "debug.oculus.fullRateCapture")
                .opt("On", "1").opt("Off", "0"));

        l.add(new Tweak("cap_size", G_CAPTURE, "Recording resolution",
                "Size of videos recorded in the headset (default 1024×1024).",
                "debug.oculus.capture.width", "debug.oculus.capture.height")
                .opt("1024 × 1024", "1024", "1024").opt("1280 × 720", "1280", "720")
                .opt("1440 × 1440", "1440", "1440").opt("1920 × 1080", "1920", "1080"));

        l.add(new Tweak("cap_bitrate", G_CAPTURE, "Recording bitrate",
                "Higher bitrate means cleaner recordings and bigger files (default 5 Mbps).",
                "debug.oculus.capture.bitrate")
                .opt("5 Mbps", "5000000").opt("10 Mbps", "10000000").opt("20 Mbps", "20000000").opt("40 Mbps", "40000000"));

        return Collections.unmodifiableList(l);
    }

    static Tweak byId(String id) {
        for (Tweak t : ALL) if (t.id.equals(id)) return t;
        return null;
    }

    static List<String> groups() {
        Set<String> s = new LinkedHashSet<>();
        for (Tweak t : ALL) s.add(t.group);
        return new ArrayList<>(s);
    }

    static Set<String> managedKeys() {
        Set<String> s = new LinkedHashSet<>();
        for (Tweak t : ALL) Collections.addAll(s, t.keys);
        return s;
    }

    // ---- presets ----

    static final class Preset {
        final String name;
        final String hint;
        final String[][] picks; // {tweakId, optionId}

        Preset(String name, String hint, String[]... picks) {
            this.name = name;
            this.hint = hint;
            this.picks = picks;
        }

        void applyTo(Profile p) {
            p.selections.clear();
            for (String[] pick : picks) p.selections.put(pick[0], pick[1]);
        }
    }

    static final List<Preset> PRESETS = List.of(
            new Preset("Battery saver", "72 Hz · 0.86x · high foveation",
                    new String[]{"refresh", "72"}, new String[]{"resolution", "1440,1512"},
                    new String[]{"ffr", "3"}),
            new Preset("Balanced", "90 Hz · default res · medium foveation",
                    new String[]{"refresh", "90"}, new String[]{"ffr", "2"}),
            new Preset("Smooth 120", "120 Hz · 0.91x · high foveation · GPU 4",
                    new String[]{"refresh", "120"}, new String[]{"resolution", "1536,1608"},
                    new String[]{"ffr", "3"}, new String[]{"gpu", "4"}),
            new Preset("Sharp", "72 Hz · 1.23x supersampling · low foveation · GPU 4",
                    new String[]{"refresh", "72"}, new String[]{"resolution", "2064,2160"},
                    new String[]{"ffr", "1"}, new String[]{"gpu", "4"}),
            new Preset("Ultra sharp", "72 Hz · 1.49x · medium foveation · CPU 4 GPU 5",
                    new String[]{"refresh", "72"}, new String[]{"resolution", "2496,2616"},
                    new String[]{"ffr", "2"}, new String[]{"cpu", "4"}, new String[]{"gpu", "5"}),
            new Preset("Game default", "Leave everything to the game",
                    new String[0][0])
    );

    // ---- command building ----

    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9._\\-]{1,90}");
    private static final Pattern SAFE_VALUE = Pattern.compile("[A-Za-z0-9._:,+\\-]{0,90}");

    static boolean safeKey(String k) {
        return SAFE_KEY.matcher(k).matches();
    }

    static boolean safeValue(String v) {
        return SAFE_VALUE.matcher(v).matches();
    }

    static String setprop(String key, String value) {
        if (!safeKey(key) || !safeValue(value)) throw new IllegalArgumentException("Unsafe property: " + key);
        return "setprop " + key + " '" + value + "'";
    }

    /** Clears every property VRTune manages, plus any extra custom keys. */
    static List<String> resetCommands(Set<String> extraKeys) {
        Set<String> keys = managedKeys();
        if (extraKeys != null) for (String k : extraKeys) if (safeKey(k)) keys.add(k);
        List<String> cmds = new ArrayList<>();
        for (String k : keys) cmds.add(setprop(k, ""));
        return cmds;
    }

    /** setprop commands for a profile (without the reset). */
    static List<String> applyCommands(Profile p) {
        List<String> cmds = new ArrayList<>();
        for (Tweak t : ALL) {
            Option o = t.options.get(t.indexOf(p.selections.get(t.id)));
            if (o.isDefault()) continue;
            for (int i = 0; i < t.keys.length; i++) cmds.add(setprop(t.keys[i], o.values[i]));
        }
        for (String[] kv : p.customProps()) cmds.add(setprop(kv[0], kv[1]));
        return cmds;
    }
}
