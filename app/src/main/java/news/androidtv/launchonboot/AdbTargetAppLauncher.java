package news.androidtv.launchonboot;

import android.content.Context;
import android.util.Log;

/** Uses the existing localhost RSA ADB connection to launch one resolved component. */
final class AdbTargetAppLauncher implements TargetAppLauncher, AutoCloseable {
    private static final String TAG = AdbTargetAppLauncher.class.getSimpleName();
    private final TargetAppResolver resolver;
    private final AdbConnectionManager manager;

    AdbTargetAppLauncher(Context context) {
        this(new TargetAppResolver(context), new AdbConnectionManager(context));
    }

    AdbTargetAppLauncher(TargetAppResolver resolver, AdbConnectionManager manager) {
        this.resolver = resolver;
        this.manager = manager;
    }

    @Override public LaunchResult launch(Target target) {
        TargetAppResolver.ResolvedTarget resolved = resolver.resolve(target);
        if (resolved == null || resolved.componentName.isEmpty()) {
            return new LaunchResult(Status.FAILED, Method.ADB,
                    target == null ? "" : target.packageName, "", "Target activity cannot be resolved");
        }
        AdbConnectionManager.CommandResult command =
                manager.startResolvedActivity(resolved.componentName, resolved.category);
        if (!command.result.isSuccessful()) {
            Log.w(TAG, "ADB_LAUNCH_FAILED: " + command.result.getError());
            return new LaunchResult(Status.FAILED, Method.ADB, resolved.packageName,
                    resolved.componentName, command.result.getMessage());
        }
        if (!AdbLaunchOutputParser.isConfirmed(command.output)) {
            Log.w(TAG, "ADB_LAUNCH_FAILED: am start output was not confirmed");
            return new LaunchResult(Status.FAILED, Method.ADB, resolved.packageName,
                    resolved.componentName, "ADB launch result was not confirmed");
        }
        Log.i(TAG, "ADB_LAUNCH_CONFIRMED");
        return new LaunchResult(Status.SUCCESS_CONFIRMED, Method.ADB, resolved.packageName,
                resolved.componentName, "ADB launch confirmed");
    }

    @Override public void close() { manager.close(); }
}
