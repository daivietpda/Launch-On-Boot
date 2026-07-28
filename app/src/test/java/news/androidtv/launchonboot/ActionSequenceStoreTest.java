package news.androidtv.launchonboot;

import org.json.JSONException;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ActionSequenceStoreTest {
    @Test
    public void defaultDemoHasWaitThenNumberOneWithRequestedDelay() throws Exception {
        List<ActionItem> actions =
                ActionSequenceStore.deserialize(ActionSequenceStore.DEFAULT_DEMO_ACTIONS_JSON);
        assertEquals(2, actions.size());
        assertEquals(ActionItem.Type.WAIT, actions.get(0).getType());
        assertEquals(1000L, actions.get(0).getDurationMs());
        assertEquals("KEYCODE_1", actions.get(1).getKeyCode());
        assertEquals(300L, actions.get(1).getDelayAfterMs());
        assertEquals(1, actions.get(1).getRepeat());
    }

    @Test
    public void textActionRoundTripsWithoutChangingOlderFormat() throws Exception {
        List<ActionItem> actions = Arrays.asList(ActionItem.key("KEYCODE_1", 200, 1),
                ActionItem.text("105", 500, 1), ActionItem.waitFor(1000));
        assertEquals(actions, ActionSequenceStore.deserialize(ActionSequenceStore.serialize(actions)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void emptyTextIsRejected() {
        ActionItem.text("   ", 0, 1);
    }
    @Test
    public void serializeAndDeserialize_preservesDocumentedFormat() throws JSONException {
        List<ActionItem> actions = Arrays.asList(
                ActionItem.waitFor(10000),
                ActionItem.key("KEYCODE_DPAD_DOWN", 500, 1),
                ActionItem.key("KEYCODE_1", 300, 2),
                ActionItem.key("KEYCODE_DPAD_CENTER", 0, 1)
        );

        String json = ActionSequenceStore.serialize(actions);
        List<ActionItem> restored = ActionSequenceStore.deserialize(json);

        assertEquals(actions, restored);
        assertTrue(json.contains("\"type\":\"WAIT\""));
        assertTrue(json.contains("\"durationMs\":10000"));
        assertTrue(json.contains("\"keyCode\":\"KEYCODE_DPAD_DOWN\""));
    }

    @Test
    public void deserialize_keyActionWithoutOptionalFields_usesDefaults() throws JSONException {
        List<ActionItem> actions = ActionSequenceStore.deserialize(
                "[{\"type\":\"KEY\",\"keyCode\":\"KEYCODE_5\"}]"
        );

        assertEquals(1, actions.size());
        assertEquals("KEYCODE_5", actions.get(0).getKeyCode());
        assertEquals(ActionItem.DEFAULT_DELAY_AFTER_MS, actions.get(0).getDelayAfterMs());
        assertEquals(ActionItem.DEFAULT_REPEAT, actions.get(0).getRepeat());
    }

    @Test
    public void deserialize_invalidAction_throwsJSONException() {
        try {
            ActionSequenceStore.deserialize("[{\"type\":\"KEY\",\"repeat\":0}]");
            fail("Expected JSONException");
        } catch (JSONException expected) {
            assertTrue(expected.getMessage().contains("keyCode"));
        }
    }

    @Test
    public void keyAction_acceptsSupportedAliasesAndStoresCanonicalAndroidName() {
        ActionItem action = ActionItem.key("DPAD_UP", 0, 1);

        assertEquals("KEYCODE_DPAD_UP", action.getKeyCode());
        assertTrue(ActionItem.getAllowedKeyCodes().contains("KEYCODE_DPAD_UP"));
        assertTrue(ActionItem.getAllowedKeyCodes().contains("KEYCODE_9"));
        assertTrue(ActionItem.getAllowedKeyCodes().contains("KEYCODE_MENU"));
    }

    @Test
    public void keyAction_rejectsUnsupportedKeyCode() {
        try {
            ActionItem.key("KEYCODE_VOLUME_UP", 0, 1);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("Unsupported keyCode"));
        }
    }

    @Test
    public void actionItem_rejectsNegativeTimesAndInvalidRepeat() {
        assertIllegalArgument(new Runnable() {
            @Override
            public void run() {
                ActionItem.waitFor(-1);
            }
        });
        assertIllegalArgument(new Runnable() {
            @Override
            public void run() {
                ActionItem.key("KEYCODE_1", -1, 1);
            }
        });
        assertIllegalArgument(new Runnable() {
            @Override
            public void run() {
                ActionItem.key("KEYCODE_1", 0, 0);
            }
        });
        assertIllegalArgument(new Runnable() {
            @Override
            public void run() {
                ActionItem.waitFor(ActionItem.MAX_ACTION_TIME_MS + 1);
            }
        });
        assertIllegalArgument(new Runnable() {
            @Override
            public void run() {
                ActionItem.key("KEYCODE_1", 0, ActionItem.MAX_REPEAT + 1);
            }
        });
    }

    @Test
    public void serialize_rejectsUnboundedSequence() {
        List<ActionItem> actions = Collections.nCopies(
                ActionSequenceStore.MAX_ACTION_COUNT + 1,
                ActionItem.key("KEYCODE_1", 0, 1));

        assertIllegalArgument(new Runnable() {
            @Override
            public void run() {
                try {
                    ActionSequenceStore.serialize(actions);
                } catch (JSONException e) {
                    throw new AssertionError(e);
                }
            }
        });
    }

    @Test
    public void deserializeSafely_invalidOrOldJson_returnsEmptyList() {
        assertTrue(ActionSequenceStore.deserializeSafely("not-json").isEmpty());
        assertTrue(ActionSequenceStore.deserializeSafely(
                "[{\"type\":\"KEY\",\"keyCode\":\"KEYCODE_VOLUME_UP\"}]"
        ).isEmpty());
    }

    @Test
    public void deserialize_rejectsNumericFieldsStoredAsStrings() {
        try {
            ActionSequenceStore.deserialize("[{\"type\":\"WAIT\",\"durationMs\":\"1000\"}]");
            fail("Expected JSONException");
        } catch (JSONException expected) {
            assertTrue(expected.getMessage().contains("durationMs"));
        }
    }

    private void assertIllegalArgument(Runnable action) {
        try {
            action.run();
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
