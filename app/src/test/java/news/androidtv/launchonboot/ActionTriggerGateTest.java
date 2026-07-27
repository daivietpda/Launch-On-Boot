package news.androidtv.launchonboot;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ActionTriggerGateTest {
    @Test
    public void runningSequence_rejectsEveryNewTrigger() {
        FakeClock clock = new FakeClock(10_000);
        MemoryStore store = new MemoryStore();
        ActionTriggerGate gate = new ActionTriggerGate(clock, store);

        assertEquals(ActionTriggerGate.Decision.ACCEPTED,
                gate.tryAcquire("SCREEN_ON", 20_000));
        assertEquals(ActionTriggerGate.Decision.ALREADY_RUNNING,
                gate.tryAcquire("DREAMING_STOPPED", 20_000));
        assertTrue(gate.isRunning());
    }

    @Test
    public void completedSequence_isDebouncedUsingElapsedRealtime() {
        FakeClock clock = new FakeClock(10_000);
        MemoryStore store = new MemoryStore();
        ActionTriggerGate gate = new ActionTriggerGate(clock, store);

        gate.tryAcquire("SCREEN_ON", 20_000);
        clock.now = 25_000;
        gate.release();
        clock.now = 30_000;

        assertEquals(ActionTriggerGate.Decision.DEBOUNCED,
                gate.tryAcquire("DREAMING_STOPPED", 20_000));
        assertFalse(gate.isRunning());
        assertEquals("SCREEN_ON", store.trigger);
    }

    @Test
    public void triggerAfterWindow_isAccepted() {
        FakeClock clock = new FakeClock(10_000);
        MemoryStore store = new MemoryStore();
        ActionTriggerGate gate = new ActionTriggerGate(clock, store);

        gate.tryAcquire("SCREEN_ON", 20_000);
        clock.now = 15_000;
        gate.release();
        clock.now = 35_000;

        assertEquals(ActionTriggerGate.Decision.ACCEPTED,
                gate.tryAcquire("DREAMING_STOPPED", 20_000));
        assertEquals("DREAMING_STOPPED", store.trigger);
    }

    @Test
    public void cancelledSequence_doesNotSuppressNextWake() {
        FakeClock clock = new FakeClock(10_000);
        MemoryStore store = new MemoryStore();
        ActionTriggerGate gate = new ActionTriggerGate(clock, store);

        gate.tryAcquire("SCREEN_ON", 20_000);
        clock.now = 11_000;
        gate.cancel();

        assertEquals(ActionTriggerGate.Decision.ACCEPTED,
                gate.tryAcquire("SCREEN_ON", 20_000));
    }

    @Test
    public void elapsedRealtimeResetAfterReboot_doesNotSuppressBoot() {
        FakeClock clock = new FakeClock(100);
        MemoryStore store = new MemoryStore();
        store.elapsed = 500_000;
        store.trigger = "SCREEN_ON";
        ActionTriggerGate gate = new ActionTriggerGate(clock, store);

        assertEquals(ActionTriggerGate.Decision.ACCEPTED,
                gate.tryAcquire("BOOT", 20_000));
    }

    @Test
    public void concurrentTriggers_onlyOneIsAccepted() throws Exception {
        FakeClock clock = new FakeClock(10_000);
        MemoryStore store = new MemoryStore();
        ActionTriggerGate gate = new ActionTriggerGate(clock, store);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();

        Runnable trigger = () -> {
            ready.countDown();
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (gate.tryAcquire("SCREEN_ON", 20_000)
                    == ActionTriggerGate.Decision.ACCEPTED) {
                accepted.incrementAndGet();
            }
        };
        Thread first = new Thread(trigger);
        Thread second = new Thread(trigger);
        first.start();
        second.start();
        ready.await();
        start.countDown();
        first.join();
        second.join();

        assertEquals(1, accepted.get());
    }

    private static final class FakeClock implements ActionTriggerGate.Clock {
        private volatile long now;

        FakeClock(long now) {
            this.now = now;
        }

        @Override
        public long elapsedRealtime() {
            return now;
        }
    }

    private static final class MemoryStore implements ActionTriggerGate.Store {
        private long elapsed = -1;
        private String trigger = "";

        @Override
        public long getLastElapsedRealtime() {
            return elapsed;
        }

        @Override
        public String getLastTrigger() {
            return trigger;
        }

        @Override
        public void save(long elapsedRealtime, String trigger) {
            elapsed = elapsedRealtime;
            this.trigger = trigger;
        }
    }
}
