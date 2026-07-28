package news.androidtv.launchonboot;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.Log;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Coordinates target launch and optional post-launch actions without retaining
 * an Activity or blocking a BroadcastReceiver/main thread.
 */
public final class PostLaunchActionScheduler {
    private static final String TAG = PostLaunchActionScheduler.class.getSimpleName();
    private static volatile PostLaunchActionScheduler instance;

    public enum Trigger {
        BOOT,
        SCREEN_ON,
        DREAMING_STOPPED
    }

    public enum ScheduleResult {
        SCHEDULED_ADVANCED,
        SCHEDULED_LAUNCH_ONLY,
        SKIPPED_DISABLED,
        SKIPPED_TRIGGER_DISABLED,
        SKIPPED_NO_TARGET,
        SKIPPED_EMPTY_SEQUENCE,
        SKIPPED_UNSUPPORTED_INJECTOR,
        SKIPPED_ALREADY_RUNNING,
        SKIPPED_DEBOUNCE,
        INVALID_CONFIGURATION
    }

    public interface CompletionListener {
        void onFinished(boolean successful);
    }

    private final Object lock = new Object();
    private final Context context;
    private final SharedPreferences preferences;
    private final TargetAppLaunchCoordinator targetAppLaunchCoordinator;
    private final ScheduledExecutorService scheduler;
    private final ActionTriggerGate triggerGate;

    private long generation;
    private ScheduledFuture<?> pendingTask;
    private ActionSequenceExecutor actionExecutor;
    private AdbKeyInjector adbKeyInjector;
    private CompletionListener completionListener;

