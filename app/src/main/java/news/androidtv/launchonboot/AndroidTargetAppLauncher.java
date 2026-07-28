package news.androidtv.launchonboot;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Android API launch cannot prove foreground delivery when invoked from background. */
final class AndroidTargetAppLauncher implements TargetAppLauncher {
    private static final String TAG = AndroidTargetAppLauncher.class.getSimpleName();
    private final Context context;
    private final TargetAppResolver resolver;

    AndroidTargetAppLauncher(Context context) {
        this.context = context.getApplicationContext();
        this.resolver = new TargetAppResolver(context);
    }

    @Override public LaunchResult launch(Target target) {
        TargetAppResolver.ResolvedTarget resolved = resolver.resolve(target);
        if (resolved == null) return new LaunchResult(Status.FAILED, Method.ANDROID,
                target == null ? "" : target.packageName, "", "Target activity cannot be resolved");
        Intent intent = new Intent(resolved.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            context.startActivity(intent);
            Log.i(TAG, "DIRECT_LAUNCH_UNVERIFIED: Android launch request submitted");
            return new LaunchResult(Status.REQUESTED_UNVERIFIED, Method.ANDROID,
                    resolved.packageName, resolved.componentName,
                    "Android launch request submitted; foreground result is not confirmed");
        } catch (ActivityNotFoundException | SecurityException | IllegalStateException e) {
            Log.w(TAG, "DIRECT_LAUNCH_FAILED", e);
            return new LaunchResult(Status.FAILED, Method.ANDROID, resolved.packageName,
                    resolved.componentName, e.getClass().getSimpleName());
        }
    }
}
