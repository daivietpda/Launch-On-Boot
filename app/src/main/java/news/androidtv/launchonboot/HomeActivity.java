package news.androidtv.launchonboot;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

/** Minimal HOME entry point. It never assumes the role was granted. */
public final class HomeActivity extends Activity {
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView message = new TextView(this);
        message.setGravity(Gravity.CENTER);
        message.setTextSize(20);
        setContentView(message);
        if (!isHomeRoleActive()) {
            message.setText(R.string.home_role_not_active);
            return;
        }
        message.setText(R.string.home_launching_target);
        PostLaunchActionScheduler.getInstance(getApplicationContext()).schedule(
                PostLaunchActionScheduler.Trigger.BOOT, null);
    }

    private boolean isHomeRoleActive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            RoleManager roles = getSystemService(RoleManager.class);
            return roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME)
                    && roles.isRoleHeld(RoleManager.ROLE_HOME);
        }
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ComponentName component = home.resolveActivity(getPackageManager());
        return component != null && getPackageName().equals(component.getPackageName());
    }
}
