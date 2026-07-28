package news.androidtv.launchonboot;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import com.tananaev.adblib.AdbConnection;
import com.tananaev.adblib.AdbCrypto;
import com.tananaev.adblib.AdbStream;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Opens an authenticated ADB connection only when it is needed and owns every
 * socket/stream created for that operation.
 */
public final class AdbConnectionManager implements AutoCloseable {
    static final String KEY_COMMAND_RESULT_PREFIX = "__LOB_KEY_EXIT__";
    static final String TEXT_COMMAND_RESULT_PREFIX = "__LOB_TEXT_EXIT__";
    static final String FORCE_STOP_COMMAND_RESULT_PREFIX = "__LOB_FORCE_STOP_EXIT__";
    static final String START_ACTIVITY_COMMAND_RESULT_PREFIX = "__LOB_START_EXIT__";
    private static final String SHELL_PROBE_MARKER = "__LOB_SHELL_OK__";
    private static final int MAX_SHELL_OUTPUT_BYTES = 4096;

    public static final long DEFAULT_CONNECTION_TIMEOUT_MS = 15_000L;
    public static final long DEFAULT_COMMAND_TIMEOUT_MS = 5_000L;
    /** `am start -W` waits for a slow TV activity to report launch completion. */
    public static final long DEFAULT_TARGET_LAUNCH_TIMEOUT_MS = 30_000L;
    public static final int MAX_RETRY_COUNT = 10;
    public static final long MAX_RETRY_DELAY_MS = 60_000L;
    public static final long MAX_TIMEOUT_MS = 120_000L;

    public enum State {
        DISCONNECTED,
        CONNECTING,
        AUTHORIZING,
        CONNECTED,
        FAILED
    }

    public enum Error {
        NONE,
        INVALID_CONFIGURATION,
        CONNECTION_TIMEOUT,
        AUTHORIZATION_TIMEOUT,
        CONNECTION_FAILED,
        COMMAND_TIMEOUT,
        SHELL_UNAVAILABLE,
        COMMAND_FAILED,
        CANCELLED,
        CLOSED
    }

    public interface StateListener {
        void onStateChanged(State state, int attempt, int maximumAttempts);
    }

    public static final class Result {
        private final boolean successful;
        private final Error error;
        private final String message;

        private Result(boolean successful, Error error, String message) {
            this.successful = successful;
            this.error = error;
            this.message = message;
        }

        public static Result success() {
            return new Result(true, Error.NONE, "");
        }

        public static Result failure(Error error, String message) {
            if (error == null || error == Error.NONE) {
                throw new IllegalArgumentException("A failure requires an error");
            }
            return new Result(false, error, message == null ? "" : message);
        }

        public boolean isSuccessful() {
            return successful;
        }

        public Error getError() {
            return error;
        }

        public String getMessage() {
            return message;
        }
    }

    /** Validated, immutable ADB connection settings. */
    public static final class Config {
        private final String host;
        private final int port;
        private final int retryCount;
        private final long retryDelayMs;
        private final long connectionTimeoutMs;
        private final long commandTimeoutMs;
        private final boolean remoteHostAllowed;

        public Config(String host, int port, int retryCount, long retryDelayMs,
                      long connectionTimeoutMs, long commandTimeoutMs) {
            this(host, port, retryCount, retryDelayMs, connectionTimeoutMs, commandTimeoutMs,
                    false);
        }

