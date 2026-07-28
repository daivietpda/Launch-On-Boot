package news.androidtv.launchonboot;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.tv.TvContract;
import android.os.Build;

/** Resolves a target only from the stored package selected in the app picker. */
final class TargetAppResolver {
    static final class ResolvedTarget {
        final Intent intent;
        final String packageName;
        final String componentName;
        final String category;

        ResolvedTarget(Intent intent, String packageName, String componentName, String category) {
            this.intent = intent;
            this.packageName = packageName;
            this.componentName = componentName;
            this.category = category;
        }
    }

    private final Context context;

    TargetAppResolver(Context context) { this.context = context.getApplicationContext(); }

    ResolvedTarget resolve(TargetAppLauncher.Target target) {
        if (target == null) return null;
        PackageManager pm = context.getPackageManager();
        if (target.liveChannels) {
            Intent intent = new Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI);
            ResolveInfo info = pm.resolveActivity(intent, 0);
            return info == null ? null : new ResolvedTarget(intent, "", "", "");
        }
        if (!AdbConnectionManager.isValidPackageName(target.packageName)
                || context.getPackageName().equals(target.packageName)) return null;
        Intent intent = pm.getLeanbackLaunchIntentForPackage(target.packageName);
        String category = Intent.CATEGORY_LEANBACK_LAUNCHER;
        if (intent == null) {
            intent = pm.getLaunchIntentForPackage(target.packageName);
            category = Intent.CATEGORY_LAUNCHER;
        }
        if (intent == null) return null;
        ResolveInfo info = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        ComponentName component = intent.getComponent();
        if (component == null && info != null && info.activityInfo != null) {
            component = new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
        }
        if (component == null || !target.packageName.equals(component.getPackageName())
                || context.getPackageName().equals(component.getPackageName())) return null;
        try {
            ActivityInfo activity = pm.getActivityInfo(component, 0);
            if (!activity.exported) return null;
        } catch (PackageManager.NameNotFoundException e) { return null; }
        String flattened = component.flattenToShortString();
        if (!AdbConnectionManager.isValidComponentName(flattened)) return null;
        intent.setComponent(component);
        return new ResolvedTarget(intent, target.packageName, flattened, category);
    }
}
