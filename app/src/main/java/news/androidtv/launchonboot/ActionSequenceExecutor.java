package news.androidtv.launchonboot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/** Executes one action sequence at a time on a dedicated background thread. */
public final class ActionSequenceExecutor implements AutoCloseable {
    public enum State {
        IDLE,
        RUNNING,
        CANCELLING,
        FINISHED
    }

    public enum Result {
        COMPLETED,
        CANCELLED,
        INJECTOR_UNAVAILABLE,
        KEY_SEND_FAILED
    }

    /**
     * Action and key-request callbacks run on the worker thread. State and
     * finished callbacks can run on either the worker or cancellation caller,
     * so Android UI callers must post visual updates to the main thread.
     */
    public interface Listener {
        void onStateChanged(State state);

        void onActionStarted(int actionIndex, ActionItem action);

        void onKeySendRequested(int actionIndex, int keyCode, int repeatIndex);

        void onFinished(Result result);
    }

    private static final Listener NO_OP_LISTENER = new Listener() {
        @Override
        public void onStateChanged(State state) { }

        @Override
        public void onActionStarted(int actionIndex, ActionItem action) { }

        @Override
        public void onKeySendRequested(int actionIndex, int keyCode, int repeatIndex) { }

        @Override
        public void onFinished(Result result) { }
    };
    private static final Object GLOBAL_EXECUTION_LOCK = new Object();
    private static ActionSequenceExecutor activeExecutor;

    private final Object lock = new Object();
    private final KeyInjector keyInjector;
    private final long defaultActionDelayMs;
    private final ExecutorService executorService;

    private volatile State state = State.IDLE;
    private Future<?> currentTask;
    private Listener activeListener = NO_OP_LISTENER;
    private boolean taskStarted;
    private boolean completionDelivered;
    private volatile boolean cancellationRequested;
    private boolean closed;

    public ActionSequenceExecutor(KeyInjector keyInjector) {
        this(keyInjector, ActionSequenceStore.DEFAULT_ACTION_DELAY_MS);
    }

    public ActionSequenceExecutor(KeyInjector keyInjector, long defaultActionDelayMs) {
        if (keyInjector == null) {
            throw new IllegalArgumentException("keyInjector must not be null");
        }
        ActionItem.validateActionTime(defaultActionDelayMs, "defaultActionDelayMs");
        this.keyInjector = keyInjector;
        this.defaultActionDelayMs = defaultActionDelayMs;
        this.executorService = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "ActionSequenceExecutor");
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    /**
     * Starts a sequence. Returns false when another sequence is running or cancelling.
     */
    public boolean start(List<ActionItem> actions, Listener listener) {
        if (actions == null) {
            throw new IllegalArgumentException("actions must not be null");
        }
        final List<ActionItem> actionCopy = Collections.unmodifiableList(new ArrayList<>(actions));
        for (ActionItem action : actionCopy) {
            if (action == null) {
                throw new IllegalArgumentException("actions must not contain null items");
            }
        }
        final Listener safeListener = listener == null ? NO_OP_LISTENER : listener;

        synchronized (lock) {
            if (closed || state == State.RUNNING || state == State.CANCELLING) {
                return false;
            }
            synchronized (GLOBAL_EXECUTION_LOCK) {
                if (activeExecutor != null && activeExecutor != this) {
                    return false;
                }
                activeExecutor = this;
            }
            state = State.RUNNING;
            activeListener = safeListener;
            taskStarted = false;
            completionDelivered = false;
            cancellationRequested = false;
            notifyStateChanged(safeListener, State.RUNNING);
            currentTask = executorService.submit(new Runnable() {
                @Override
                public void run() {
                    synchronized (lock) {
                        if (completionDelivered) {
                            return;
                        }
                        taskStarted = true;
                    }
                    execute(actionCopy, safeListener);
                }
            });
            return true;
        }
    }

    public State getState() {
        return state;
    }

