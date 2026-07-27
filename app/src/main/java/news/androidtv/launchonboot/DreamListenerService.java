package news.androidtv.launchonboot;

import static android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

/**
 * Foreground wake listener. It exists only while wake launching is enabled, or
 * temporarily while a boot-triggered flow is still running.
 */
public final class DreamListenerService extends Service {
    private static final String TAG = DreamListenerService.class.getSimpleName();
    private static final int ONGOING_NOTIFICATION_ID = 1;
    private static final String NOTIFICATION_CHANNEL_ID = "launch_on_wake";
    private static final String EXTRA_TRIGGER = "trigger";

    private PostLaunchActionScheduler scheduler;
    private boolean receiverRegistered;

    private final BroadcastReceiver wakeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                scheduler.cancel();
                return;
            }
            if (Intent.ACTION_SCREEN_ON.equals(action)) {
                handleTrigger(PostLaunchActionScheduler.Trigger.SCREEN_ON, 0);
            } else if (Intent.ACTION_DREAMING_STOPPED.equals(action)) {
                handleTrigger(PostLaunchActionScheduler.Trigger.DREAMING_STOPPED, 0);
            }
        }
    };

    public static void startForTrigger(Context context,
                                       PostLaunchActionScheduler.Trigger trigger) {
        Intent serviceIntent = new Intent(context, DreamListenerService.class);
        serviceIntent.putExtra(EXTRA_TRIGGER, trigger.name());
        startServiceCompat(context, serviceIntent);
    }

    /** Starts/stops wake monitoring after the user changes relevant settings. */
    public static void updateRunningState(Context context) {
        Context applicationContext = context.getApplicationContext();
        if (isWakeMonitoringRequired(applicationContext)) {
            startServiceCompat(applicationContext,
                    new Intent(applicationContext, DreamListenerService.class));
        } else {
            PostLaunchActionScheduler.getInstance(applicationContext).cancel();
            applicationContext.stopService(
                    new Intent(applicationContext, DreamListenerService.class));
        }
    }

    private static void startServiceCompat(Context context, Intent intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (IllegalStateException | SecurityException e) {
            Log.w(TAG, "Unable to start wake/action service", e);
        }
    }

    static boolean isWakeMonitoringRequired(Context context) {
        SettingsManager settings = new SettingsManager(context);
        if (!settings.getBoolean(SettingsManagerConstants.BOOT_APP_ENABLED)) {
            return false;
        }
        return settings.getBoolean(SettingsManagerConstants.ON_WAKEUP)
                || (settings.getBoolean(SettingsManagerConstants.ADVANCED_ACTIONS_ENABLED)
                && settings.getBoolean(SettingsManagerConstants.ACTION_TRIGGER_WAKE, true));
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        scheduler = PostLaunchActionScheduler.getInstance(getApplicationContext());
        startForegroundSafely();
        registerWakeReceiver();
    }

    private void startForegroundSafely() {
        NotificationManager notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID, getString(R.string.app_name),
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.notification_text));
            channel.setSound(null, null);
            notificationManager.createNotificationChannel(channel);
        }

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                : new Notification.Builder(this);
        Notification notification = builder
                .setContentTitle(getText(R.string.app_name))
                .setContentText(getText(R.string.notification_text))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setLargeIcon(BitmapFactory.decodeResource(getResources(), R.drawable.banner))
                .setContentIntent(pendingIntent)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setPriority(Notification.PRIORITY_MIN)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ONGOING_NOTIFICATION_ID, notification,
                    FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(ONGOING_NOTIFICATION_ID, notification);
        }
    }

    private void registerWakeReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_DREAMING_STOPPED);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wakeReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(wakeReceiver, filter);
        }
        receiverRegistered = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String triggerName = intent.getStringExtra(EXTRA_TRIGGER);
            if (triggerName != null) {
                try {
                    handleTrigger(PostLaunchActionScheduler.Trigger.valueOf(triggerName),
                            startId);
                } catch (IllegalArgumentException e) {
                    Log.w(TAG, "Ignoring invalid trigger");
                }
            }
        }
        if (!isWakeMonitoringRequired(this) && !scheduler.isRunning()) {
            stopSelfResult(startId);
        }
        return isWakeMonitoringRequired(this) ? START_STICKY : START_NOT_STICKY;
    }

    private void handleTrigger(PostLaunchActionScheduler.Trigger trigger, final int startId) {
        PostLaunchActionScheduler.ScheduleResult result = scheduler.schedule(trigger,
                new PostLaunchActionScheduler.CompletionListener() {
                    @Override
                    public void onFinished(boolean successful) {
                        if (!isWakeMonitoringRequired(DreamListenerService.this)) {
                            if (startId > 0) {
                                stopSelfResult(startId);
                            } else {
                                stopSelf();
                            }
                        }
                    }
                });
        Log.i(TAG, "Trigger " + trigger + " result: " + result);
        if (result != PostLaunchActionScheduler.ScheduleResult.SCHEDULED_ADVANCED
                && result != PostLaunchActionScheduler.ScheduleResult.SCHEDULED_LAUNCH_ONLY
                && !isWakeMonitoringRequired(this)) {
            if (startId > 0) {
                stopSelfResult(startId);
            } else {
                stopSelf();
            }
        }
    }

    @Override
    public void onDestroy() {
        scheduler.cancel();
        if (receiverRegistered) {
            unregisterReceiver(wakeReceiver);
            receiverRegistered = false;
        }
        stopForeground(true);
        super.onDestroy();
    }
}