        /**
         * Enabling a non-loopback host must be an explicit UI decision accompanied
         * by a security warning. Preference-based configuration never enables it.
         */
        public Config(String host, int port, int retryCount, long retryDelayMs,
                      long connectionTimeoutMs, long commandTimeoutMs,
                      boolean remoteHostAllowed) {
            String normalizedHost = normalizeHost(host);
            if (!remoteHostAllowed && !isLoopbackHost(normalizedHost)) {
                throw new IllegalArgumentException("Only localhost ADB is allowed");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("ADB port must be between 1 and 65535");
            }
            if (retryCount < 0) {
                throw new IllegalArgumentException("retryCount must not be negative");
            }
            if (retryCount > MAX_RETRY_COUNT) {
                throw new IllegalArgumentException(
                        "retryCount must not exceed " + MAX_RETRY_COUNT);
            }
            if (retryDelayMs < 0) {
                throw new IllegalArgumentException("retryDelayMs must not be negative");
            }
            if (retryDelayMs > MAX_RETRY_DELAY_MS) {
                throw new IllegalArgumentException(
                        "retryDelayMs must not exceed " + MAX_RETRY_DELAY_MS + " ms");
            }
            if (connectionTimeoutMs < 1 || commandTimeoutMs < 1) {
                throw new IllegalArgumentException("ADB timeouts must be positive");
            }
            if (connectionTimeoutMs > MAX_TIMEOUT_MS || commandTimeoutMs > MAX_TIMEOUT_MS) {
                throw new IllegalArgumentException(
                        "ADB timeouts must not exceed " + MAX_TIMEOUT_MS + " ms");
            }
            this.host = normalizedHost;
            this.port = port;
            this.retryCount = retryCount;
            this.retryDelayMs = retryDelayMs;
            this.connectionTimeoutMs = connectionTimeoutMs;
            this.commandTimeoutMs = commandTimeoutMs;
            this.remoteHostAllowed = remoteHostAllowed;
        }

        public static Config fromPreferences(Context context) {
            SharedPreferences preferences =
                    PreferenceManager.getDefaultSharedPreferences(context);
            String host = getStringPreference(preferences, SettingsManagerConstants.ADB_HOST,
                    ActionSequenceStore.DEFAULT_ADB_HOST);
            int port = getIntPreference(preferences, SettingsManagerConstants.ADB_PORT,
                    ActionSequenceStore.DEFAULT_ADB_PORT);
            int retryCount = getIntPreference(preferences,
                    SettingsManagerConstants.ADB_RETRY_COUNT,
                    ActionSequenceStore.DEFAULT_ADB_RETRY_COUNT);
            long retryDelayMs = getLongPreference(preferences,
                    SettingsManagerConstants.ADB_RETRY_DELAY_MS,
                    ActionSequenceStore.DEFAULT_ADB_RETRY_DELAY_MS);
            return new Config(host, port, retryCount, retryDelayMs,
                    DEFAULT_CONNECTION_TIMEOUT_MS, DEFAULT_COMMAND_TIMEOUT_MS);
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }

        public int getRetryCount() {
            return retryCount;
        }

        public long getRetryDelayMs() {
            return retryDelayMs;
        }

        public long getConnectionTimeoutMs() {
            return connectionTimeoutMs;
        }

        public long getCommandTimeoutMs() {
            return commandTimeoutMs;
        }

        public boolean isRemoteHostAllowed() {
            return remoteHostAllowed;
        }

        public boolean isRemoteHost() {
            return !isLoopbackHost(host);
        }

        private static String normalizeHost(String host) {
            if (host == null || host.trim().isEmpty()) {
                throw new IllegalArgumentException("ADB host must not be empty");
            }
            return host.trim().toLowerCase(Locale.US);
        }

        static boolean isLoopbackHost(String host) {
            return "127.0.0.1".equals(host)
                    || "localhost".equals(host)
                    || "::1".equals(host)
                    || "[::1]".equals(host);
        }

        private static String getStringPreference(SharedPreferences preferences, String key,
                                                  String defaultValue) {
            try {
                return preferences.getString(key, defaultValue);
            } catch (ClassCastException e) {
                Object value = preferences.getAll().get(key);
                return value == null ? defaultValue : String.valueOf(value);
            }
        }

        private static int getIntPreference(SharedPreferences preferences, String key,
                                            int defaultValue) {
            try {
                return preferences.getInt(key, defaultValue);
            } catch (ClassCastException e) {
                Object value = preferences.getAll().get(key);
                try {
                    return value == null ? defaultValue : Integer.parseInt(String.valueOf(value));
                } catch (NumberFormatException ignored) {
                    return defaultValue;
                }
            }
        }

        private static long getLongPreference(SharedPreferences preferences, String key,
                                              long defaultValue) {
            try {
                return preferences.getLong(key, defaultValue);
            } catch (ClassCastException e) {
                Object value = preferences.getAll().get(key);
                try {
                    return value == null ? defaultValue : Long.parseLong(String.valueOf(value));
                } catch (NumberFormatException ignored) {
                    return defaultValue;
                }
            }
        }
    }

