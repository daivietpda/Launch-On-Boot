package news.androidtv.launchonboot;

import android.content.Context;
import android.provider.Settings;

/** Chooses a safe launch backend; ADB failure never falls back to a risky background launch. */
final class TargetAppLaunchCoordinator {
    private final Context context;

    TargetAppLaunchCoordinator(Context context) { this.context = context.getApplicationContext(); }

    static TargetAppLauncher.Method choose(TargetAppLauncher.Method requested,
                                           boolean advancedUsesAdb, boolean overlayGranted) {
        if (requested == TargetAppLauncher.Method.ADB || advancedUsesAdb) {
            return TargetAppLauncher.Method.ADB;
        }
        if (requested == TargetAppLauncher.Method.ANDROID) return TargetAppLauncher.Method.ANDROID;
        return overlayGranted ? TargetAppLauncher.Method.ANDROID : TargetAppLauncher.Method.ADB;
    }

    TargetAppLauncher.LaunchResult launch(TargetAppLauncher.Target target,
                                            TargetAppLauncher.Method requested,
                                            boolean advancedUsesAdb) {
        TargetAppLauncher.Method method = choose(requested, advancedUsesAdb,
                Settings.canDrawOverlays(context));
        if (method == TargetAppLauncher.Method.ADB) {
            AdbTargetAppLauncher launcher = new AdbTargetAppLauncher(context);
            try { return launcher.launch(target); } finally { launcher.close(); }
        }
        return new AndroidTargetAppLauncher(context).launch(target);
    }
}