    /** Requests cancellation and interrupts an in-progress WAIT immediately. */
    public boolean cancel() {
        Future<?> task;
        Listener listener;
        synchronized (lock) {
            if (state != State.RUNNING) {
                return false;
            }
            state = State.CANCELLING;
            cancellationRequested = true;
            task = currentTask;
            listener = activeListener;
        }

        // Keep callback ordering deterministic even if Future.cancel() causes
        // the worker to finish immediately.
        notifyStateChanged(listener, State.CANCELLING);
        boolean cancelled = task != null && task.cancel(true);
        boolean cancelledBeforeStart;
        synchronized (lock) {
            cancelledBeforeStart = cancelled && !taskStarted && !completionDelivered;
        }
        if (cancelledBeforeStart) {
            complete(listener, Result.CANCELLED);
        }
        return true;
    }

    private void execute(List<ActionItem> actions, Listener listener) {
        Result result = Result.COMPLETED;
        try {
            for (int actionIndex = 0; actionIndex < actions.size(); actionIndex++) {
                if (isCancellationRequested()) {
                    result = Result.CANCELLED;
                    break;
                }

                ActionItem action = actions.get(actionIndex);
                listener.onActionStarted(actionIndex, action);
                if (action.getType() == ActionItem.Type.WAIT) {
                    if (!waitFor(action.getDurationMs())) {
                        result = Result.CANCELLED;
                        break;
                    }
                    continue;
                }

                if (!keyInjector.isAvailable()) {
                    result = Result.INJECTOR_UNAVAILABLE;
                    break;
                }

                int keyCode = ActionItem.KeyCode.fromValue(action.getKeyCode()).getKeyEventCode();
                long delayAfterMs = action.isDelayAfterMsSet()
                        ? action.getDelayAfterMs() : defaultActionDelayMs;
                for (int repeatIndex = 0; repeatIndex < action.getRepeat(); repeatIndex++) {
                    if (isCancellationRequested()) {
                        result = Result.CANCELLED;
                        break;
                    }
                    listener.onKeySendRequested(actionIndex, keyCode, repeatIndex);
                    if (!keyInjector.sendKey(keyCode)) {
                        result = isCancellationRequested()
                                ? Result.CANCELLED : Result.KEY_SEND_FAILED;
                        break;
                    }
                    if (!waitFor(delayAfterMs)) {
                        result = Result.CANCELLED;
                        break;
                    }
                }
                if (result != Result.COMPLETED) {
                    break;
                }
            }
        } catch (RuntimeException e) {
            result = Result.KEY_SEND_FAILED;
        } finally {
            complete(listener, result);
        }
    }

    private boolean waitFor(long durationMs) {
        if (durationMs == 0) {
            return !isCancellationRequested();
        }
        long deadline = System.nanoTime() + durationMs * 1_000_000L;
        while (!isCancellationRequested()) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                return true;
            }
            try {
                long remainingMillis = Math.max(1L, remainingNanos / 1_000_000L);
                Thread.sleep(remainingMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private boolean isCancellationRequested() {
        return cancellationRequested || Thread.currentThread().isInterrupted();
    }

    private void complete(Listener listener, Result result) {
        synchronized (lock) {
            if (completionDelivered) {
                return;
            }
            completionDelivered = true;
            currentTask = null;
            state = State.FINISHED;
            activeListener = NO_OP_LISTENER;
        }
        synchronized (GLOBAL_EXECUTION_LOCK) {
            if (activeExecutor == this) {
                activeExecutor = null;
            }
        }
        try {
            keyInjector.release();
        } catch (RuntimeException e) {
            if (result == Result.COMPLETED) {
                result = Result.KEY_SEND_FAILED;
            }
        }
        notifyStateChanged(listener, State.FINISHED);
        notifyFinished(listener, result);
    }

    private static void notifyStateChanged(Listener listener, State state) {
        try {
            listener.onStateChanged(state);
        } catch (RuntimeException ignored) {
            // Client callbacks must not corrupt executor lifecycle state.
        }
    }

    private static void notifyFinished(Listener listener, Result result) {
        try {
            listener.onFinished(result);
        } catch (RuntimeException ignored) {
            // The sequence is already complete; do not strand executor resources.
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            closed = true;
        }
        cancel();
        executorService.shutdownNow();
    }
}