    interface Session extends Closeable {
        boolean execute(String command, String expectedMarker)
                throws IOException, InterruptedException;

        /** Raw shell output is needed only for the fixed am start -W command. */
        default String executeForOutput(String command, String expectedMarker)
                throws IOException, InterruptedException {
            return execute(command, expectedMarker) ? expectedMarker : "";
        }
    }

    interface SessionFactory {
        Session open(Config config, Runnable socketConnected)
                throws IOException, InterruptedException, AuthorizationTimeoutException;
    }

    interface Sleeper {
        void sleep(long durationMs) throws InterruptedException;
    }

    static final class AuthorizationTimeoutException extends IOException {
        AuthorizationTimeoutException() {
            super("ADB authorization was not confirmed before the timeout");
        }
    }

    private final Object lock = new Object();
    private final Object operationLock = new Object();
    private final Config config;
    private final SessionFactory sessionFactory;
    private final Sleeper sleeper;
    private final ExecutorService ioExecutor;

    private volatile State state = State.DISCONNECTED;
    private volatile Result lastResult = Result.failure(Error.CONNECTION_FAILED,
            "ADB has not been connected");
    private volatile boolean closed;
    private Session activeSession;
    private Future<?> inFlightOperation;

    public AdbConnectionManager(Context context) {
        this(Config.fromPreferences(context),
                new AdbLibSessionFactory(new AdbKeyStore(context.getApplicationContext())),
                new Sleeper() {
                    @Override
                    public void sleep(long durationMs) throws InterruptedException {
                        Thread.sleep(durationMs);
                    }
                });
    }

    AdbConnectionManager(Config config, SessionFactory sessionFactory, Sleeper sleeper) {
        if (config == null || sessionFactory == null || sleeper == null) {
            throw new IllegalArgumentException("ADB dependencies must not be null");
        }
        this.config = config;
        this.sessionFactory = sessionFactory;
        this.sleeper = sleeper;
        this.ioExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "AdbConnectionIo");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    public State getState() {
        return state;
    }

    public Result getLastResult() {
        return lastResult;
    }

    public boolean isConnected() {
        synchronized (lock) {
            return !closed && state == State.CONNECTED && activeSession != null;
        }
    }

    /**
     * Verifies both ADB authentication and a working shell. Call from a worker
     * thread; UI code should use {@link AdbConnectionTestController}.
     */
    public Result testConnection(StateListener listener) {
        synchronized (operationLock) {
            Result result = ensureConnected(listener);
            lastResult = result;
            return result;
        }
    }

    Result sendKey(int keyCode) {
        synchronized (operationLock) {
            if (!AdbKeyCodeMapper.isAllowed(keyCode)) {
                Result result = Result.failure(Error.INVALID_CONFIGURATION,
                        "The requested keyCode is not allowed");
                lastResult = result;
                return result;
            }

            Result connectionResult = ensureConnected(null);
            if (!connectionResult.isSuccessful()) {
                lastResult = connectionResult;
                return connectionResult;
            }

            String command = AdbKeyCodeMapper.buildShellCommand(keyCode);
            Result result = executeCommand(command, KEY_COMMAND_RESULT_PREFIX + "0");
            lastResult = result;
            if (!result.isSuccessful()) {
                disconnectInternal(false);
                transition(State.FAILED, null, 0, config.retryCount + 1);
            }
            return result;
        }
    }

    Result sendText(String text) {
        synchronized (operationLock) {
            String command = AdbTextEncoder.buildShellCommand(text);
            Result connectionResult = ensureConnected(null);
            if (!connectionResult.isSuccessful()) {
                lastResult = connectionResult;
                return connectionResult;
            }
            Result result = executeCommand(command, TEXT_COMMAND_RESULT_PREFIX + "0");
            lastResult = result;
            if (!result.isSuccessful()) {
                disconnectInternal(false);
                transition(State.FAILED, null, 0, config.retryCount + 1);
            }
            return result;
        }
    }

