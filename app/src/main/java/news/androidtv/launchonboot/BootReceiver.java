package news.androidtv.launchonboot;

import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.media.tv.TvContract;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.felkertech.settingsmanager.SettingsManager;

/**
 * Created by Nick on 10/23/2016.
 */

public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = BootReceiver.class.getSimpleName();
    private static final boolean DEBUG = true;

    private boolean mScreenOnListener = false;
    private Context mContext;

    @Override
    public void onReceive(Context context, Intent intent) {
        mContext = context;
        if (intent.getAction() != null &&
                intent.getAction().equals(Intent.ACTION_BOOT_COMPLETED)) {
            startForegroundService();
        }
        processEvent(context, intent);
    }

    public static void processEvent(Context context, Intent intent) {
        if (DEBUG) {
            Log.d(TAG, "Received intent");
            Log.d(TAG, intent.toString());
        }
/*        if (!mScreenOnListener) {
            context.registerReceiver(this, new IntentFilter(Intent.ACTION_SCREEN_ON));
            mScreenOnListener = true;
        }*/

        final SettingsManager settingsManager = new SettingsManager(context);
        if (!settingsManager.getBoolean(SettingsManagerConstants.BOOT_APP_ENABLED)) {
            return;
        }
        if (intent.getAction() != null &&
                intent.getAction().equals(Intent.ACTION_USER_PRESENT) &&
                !settingsManager.getBoolean(SettingsManagerConstants.ON_WAKEUP)) {
            return;
        }
        if (intent.getAction() != null &&
                intent.getAction().equals(Intent.ACTION_SCREEN_ON) &&
                !settingsManager.getBoolean(SettingsManagerConstants.ON_WAKEUP)) {
            return;
        }
        if (intent.getAction() != null &&
                intent.getAction().equals(Intent.ACTION_DREAMING_STOPPED) &&
                !settingsManager.getBoolean(SettingsManagerConstants.ON_WAKEUP)) {
            return;
        }

        int delay = 0;
        try {
            String delayString = settingsManager.getString(SettingsManagerConstants.LAUNCH_DELAY);
            if (!delayString.isEmpty()) {
                delay = Integer.parseInt(delayString);
            }
        } catch (NumberFormatException ignored) {}

        if (delay > 0) {
            final Context finalContext = context;
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    launchApp(finalContext, settingsManager);
                }
            }, delay * 1000L);
        } else {
            launchApp(context, settingsManager);
        }
    }

    private static void launchApp(Context context, SettingsManager settingsManager) {
        if (settingsManager.getBoolean(SettingsManagerConstants.LAUNCH_LIVE_CHANNELS) &&
                context.getResources().getBoolean(R.bool.TIF_SUPPORT) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Intent i = new Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(i);
            } catch (ActivityNotFoundException e) {
                Log.e(TAG, "Could not start Live Channels: " + e.getMessage());
                Toast.makeText(context, R.string.null_intent, Toast.LENGTH_SHORT).show();
            }
        } else if (!settingsManager.getString(SettingsManagerConstants.LAUNCH_ACTIVITY).isEmpty()) {
            Intent i;
            if (context.getResources().getBoolean(R.bool.IS_TV)) {
                i = context.getPackageManager().getLeanbackLaunchIntentForPackage(
                        settingsManager.getString(SettingsManagerConstants.LAUNCH_ACTIVITY));
            } else {
                i = context.getPackageManager().getLaunchIntentForPackage(
                        settingsManager.getString(SettingsManagerConstants.LAUNCH_ACTIVITY));
            }

            if (i == null) {
                Toast.makeText(context, R.string.null_intent, Toast.LENGTH_SHORT).show();
                return;
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(i);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(context, R.string.null_intent, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void startForegroundService() {
        Intent i = new Intent(mContext, DreamListenerService.class);
        ContextCompat.startForegroundService(mContext, i);
    }
}