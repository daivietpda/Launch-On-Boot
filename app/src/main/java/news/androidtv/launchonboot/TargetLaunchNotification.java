package news.androidtv.launchonboot;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** User-initiated fallback when Android policy or unavailable ADB prevents auto launch. */
final class TargetLaunchNotification {
    private static final String CHANNEL = "target_launch_fallback";
    private static final int ID = 42;
    private TargetLaunchNotification() { }

    static void show(Context context, TargetAppLauncher.Target target) {
        TargetAppResolver.ResolvedTarget resolved = new TargetAppResolver(context).resolve(target);
        if (resolved == null) return;
        Intent launchIntent = new Intent(resolved.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(context, ID, launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationManager manager = (NotificationManager) context.getSystemService(
                Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL,
                    context.getString(R.string.target_launch_notification_channel),
                    NotificationManager.IMPORTANCE_DEFAULT));
        }
        android.app.Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new android.app.Notification.Builder(context, CHANNEL)
                : new android.app.Notification.Builder(context);
        manager.notify(ID, builder.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(context.getString(R.string.target_launch_notification_title))
                .setContentText(context.getString(R.string.target_launch_notification_text))
                .setContentIntent(pendingIntent).setAutoCancel(true).build());
    }
}
