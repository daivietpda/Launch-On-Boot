package news.androidtv.launchonboot;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

/**
 * Minimal advanced-actions screen for ADB connection testing and real sequence
 * execution. The action editor remains outside Stage 2.
 */
public final class AdvancedSettingsActivity extends AppCompatActivity {
    private static final String TAG = "AdvancedSettings";
    private EditText hostView;
    private EditText portView;
    private EditText appLaunchDelayView;
    private EditText postLaunchDelayView;
    private EditText defaultActionDelayView;
    private EditText actionJsonView;
    private CheckBox advancedEnabledView;
    private CheckBox triggerBootView;
    private CheckBox triggerWakeView;
    private TextView statusView;
    private Button testButton;
    private Button saveButton;
    private Button runButton;
    private Button stopButton;

    private AdbConnectionTestController connectionTestController;
    private AdbConnectionTestController.Listener connectionTestListener;
    private ActionSequenceExecutor actionExecutor;
    private AdbKeyInjector adbKeyInjector;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_advanced_settings);

        hostView = findViewById(R.id.edit_adb_host);
        portView = findViewById(R.id.edit_adb_port);
        appLaunchDelayView = findViewById(R.id.edit_app_launch_delay);
        postLaunchDelayView = findViewById(R.id.edit_post_launch_delay);
        defaultActionDelayView = findViewById(R.id.edit_default_action_delay);
        actionJsonView = findViewById(R.id.edit_action_json);
        advancedEnabledView = findViewById(R.id.check_advanced_enabled);
        triggerBootView = findViewById(R.id.check_trigger_boot);
        triggerWakeView = findViewById(R.id.check_trigger_wake);
        statusView = findViewById(R.id.text_adb_status);
        testButton = findViewById(R.id.button_test_adb_connection);
        saveButton = findViewById(R.id.button_save_advanced_configuration);
        runButton = findViewById(R.id.button_run_action_sequence);
        stopButton = findViewById(R.id.button_stop_action_sequence);

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        hostView.setText(readString(preferences, SettingsManagerConstants.ADB_HOST,
                ActionSequenceStore.DEFAULT_ADB_HOST));
        portView.setText(String.valueOf(readInt(preferences, SettingsManagerConstants.ADB_PORT,
                ActionSequenceStore.DEFAULT_ADB_PORT)));
        appLaunchDelayView.setText(String.valueOf(readLong(preferences,
                SettingsManagerConstants.APP_LAUNCH_DELAY_MS,
                ActionSequenceStore.DEFAULT_APP_LAUNCH_DELAY_MS)));
        postLaunchDelayView.setText(String.valueOf(readLong(preferences,
                SettingsManagerConstants.POST_LAUNCH_DELAY_MS,
                ActionSequenceStore.DEFAULT_POST_LAUNCH_DELAY_MS)));
        defaultActionDelayView.setText(String.valueOf(readLong(preferences,
                SettingsManagerConstants.DEFAULT_ACTION_DELAY_MS,
                ActionSequenceStore.DEFAULT_ACTION_DELAY_MS)));
        loadSavedActionSequence(preferences);
        advancedEnabledView.setChecked(readBoolean(preferences,
                SettingsManagerConstants.ADVANCED_ACTIONS_ENABLED, false));
        triggerBootView.setChecked(readBoolean(preferences,
                SettingsManagerConstants.ACTION_TRIGGER_BOOT, true));
        triggerWakeView.setChecked(readBoolean(preferences,
                SettingsManagerConstants.ACTION_TRIGGER_WAKE, true));

        testButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startConnectionTest();
            }
        });
        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (saveAndValidateConfiguration()) {
                    statusView.setText(R.string.advanced_configuration_saved);
                }
            }
        });
        runButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                runActionSequence();
            }
        });
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stopCurrentOperation();
            }
        });
    }

    private void startConnectionTest() {
        stopCurrentOperation();
        if (!saveAndValidateConfiguration()) {
            return;
        }

        final AdbConnectionManager connectionManager;
        try {
            connectionManager = new AdbConnectionManager(this);
        } catch (IllegalArgumentException e) {
            showInvalidConfiguration(e);
            return;
        }
        final AdbConnectionTestController controller =
                new AdbConnectionTestController(connectionManager);
        connectionTestController = controller;
        setBusy(true);
        connectionTestListener = new AdbConnectionTestController.Listener() {
                    @Override
                    public void onStateChanged(AdbConnectionManager.State state,
                                               int attempt, int maximumAttempts) {
                        postConnectionState(state, attempt, maximumAttempts);
                    }

                    @Override
                    public void onFinished(AdbConnectionManager.Result result) {
                        postConnectionResult(controller, result);
                    }
                };
        boolean started = controller.start(connectionTestListener);
        if (!started) {
            controller.close();
            connectionTestController = null;
            connectionTestListener = null;
            setBusy(false);
        }
    }

    private void runActionSequence() {
        stopCurrentOperation();
        if (!saveAndValidateConfiguration()) {
            return;
        }

        final List<ActionItem> actions = new ActionSequenceStore(this).getActionSequence();
        if (actions.isEmpty()) {
            statusView.setText(R.string.action_sequence_empty);
            return;
        }

        try {
            adbKeyInjector = new AdbKeyInjector(new AdbConnectionManager(this));
        } catch (IllegalArgumentException e) {
            showInvalidConfiguration(e);
            return;
        }

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        long defaultDelayMs = readLong(preferences,
                SettingsManagerConstants.DEFAULT_ACTION_DELAY_MS,
                ActionSequenceStore.DEFAULT_ACTION_DELAY_MS);
        if (defaultDelayMs < 0) {
            showInvalidConfiguration(new IllegalArgumentException(
                    "Default action delay must not be negative"));
            adbKeyInjector.close();
            adbKeyInjector = null;
            return;
        }

        final AdbKeyInjector sequenceInjector = adbKeyInjector;
        final ActionSequenceExecutor sequenceExecutor =
                new ActionSequenceExecutor(sequenceInjector, defaultDelayMs);
        actionExecutor = sequenceExecutor;
        setBusy(true);
        boolean started = sequenceExecutor.start(actions, new ActionSequenceExecutor.Listener() {
            @Override
            public void onStateChanged(ActionSequenceExecutor.State state) {
            }

            @Override
            public void onActionStarted(int actionIndex, ActionItem action) {
                final String description = action.getType() == ActionItem.Type.WAIT
                        ? "WAIT " + action.getDurationMs() + " ms"
                        : action.getKeyCode();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        statusView.setText(getString(R.string.action_sequence_running,
                                actionIndex + 1, actions.size(), description));
                    }
                });
            }

            @Override
            public void onKeySendRequested(int actionIndex, int keyCode, int repeatIndex) {
            }

            @Override
            public void onFinished(ActionSequenceExecutor.Result result) {
                final ActionSequenceExecutor.Result finalResult = result;
                final AdbConnectionManager.Result adbResult = sequenceInjector.getLastResult();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        sequenceExecutor.close();
                        sequenceInjector.close();
                        if (actionExecutor != sequenceExecutor) {
                            return;
                        }
                        actionExecutor = null;
                        adbKeyInjector = null;
                        if (finalResult == ActionSequenceExecutor.Result.KEY_SEND_FAILED
                                && !adbResult.isSuccessful()) {
                            statusView.setText(getString(R.string.adb_status_failed,
                                    adbResult.getMessage()));
                        } else {
                            statusView.setText(getString(R.string.action_sequence_finished,
                                    finalResult.name()));
                        }
                        setBusy(false);
                    }
                });
            }
        });
        if (!started) {
            sequenceExecutor.close();
            sequenceInjector.close();
            actionExecutor = null;
            adbKeyInjector = null;
            setBusy(false);
        }
    }

    private boolean saveAndValidateConfiguration() {
        String host = hostView.getText().toString().trim();
        int port;
        long appLaunchDelay;
        long postLaunchDelay;
        long defaultActionDelay;
        String actionJson = actionJsonView.getText().toString().trim();
        final List<ActionItem> actions;
        try {
            port = Integer.parseInt(portView.getText().toString().trim());
            appLaunchDelay = parseNonNegativeLong(appLaunchDelayView, "App launch delay");
            postLaunchDelay = parseNonNegativeLong(postLaunchDelayView, "Post-launch delay");
            defaultActionDelay =
                    parseNonNegativeLong(defaultActionDelayView, "Default action delay");
            new AdbConnectionManager.Config(host, port,
                    ActionSequenceStore.DEFAULT_ADB_RETRY_COUNT,
                    ActionSequenceStore.DEFAULT_ADB_RETRY_DELAY_MS,
                    AdbConnectionManager.DEFAULT_CONNECTION_TIMEOUT_MS,
                    AdbConnectionManager.DEFAULT_COMMAND_TIMEOUT_MS);
            actions = ActionSequenceStore.deserialize(actionJson);
        } catch (IllegalArgumentException e) {
            showInvalidConfiguration(e);
            return false;
        } catch (org.json.JSONException e) {
            statusView.setText(getString(R.string.advanced_invalid_json, e.getMessage()));
            return false;
        }

        ActionSequenceStore sequenceStore = new ActionSequenceStore(this);
        final String savedJson;
        try {
            logSequenceDebug("Saving action sequence: actionCount=" + actions.size());
            savedJson = sequenceStore.save(actions);
        } catch (org.json.JSONException e) {
            // The JSON was already validated above, but do not persist partial configuration
            // if serialization fails unexpectedly.
            statusView.setText(getString(R.string.advanced_invalid_json, e.getMessage()));
            return false;
        }

        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putString(SettingsManagerConstants.ADB_HOST, host)
                .putInt(SettingsManagerConstants.ADB_PORT, port)
                .putBoolean(SettingsManagerConstants.ADVANCED_ACTIONS_ENABLED,
                        advancedEnabledView.isChecked())
                .putBoolean(SettingsManagerConstants.ACTION_TRIGGER_BOOT,
                        triggerBootView.isChecked())
                .putBoolean(SettingsManagerConstants.ACTION_TRIGGER_WAKE,
                        triggerWakeView.isChecked())
                .putLong(SettingsManagerConstants.APP_LAUNCH_DELAY_MS, appLaunchDelay)
                .putLong(SettingsManagerConstants.POST_LAUNCH_DELAY_MS, postLaunchDelay)
                .putLong(SettingsManagerConstants.DEFAULT_ACTION_DELAY_MS, defaultActionDelay)
                .putString(SettingsManagerConstants.KEY_INJECTION_METHOD, "ADB")
                .apply();
        logSequenceDebug("Saved " + SettingsManagerConstants.ADVANCED_ACTIONS_JSON
                + "=" + savedJson);
        logSequenceDebug("Read-back actionCount=" + sequenceStore.getActionSequence().size());
        // A running boot/wake flow owns an immutable snapshot. Saving any new
        // configuration cancels that flow so old and new settings are never mixed.
        PostLaunchActionScheduler.getInstance(this).cancel();
        DreamListenerService.updateRunningState(this);
        return true;
    }

    /**
     * Loads the persisted document through {@link ActionSequenceStore}. The screen currently
     * uses a JSON editor rather than a visual list editor, so it displays canonical JSON rebuilt
     * from the deserialized {@link ActionItem} list. Invalid stored JSON is deliberately kept in
     * the editor for recovery; it is never cleared as a side effect of reading.
     */
    private void loadSavedActionSequence(SharedPreferences preferences) {
        String rawJson = readString(preferences, SettingsManagerConstants.ADVANCED_ACTIONS_JSON, "");
        if (rawJson.trim().isEmpty()) {
            actionJsonView.setText(ActionSequenceStore.DEFAULT_DEMO_ACTIONS_JSON);
            logSequenceDebug("Read action sequence: actionCount=0 (no stored JSON; demo shown)");
            return;
        }

        try {
            // Validate first so corrupted JSON is not silently replaced in the editor.
            ActionSequenceStore.deserialize(rawJson);
            List<ActionItem> actions = new ActionSequenceStore(this).getActionSequence();
            if (actions.isEmpty()) {
                // Earlier versions and Restore defaults persist "[]". Treat it the same as a
                // missing sequence for the editor, while leaving the stored value untouched
                // until the user explicitly saves the displayed demo.
                actionJsonView.setText(ActionSequenceStore.DEFAULT_DEMO_ACTIONS_JSON);
                logSequenceDebug("Read action sequence: actionCount=0 (empty array; demo shown)");
                return;
            }
            actionJsonView.setText(ActionSequenceStore.serialize(actions));
            logSequenceDebug("Read action sequence: actionCount=" + actions.size());
        } catch (org.json.JSONException | IllegalArgumentException e) {
            actionJsonView.setText(rawJson);
            Log.w(TAG, "Stored action JSON is invalid; preserving it for user recovery", e);
        }
    }

    private static void logSequenceDebug(String message) {
        // The sequence contains only action types, Android key names, and delays; never log
        // ADB credentials, RSA material, or arbitrary user input here.
        Log.d(TAG, message);
    }

    private static long parseNonNegativeLong(EditText view, String name) {
        long value;
        try {
            value = Long.parseLong(view.getText().toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a number");
        }
        ActionItem.validateActionTime(value, name);
        return value;
    }

    private void postConnectionState(final AdbConnectionManager.State state,
                                     final int attempt, final int maximumAttempts) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (state == AdbConnectionManager.State.CONNECTING) {
                    statusView.setText(getString(R.string.adb_status_connecting,
                            attempt, maximumAttempts));
                } else if (state == AdbConnectionManager.State.AUTHORIZING) {
                    statusView.setText(getString(R.string.adb_status_authorizing,
                            attempt, maximumAttempts));
                } else if (state == AdbConnectionManager.State.CONNECTED) {
                    statusView.setText(R.string.adb_status_connected);
                }
            }
        });
    }

    private void postConnectionResult(final AdbConnectionTestController controller,
                                      final AdbConnectionManager.Result result) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                controller.close();
                if (connectionTestController != controller) {
                    return;
                }
                connectionTestController = null;
                connectionTestListener = null;
                if (result.isSuccessful()) {
                    statusView.setText(R.string.adb_status_connected);
                } else if (result.getError() == AdbConnectionManager.Error.CANCELLED) {
                    statusView.setText(R.string.adb_status_cancelled);
                } else {
                    statusView.setText(getString(R.string.adb_status_failed,
                            result.getMessage()));
                }
                setBusy(false);
            }
        });
    }

    private void stopCurrentOperation() {
        if (connectionTestController != null) {
            connectionTestController.close();
            connectionTestController = null;
        }
        connectionTestListener = null;
        if (actionExecutor != null) {
            actionExecutor.cancel();
            actionExecutor.close();
            actionExecutor = null;
        }
        if (adbKeyInjector != null) {
            adbKeyInjector.cancel();
            adbKeyInjector.close();
            adbKeyInjector = null;
        }
        setBusy(false);
    }

    private void setBusy(boolean busy) {
        testButton.setEnabled(!busy);
        saveButton.setEnabled(!busy);
        runButton.setEnabled(!busy);
        stopButton.setEnabled(busy);
        hostView.setEnabled(!busy);
        portView.setEnabled(!busy);
        appLaunchDelayView.setEnabled(!busy);
        postLaunchDelayView.setEnabled(!busy);
        defaultActionDelayView.setEnabled(!busy);
        actionJsonView.setEnabled(!busy);
        advancedEnabledView.setEnabled(!busy);
        triggerBootView.setEnabled(!busy);
        triggerWakeView.setEnabled(!busy);
    }

    private void showInvalidConfiguration(IllegalArgumentException error) {
        statusView.setText(getString(R.string.adb_invalid_configuration,
                error.getMessage()));
        setBusy(false);
    }

    @Override
    protected void onDestroy() {
        stopCurrentOperation();
        super.onDestroy();
    }

    private static String readString(SharedPreferences preferences, String key,
                                     String defaultValue) {
        try {
            return preferences.getString(key, defaultValue);
        } catch (ClassCastException e) {
            Object value = preferences.getAll().get(key);
            return value == null ? defaultValue : String.valueOf(value);
        }
    }

    private static int readInt(SharedPreferences preferences, String key, int defaultValue) {
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

    private static long readLong(SharedPreferences preferences, String key, long defaultValue) {
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

    private static boolean readBoolean(SharedPreferences preferences, String key,
                                       boolean defaultValue) {
        try {
            return preferences.getBoolean(key, defaultValue);
        } catch (ClassCastException e) {
            Object value = preferences.getAll().get(key);
            return value == null ? defaultValue
                    : Boolean.parseBoolean(String.valueOf(value));
        }
    }
}
