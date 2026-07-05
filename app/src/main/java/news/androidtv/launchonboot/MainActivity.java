package news.androidtv.launchonboot;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.provider.Settings;
import android.text.TextUtils;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;

import androidx.core.content.ContextCompat;

import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Toast;

import com.felkertech.settingsmanager.SettingsManager;

import java.util.List;

import static android.view.View.GONE;
import static news.androidtv.launchonboot.SettingsManagerConstants.ONBOARDING;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = MainActivity.class.getSimpleName();
    private static final boolean DEBUG = true;

    private SettingsManager mSettingsManager;
    private Switch mSwitchEnabled;
    private Switch mSwitchLiveChannels;
    private Switch mSwitchWakeup;
    private Button mButtonSelectApp;
    private TextView mPackageName;
    private EditText mEditDelay;
    private BootReceiver mBootReceiver = new BootReceiver();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mSettingsManager = new SettingsManager(this);
        if (!mSettingsManager.getBoolean(ONBOARDING)) {
            startActivity(new Intent(this, OnboardingActivity.class));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mSwitchEnabled = ((Switch) findViewById(R.id.switch_enable));
        mSwitchLiveChannels = ((Switch) findViewById(R.id.switch_live_channels));
        mSwitchWakeup = ((Switch) findViewById(R.id.switch_wakeup));
        mButtonSelectApp = (Button) findViewById(R.id.button_select_app);
        mPackageName = ((TextView) findViewById(R.id.text_package_name));
        mEditDelay = (EditText) findViewById(R.id.edit_delay);

        if (mSwitchEnabled != null) {
            mSwitchEnabled.setChecked(
                    mSettingsManager.getBoolean(SettingsManagerConstants.BOOT_APP_ENABLED));
        }
        if (mSwitchLiveChannels != null) {
            mSwitchLiveChannels.setChecked(
                    mSettingsManager.getBoolean(SettingsManagerConstants.LAUNCH_LIVE_CHANNELS));
        }
        if (mSwitchWakeup != null) {
            mSwitchWakeup.setChecked(
                    mSettingsManager.getBoolean(SettingsManagerConstants.ON_WAKEUP));
        }
        if (mPackageName != null) {
            mPackageName
                    .setText(mSettingsManager.getString(SettingsManagerConstants.LAUNCH_ACTIVITY));
        }
        String delayValue = mSettingsManager.getString(SettingsManagerConstants.LAUNCH_DELAY);
        if (mEditDelay != null) {
            mEditDelay.setText(TextUtils.isEmpty(delayValue) ? "0" : delayValue);
        }
        updateSelectionView();

        if (mSwitchEnabled != null) {
            mSwitchEnabled.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    mSettingsManager.setBoolean(
                            SettingsManagerConstants.BOOT_APP_ENABLED, isChecked);
                    updateSelectionView();
                }
            });
        }
        if (mSwitchLiveChannels != null) {
            mSwitchLiveChannels.setOnCheckedChangeListener
                    (new CompoundButton.OnCheckedChangeListener() {
                        @Override
                        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                            mSettingsManager.setBoolean(
                                    SettingsManagerConstants.LAUNCH_LIVE_CHANNELS, isChecked);
                            updateSelectionView();
                        }
                    });
        }
        if (mSwitchWakeup != null) {
            mSwitchWakeup.setOnCheckedChangeListener
                    (new CompoundButton.OnCheckedChangeListener() {
                        @Override
                        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                            mSettingsManager.setBoolean(
                                    SettingsManagerConstants.ON_WAKEUP, isChecked);
                            updateSelectionView();
                            if (isChecked) {
                                startForegroundService();
                            }
                        }
                    });
        }

        if (mEditDelay != null) {
            mEditDelay.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    mSettingsManager.setString(SettingsManagerConstants.LAUNCH_DELAY, s.toString());
                }
            });
        }

        if (findViewById(R.id.button_test) != null) {
            if (!getResources().getBoolean(R.bool.DEBUG_FLAG_TEST_BUTTON)) {
                findViewById(R.id.button_test).setVisibility(GONE);
            }
            findViewById(R.id.button_test).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent i = new Intent(MainActivity.this, BootReceiver.class);
                    sendBroadcast(i);
                }
            });
            findViewById(R.id.button_test).setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    v.setBackgroundColor(hasFocus ? getResources().getColor(R.color.colorAccent) :
                            getResources().getColor(R.color.colorPrimaryDark));
                }
            });
        }

        if (mButtonSelectApp != null) {
            mButtonSelectApp.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    new AlertDialog.Builder(new ContextThemeWrapper(MainActivity.this, android.R.style.Theme_Material_Light_Dialog))
                            .setTitle(R.string.dialog_select_app_title)
                            .setItems(getAppNames(getLauncherApps()), new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    String packageName = getPackageName(getLauncherApps().get(which));
                                    mSettingsManager.setString(SettingsManagerConstants.LAUNCH_ACTIVITY,
                                            packageName);
                                    if (mPackageName != null) {
                                        mPackageName.setText(packageName);
                                    }
                                }
                            })
                            .show();
                }
            });
            mButtonSelectApp.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    v.setBackgroundColor(hasFocus ? getResources().getColor(R.color.colorAccent) :
                            getResources().getColor(R.color.colorPrimaryDark));
                }
            });
        }

        if (DEBUG) {
            Log.d(TAG, getLauncherApps().toString());
            getAppNames(getLauncherApps());
        }

        if (mSettingsManager.getBoolean(SettingsManagerConstants.ON_WAKEUP)) {
            startForegroundService();
        }

        View.OnClickListener accessibilityListener = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Settings.ACTION_SETTINGS);
                startActivity(intent);
            }
        };

        if (findViewById(R.id.text_go_to_accessibility) != null) {
            findViewById(R.id.text_go_to_accessibility).setOnClickListener(accessibilityListener);
        }
        if (findViewById(R.id.button_go_to_accessibility) != null) {
            findViewById(R.id.button_go_to_accessibility).setOnClickListener(accessibilityListener);
            findViewById(R.id.button_go_to_accessibility).setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    v.setBackgroundColor(hasFocus ? getResources().getColor(R.color.colorAccent) :
                            getResources().getColor(R.color.colorPrimaryDark));
                }
            });
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerReceiver(mBootReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
    }

    @Override
    protected void onStop() {
        super.onStop();
        try {
            unregisterReceiver(mBootReceiver);
        } catch (IllegalArgumentException e) {
            // Receiver not registered
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    public List<ResolveInfo> getLauncherApps() {
        final Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        // Change which category is used based on form factor.
        if (getResources().getBoolean(R.bool.IS_TV)) {
            mainIntent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        } else {
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        }
        return getPackageManager().queryIntentActivities(mainIntent, 0);
    }

    public String[] getAppNames(List<ResolveInfo> leanbackApps) {
        String[] appNames = new String[leanbackApps.size()];
        for (int i = 0; i < leanbackApps.size(); i++) {
            ResolveInfo info = leanbackApps.get(i);
            appNames[i] = info.loadLabel(this.getPackageManager()).toString();
            Log.d(TAG, info.loadLabel(this.getPackageManager()).toString());
            Log.d(TAG, info.activityInfo.toString());
            Log.d(TAG, info.activityInfo.name);
        }
        return appNames;
    }

    public String getPackageName(ResolveInfo resolveInfo) {
        return resolveInfo.activityInfo.packageName;
    }

    private void updateSelectionView() {
        if (mSwitchEnabled == null) {
            return;
        }
        if (mSwitchEnabled.isChecked()) {
            if (mSwitchLiveChannels != null) {
                mSwitchLiveChannels.setEnabled(true);
            }
            if (findViewById(R.id.button_test) != null) {
                findViewById(R.id.button_test).setEnabled(true);
            }
            if (mSwitchLiveChannels != null && mSwitchLiveChannels.isChecked()) {
                if (mButtonSelectApp != null) {
                    mButtonSelectApp.setVisibility(GONE);
                }
                if (mPackageName != null) {
                    mPackageName.setVisibility(GONE);
                }
            } else {
                if (mButtonSelectApp != null) {
                    mButtonSelectApp.setVisibility(View.VISIBLE);
                }
                if (mPackageName != null) {
                    mPackageName.setVisibility(View.VISIBLE);
                }
            }
            if (findViewById(R.id.delay_container) != null) {
                findViewById(R.id.delay_container).setVisibility(View.VISIBLE);
            }
        } else {
            if (mButtonSelectApp != null) {
                mButtonSelectApp.setVisibility(GONE);
            }
            if (mPackageName != null) {
                mPackageName.setVisibility(GONE);
            }
            if (findViewById(R.id.delay_container) != null) {
                findViewById(R.id.delay_container).setVisibility(GONE);
            }
            if (mSwitchLiveChannels != null) {
                mSwitchLiveChannels.setEnabled(false);
            }
            if (findViewById(R.id.button_test) != null) {
                findViewById(R.id.button_test).setEnabled(false);
            }
        }
    }

    private void startForegroundService() {
        // Ideally only starts once :thinking-emoji:
        Intent i = new Intent(MainActivity.this, DreamListenerService.class);
        ContextCompat.startForegroundService(this, i);
    }
}
