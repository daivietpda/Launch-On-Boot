package news.androidtv.launchonboot;

import android.view.KeyEvent;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AdbConnectionManagerTest {
    private AdbConnectionManager manager;

    @After
    public void tearDown() {
        if (manager != null) {
            manager.close();
        }
    }

    @Test
    public void defaultConfig_rejectsRemoteHost() {
        try {
            config("192.168.1.20", 0, 0, 100, 100);
            fail("Expected remote host to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("localhost"));
        }
    }

    @Test
    public void explicitRemoteOptIn_isVisibleToUi() {
        AdbConnectionManager.Config config = new AdbConnectionManager.Config(
                "192.168.1.20", 5555, 0, 0, 100, 100, true);

        assertTrue(config.isRemoteHostAllowed());
        assertTrue(config.isRemoteHost());
    }

    @Test
    public void invalidPort_isRejected() {
        try {
            new AdbConnectionManager.Config("127.0.0.1", 70000,
                    0, 0, 100, 100);
            fail("Expected invalid port to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("port"));
        }
    }

    @Test
    public void excessiveRetryConfiguration_isRejected() {
        try {
            new AdbConnectionManager.Config("127.0.0.1", 5555,
                    AdbConnectionManager.MAX_RETRY_COUNT + 1, 0, 100, 100);
            fail("Expected excessive retry count to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("retryCount"));
        }

        try {
            new AdbConnectionManager.Config("127.0.0.1", 5555,
                    0, AdbConnectionManager.MAX_RETRY_DELAY_MS + 1, 100, 100);
            fail("Expected excessive retry delay to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("retryDelayMs"));
        }
    }

    @Test
    public void connection_retriesConfiguredNumberOfTimes() {
        AtomicInteger attempts = new AtomicInteger();
        RecordingSleeper sleeper = new RecordingSleeper();
        manager = new AdbConnectionManager(
                config("127.0.0.1", 2, 25, 500, 500),
                (settings, connected) -> {
                    int attempt = attempts.incrementAndGet();
                    if (attempt < 3) {
                        throw new IOException("not ready");
                    }
                    connected.run();
                    return successfulSession();
                },
                sleeper);

        AdbConnectionManager.Result result = manager.testConnection(null);

        assertTrue(result.isSuccessful());
        assertEquals(3, attempts.get());
        assertEquals(Arrays.asList(25L, 25L), sleeper.delays);
        assertEquals(AdbConnectionManager.State.CONNECTED, manager.getState());
    }

    @Test
    public void connectionTimeout_returnsSpecificFailure() {
        manager = new AdbConnectionManager(
                config("127.0.0.1", 0, 0, 40, 100),
                (settings, connected) -> {
                    Thread.sleep(5_000);
                    return successfulSession();
                },
                durationMs -> { });

        long startedAt = System.nanoTime();
        AdbConnectionManager.Result result = manager.testConnection(null);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

        assertFalse(result.isSuccessful());
        assertEquals(AdbConnectionManager.Error.CONNECTION_TIMEOUT, result.getError());
        assertTrue("Timeout took too long: " + elapsedMs, elapsedMs < 1_000);
    }

    @Test
    public void authorizationTimeout_isReportedSeparately() {
        manager = new AdbConnectionManager(
                config("127.0.0.1", 0, 0, 100, 100),
                (settings, connected) -> {
                    connected.run();
                    throw new AdbConnectionManager.AuthorizationTimeoutException();
                },
                durationMs -> { });

        AdbConnectionManager.Result result = manager.testConnection(null);

        assertFalse(result.isSuccessful());
        assertEquals(AdbConnectionManager.Error.AUTHORIZATION_TIMEOUT, result.getError());
    }

    @Test
    public void socketAlone_isNotReportedAsSuccessWhenShellProbeFails() {
        manager = new AdbConnectionManager(
                config("127.0.0.1", 0, 0, 100, 100),
                (settings, connected) -> {
                    connected.run();
                    return new AdbConnectionManager.Session() {
                        @Override
                        public boolean execute(String command, String marker) {
                            return false;
                        }

                        @Override
                        public void close() {
                        }
                    };
                },
                durationMs -> { });

        AdbConnectionManager.Result result = manager.testConnection(null);

        assertFalse(result.isSuccessful());
        assertEquals(AdbConnectionManager.Error.COMMAND_FAILED, result.getError());
        assertEquals(AdbConnectionManager.State.FAILED, manager.getState());
    }

    @Test
    public void commandTimeout_closesSessionAndFailsProbe() {
        AtomicInteger closes = new AtomicInteger();
        manager = new AdbConnectionManager(
                config("127.0.0.1", 0, 0, 100, 40),
                (settings, connected) -> {
                    connected.run();
                    return new AdbConnectionManager.Session() {
                        @Override
                        public boolean execute(String command, String marker)
                                throws InterruptedException {
                            Thread.sleep(5_000);
                            return true;
                        }

                        @Override
                        public void close() {
                            closes.incrementAndGet();
                        }
                    };
                },
                durationMs -> { });

        AdbConnectionManager.Result result = manager.testConnection(null);

        assertFalse(result.isSuccessful());
        assertEquals(AdbConnectionManager.Error.COMMAND_TIMEOUT, result.getError());
        assertEquals(1, closes.get());
    }

    @Test
    public void stateListener_receivesAuthorizationBeforeConnected() {
        List<AdbConnectionManager.State> states = new ArrayList<>();
        manager = new AdbConnectionManager(
                config("127.0.0.1", 0, 0, 100, 100),
                (settings, connected) -> {
                    connected.run();
                    return successfulSession();
                },
                durationMs -> { });

        AdbConnectionManager.Result result = manager.testConnection(
                (state, attempt, maximumAttempts) -> states.add(state));

        assertTrue(result.isSuccessful());
        assertEquals(Arrays.asList(
                AdbConnectionManager.State.CONNECTING,
                AdbConnectionManager.State.AUTHORIZING,
                AdbConnectionManager.State.CONNECTED), states);
    }

    @Test
    public void sendKey_usesOnlyMappedCommandAfterShellProbe() {
        List<String> commands = new ArrayList<>();
        manager = new AdbConnectionManager(
                config("127.0.0.1", 0, 0, 100, 100),
                (settings, connected) -> {
                    connected.run();
                    return new AdbConnectionManager.Session() {
                        @Override
                        public boolean execute(String command, String marker) {
                            commands.add(command);
                            return true;
                        }

                        @Override
                        public void close() {
                        }
                    };
                },
                durationMs -> { });

        AdbConnectionManager.Result result = manager.sendKey(KeyEvent.KEYCODE_7);

        assertTrue(result.isSuccessful());
        assertEquals(Arrays.asList(
                "echo __LOB_SHELL_OK__",
                "input keyevent KEYCODE_7; echo __LOB_KEY_EXIT__$?"), commands);
    }

    @Test
    public void sendKey_rejectsUnsupportedCodeBeforeConnecting() {
        AtomicInteger attempts = new AtomicInteger();
        manager = new AdbConnectionManager(
                config("127.0.0.1", 0, 0, 100, 100),
                (settings, connected) -> {
                    attempts.incrementAndGet();
                    return successfulSession();
                },
                durationMs -> { });

        AdbConnectionManager.Result result = manager.sendKey(KeyEvent.KEYCODE_VOLUME_UP);

        assertFalse(result.isSuccessful());
        assertEquals(AdbConnectionManager.Error.INVALID_CONFIGURATION, result.getError());
        assertEquals(0, attempts.get());
    }

    @Test
    public void forceStopPackage_usesOnlyValidatedPackageAfterShellProbe() {
        List<String> commands = new ArrayList<>();
        manager = new AdbConnectionManager(
                config("127.0.0.1", 0, 0, 100, 100),
                (settings, connected) -> {
                    connected.run();
                    return new AdbConnectionManager.Session() {
                        @Override
                        public boolean execute(String command, String marker) {
                            commands.add(command);
                            return true;
                        }

                        @Override
                        public void close() {
                        }
                    };
                },
                durationMs -> { });

        AdbConnectionManager.Result result = manager.forceStopPackage("com.example.tv");

        assertTrue(result.isSuccessful());
        assertEquals(Arrays.asList(
                "echo __LOB_SHELL_OK__",
                "am force-stop --user 0 com.example.tv; echo __LOB_FORCE_STOP_EXIT__$?"),
                commands);
    }

    @Test
    public void forceStopPackage_rejectsMalformedPackageBeforeConnecting() {
        AtomicInteger attempts = new AtomicInteger();
        manager = new AdbConnectionManager(
                config("127.0.0.1", 0, 0, 100, 100),
                (settings, connected) -> {
                    attempts.incrementAndGet();
                    return successfulSession();
                },
                durationMs -> { });

        AdbConnectionManager.Result result =
                manager.forceStopPackage("com.example.tv; input keyevent HOME");

        assertFalse(result.isSuccessful());
        assertEquals(AdbConnectionManager.Error.INVALID_CONFIGURATION, result.getError());
        assertEquals(0, attempts.get());
    }

    @Test
    public void resolvedComponentValidation_rejectsShellInjection() {
        assertTrue(AdbConnectionManager.isValidComponentName(
                "com.example.tv/com.example.tv.MainActivity"));
        assertFalse(AdbConnectionManager.isValidComponentName(
                "com.example.tv;input keyevent HOME/MainActivity"));
        assertFalse(AdbConnectionManager.isValidComponentName(
                "com.example.tv/MainActivity && id"));
        assertFalse(AdbConnectionManager.isValidComponentName(
                "com.example.tv/$(id)"));
        assertFalse(AdbConnectionManager.isValidComponentName(
                "com.example.tv/Main\nActivity"));
    }

    private static AdbConnectionManager.Config config(
            String host, int retryCount, long retryDelayMs,
            long connectionTimeoutMs, long commandTimeoutMs) {
        return new AdbConnectionManager.Config(host, 5555, retryCount, retryDelayMs,
                connectionTimeoutMs, commandTimeoutMs);
    }

    private static AdbConnectionManager.Session successfulSession() {
        return new AdbConnectionManager.Session() {
            @Override
            public boolean execute(String command, String marker) {
                return true;
            }

            @Override
            public void close() {
            }
        };
    }

    private static final class RecordingSleeper implements AdbConnectionManager.Sleeper {
        private final List<Long> delays = new ArrayList<>();

        @Override
        public void sleep(long durationMs) {
            delays.add(durationMs);
        }
    }
}
