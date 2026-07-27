package news.androidtv.launchonboot;

import android.view.KeyEvent;

import org.junit.After;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ActionSequenceExecutorTest {
    private ActionSequenceExecutor executor;

    @After
    public void tearDown() {
        if (executor != null) {
            executor.close();
        }
    }

    @Test
    public void emptySequence_completesWithoutSendingKeys() throws Exception {
        FakeKeyInjector injector = new FakeKeyInjector();
        executor = new ActionSequenceExecutor(injector, 0);
        RecordingListener listener = new RecordingListener();

        assertTrue(executor.start(Collections.<ActionItem>emptyList(), listener));
        assertEquals(ActionSequenceExecutor.Result.COMPLETED, listener.awaitResult());
        assertTrue(injector.getRequestedKeyCodes().isEmpty());
    }

    @Test
    public void waitAction_waitsOnWorkerAndCompletes() throws Exception {
        FakeKeyInjector injector = new FakeKeyInjector();
        executor = new ActionSequenceExecutor(injector, 0);
        RecordingListener listener = new RecordingListener();
        long startedAt = System.nanoTime();

        assertTrue(executor.start(Collections.singletonList(ActionItem.waitFor(80)), listener));
        assertEquals(ActionSequenceExecutor.Result.COMPLETED, listener.awaitResult());
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertTrue("WAIT completed too early: " + elapsedMs, elapsedMs >= 60);
    }

    @Test
    public void consecutiveKeys_preserveOrder() throws Exception {
        FakeKeyInjector injector = new FakeKeyInjector();
        executor = new ActionSequenceExecutor(injector, 0);
        RecordingListener listener = new RecordingListener();

        executor.start(Arrays.asList(
                ActionItem.key("KEYCODE_1", 0, 1),
                ActionItem.key("DPAD_DOWN", 0, 1),
                ActionItem.key("ENTER", 0, 1)
        ), listener);

        assertEquals(ActionSequenceExecutor.Result.COMPLETED, listener.awaitResult());
        assertEquals(Arrays.asList(KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_ENTER), injector.getRequestedKeyCodes());
    }

    @Test
    public void repeatedKey_sendsRequestedNumberOfTimes() throws Exception {
        FakeKeyInjector injector = new FakeKeyInjector();
        executor = new ActionSequenceExecutor(injector, 0);
        RecordingListener listener = new RecordingListener();

        executor.start(Collections.singletonList(ActionItem.key("KEYCODE_5", 0, 3)), listener);

        assertEquals(ActionSequenceExecutor.Result.COMPLETED, listener.awaitResult());
        assertEquals(Arrays.asList(KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_5,
                KeyEvent.KEYCODE_5), injector.getRequestedKeyCodes());
    }

    @Test
    public void missingDelay_usesExecutorDefaultDelay() throws Exception {
        FakeKeyInjector injector = new FakeKeyInjector();
        executor = new ActionSequenceExecutor(injector, 70);
        RecordingListener listener = new RecordingListener();
        ActionItem withoutDelay = ActionItem.fromJson(new org.json.JSONObject(
                "{\"type\":\"KEY\",\"keyCode\":\"KEYCODE_1\"}"));
        long startedAt = System.nanoTime();

        executor.start(Collections.singletonList(withoutDelay), listener);
        assertEquals(ActionSequenceExecutor.Result.COMPLETED, listener.awaitResult());
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertTrue("Default delay was not used: " + elapsedMs, elapsedMs >= 50);
    }

    @Test
    public void explicitDelay_delaysTheNextKey() throws Exception {
        FakeKeyInjector injector = new FakeKeyInjector();
        executor = new ActionSequenceExecutor(injector, 0);
        KeyTimingListener listener = new KeyTimingListener();

        executor.start(Arrays.asList(
                ActionItem.key("KEYCODE_1", 80, 1),
                ActionItem.key("KEYCODE_2", 0, 1)
        ), listener);

        assertEquals(ActionSequenceExecutor.Result.COMPLETED, listener.awaitResult());
        assertEquals(2, listener.requestTimes.size());
        long gapMs = TimeUnit.NANOSECONDS.toMillis(
                listener.requestTimes.get(1) - listener.requestTimes.get(0));
        assertTrue("Explicit delay was not used: " + gapMs, gapMs >= 60);
    }

    @Test
    public void cancelDuringWait_stopsWithoutSendingNextKey() throws Exception {
        FakeKeyInjector injector = new FakeKeyInjector();
        executor = new ActionSequenceExecutor(injector, 0);
        RecordingListener listener = new RecordingListener();

        executor.start(Arrays.asList(ActionItem.waitFor(5000), ActionItem.key("KEYCODE_1", 0, 1)),
                listener);
        assertTrue(listener.awaitActionStarted());
        assertTrue(executor.cancel());

        assertEquals(ActionSequenceExecutor.Result.CANCELLED, listener.awaitResult());
        assertTrue(injector.getRequestedKeyCodes().isEmpty());
    }

    @Test
    public void cancelImmediately_stillFinishesAndReleasesInjector() throws Exception {
        RecordingReleaseInjector injector = new RecordingReleaseInjector();
        executor = new ActionSequenceExecutor(injector, 0);
        RecordingListener listener = new RecordingListener();

        assertTrue(executor.start(
                Collections.singletonList(ActionItem.waitFor(5000)), listener));
        assertTrue(executor.cancel());

        assertEquals(ActionSequenceExecutor.Result.CANCELLED, listener.awaitResult());
        assertEquals(1, injector.releaseCount);
    }

    @Test
    public void unavailableInjector_stopsBeforeSendingKey() throws Exception {
        FakeKeyInjector injector = new FakeKeyInjector();
        injector.setAvailable(false);
        executor = new ActionSequenceExecutor(injector, 0);
        RecordingListener listener = new RecordingListener();

        executor.start(Collections.singletonList(ActionItem.key("KEYCODE_1", 0, 1)), listener);

        assertEquals(ActionSequenceExecutor.Result.INJECTOR_UNAVAILABLE, listener.awaitResult());
        assertTrue(injector.getRequestedKeyCodes().isEmpty());
    }

    @Test
    public void failedSend_stopsSequence() throws Exception {
        FakeKeyInjector injector = new FakeKeyInjector();
        injector.setSendSucceeds(false);
        executor = new ActionSequenceExecutor(injector, 0);
        RecordingListener listener = new RecordingListener();

        executor.start(Collections.singletonList(ActionItem.key("KEYCODE_1", 0, 1)), listener);

        assertEquals(ActionSequenceExecutor.Result.KEY_SEND_FAILED, listener.awaitResult());
        assertEquals(Collections.singletonList(KeyEvent.KEYCODE_1), injector.getRequestedKeyCodes());
    }

    @Test
    public void secondStartWhileRunning_isRejected() throws Exception {
        FakeKeyInjector injector = new FakeKeyInjector();
        executor = new ActionSequenceExecutor(injector, 0);
        RecordingListener listener = new RecordingListener();

        assertTrue(executor.start(Collections.singletonList(ActionItem.waitFor(5000)), listener));
        assertTrue(listener.awaitActionStarted());
        assertFalse(executor.start(Collections.<ActionItem>emptyList(), new RecordingListener()));
        executor.cancel();
        assertEquals(ActionSequenceExecutor.Result.CANCELLED, listener.awaitResult());
    }

    @Test
    public void secondExecutorInstanceCannotRunConcurrently() throws Exception {
        FakeKeyInjector firstInjector = new FakeKeyInjector();
        executor = new ActionSequenceExecutor(firstInjector, 0);
        RecordingListener firstListener = new RecordingListener();
        ActionSequenceExecutor second =
                new ActionSequenceExecutor(new FakeKeyInjector(), 0);

        try {
            assertTrue(executor.start(
                    Collections.singletonList(ActionItem.waitFor(5000)), firstListener));
            assertTrue(firstListener.awaitActionStarted());
            assertFalse(second.start(
                    Collections.<ActionItem>emptyList(), new RecordingListener()));

            assertTrue(executor.cancel());
            assertEquals(ActionSequenceExecutor.Result.CANCELLED,
                    firstListener.awaitResult());

            RecordingListener secondListener = new RecordingListener();
            assertTrue(second.start(Collections.<ActionItem>emptyList(), secondListener));
            assertEquals(ActionSequenceExecutor.Result.COMPLETED,
                    secondListener.awaitResult());
        } finally {
            second.close();
        }
    }

    private static class RecordingListener implements ActionSequenceExecutor.Listener {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);
        private volatile ActionSequenceExecutor.Result result;

        @Override
        public void onStateChanged(ActionSequenceExecutor.State state) { }

        @Override
        public void onActionStarted(int actionIndex, ActionItem action) {
            started.countDown();
        }

        @Override
        public void onKeySendRequested(int actionIndex, int keyCode, int repeatIndex) { }

        @Override
        public void onFinished(ActionSequenceExecutor.Result result) {
            this.result = result;
            finished.countDown();
        }

        boolean awaitActionStarted() throws InterruptedException {
            return started.await(2, TimeUnit.SECONDS);
        }

        ActionSequenceExecutor.Result awaitResult() throws InterruptedException {
            assertTrue("Executor did not finish", finished.await(3, TimeUnit.SECONDS));
            return result;
        }
    }

    private static final class KeyTimingListener extends RecordingListener {
        private final List<Long> requestTimes = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void onKeySendRequested(int actionIndex, int keyCode, int repeatIndex) {
            requestTimes.add(System.nanoTime());
        }
    }

    private static final class RecordingReleaseInjector implements KeyInjector {
        private volatile int releaseCount;

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public boolean sendKey(int keyCode) {
            return true;
        }

        @Override
        public void release() {
            releaseCount++;
        }
    }
}
