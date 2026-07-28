package news.androidtv.launchonboot;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AdbLaunchOutputParserTest {
    @Test public void confirmsOnlyStatusOkWithoutErrors() {
        assertTrue(AdbLaunchOutputParser.isConfirmed("Starting: Intent {}\nStatus: ok\n"));
        assertFalse(AdbLaunchOutputParser.isConfirmed("Status: ok\nError: Activity class does not exist"));
        assertFalse(AdbLaunchOutputParser.isConfirmed("SecurityException: Permission Denial"));
        assertFalse(AdbLaunchOutputParser.isConfirmed(""));
        assertFalse(AdbLaunchOutputParser.isConfirmed(null));
    }
}
