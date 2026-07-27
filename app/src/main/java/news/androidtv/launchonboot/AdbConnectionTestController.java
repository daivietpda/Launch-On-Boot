package news.androidtv.launchonboot;

import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/**
 * UI-facing asynchronous wrapper for the "Test ADB connection" action.
 *
 * <p>The callback is weakly referenced and all callback methods run on this
 * controller's worker thread. An Activity must post visual updates to its main
 * thread and close the controller from onDestroy().</p>
 */
public final class AdbConnectionTestController implements AutoCloseable {
    public interface Listener {
        void onStateChanged(AdbConnectionManager.State state, int attempt, int maximumAttempts);

        void onFinished(AdbConnectionManager.Result result);
    }

    private final Object lock = new Object();
    private final AdbConnectionManager connectionManager;
    private final ExecutorService executorService;
    private Future<?> task;
    private boolean closed;

    public AdbConnectionTestController(AdbConnectionManager connectionManager) {
        if (connectionManager == null) {
            throw new IllegalArgumentException("connectionManager must not be null");
        }
        this.connectionManager = connectionManager;
        executorService = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "AdbConnectionTest");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    public boolean start(Listener listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        final WeakReference<Listener> listenerReference = new WeakReference<>(listener);
        synchronized (lock) {
            if (closed || (task != null && !task.isDone())) {
                return false;
            }
            task = executorService.submit(new Runnable() {
                @Override
                public void run() {
                    AdbConnectionManager.Result result =
                            connectionManager.testConnection(
                                    new AdbConnectionManager.StateListener() {
                                        @Override
                                        public void onStateChanged(
                                                AdbConnectionManager.State state,
                                                int attempt, int maximumAttempts) {
                                            Listener current = listenerReference.get();
                                            if (current != null) {
                                                current.onStateChanged(state, attempt,
                                                        maximumAttempts);
                                            }
                                        }
                                    });
                    Listener current = listenerReference.get();
                    if (current != null) {
                        current.onFinished(result);
                    }
                    connectionManager.disconnect();
                }
            });
            return true;
        }
    }

    public void cancel() {
        Future<?> currentTask;
        synchronized (lock) {
            currentTask = task;
        }
        if (currentTask != null) {
            currentTask.cancel(true);
        }
        connectionManager.cancelCurrentOperation();
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
        }
        cancel();
        executorService.shutdownNow();
        connectionManager.close();
    }
}
