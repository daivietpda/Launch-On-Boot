package news.androidtv.launchonboot;

/** A launch backend reports whether opening the target was actually confirmed. */
interface TargetAppLauncher {
    enum Method { AUTO, ANDROID, ADB }
    enum Status { SUCCESS_CONFIRMED, REQUESTED_UNVERIFIED, FAILED }

    final class Target {
        final boolean liveChannels;
        final String packageName;

        Target(boolean liveChannels, String packageName) {
            this.liveChannels = liveChannels;
            this.packageName = packageName == null ? "" : packageName.trim();
        }
    }

    final class LaunchResult {
        final Status status;
        final Method method;
        final String packageName;
        final String componentName;
        final String message;

        LaunchResult(Status status, Method method, String packageName,
                     String componentName, String message) {
            this.status = status;
            this.method = method;
            this.packageName = packageName == null ? "" : packageName;
            this.componentName = componentName == null ? "" : componentName;
            this.message = message == null ? "" : message;
        }

        boolean isConfirmed() { return status == Status.SUCCESS_CONFIRMED; }
    }

    LaunchResult launch(Target target);
}