    /**
     * Stops one validated application package. This deliberately accepts a
     * package name rather than an arbitrary shell command, so preferences
     * cannot turn the ADB channel into a general command executor.
     */
    Result forceStopPackage(String packageName) {
        synchronized (operationLock) {
            if (!isValidPackageName(packageName)) {
                Result result = Result.failure(Error.INVALID_CONFIGURATION,
                        "The target package name is invalid");
                lastResult = result;
                return result;
            }

            Result connectionResult = ensureConnected(null);
            if (!connectionResult.isSuccessful()) {
                lastResult = connectionResult;
                return connectionResult;
            }

            String command = "am force-stop --user 0 " + packageName
                    + "; echo " + FORCE_STOP_COMMAND_RESULT_PREFIX + "$?";
            Result result = executeCommand(command, FORCE_STOP_COMMAND_RESULT_PREFIX + "0");
            lastResult = result;
            if (!result.isSuccessful()) {
                disconnectInternal(false);
                transition(State.FAILED, null, 0, config.retryCount + 1);
            }
            return result;
        }
    }

    CommandResult startResolvedActivity(String componentName, String category) {
        synchronized (operationLock) {
            if (!isValidComponentName(componentName)
                    || !("android.intent.category.LEANBACK_LAUNCHER".equals(category)
                    || "android.intent.category.LAUNCHER".equals(category))) {
                Result failure = Result.failure(Error.INVALID_CONFIGURATION,
                        "The resolved target component is invalid");
                lastResult = failure;
                return new CommandResult(failure, "");
            }
            Result connection = ensureConnected(null);
            if (!connection.isSuccessful()) {
                lastResult = connection;
                return new CommandResult(connection, "");
            }
            String command = "am start -W --user 0 -a android.intent.action.MAIN -c "
                    + category + " -n " + componentName + "; echo "
                    + START_ACTIVITY_COMMAND_RESULT_PREFIX + "$?";
            CommandResult result = executeCommandForOutput(command,
                    START_ACTIVITY_COMMAND_RESULT_PREFIX + "0",
                    Math.max(config.commandTimeoutMs, DEFAULT_TARGET_LAUNCH_TIMEOUT_MS));
            lastResult = result.result;
            if (!result.result.isSuccessful()) {
                disconnectInternal(false);
                transition(State.FAILED, null, 0, config.retryCount + 1);
            }
            return result;
        }
    }

    static final class CommandResult {
        final Result result;
        final String output;

        CommandResult(Result result, String output) {
            this.result = result;
            this.output = output == null ? "" : output;
        }
    }

    static boolean isValidPackageName(String packageName) {
        return packageName != null
                && packageName.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    }

    static boolean isValidComponentName(String componentName) {
        if (componentName == null || !componentName.matches(
                "[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+/[A-Za-z0-9_.$]+")) {
            return false;
        }
        int separator = componentName.indexOf('/');
        return isValidPackageName(componentName.substring(0, separator));
    }

    private Result ensureConnected(StateListener listener) {
        synchronized (lock) {
            if (closed) {
                return Result.failure(Error.CLOSED, "ADB manager is closed");
            }
            if (state == State.CONNECTED && activeSession != null) {
                return Result.success();
            }
        }

        int maximumAttempts = config.retryCount + 1;
        Result failure = Result.failure(Error.CONNECTION_FAILED, "Unable to connect to ADB");
        for (int attempt = 1; attempt <= maximumAttempts; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                return cancelledResult(listener, attempt, maximumAttempts);
            }
            transition(State.CONNECTING, listener, attempt, maximumAttempts);
            try {
                final int currentAttempt = attempt;
                Session session = runWithTimeout(new Callable<Session>() {
                    @Override
                    public Session call() throws Exception {
                        return sessionFactory.open(config, new Runnable() {
                            @Override
                            public void run() {
                                transition(State.AUTHORIZING, listener, currentAttempt,
                                        maximumAttempts);
                            }
                        });
                    }
                }, config.connectionTimeoutMs, Error.CONNECTION_TIMEOUT);
                synchronized (lock) {
                    if (closed || Thread.currentThread().isInterrupted()) {
                        closeQuietly(session);
                        return cancelledResult(listener, attempt, maximumAttempts);
                    }
                    activeSession = session;
                }

                Result shellResult = executeCommand(
                        "echo " + SHELL_PROBE_MARKER, SHELL_PROBE_MARKER);
                if (shellResult.isSuccessful()) {
                    transition(State.CONNECTED, listener, attempt, maximumAttempts);
                    lastResult = shellResult;
                    return shellResult;
                }
                failure = shellResult;
            } catch (OperationException e) {
                failure = e.result;
            }

            disconnectInternal(false);
            if (attempt < maximumAttempts) {
                try {
                    sleeper.sleep(config.retryDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return cancelledResult(listener, attempt, maximumAttempts);
                }
            }
        }

        transition(State.FAILED, listener, maximumAttempts, maximumAttempts);
        lastResult = failure;
        return failure;
    }

