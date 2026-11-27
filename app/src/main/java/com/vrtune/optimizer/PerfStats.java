package com.vrtune.optimizer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the once-per-second performance line the Quest runtime writes to logcat (tag "VrApi"), e.g.
 * "FPS=72/72,Prd=45ms,Tear=0,Early=0,Stale=0,...,CPU4/GPU=2/2,...,Temp=32.0C/0.0C,...,GPU%=0.47,CPU%=0.20(W0.27),..."
 * and turns a session's worth of them into a report. Pure Java so it can be unit-tested off-device.
 */
final class PerfStats {
    static final class Sample {
        double epoch;
        int fps = -1, target = -1, stale = 0, cpuLevel = -1, gpuLevel = -1, asw = 0;
        double gpuUtil = -1, cpuUtil = -1, temp = -1;
    }

    private static final Pattern EPOCH = Pattern.compile("^\\s*(\\d{9,}\\.\\d+)");
    private static final Pattern FPS = Pattern.compile("FPS=(\\d+)/(\\d+)");

    /** Parses one logcat line (any format). Returns null if it isn't a VrApi stats line. */
    static Sample parse(String line) {
        int i = line.indexOf("FPS=");
        if (i < 0) return null;
        Matcher fm = FPS.matcher(line);
        if (!fm.find()) return null;
        Sample s = new Sample();
        Matcher em = EPOCH.matcher(line);
        if (em.find()) s.epoch = Double.parseDouble(em.group(1));
        s.fps = Integer.parseInt(fm.group(1));
        s.target = Integer.parseInt(fm.group(2));
        Map<String, String> kv = new LinkedHashMap<>();
        for (String part : line.substring(i).split(",")) {
            int eq = part.indexOf('=');
            if (eq > 0) kv.put(part.substring(0, eq).trim(), part.substring(eq + 1).trim());
        }
        s.stale = intOf(kv.get("Stale"), 0);
        s.asw = intOf(kv.get("ASW"), 0);
        String levels = kv.get("CPU4/GPU");
        if (levels == null) levels = kv.get("CPU/GPU");
        if (levels != null && levels.contains("/")) {
            String[] lv = levels.split("/");
            s.cpuLevel = intOf(lv[0], -1);
            s.gpuLevel = intOf(lv[1], -1);
        }
        s.gpuUtil = doubleOf(kv.get("GPU%"), -1);
        s.cpuUtil = doubleOf(kv.get("CPU%"), -1);
        String t = kv.get("Temp");
        if (t != null) s.temp = doubleOf(t.split("/")[0], -1);
        return s;
    }

    private static int intOf(String v, int def) {
        if (v == null) return def;
        Matcher m = Pattern.compile("-?\\d+").matcher(v);
        return m.find() ? Integer.parseInt(m.group()) : def;
    }

    private static double doubleOf(String v, double def) {
        if (v == null) return def;
        Matcher m = Pattern.compile("-?\\d+(\\.\\d+)?").matcher(v);
        return m.find() ? Double.parseDouble(m.group()) : def;
    }

    // ---- report ----

    static final class Report {
        int seconds;
        int target;
        double avgFps;
        int minFps;
        double dropPct;      // % of seconds below 95% of target
        int staleFrames;
        double avgGpu = -1, avgCpu = -1, maxTemp = -1;
        int cpuLevel = -1, gpuLevel = -1;
        boolean asw;
        String verdict;      // "heavy", "ok", "headroom"

        String headline() {
            return String.format(Locale.US, "%.0f / %d FPS average · %.0f%% of time below target · %d dropped frames",
                    avgFps, target, dropPct, staleFrames);
        }

        String details() {
            List<String> p = new ArrayList<>();
            p.add("Played " + fmtDuration(seconds) + ", lowest " + minFps + " FPS");
            if (avgGpu >= 0) p.add(String.format(Locale.US, "GPU busy %.0f%%", avgGpu * 100));
            if (avgCpu >= 0) p.add(String.format(Locale.US, "CPU busy %.0f%%", avgCpu * 100));
            if (cpuLevel >= 0) p.add("CPU/GPU level " + cpuLevel + "/" + gpuLevel);
            if (maxTemp > 0) p.add(String.format(Locale.US, "max battery temp %.0f°C", maxTemp));
            if (asw) p.add("ASW (frame synthesis) kicked in");
            return String.join(" · ", p);
        }

        String advice() {
            switch (verdict) {
                case "heavy":
                    return "Struggling: lower Render resolution one step, raise Foveation, or raise GPU level.";
                case "headroom":
                    return "Lots of headroom: you can raise Render resolution one step (sharper) or try a higher refresh rate.";
                default:
                    return "Running smoothly at these settings.";
            }
        }
    }

    static String fmtDuration(int s) {
        if (s < 60) return s + "s";
        return (s / 60) + "m " + (s % 60) + "s";
    }

    /** Builds a report, or null if there weren't enough samples. */
    static Report report(List<Sample> samples) {
        List<Sample> ok = new ArrayList<>();
        for (Sample s : samples) if (s.fps >= 0 && s.target > 0) ok.add(s);
        // Skip the first few seconds (loading screens).
        if (ok.size() > 20) ok = ok.subList(5, ok.size());
        if (ok.size() < 5) return null;

        Report r = new Report();
        r.seconds = ok.size();
        Map<Integer, Integer> targets = new LinkedHashMap<>();
        double sumFps = 0, sumGpu = 0, sumCpu = 0;
        int nGpu = 0, nCpu = 0, below = 0;
        r.minFps = Integer.MAX_VALUE;
        for (Sample s : ok) {
            targets.merge(s.target, 1, Integer::sum);
            sumFps += s.fps;
            r.minFps = Math.min(r.minFps, s.fps);
            if (s.fps < s.target * 0.95) below++;
            r.staleFrames += s.stale;
            if (s.gpuUtil >= 0) { sumGpu += s.gpuUtil; nGpu++; }
            if (s.cpuUtil >= 0) { sumCpu += s.cpuUtil; nCpu++; }
            r.maxTemp = Math.max(r.maxTemp, s.temp);
            if (s.asw > 0) r.asw = true;
            if (s.cpuLevel >= 0) { r.cpuLevel = s.cpuLevel; r.gpuLevel = s.gpuLevel; }
        }
        int best = 0;
        for (Map.Entry<Integer, Integer> e : targets.entrySet())
            if (e.getValue() > best) { best = e.getValue(); r.target = e.getKey(); }
        r.avgFps = sumFps / ok.size();
        r.dropPct = 100.0 * below / ok.size();
        if (nGpu > 0) r.avgGpu = sumGpu / nGpu;
        if (nCpu > 0) r.avgCpu = sumCpu / nCpu;

        if (r.dropPct > 8 || r.avgGpu > 0.95) r.verdict = "heavy";
        else if (r.dropPct < 1 && r.avgGpu >= 0 && r.avgGpu < 0.70) r.verdict = "headroom";
        else r.verdict = "ok";
        return r;
    }
}
