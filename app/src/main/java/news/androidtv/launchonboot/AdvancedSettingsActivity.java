package news.androidtv.launchonboot;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Build;
import android.app.role.RoleManager;
import android.provider.Settings;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.List;

/** General Advanced Actions configuration. Sequence editing lives in its own Activity. */
public final class AdvancedSettingsActivity extends AppCompatActivity {
    private EditText hostView, portView, appLaunchDelayView, postLaunchDelayView, defaultActionDelayView;
    private Spinner targetLaunchMethodView;
    private CheckBox advancedEnabledView, triggerBootView, triggerWakeView, restartTargetOnWakeView;
    private TextView statusView, sequenceSummaryView;
    private AdbConnectionTestController connectionTestController;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_advanced_settings);
        requestNotificationPermissionIfNeeded();
        hostView = findViewById(R.id.edit_adb_host); portView = findViewById(R.id.edit_adb_port);
        appLaunchDelayView = findViewById(R.id.edit_app_launch_delay);
        postLaunchDelayView = findViewById(R.id.edit_post_launch_delay);
        defaultActionDelayView = findViewById(R.id.edit_default_action_delay);
        targetLaunchMethodView = findViewById(R.id.spinner_target_launch_method);
        ArrayAdapter<String> launchMethodAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new String[] {
                getString(R.string.launch_method_auto), getString(R.string.launch_method_android),
                getString(R.string.launch_method_adb)});
        launchMethodAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        targetLaunchMethodView.setAdapter(launchMethodAdapter);
        advancedEnabledView = findViewById(R.id.check_advanced_enabled);
        triggerBootView = findViewById(R.id.check_trigger_boot);
        triggerWakeView = findViewById(R.id.check_trigger_wake);
        restartTargetOnWakeView = findViewById(R.id.check_restart_target_on_wake);
        statusView = findViewById(R.id.text_adb_status);
        sequenceSummaryView = findViewById(R.id.text_action_sequence_summary);
        loadConfiguration();
        try {
            new ActionSequenceStore(this).ensureInitialDemo();
        } catch (org.json.JSONException e) {
            statusView.setText(getString(R.string.advanced_invalid_json, e.getMessage()));
        }
        findViewById(R.id.button_edit_action_sequence).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(AdvancedSettingsActivity.this, ActionSequenceEditorActivity.class));
            }
        });
        findViewById(R.id.button_save_advanced_configuration).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { saveConfiguration(); }
        });
        findViewById(R.id.button_test_adb_connection).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { testConnection(); }
        });
        findViewById(R.id.button_test_target_launch).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { testTargetLaunch(); }
        });
        findViewById(R.id.button_request_home_role).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestHomeRole(); }
        });
        findViewById(R.id.button_open_home_settings).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { openHomeSettings(); }
        });
    }

    @Override protected void onResume() { super.onResume(); updateSequenceSummary(); }

    private void loadConfiguration() {
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(this);
        hostView.setText(stringValue(p, SettingsManagerConstants.ADB_HOST, ActionSequenceStore.DEFAULT_ADB_HOST));
        portView.setText(String.valueOf(intValue(p, SettingsManagerConstants.ADB_PORT, ActionSequenceStore.DEFAULT_ADB_PORT)));
        appLaunchDelayView.setText(String.valueOf(longValue(p, SettingsManagerConstants.APP_LAUNCH_DELAY_MS, ActionSequenceStore.DEFAULT_APP_LAUNCH_DELAY_MS)));
        postLaunchDelayView.setText(String.valueOf(longValue(p, SettingsManagerConstants.POST_LAUNCH_DELAY_MS, ActionSequenceStore.DEFAULT_POST_LAUNCH_DELAY_MS)));
        defaultActionDelayView.setText(String.valueOf(longValue(p, SettingsManagerConstants.DEFAULT_ACTION_DELAY_MS, ActionSequenceStore.DEFAULT_ACTION_DELAY_MS)));
        advancedEnabledView.setChecked(booleanValue(p, SettingsManagerConstants.ADVANCED_ACTIONS_ENABLED, false));
        triggerBootView.setChecked(booleanValue(p, SettingsManagerConstants.ACTION_TRIGGER_BOOT, true));
        triggerWakeView.setChecked(booleanValue(p, SettingsManagerConstants.ACTION_TRIGGER_WAKE, true));
        restartTargetOnWakeView.setChecked(booleanValue(p,
                SettingsManagerConstants.RESTART_TARGET_ON_WAKE,
                ActionSequenceStore.DEFAULT_RESTART_TARGET_ON_WAKE));
        targetLaunchMethodView.setSelection(launchMethodPosition(stringValue(p,
                SettingsManagerConstants.TARGET_APP_LAUNCH_METHOD,
                ActionSequenceStore.DEFAULT_TARGET_APP_LAUNCH_METHOD)));
    }

    private boolean saveConfiguration() {
        try {
            String host = hostView.getText().toString().trim();
            int port = Integer.parseInt(portView.getText().toString().trim());
            long launch = nonNegative(appLaunchDelayView), post = nonNegative(postLaunchDelayView), delay = nonNegative(defaultActionDelayView);
            new AdbConnectionManager.Config(host, port, ActionSequenceStore.DEFAULT_ADB_RETRY_COUNT,
                    ActionSequenceStore.DEFAULT_ADB_RETRY_DELAY_MS, AdbConnectionManager.DEFAULT_CONNECTION_TIMEOUT_MS,
                    AdbConnectionManager.DEFAULT_COMMAND_TIMEOUT_MS);
            PreferenceManager.getDefaultSharedPreferences(this).edit().putString(SettingsManagerConstants.ADB_HOST, host)
                    .putInt(SettingsManagerConstants.ADB_PORT, port).putLong(SettingsManagerConstants.APP_LAUNCH_DELAY_MS, launch)
                    .putLong(SettingsManagerConstants.POST_LAUNCH_DELAY_MS, post).putLong(SettingsManagerConstants.DEFAULT_ACTION_DELAY_MS, delay)
                    .putBoolean(SettingsManagerConstants.ADVANCED_ACTIONS_ENABLED, advancedEnabledView.isChecked())
                    .putBoolean(SettingsManagerConstants.ACTION_TRIGGER_BOOT, triggerBootView.isChecked())
                    .putBoolean(SettingsManagerConstants.ACTION_TRIGGER_WAKE, triggerWakeView.isChecked())
                    .putBoolean(SettingsManagerConstants.RESTART_TARGET_ON_WAKE,
                            restartTargetOnWakeView.isChecked())
                    .putString(SettingsManagerConstants.TARGET_APP_LAUNCH_METHOD,
                            launchMethodValue())
                    .putString(SettingsManagerConstants.KEY_INJECTION_METHOD, "ADB").apply();
            PostLaunchActionScheduler.getInstance(this).cancel();
            DreamListenerService.updateRunningState(this);
            statusView.setText(R.string.advanced_configuration_saved);
            return true;
        } catch (IllegalArgumentException e) { statusView.setText(getString(R.string.adb_invalid_configuration, e.getMessage())); return false; }
    }

    private void testConnection() {
        if (!saveConfiguration()) return;
        if (connectionTestController != null) connectionTestController.close();
        final AdbConnectionTestController controller = new AdbConnectionTestController(new AdbConnectionManager(this));
        connectionTestController = controller;
        statusView.setText(R.string.adb_status_connecting);
        controller.start(new AdbConnectionTestController.Listener() {
            @Override public void onStateChanged(AdbConnectionManager.State state, int attempt, int maximum) {
                runOnUiThread(new Runnable() { @Override public void run() { statusView.setText(state == AdbConnectionManager.State.AUTHORIZING ? R.string.adb_status_authorizing : R.string.adb_status_connecting); } });
            }
            @Override public void onFinished(final AdbConnectionManager.Result result) { runOnUiThread(new Runnable() { @Override public void run() { if (connectionTestController == controller) connectionTestController = null; controller.close(); if (result.isSuccessful()) { statusView.setText(R.string.adb_status_connected); } else { statusView.setText(getString(R.string.adb_status_failed, result.getMessage())); } } }); }
        });
    }

    private void testTargetLaunch() {
        if (!saveConfiguration()) return;
        statusView.setText(R.string.target_launch_testing);
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        final TargetAppLauncher.Target target = new TargetAppLauncher.Target(
                booleanValue(preferences, SettingsManagerConstants.LAUNCH_LIVE_CHANNELS, false),
                stringValue(preferences, SettingsManagerConstants.LAUNCH_ACTIVITY, ""));
        final TargetAppLauncher.Method method = TargetAppLauncher.Method.valueOf(launchMethodValue());
        final boolean advancedUsesAdb = advancedEnabledView.isChecked();
        new Thread(new Runnable() {
            @Override public void run() {
                final TargetAppLauncher.LaunchResult result = new TargetAppLaunchCoordinator(
                        getApplicationContext()).launch(target, method,
                        advancedUsesAdb);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (result.isConfirmed()) {
                            statusView.setText(R.string.target_launch_confirmed);
                        } else {
                            statusView.setText(getString(R.string.target_launch_failed,
                                    result.message));
                        }
                    }
                });
            }
        }, "TargetLaunchTest").start();
    }

    private String launchMethodValue() {
        switch (targetLaunchMethodView.getSelectedItemPosition()) {
            case 1: return TargetAppLauncher.Method.ANDROID.name();
            case 2: return TargetAppLauncher.Method.ADB.name();
            default: return TargetAppLauncher.Method.AUTO.name();
        }
    }

    private static int launchMethodPosition(String value) {
        if (TargetAppLauncher.Method.ANDROID.name().equalsIgnoreCase(value)) return 1;
        if (TargetAppLauncher.Method.ADB.name().equalsIgnoreCase(value)) return 2;
        return 0;
    }

    private void requestHomeRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager roles = getSystemService(RoleManager.class);
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME)) {
                if (roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                    statusView.setText(R.string.home_role_active);
                } else {
                    startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_HOME), 6001);
                }
                return;
            }
        }
        openHomeSettings();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { android.Manifest.permission.POST_NOTIFICATIONS }, 6002);
        }
    }

    private void openHomeSettings() {
        try { startActivity(new Intent(Settings.ACTION_HOME_SETTINGS)); }
        catch (android.content.ActivityNotFoundException e) {
            statusView.setText(R.string.home_settings_unavailable);
        }
    }

    private void updateSequenceSummary() {
        List<ActionItem> actions = new ActionSequenceStore(this).getActionSequence();
        if (actions.isEmpty()) { sequenceSummaryView.setText(R.string.action_sequence_empty); return; }
        StringBuilder preview = new StringBuilder();
        for (int i = 0; i < actions.size() && i < 4; i++) { if (i > 0) preview.append(" → "); preview.append(ActionSequenceEditorActivity.displayName(this, actions.get(i))); }
        sequenceSummaryView.setText(getString(R.string.action_sequence_summary, actions.size(), preview));
    }
    private static long nonNegative(EditText v) { long x = Long.parseLong(v.getText().toString().trim()); ActionItem.validateActionTime(x, "time"); return x; }
    private static String stringValue(SharedPreferences p, String k, String d) { try { return p.getString(k,d); } catch (ClassCastException e) { return d; } }
    private static int intValue(SharedPreferences p, String k, int d) { try { return p.getInt(k,d); } catch (ClassCastException e) { return d; } }
    private static long longValue(SharedPreferences p, String k, long d) { try { return p.getLong(k,d); } catch (ClassCastException e) { return d; } }
    private static boolean booleanValue(SharedPreferences p, String k, boolean d) { try { return p.getBoolean(k,d); } catch (ClassCastException e) { return d; } }
    @Override protected void onDestroy() { if (connectionTestController != null) connectionTestController.close(); super.onDestroy(); }
}
