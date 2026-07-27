package news.androidtv.launchonboot;

import android.content.Context;
import android.util.Base64;

import com.tananaev.adblib.AdbBase64;
import com.tananaev.adblib.AdbCrypto;

import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;

/**
 * Owns the app-specific ADB RSA identity.
 *
 * <p>Keys are stored below {@link Context#getNoBackupFilesDir()}, which is
 * private to the application sandbox and excluded from Android backup.</p>
 */
final class AdbKeyStore {
    private static final String KEY_DIRECTORY = "adb";
    private static final String PRIVATE_KEY_FILE = "adbkey";
    private static final String PUBLIC_KEY_FILE = "adbkey.pub";

    private static final AdbBase64 BASE_64 = new AdbBase64() {
        @Override
        public String encodeToString(byte[] data) {
            return Base64.encodeToString(data, Base64.NO_WRAP);
        }
    };

    private final File keyDirectory;

    AdbKeyStore(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        keyDirectory = new File(context.getApplicationContext().getNoBackupFilesDir(),
                KEY_DIRECTORY);
    }

    synchronized AdbCrypto getOrCreate() throws IOException {
        if (!keyDirectory.exists() && !keyDirectory.mkdirs()) {
            throw new IOException("Unable to create the private ADB key directory");
        }
        if (!keyDirectory.isDirectory()) {
            throw new IOException("ADB key path is not a directory");
        }

        File privateKey = new File(keyDirectory, PRIVATE_KEY_FILE);
        File publicKey = new File(keyDirectory, PUBLIC_KEY_FILE);
        if (privateKey.isFile() && publicKey.isFile()) {
            try {
                return AdbCrypto.loadAdbKeyPair(BASE_64, privateKey, publicKey);
            } catch (NoSuchAlgorithmException | InvalidKeySpecException | IOException e) {
                // A partially written or corrupt pair cannot be reused. Replace
                // both files together and let Android request authorization again.
                deleteKeyFile(privateKey);
                deleteKeyFile(publicKey);
            }
        } else {
            deleteKeyFile(privateKey);
            deleteKeyFile(publicKey);
        }

        try {
            AdbCrypto crypto = AdbCrypto.generateAdbKeyPair(BASE_64);
            crypto.saveAdbKeyPair(privateKey, publicKey);
            restrictToOwner(privateKey);
            restrictToOwner(publicKey);
            return crypto;
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("RSA is not available on this device", e);
        }
    }

    private static void deleteKeyFile(File file) throws IOException {
        if (file.exists() && !file.delete()) {
            throw new IOException("Unable to replace an invalid ADB key");
        }
    }

    private static void restrictToOwner(File file) throws IOException {
        boolean permissionsChanged = file.setReadable(false, false)
                && file.setWritable(false, false)
                && file.setReadable(true, true)
                && file.setWritable(true, true);
        if (!permissionsChanged) {
            throw new IOException("Unable to restrict ADB key permissions");
        }
    }
}
