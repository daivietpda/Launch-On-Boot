package news.androidtv.launchonboot;

/**
 * Real key injector backed by an authenticated, embedded ADB client.
 */
public final class AdbKeyInjector implements KeyInjector, AutoCloseable {
    private final AdbConnectionManager connectionManager;
    private volatile AdbConnectionManager.Result lastResult =
            AdbConnectionManager.Result.failure(
                    AdbConnectionManager.Error.CONNECTION_FAILED,
                    "No ADB key has been sent");

    public AdbKeyInjector(AdbConnectionManager connectionManager) {
        if (connectionManager == null) {
            throw new IllegalArgumentException("connectionManager must not be null");
        }
        this.connectionManager = connectionManager;
    }

    /**
     * Disconnected is considered available because sendKey performs a bounded,
     * authenticated connection attempt on the executor's worker thread.
     */
    @Override
    public boolean isAvailable() {
        return connectionManager.getLastResult().getError()
                != AdbConnectionManager.Error.CLOSED;
    }

    @Override
    public boolean sendKey(int keyCode) {
        if (!AdbKeyCodeMapper.isAllowed(keyCode)) {
            lastResult = AdbConnectionManager.Result.failure(
                    AdbConnectionManager.Error.INVALID_CONFIGURATION,
                    "The requested keyCode is not allowed");
            return false;
        }
        lastResult = connectionManager.sendKey(keyCode);
        return lastResult.isSuccessful();
    }

    public AdbConnectionManager.Result getLastResult() {
        return lastResult;
    }

    public void cancel() {
        connectionManager.cancelCurrentOperation();
    }

    @Override
    public void release() {
        connectionManager.disconnect();
    }

    @Override
    public void close() {
        connectionManager.close();
    }
}
