package news.androidtv.launchonboot;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ActionSequenceBackupStoreTest {
    @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void savesListsReadsAndDeletesMixedSequence() throws Exception {
        ActionSequenceBackupStore store = new ActionSequenceBackupStore(
                temporaryFolder.newFolder("backups"));
        List<ActionItem> actions = Arrays.asList(ActionItem.waitFor(1000),
                ActionItem.key("KEYCODE_1", 300, 1),
                ActionItem.text("Kênh 😀", 500, 1));

        assertEquals("tv360.json", store.save("tv360", actions));
        assertTrue(store.exists("tv360.json"));
        assertEquals(actions, store.read("tv360.json"));
        assertEquals(1, store.list().size());
        assertEquals("tv360.json", store.list().get(0).getFileName());
        assertTrue(store.delete("tv360.json"));
        assertFalse(store.exists("tv360.json"));
    }

    @Test
    public void overwriteReplacesBackupAndEmptySequenceRemainsEmpty() throws Exception {
        ActionSequenceBackupStore store = new ActionSequenceBackupStore(
                temporaryFolder.newFolder("overwrite"));
        store.save("channel", Collections.singletonList(ActionItem.waitFor(1000)));
        store.save("channel.json", Collections.<ActionItem>emptyList());

        assertTrue(store.read("channel").isEmpty());
        assertEquals(1, store.list().size());
    }

    @Test
    public void normalizedNameCannotEscapeBackupDirectory() throws Exception {
        ActionSequenceBackupStore store = new ActionSequenceBackupStore(
                temporaryFolder.newFolder("safe"));

        assertEquals(".._outside.json", store.save("../outside", Collections.<ActionItem>emptyList()));
        assertTrue(store.exists(".._outside.json"));
    }
}