    private Result executeCommand(final String command, final String expectedMarker) {
        final Session session;
        synchronized (lock) {
            session = activeSession;
        }
        if (session == null) {
            return Result.failure(Error.CONNECTION_FAILED, "ADB is not connected");
        }
        try {
            Boolean successful = runWithTimeout(new Callable<Boolean>() {
                @Override
                public Boolean call() throws Exception {
                    return session.execute(command, expectedMarker);
                }
            }, config.commandTimeoutMs, Error.COMMAND_TIMEOUT);
            return successful
                    ? Result.success()
                    : Result.failure(Error.COMMAND_FAILED,
                    "ADB shell reported that the command failed");
        } catch (OperationException e) {
            return e.result;
        }
    }

    private CommandResult executeCommandForOutput(final String command,
                                                  final String expectedMarker,
                                                  final long timeoutMs) {
        final Session session;
        synchronized (lock) {
            session = activeSession;
        }
        if (session == null) {
            return new CommandResult(Result.failure(Error.CONNECTION_FAILED,
                    "ADB is not connected"), "");
        }
        try {
            String output = runWithTimeout(new Callable<String>() {
                @Override
                public String call() throws Exception {
                    return session.executeForOutput(command, expectedMarker);
                }
            }, timeoutMs, Error.COMMAND_TIMEOUT);
            return new CommandResult(output.contains(expectedMarker)
                    ? Result.success()
                    : Result.failure(Error.COMMAND_FAILED,
                    "ADB shell reported that the command failed"), output);
        } catch (OperationException e) {
            return new CommandResult(e.result, "");
        }
    }

