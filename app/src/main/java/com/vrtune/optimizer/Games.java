package com.vrtune.optimizer;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;

import java.text.Collator;
import java.util.ArrayList;
import java.util.List;

/** Finds installed apps/games and how to launch them. */
final class Games {
    static final class Game {
        final String pkg;
        final String name;
        final Drawable icon;
        final boolean vr;
        final ComponentName launch;

        Game(String pkg, String name, Drawable icon, boolean vr, ComponentName launch) {
            this.pkg = pkg;
            this.name = name;
            this.icon = icon;
            this.vr = vr;
            this.launch = launch;
        }
    }

    private static final String VR_CATEGORY = "com.oculus.intent.category.VR";

    static List<Game> scan(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        List<Game> out = new ArrayList<>();
        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        for (ApplicationInfo ai : apps) {
            if (ai.packageName.equals(ctx.getPackageName())) continue;
            boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                    && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
            if (system) continue;
            ComponentName launch = findLaunch(pm, ai.packageName);
            if (launch == null) continue;
            boolean vr = isVr(pm, ai, launch);
            Drawable icon;
            try {
                icon = ai.loadIcon(pm);
            } catch (Exception e) {
                icon = null;
            }
            out.add(new Game(ai.packageName, String.valueOf(ai.loadLabel(pm)), icon, vr, launch));
        }
        Collator col = Collator.getInstance();
        out.sort((a, b) -> col.compare(a.name, b.name));
        return out;
    }

    private static ComponentName findLaunch(PackageManager pm, String pkg) {
        String[] categories = {VR_CATEGORY, Intent.CATEGORY_LAUNCHER, null};
        for (String cat : categories) {
            Intent i = new Intent(Intent.ACTION_MAIN).setPackage(pkg);
            if (cat != null) i.addCategory(cat);
            List<ResolveInfo> r = pm.queryIntentActivities(i, 0);
            for (ResolveInfo ri : r) {
                if (ri.activityInfo != null && ri.activityInfo.exported)
                    return new ComponentName(ri.activityInfo.packageName, ri.activityInfo.name);
            }
        }
        return null;
    }

    private static boolean isVr(PackageManager pm, ApplicationInfo ai, ComponentName launch) {
        Bundle md = ai.metaData;
        if (md != null && (md.containsKey("com.oculus.supportedDevices")
                || md.containsKey("com.samsung.android.vr.application.mode")
                || md.containsKey("com.oculus.vr.focusaware"))) {
            return true;
        }
        Intent vrIntent = new Intent(Intent.ACTION_MAIN).setPackage(ai.packageName).addCategory(VR_CATEGORY);
        if (!pm.queryIntentActivities(vrIntent, 0).isEmpty()) return true;
        try {
            PackageInfo pi = pm.getPackageInfo(ai.packageName, PackageManager.GET_CONFIGURATIONS);
            if (pi.reqFeatures != null) {
                for (FeatureInfo f : pi.reqFeatures) {
                    if (f.name != null && (f.name.startsWith("android.hardware.vr.headtracking")
                            || f.name.startsWith("oculus.software.handtracking")))
                        return true;
                }
            }
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        return false;
    }

    static void launch(Context ctx, ComponentName c) {
        Intent i = new Intent(Intent.ACTION_MAIN).setComponent(c)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        ctx.startActivity(i);
    }
}
