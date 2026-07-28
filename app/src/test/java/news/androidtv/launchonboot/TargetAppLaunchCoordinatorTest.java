package news.androidtv.launchonboot;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TargetAppLaunchCoordinatorTest {
    @Test public void autoUsesAdbWhenOverlayIsUnavailable() {
        assertEquals(TargetAppLauncher.Method.ADB, TargetAppLaunchCoordinator.choose(
                TargetAppLauncher.Method.AUTO, false, false));
    }

    @Test public void autoUsesAndroidWhenOverlayIsGranted() {
        assertEquals(TargetAppLauncher.Method.ANDROID, TargetAppLaunchCoordinator.choose(
                TargetAppLauncher.Method.AUTO, false, true));
    }

    @Test public void advancedAdbNeverFallsBackToAndroid() {
        assertEquals(TargetAppLauncher.Method.ADB, TargetAppLaunchCoordinator.choose(
                TargetAppLauncher.Method.ANDROID, true, true));
    }
}
