package news.androidtv.launchonboot;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ActionSequenceFileManagerTest {
    @Test
    public void normalizesFileNamesWithoutCreatingPathsOrDuplicateExtensions() {
        assertEquals("tv360.json", ActionSequenceFileManager.normalizeFileName(" tv360 "));
        assertEquals("VTVGO.json", ActionSequenceFileManager.normalizeFileName("VTVGO.JSON"));
        assertEquals(".._tv360.json", ActionSequenceFileManager.normalizeFileName("../tv360.json"));
        assertEquals("bad_name.json", ActionSequenceFileManager.normalizeFileName("bad/name"));
    }

    @Test
    public void rejectsEmptyFileName() {
        try {
            ActionSequenceFileManager.normalizeFileName(" .json ");
            fail("Expected invalid file name");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("File name"));
        }
    }

    @Test
    public void exportsPrettyJsonAndImportsUnicodeTextWithoutChangingIt() throws Exception {
        List<ActionItem> actions = Arrays.asList(ActionItem.waitFor(1000),
                ActionItem.key("KEYCODE_1", 300, 1),
                ActionItem.text("Kênh \"1\"\n😀", 500, 1));
        String json = ActionSequenceFileManager.serializeForExport(actions);

        assertTrue(json.contains("\n  {\n    \"type\""));
        assertEquals(actions, ActionSequenceFileManager.parseImportJson("\ufeff" + json));
    }

    @Test
    public void emptySequenceRoundTrips() throws Exception {
        String json = ActionSequenceFileManager.serializeForExport(Collections.<ActionItem>emptyList());
        assertEquals("[]", json);
        assertTrue(ActionSequenceFileManager.parseImportJson(json).isEmpty());
    }

    @Test
    public void rejectsNonArrayAndInvalidActionFields() throws Exception {
        assertInvalid("{\"type\":\"WAIT\"}");
        assertInvalid("[{\"type\":\"WAIT\",\"durationMs\":\"1000\"}]");
        assertInvalid("[{\"type\":\"KEY\",\"keyCode\":\"KEYCODE_VOLUME_UP\"}]");
        assertInvalid("[{\"type\":\"TEXT\",\"text\":7}]");
        assertInvalid("[{\"type\":\"LONG_PRESS\"}]");
    }

    private static void assertInvalid(String json) throws Exception {
        try {
            ActionSequenceFileManager.parseImportJson(json);
            fail("Expected invalid JSON action");
        } catch (org.json.JSONException expected) {
            // Expected.
        }
    }
}
