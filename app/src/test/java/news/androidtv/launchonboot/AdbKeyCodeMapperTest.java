package news.androidtv.launchonboot;

import android.view.KeyEvent;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AdbKeyCodeMapperTest {
    @Test
    public void allActionKeyCodes_areAllowedAndMappedCanonically() {
        for (ActionItem.KeyCode keyCode : ActionItem.KeyCode.values()) {
            assertTrue(AdbKeyCodeMapper.isAllowed(keyCode.getKeyEventCode()));
            assertEquals(keyCode.getAndroidKeyCode(),
                    AdbKeyCodeMapper.toAdbKeyCode(keyCode.getKeyEventCode()));
        }
    }

    @Test
    public void unsupportedKeyCode_isRejected() {
        assertFalse(AdbKeyCodeMapper.isAllowed(KeyEvent.KEYCODE_VOLUME_UP));
        try {
            AdbKeyCodeMapper.toAdbKeyCode(KeyEvent.KEYCODE_VOLUME_UP);
            fail("Expected unsupported keyCode to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Unsupported"));
        }
    }

    @Test
    public void shellCommand_isBuiltOnlyFromFixedMapping() {
        assertEquals("input keyevent KEYCODE_DPAD_LEFT; echo __LOB_KEY_EXIT__$?",
                AdbKeyCodeMapper.buildShellCommand(KeyEvent.KEYCODE_DPAD_LEFT));
    }
}
