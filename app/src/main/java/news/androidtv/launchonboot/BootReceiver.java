package news.androidtv.launchonboot;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Receives boot and immediately delegates all work to the foreground service. */
public final class BootReceiver extends BroadcastReceiver {
    private static final String TAG = BootReceiver.class.getSimpleName();

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.w(TAG, "Ignoring unsupported receiver action");
            return;
        }
        SettingsManager settings = new SettingsManager(context);
        if (!settings.getBoolean(SettingsManagerConstants.BOOT_APP_ENABLED)) {
            return;
        }
        DreamListenerService.startForTrigger(
                context.getApplicationContext(), PostLaunchActionScheduler.Trigger.BOOT);
    }
}