    private <T> T runWithTimeout(Callable<T> callable, long timeoutMs, Error timeoutError)
            throws OperationException {
        Future<T> future;
        synchronized (lock) {
            if (closed) {
                throw new OperationException(Result.failure(Error.CLOSED,
                        "ADB manager is closed"));
            }
            future = ioExecutor.submit(callable);
            inFlightOperation = future;
        }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            disconnectInternal(false);
            throw new OperationException(Result.failure(timeoutError,
                    timeoutError == Error.COMMAND_TIMEOUT
                            ? "ADB shell command timed out"
                            : "ADB connection timed out"));
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            disconnectInternal(false);
            throw new OperationException(Result.failure(Error.CANCELLED,
                    "ADB operation was cancelled"));
        } catch (CancellationException e) {
            disconnectInternal(false);
            throw new OperationException(Result.failure(Error.CANCELLED,
                    "ADB operation was cancelled"));
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof AuthorizationTimeoutException) {
                throw new OperationException(Result.failure(Error.AUTHORIZATION_TIMEOUT,
                        "Waiting for ADB authorization timed out"));
            }
            if (cause instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                throw new OperationException(Result.failure(Error.CANCELLED,
                        "ADB operation was cancelled"));
            }
            Error error = timeoutError == Error.COMMAND_TIMEOUT
                    ? Error.SHELL_UNAVAILABLE : Error.CONNECTION_FAILED;
            throw new OperationException(Result.failure(error,
                    error == Error.SHELL_UNAVAILABLE
                            ? "ADB shell is not available"
                            : "Unable to connect to ADB"));
        } finally {
            synchronized (lock) {
                if (inFlightOperation == future) {
                    inFlightOperation = null;
                }
            }
        }
    }

    public void cancelCurrentOperation() {
        Future<?> future;
        synchronized (lock) {
            future = inFlightOperation;
        }
        if (future != null) {
            future.cancel(true);
        }
        disconnectInternal(true);
        lastResult = Result.failure(Error.CANCELLED, "ADB operation was cancelled");
    }

    /** Closes the current authenticated session but permits a later reconnect. */
    public void disconnect() {
        disconnectInternal(true);
    }

    private void disconnectInternal(boolean updateState) {
        Session session;
        synchronized (lock) {
            session = activeSession;
            activeSession = null;
        }
        closeQuietly(session);
        if (updateState && !closed) {
            transition(State.DISCONNECTED, null, 0, config.retryCount + 1);
        }
    }

    private Result cancelledResult(StateListener listener, int attempt, int maximumAttempts) {
        disconnectInternal(false);
        transition(State.DISCONNECTED, listener, attempt, maximumAttempts);
        Result result = Result.failure(Error.CANCELLED, "ADB operation was cancelled");
        lastResult = result;
        return result;
    }

    private void transition(State newState, StateListener listener, int attempt,
                            int maximumAttempts) {
        state = newState;
        if (listener != null) {
            listener.onStateChanged(newState, attempt, maximumAttempts);
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // Best-effort cleanup. No key material or connection data is logged.
            }
        }
    }

    @Override
    public void close() {
        Future<?> future;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            future = inFlightOperation;
            inFlightOperation = null;
        }
        if (future != null) {
            future.cancel(true);
        }
        disconnectInternal(false);
        state = State.DISCONNECTED;
        lastResult = Result.failure(Error.CLOSED, "ADB manager is closed");
        ioExecutor.shutdownNow();
    }

    private static final class OperationException extends Exception {
        private final Result result;

        OperationException(Result result) {
            this.result = result;
        }
    }

    private static final class AdbLibSessionFactory implements SessionFactory {
        private final AdbKeyStore keyStore;

        AdbLibSessionFactory(AdbKeyStore keyStore) {
            this.keyStore = keyStore;
        }

        @Override
        public Session open(Config config, Runnable socketConnected)
                throws IOException, InterruptedException, AuthorizationTimeoutException {
            Socket socket = new Socket();
            AdbConnection connection = null;
            try {
                socket.connect(new InetSocketAddress(config.host, config.port),
                        toIntTimeout(config.connectionTimeoutMs));
                socketConnected.run();

                AdbCrypto crypto = keyStore.getOrCreate();
                connection = AdbConnection.create(socket, crypto);
                boolean connected = connection.connect(config.connectionTimeoutMs,
                        TimeUnit.MILLISECONDS, false);
                if (!connected) {
                    throw new AuthorizationTimeoutException();
                }
                return new AdbLibSession(connection);
            } catch (IOException | InterruptedException | RuntimeException e) {
                if (connection != null) {
                    closeQuietly(connection);
                } else {
                    closeQuietly(socket);
                }
                throw e;
            }
        }

        private static int toIntTimeout(long timeoutMs) {
            return (int) Math.min(Integer.MAX_VALUE, timeoutMs);
        }
    }

    private static final class AdbLibSession implements Session {
        private final AdbConnection connection;

        AdbLibSession(AdbConnection connection) {
            this.connection = connection;
        }

        @Override
        public boolean execute(String command, String expectedMarker)
                throws IOException, InterruptedException {
            return executeForOutput(command, expectedMarker).contains(expectedMarker);
        }

        @Override
        public String executeForOutput(String command, String expectedMarker)
                throws IOException, InterruptedException {
            AdbStream stream = null;
            try {
                stream = connection.open("shell:" + command);
                StringBuilder output = new StringBuilder();
                while (!stream.isClosed() && output.length() < MAX_SHELL_OUTPUT_BYTES) {
                    byte[] bytes;
                    try {
                        bytes = stream.read();
                    } catch (IOException e) {
                        break;
                    }
                    if (bytes != null) {
                        output.append(new String(bytes, StandardCharsets.UTF_8));
                        if (output.indexOf(expectedMarker) >= 0) break;
                    }
                }
                return output.toString();
            } finally {
                closeQuietly(stream);
            }
        }

        @Override
        public void close() throws IOException {
            connection.close();
        }
    }
}
