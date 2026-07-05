package news.androidtv.launchonboot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.Nullable;
import android.util.Log;

/**
 * Created by Nick on 5/11/2017.
 *
 * A foreground service that listens for Screensaver events and responds.
 */
public class DreamListenerService extends Service {
    private static final String TAG = DreamListenerService.class.getSimpleName();

    private static final int ONGOING_NOTIFICATION_ID = 1;
    private static final String CHANNEL_ID = "dream_listener_channel";

    private BroadcastReceiver dreamHandler = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Redirect intent.
            Log.d(TAG, "Received service event: " + intent.getAction());
            BootReceiver.processEvent(context, intent);
        }
    };

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // Create a foreground service.
        createNotificationChannel();
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }

        Notification notification = builder
                .setContentTitle(getText(R.string.app_name))
                .setContentText(getText(R.string.notification_text))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setLargeIcon(BitmapFactory.decodeResource(getResources(), R.drawable.banner))
                .setContentIntent(pendingIntent)
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
                .setPriority(Notification.PRIORITY_MIN)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ONGOING_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(ONGOING_NOTIFICATION_ID, notification);
        }
        Log.d(TAG, "Deploy notification");

        // Register listeners.
        IntentFilter filter = new IntentFilter(Intent.ACTION_DREAMING_STOPPED);
        registerReceiver(dreamHandler, filter);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = getString(R.string.app_name);
            String description = getString(R.string.notification_text);
            int importance = NotificationManager.IMPORTANCE_LOW;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Unregister listener.
        unregisterReceiver(dreamHandler);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }
}
