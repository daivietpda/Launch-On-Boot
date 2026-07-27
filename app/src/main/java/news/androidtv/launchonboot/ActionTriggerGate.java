package news.androidtv.launchonboot;

/** Thread-safe running/debounce gate using a monotonic clock. */
final class ActionTriggerGate {
    enum Decision {
        ACCEPTED,
        ALREADY_RUNNING,
        DEBOUNCED
    }

    interface Clock {
        long elapsedRealtime();
    }

    interface Store {
        long getLastElapsedRealtime();

        String getLastTrigger();

        void save(long elapsedRealtime, String trigger);
    }

    private final Object lock = new Object();
    private final Clock clock;
    private final Store store;
    private boolean running;
    private String activeTrigger;

    ActionTriggerGate(Clock clock, Store store) {
        this.clock = clock;
        this.store = store;
    }

    Decision tryAcquire(String trigger, long debounceMs) {
        synchronized (lock) {
            if (running) {
                return Decision.ALREADY_RUNNING;
            }
            long now = clock.elapsedRealtime();
            long last = store.getLastElapsedRealtime();
            // elapsedRealtime resets after reboot. A value in the future is
            // therefore stale and must not suppress the new boot.
            if (last >= 0 && now >= last && now - last < debounceMs) {
                return Decision.DEBOUNCED;
            }
            running = true;
            activeTrigger = trigger;
            store.save(now, trigger);
            return Decision.ACCEPTED;
        }
    }

    /** Marks a completed attempt and starts the debounce window at completion time. */
    void release() {
        synchronized (lock) {
            if (running) {
                store.save(clock.elapsedRealtime(),
                        activeTrigger == null ? "" : activeTrigger);
            }
            running = false;
            activeTrigger = null;
        }
    }

    /** Cancels an incomplete attempt so the next real wake can run immediately. */
    void cancel() {
        synchronized (lock) {
            running = false;
            activeTrigger = null;
            store.save(-1L, "");
        }
    }

    boolean isRunning() {
        synchronized (lock) {
            return running;
        }
    }
}