    public static PostLaunchActionScheduler getInstance(Context context) {
        if (instance == null) {
            synchronized (PostLaunchActionScheduler.class) {
                if (instance == null) {
                    instance = new PostLaunchActionScheduler(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private PostLaunchActionScheduler(Context context) {
        this.context = context;
        preferences = PreferenceManager.getDefaultSharedPreferences(context);
        targetAppLaunchCoordinator = new TargetAppLaunchCoordinator(context);
        scheduler = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "PostLaunchActionScheduler");
                thread.setDaemon(true);
                return thread;
            }
        });
        triggerGate = new ActionTriggerGate(
                new ActionTriggerGate.Clock() {
                    @Override
                    public long elapsedRealtime() {
                        return SystemClock.elapsedRealtime();
                    }
                },
                new PreferenceGateStore(preferences));
    }

    public ScheduleResult schedule(Trigger trigger, CompletionListener listener) {
        if (trigger == null) {
            throw new IllegalArgumentException("trigger must not be null");
        }

        final Plan plan;
        try {
            plan = readPlan(trigger);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Invalid post-launch configuration: " + e.getMessage());
            return ScheduleResult.INVALID_CONFIGURATION;
        }
        if (!plan.legacyEnabled && !plan.advancedEnabled) {
            return ScheduleResult.SKIPPED_DISABLED;
        }
        if (!plan.triggerEnabled) {
            return ScheduleResult.SKIPPED_TRIGGER_DISABLED;
        }
        if (plan.target == null) {
            return ScheduleResult.SKIPPED_NO_TARGET;
        }
        if (plan.advancedEnabled && plan.actions.isEmpty()) {
            return ScheduleResult.SKIPPED_EMPTY_SEQUENCE;
        }
        if (plan.advancedEnabled && !"ADB".equalsIgnoreCase(plan.injectionMethod)) {
            return ScheduleResult.SKIPPED_UNSUPPORTED_INJECTOR;
        }

        ActionTriggerGate.Decision decision =
                triggerGate.tryAcquire(trigger.name(), plan.debounceMs);
        if (decision == ActionTriggerGate.Decision.ALREADY_RUNNING) {
            return ScheduleResult.SKIPPED_ALREADY_RUNNING;
        }
        if (decision == ActionTriggerGate.Decision.DEBOUNCED) {
            return ScheduleResult.SKIPPED_DEBOUNCE;
        }

        final long runGeneration;
        synchronized (lock) {
            generation++;
            runGeneration = generation;
            completionListener = listener;
            pendingTask = scheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    restartTargetIfNeededThenLaunch(runGeneration, plan);
                }
            }, plan.appLaunchDelayMs, TimeUnit.MILLISECONDS);
        }
        Log.i(TAG, "Scheduled " + trigger + " post-launch flow");
        return plan.advancedEnabled
                ? ScheduleResult.SCHEDULED_ADVANCED
                : ScheduleResult.SCHEDULED_LAUNCH_ONLY;
    }

    /**
     * A resumed media app can retain playback and focus after sleep.  On an
     * opted-in wake flow, stop its selected package first, wait briefly for the
     * task to disappear, then launch it as a fresh task.  Failure is terminal:
     * actions must never be sent to the old resumed session.
     */
    private void restartTargetIfNeededThenLaunch(final long runGeneration, final Plan plan) {
        if (!plan.restartTargetOnWake) {
            launchTarget(runGeneration, plan);
            return;
        }
        if (!isCurrent(runGeneration) || !isStillEnabled(plan)) {
            finish(runGeneration, false);
            return;
        }

        AdbConnectionManager manager = null;
        try {
            manager = new AdbConnectionManager(context);
            AdbConnectionManager.Result result = manager.forceStopPackage(plan.target.packageName);
            if (!result.isSuccessful()) {
                Log.w(TAG, "Could not restart the configured target before wake actions: "
                        + result.getError());
                finish(runGeneration, false);
                return;
            }
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Invalid ADB or target configuration for wake restart");
            finish(runGeneration, false);
            return;
        } finally {
            if (manager != null) {
                manager.close();
            }
        }

        if (!isCurrent(runGeneration) || !isStillEnabled(plan)) {
            finish(runGeneration, false);
            return;
        }
        synchronized (lock) {
            if (generation != runGeneration) {
                return;
            }
            pendingTask = scheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    launchTarget(runGeneration, plan);
                }
            }, ActionSequenceStore.DEFAULT_WAKE_TARGET_RESTART_DELAY_MS,
                    TimeUnit.MILLISECONDS);
        }
    }

    private void launchTarget(long runGeneration, Plan plan) {
        if (!isCurrent(runGeneration) || !isStillEnabled(plan)) {
            finish(runGeneration, false);
            return;
        }
        TargetAppLauncher.LaunchResult launchResult = targetAppLaunchCoordinator.launch(plan.target,
                plan.launchMethod, plan.advancedEnabled && "ADB".equalsIgnoreCase(plan.injectionMethod)
                        || plan.restartTargetOnWake);
        if (!launchResult.isConfirmed()) {
            Log.w(TAG, "Target launch was not confirmed: " + launchResult.status + " "
                    + launchResult.message);
            TargetLaunchNotification.show(context, plan.target);
            finish(runGeneration, false);
            return;
        }
        if (!plan.advancedEnabled) {
            finish(runGeneration, true);
            return;
        }
        synchronized (lock) {
            if (generation != runGeneration) {
                return;
            }
            pendingTask = scheduler.schedule(new Runnable() {
                @Override
                public void run() {
                    connectAndExecute(runGeneration, plan);
                }
            }, plan.postLaunchDelayMs, TimeUnit.MILLISECONDS);
        }
    }

    private void connectAndExecute(final long runGeneration, final Plan plan) {
        if (!isCurrent(runGeneration) || !isStillEnabled(plan)) {
            finish(runGeneration, false);
            return;
        }

        boolean hasKey = false;
        for (ActionItem action : plan.actions) {
            if (action.getType() == ActionItem.Type.KEY) {
                hasKey = true;
                break;
            }
        }

        KeyInjector injector;
        if (hasKey) {
            try {
                AdbConnectionManager manager = new AdbConnectionManager(context);
                AdbConnectionManager.Result connection = manager.testConnection(null);
                if (!connection.isSuccessful()) {
                    manager.close();
                    Log.w(TAG, "ADB was unavailable: " + connection.getError());
                    finish(runGeneration, false);
                    return;
                }
                injector = new AdbKeyInjector(manager);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Invalid ADB configuration");
                finish(runGeneration, false);
                return;
            }
        } else {
            injector = new FakeKeyInjector();
        }

        final AdbKeyInjector ownedAdbInjector =
                injector instanceof AdbKeyInjector ? (AdbKeyInjector) injector : null;
        ActionSequenceExecutor executor =
                new ActionSequenceExecutor(injector, plan.defaultActionDelayMs);
        synchronized (lock) {
            if (generation != runGeneration) {
                executor.close();
                if (ownedAdbInjector != null) {
                    ownedAdbInjector.close();
                }
                return;
            }
            actionExecutor = executor;
            adbKeyInjector = ownedAdbInjector;
        }
        boolean started = executor.start(plan.actions, new ActionSequenceExecutor.Listener() {
            @Override
            public void onStateChanged(ActionSequenceExecutor.State state) {
            }

            @Override
            public void onActionStarted(int actionIndex, ActionItem action) {
                if (!isStillEnabled(plan)) {
                    cancel();
                }
            }

            @Override
            public void onKeySendRequested(int actionIndex, int keyCode, int repeatIndex) {
            }

            @Override
            public void onFinished(ActionSequenceExecutor.Result result) {
                finish(runGeneration,
                        result == ActionSequenceExecutor.Result.COMPLETED);
            }
        });
        if (!started) {
            finish(runGeneration, false);
        }
    }

    public void cancel() {
        CompletionListener listener;
        synchronized (lock) {
            generation++;
            if (pendingTask != null) {
                pendingTask.cancel(true);
                pendingTask = null;
            }
            if (actionExecutor != null) {
                actionExecutor.cancel();
                actionExecutor.close();
                actionExecutor = null;
            }
            if (adbKeyInjector != null) {
                adbKeyInjector.cancel();
                adbKeyInjector.close();
                adbKeyInjector = null;
            }
            listener = completionListener;
            completionListener = null;
        }
        triggerGate.cancel();
        if (listener != null) {
            listener.onFinished(false);
        }
    }

    public boolean isRunning() {
        return triggerGate.isRunning();
    }

    private void finish(long runGeneration, boolean successful) {
        CompletionListener listener;
        ActionSequenceExecutor executorToClose;
        AdbKeyInjector injectorToClose;
        synchronized (lock) {
            if (generation != runGeneration) {
                return;
            }
            pendingTask = null;
            executorToClose = actionExecutor;
            injectorToClose = adbKeyInjector;
            actionExecutor = null;
            adbKeyInjector = null;
            listener = completionListener;
            completionListener = null;
        }
        triggerGate.release();
        if (executorToClose != null) {
            executorToClose.close();
        }
        if (injectorToClose != null) {
            injectorToClose.close();
        }
        if (listener != null) {
            listener.onFinished(successful);
        }
    }

    private boolean isCurrent(long runGeneration) {
        synchronized (lock) {
            return generation == runGeneration;
        }
    }

    private boolean isStillEnabled(Plan plan) {
        if (!readBoolean(SettingsManagerConstants.BOOT_APP_ENABLED, false)) {
            return false;
        }
        if (plan.advancedEnabled
                && !readBoolean(SettingsManagerConstants.ADVANCED_ACTIONS_ENABLED, false)) {
            return false;
        }
        return plan.trigger == Trigger.BOOT
                ? (!plan.advancedEnabled
                || readBoolean(SettingsManagerConstants.ACTION_TRIGGER_BOOT, true))
                : (!plan.advancedEnabled
                ? readBoolean(SettingsManagerConstants.ON_WAKEUP, false)
                : readBoolean(SettingsManagerConstants.ACTION_TRIGGER_WAKE, true));
    }

    private Plan readPlan(Trigger trigger) {
        boolean legacyEnabled = readBoolean(SettingsManagerConstants.BOOT_APP_ENABLED, false);
        boolean advancedConfigured = legacyEnabled
                && readBoolean(SettingsManagerConstants.ADVANCED_ACTIONS_ENABLED, false);
        boolean advancedEnabled = advancedConfigured && (trigger == Trigger.BOOT
                ? readBoolean(SettingsManagerConstants.ACTION_TRIGGER_BOOT, true)
                : readBoolean(SettingsManagerConstants.ACTION_TRIGGER_WAKE, true));
        boolean legacyTriggerEnabled = trigger == Trigger.BOOT
                || readBoolean(SettingsManagerConstants.ON_WAKEUP, false);
        boolean triggerEnabled = legacyTriggerEnabled || advancedEnabled;

        boolean liveChannels =
                readBoolean(SettingsManagerConstants.LAUNCH_LIVE_CHANNELS, false);
        String packageName = readString(SettingsManagerConstants.LAUNCH_ACTIVITY, "");
        TargetAppLauncher.Target target =
                liveChannels || !packageName.trim().isEmpty()
                        ? new TargetAppLauncher.Target(liveChannels, packageName) : null;
        boolean restartTargetOnWake = trigger != Trigger.BOOT
                && advancedEnabled
                && readBoolean(SettingsManagerConstants.RESTART_TARGET_ON_WAKE,
                ActionSequenceStore.DEFAULT_RESTART_TARGET_ON_WAKE);
        if (restartTargetOnWake && (target == null
                || !AdbConnectionManager.isValidPackageName(target.packageName))) {
            throw new IllegalArgumentException(
                    "Restart after wake requires a selected application package");
        }
        List<ActionItem> actions = advancedEnabled
                ? new ActionSequenceStore(context).getActionSequence()
                : java.util.Collections.<ActionItem>emptyList();
        long launchDelay = requireValidDelay(
                readLong(SettingsManagerConstants.APP_LAUNCH_DELAY_MS,
                        ActionSequenceStore.DEFAULT_APP_LAUNCH_DELAY_MS),
                "APP_LAUNCH_DELAY_MS");
        long postDelay = requireValidDelay(
                readLong(SettingsManagerConstants.POST_LAUNCH_DELAY_MS,
                        ActionSequenceStore.DEFAULT_POST_LAUNCH_DELAY_MS),
                "POST_LAUNCH_DELAY_MS");
        long defaultDelay = requireValidDelay(
                readLong(SettingsManagerConstants.DEFAULT_ACTION_DELAY_MS,
                        ActionSequenceStore.DEFAULT_ACTION_DELAY_MS),
                "DEFAULT_ACTION_DELAY_MS");
        long debounce = requireValidDelay(
                readLong(SettingsManagerConstants.ACTION_DEBOUNCE_MS,
                        ActionSequenceStore.DEFAULT_ACTION_DEBOUNCE_MS),
                "ACTION_DEBOUNCE_MS");
        return new Plan(trigger, legacyEnabled, advancedEnabled, triggerEnabled, target,
                actions, launchDelay, postDelay, defaultDelay, debounce,
                restartTargetOnWake,
                parseLaunchMethod(readString(SettingsManagerConstants.TARGET_APP_LAUNCH_METHOD,
                        ActionSequenceStore.DEFAULT_TARGET_APP_LAUNCH_METHOD)),
                readString(SettingsManagerConstants.KEY_INJECTION_METHOD,
                        ActionSequenceStore.DEFAULT_KEY_INJECTION_METHOD));
    }

    private boolean readBoolean(String key, boolean defaultValue) {
        try {
            return preferences.getBoolean(key, defaultValue);
        } catch (ClassCastException e) {
            return Boolean.parseBoolean(String.valueOf(preferences.getAll().get(key)));
        }
    }

    private long readLong(String key, long defaultValue) {
        try {
            return preferences.getLong(key, defaultValue);
        } catch (ClassCastException e) {
            Object value = preferences.getAll().get(key);
            try {
                return value == null ? defaultValue : Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException e2) {
                return defaultValue;
            }
        }
    }

    private String readString(String key, String defaultValue) {
        try {
            String value = preferences.getString(key, defaultValue);
            return value == null ? defaultValue : value;
        } catch (ClassCastException e) {
            Object value = preferences.getAll().get(key);
            return value == null ? defaultValue : String.valueOf(value);
        }
    }

    private static long requireValidDelay(long value, String name) {
        ActionItem.validateActionTime(value, name);
        return value;
    }

    private static TargetAppLauncher.Method parseLaunchMethod(String value) {
        try {
            return TargetAppLauncher.Method.valueOf(value.trim().toUpperCase(java.util.Locale.US));
        } catch (IllegalArgumentException | NullPointerException e) {
            return TargetAppLauncher.Method.AUTO;
        }
    }

    private static final class Plan {
        final Trigger trigger;
        final boolean legacyEnabled;
        final boolean advancedEnabled;
        final boolean triggerEnabled;
        final TargetAppLauncher.Target target;
        final List<ActionItem> actions;
        final long appLaunchDelayMs;
        final long postLaunchDelayMs;
        final long defaultActionDelayMs;
        final long debounceMs;
        final boolean restartTargetOnWake;
        final TargetAppLauncher.Method launchMethod;
        final String injectionMethod;

        Plan(Trigger trigger, boolean legacyEnabled, boolean advancedEnabled,
             boolean triggerEnabled, TargetAppLauncher.Target target,
             List<ActionItem> actions, long appLaunchDelayMs, long postLaunchDelayMs,
             long defaultActionDelayMs, long debounceMs, boolean restartTargetOnWake,
             TargetAppLauncher.Method launchMethod,
             String injectionMethod) {
            this.trigger = trigger;
            this.legacyEnabled = legacyEnabled;
            this.advancedEnabled = advancedEnabled;
            this.triggerEnabled = triggerEnabled;
            this.target = target;
            this.actions = actions;
            this.appLaunchDelayMs = appLaunchDelayMs;
            this.postLaunchDelayMs = postLaunchDelayMs;
            this.defaultActionDelayMs = defaultActionDelayMs;
            this.debounceMs = debounceMs;
            this.restartTargetOnWake = restartTargetOnWake;
            this.launchMethod = launchMethod;
            this.injectionMethod = injectionMethod;
        }
    }

    private static final class PreferenceGateStore implements ActionTriggerGate.Store {
        private final SharedPreferences preferences;

        PreferenceGateStore(SharedPreferences preferences) {
            this.preferences = preferences;
        }

        @Override
        public long getLastElapsedRealtime() {
            try {
                return preferences.getLong(
                        SettingsManagerConstants.LAST_ACTION_ELAPSED_MS, -1L);
            } catch (ClassCastException e) {
                return -1L;
            }
        }

        @Override
        public String getLastTrigger() {
            try {
                String trigger = preferences.getString(
                        SettingsManagerConstants.LAST_ACTION_TRIGGER, "");
                return trigger == null ? "" : trigger;
            } catch (ClassCastException e) {
                return "";
            }
        }

        @Override
        public void save(long elapsedRealtime, String trigger) {
            preferences.edit()
                    .putLong(SettingsManagerConstants.LAST_ACTION_ELAPSED_MS, elapsedRealtime)
                    .putString(SettingsManagerConstants.LAST_ACTION_TRIGGER, trigger)
                    .apply();
        }
    }
}
