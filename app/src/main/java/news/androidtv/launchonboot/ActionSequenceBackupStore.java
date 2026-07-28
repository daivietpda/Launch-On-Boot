package news.androidtv.launchonboot;

import android.content.Context;

import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** App-private backup storage that does not require a system file picker or storage permission. */
public final class ActionSequenceBackupStore {
    public static final int MAX_BACKUP_COUNT = 100;
    private final File directory;

    public ActionSequenceBackupStore(Context context) {
        this(new File(context.getFilesDir(), "action-sequence-backups"));
    }

    ActionSequenceBackupStore(File directory) {
        if (directory == null) throw new IllegalArgumentException("directory must not be null");
        this.directory = directory;
    }

    public boolean exists(String requestedName) {
        return resolveBackupFile(requestedName).isFile();
    }

    public String save(String requestedName, List<ActionItem> actions)
            throws IOException, JSONException {
        String fileName = ActionSequenceFileManager.normalizeFileName(requestedName);
        ensureDirectory();
        if (!new File(directory, fileName).isFile() && list().size() >= MAX_BACKUP_COUNT) {
            throw new IOException("Backup limit reached");
        }
        File target = resolveBackupFile(fileName);
        File temporary = new File(directory, fileName + ".tmp");
        byte[] json = ActionSequenceFileManager.serializeForExport(actions)
                .getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(temporary, false)) {
            output.write(json);
            output.flush();
            output.getFD().sync();
        }
        File previous = new File(directory, fileName + ".previous");
        if (previous.exists() && !previous.delete()) {
            throw new IOException("Cannot prepare backup replacement");
        }
        boolean hadTarget = target.exists();
        if (hadTarget && !target.renameTo(previous)) {
            throw new IOException("Cannot preserve existing backup");
        }
        if (!temporary.renameTo(target)) {
            if (hadTarget) previous.renameTo(target);
            throw new IOException("Cannot finish backup");
        }
        if (previous.exists() && !previous.delete()) previous.deleteOnExit();
        return fileName;
    }

    public List<BackupInfo> list() throws IOException {
        if (!directory.exists()) return Collections.emptyList();
        File[] files = directory.listFiles();
        if (files == null) throw new IOException("Cannot read backup directory");
        List<BackupInfo> backups = new ArrayList<>();
        for (File file : files) {
            if (file.isFile() && file.getName().toLowerCase(java.util.Locale.US).endsWith(".json")) {
                backups.add(new BackupInfo(file.getName(), file.length(), file.lastModified()));
            }
        }
        Collections.sort(backups, new Comparator<BackupInfo>() {
            @Override public int compare(BackupInfo left, BackupInfo right) {
                return Long.compare(right.lastModified, left.lastModified);
            }
        });
        return Collections.unmodifiableList(backups);
    }

    public List<ActionItem> read(String fileName) throws IOException, JSONException {
        File file = resolveBackupFile(fileName);
        if (!file.isFile()) throw new IOException("Backup does not exist");
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > ActionSequenceFileManager.MAX_FILE_SIZE_BYTES) {
                    throw new IOException("Backup is too large");
                }
                bytes.write(buffer, 0, count);
            }
            return ActionSequenceFileManager.parseImportJson(
                    new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    public boolean delete(String fileName) {
        File file = resolveBackupFile(fileName);
        return !file.exists() || file.delete();
    }

    private void ensureDirectory() throws IOException {
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Cannot create backup directory");
        }
        if (!directory.isDirectory()) throw new IOException("Backup location is unavailable");
    }

    private File resolveBackupFile(String requestedName) {
        String fileName = ActionSequenceFileManager.normalizeFileName(requestedName);
        File file = new File(directory, fileName);
        try {
            String directoryPath = directory.getCanonicalPath() + File.separator;
            if (!file.getCanonicalPath().startsWith(directoryPath)) {
                throw new IllegalArgumentException("Invalid backup name");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid backup name", e);
        }
        return file;
    }

    public static final class BackupInfo {
        private final String fileName;
        private final long sizeBytes;
        private final long lastModified;

        BackupInfo(String fileName, long sizeBytes, long lastModified) {
            this.fileName = fileName;
            this.sizeBytes = sizeBytes;
            this.lastModified = lastModified;
        }

        public String getFileName() { return fileName; }
        public long getSizeBytes() { return sizeBytes; }
        public long getLastModified() { return lastModified; }
    }
}
