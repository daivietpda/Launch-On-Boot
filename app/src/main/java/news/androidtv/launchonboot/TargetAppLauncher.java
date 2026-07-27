package news.androidtv.launchonboot;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.os.Build;
import android.util.Log;

/** Resolves and launches only the configured target application. */
final class TargetAppLauncher {
    private static final String TAG = TargetAppLauncher.class.getSimpleName();

    static final class Target {
        final boolean liveChannels;
        final String packageName;

        Target(boolean liveChannels, String packageName) {
            this.liveChannels = liveChannels;
            this.packageName = packageName;
        }
    }

    private final Context context;

    TargetAppLauncher(Context context) {
        this.context = context.getApplicationContext();
    }

    boolean launch(Target target) {
        Intent intent = resolve(target);
        if (intent == null) {
            Log.w(TAG, "Configured target application cannot be resolved");
            return false;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            context.startActivity(intent);
            Log.i(TAG, "Target application launch request accepted");
            return true;
        } catch (ActivityNotFoundException | SecurityException | IllegalStateException e) {
            Log.w(TAG, "Target application launch request failed", e);
            return false;
        }
    }

    private Intent resolve(Target target) {
        if (target.liveChannels
                && context.getResources().getBoolean(R.bool.TIF_SUPPORT)
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Intent intent = new Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI);
            return intent.resolveActivity(context.getPackageManager()) == null ? null : intent;
        }
        if (target.packageName == null || target.packageName.trim().isEmpty()) {
            return null;
        }
        Intent intent = context.getPackageManager()
                .getLeanbackLaunchIntentForPackage(target.packageName);
        if (intent == null) {
            intent = context.getPackageManager().getLaunchIntentForPackage(target.packageName);
        }
        return intent;
    }
}
