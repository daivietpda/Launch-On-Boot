package news.androidtv.launchonboot;

/**
 * Created by Nick on 10/23/2016.
 */

public class SettingsManagerConstants {
    public static final String BOOT_APP_ENABLED = "BOOT_APP_ENABLED";
    public static final String LAUNCH_LIVE_CHANNELS = "LAUNCH_LIVE_CHANNELS";
    public static final String LAUNCH_ACTIVITY = "LAUNCH_ACTIVITY";
    public static final String ON_WAKEUP = "ON_WAKEUP";
    public static final String ONBOARDING = "ONBOARDING";
    public static final String SHOW_ALL_APPS = "SHOW_ALL_APPS";
    public static final String ADVANCED_ACTIONS_ENABLED = "ADVANCED_ACTIONS_ENABLED";
    public static final String ADVANCED_ACTIONS_JSON = "ADVANCED_ACTIONS_JSON";
    /** Prevents the starter sequence from returning after a user deliberately clears it. */
    public static final String ADVANCED_ACTIONS_DEMO_INITIALIZED =
            "ADVANCED_ACTIONS_DEMO_INITIALIZED";
    public static final String ACTION_TRIGGER_BOOT = "ACTION_TRIGGER_BOOT";
    public static final String ACTION_TRIGGER_WAKE = "ACTION_TRIGGER_WAKE";
    /**
     * When enabled, a wake trigger stops the selected package through the
     * localhost ADB connection before it is launched again.
     */
    public static final String RESTART_TARGET_ON_WAKE = "RESTART_TARGET_ON_WAKE";
    public static final String TARGET_APP_LAUNCH_METHOD = "TARGET_APP_LAUNCH_METHOD";
    public static final String APP_LAUNCH_DELAY_MS = "APP_LAUNCH_DELAY_MS";
    public static final String POST_LAUNCH_DELAY_MS = "POST_LAUNCH_DELAY_MS";
    public static final String DEFAULT_ACTION_DELAY_MS = "DEFAULT_ACTION_DELAY_MS";
    public static final String KEY_INJECTION_METHOD = "KEY_INJECTION_METHOD";
    public static final String ADB_HOST = "ADB_HOST";
    public static final String ADB_PORT = "ADB_PORT";
    public static final String ADB_RETRY_COUNT = "ADB_RETRY_COUNT";
    public static final String ADB_RETRY_DELAY_MS = "ADB_RETRY_DELAY_MS";
    public static final String ACTION_DEBOUNCE_MS = "ACTION_DEBOUNCE_MS";
    public static final String LAST_ACTION_TRIGGER = "LAST_ACTION_TRIGGER";
    public static final String LAST_ACTION_ELAPSED_MS = "LAST_ACTION_ELAPSED_MS";
}
