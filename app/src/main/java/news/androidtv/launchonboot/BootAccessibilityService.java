package news.androidtv.launchonboot;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

public class BootAccessibilityService extends AccessibilityService {
    private static final String TAG = BootAccessibilityService.class.getSimpleName();

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // Không cần xử lý các sự kiện cụ thể ở đây cho mục đích khởi động
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "Accessibility Service Connected");

        // Cấu hình lại service để giảm thiểu can thiệp vào hệ thống (đặc biệt là trên Android TV)
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            // Thiết lập eventTypes thành 0 để không nhận bất kỳ sự kiện nào, 
            // tránh việc Launcher hiểu lầm là service muốn can thiệp vào điều hướng.
            info.eventTypes = 0;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_VISUAL;
            info.notificationTimeout = 0;
            setServiceInfo(info);
        }

        // Chỉ kích hoạt logic khởi động nếu thiết bị vừa mới khởi động (trong vòng 5 phút)
        // Điều này giúp tránh việc tự động mở ứng dụng khi người dùng vừa mới bật dịch vụ trong Cài đặt.
        if (SystemClock.elapsedRealtime() < 5 * 60 * 1000) {
            BootReceiver.processEvent(this, new Intent(Intent.ACTION_BOOT_COMPLETED));
        }
    }
}
