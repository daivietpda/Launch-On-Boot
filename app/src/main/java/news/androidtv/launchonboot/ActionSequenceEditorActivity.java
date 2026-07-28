package news.androidtv.launchonboot;

import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.FileProvider;

import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** TV-friendly, explicit editor for the internally stored action JSON. */
public final class ActionSequenceEditorActivity extends AppCompatActivity {
    private static final String TAG = "ActionSequenceEditor";
    private final List<ActionItem> actions = new ArrayList<>();
    private LinearLayout actionList;
    private TextView status;
    private boolean dirty;
    private ActionSequenceExecutor executor;
    private AdbKeyInjector injector;
    private ActionSequenceBackupStore backupStore;
    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor();
    private ActivityResultLauncher<String> createDocumentLauncher;
    private ActivityResultLauncher<String[]> openDocumentLauncher;
    private ActivityResultLauncher<Intent> getContentFallbackLauncher;
    private String pendingExportJson;
    private boolean fileOperation;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        registerFilePickers();
        setContentView(R.layout.activity_action_sequence_editor);
        actionList = findViewById(R.id.action_list_container);
        status = findViewById(R.id.text_editor_status);
        backupStore = new ActionSequenceBackupStore(this);
        ActionSequenceStore store = new ActionSequenceStore(this);
        try {
            actions.addAll(store.ensureInitialDemo());
        } catch (JSONException e) {
            throw new IllegalStateException("Invalid built-in action demo", e);
        }
        render();
        findViewById(R.id.button_add_action).setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { chooseGroup(); } });
        findViewById(R.id.button_export_action_sequence).setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { exportSequence(); } });
        findViewById(R.id.button_import_action_sequence).setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { importSequence(); } });
        findViewById(R.id.button_save_internal_backup).setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { saveInternalBackup(); } });
        findViewById(R.id.button_open_internal_backups).setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { openInternalBackups(); } });
        findViewById(R.id.button_save_action_sequence).setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { save(); } });
        findViewById(R.id.button_clear_action_sequence).setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { confirmClear(); } });
        findViewById(R.id.button_run_editor_sequence).setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { run(); } });
        findViewById(R.id.button_stop_editor_sequence).setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { stop(); } });
    }

    private void registerFilePickers() {
        createDocumentLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/json"),
                new androidx.activity.result.ActivityResultCallback<Uri>() {
                    @Override public void onActivityResult(Uri uri) { writeExport(uri); }
                });
        openDocumentLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(),
                new androidx.activity.result.ActivityResultCallback<Uri>() {
                    @Override public void onActivityResult(Uri uri) { readImport(uri); }
                });
        getContentFallbackLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                new androidx.activity.result.ActivityResultCallback<androidx.activity.result.ActivityResult>() {
                    @Override public void onActivityResult(androidx.activity.result.ActivityResult result) {
                        Intent data = result.getData();
                        readImport(data == null ? null : data.getData());
                    }
                });
    }

    static String displayName(android.content.Context context, ActionItem item) {
        if (item.getType() == ActionItem.Type.WAIT) return context.getString(R.string.action_wait_display, item.getDurationMs());
        if (item.getType() == ActionItem.Type.TEXT) return context.getString(R.string.action_text_display, shorten(item.getText()));
        return item.getKeyCode().replace("KEYCODE_DPAD_", "").replace("KEYCODE_", "");
    }
    private static String shorten(String text) { return text.length() > 24 ? text.substring(0, 21) + "..." : text; }

    private void render() {
        actionList.removeAllViews();
        if (actions.isEmpty()) { TextView empty = new TextView(this); empty.setText(R.string.action_sequence_empty); actionList.addView(empty); return; }
        for (int i = 0; i < actions.size(); i++) addRow(i, actions.get(i));
    }
    private void addRow(final int index, final ActionItem item) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.VERTICAL); row.setPadding(0, 12, 0, 12);
        TextView title = new TextView(this); title.setText((index + 1) + ". " + displayName(this, item)); title.setTextSize(20); row.addView(title);
        TextView detail = new TextView(this); detail.setText(item.getType() == ActionItem.Type.WAIT ? "" : getString(R.string.action_detail, item.getDelayAfterMs(), item.getRepeat())); row.addView(detail);
        LinearLayout controls = new LinearLayout(this); controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.addView(button(R.string.button_move_up, new View.OnClickListener() { @Override public void onClick(View v) { move(index, -1); } }, index > 0));
        controls.addView(button(R.string.button_move_down, new View.OnClickListener() { @Override public void onClick(View v) { move(index, 1); } }, index < actions.size() - 1));
        controls.addView(button(R.string.button_edit, new View.OnClickListener() { @Override public void onClick(View v) { edit(index); } }, true));
        controls.addView(button(R.string.button_delete, new View.OnClickListener() { @Override public void onClick(View v) { actions.remove(index); changed(); } }, true));
        row.addView(controls); actionList.addView(row);
    }
    private Button button(int text, View.OnClickListener listener, boolean enabled) { Button b = new Button(this); b.setText(text); b.setOnClickListener(listener); b.setEnabled(enabled); return b; }
    private void move(int index, int delta) { int next = index + delta; if (next < 0 || next >= actions.size()) return; ActionItem item = actions.remove(index); actions.add(next, item); changed(); }
    private void changed() { dirty = true; render(); }

    private void saveInternalBackup() {
        if (fileOperation) return;
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText("action-sequence.json");
        input.setHint(R.string.export_file_name_hint);
        new AlertDialog.Builder(this).setTitle(R.string.internal_backup_name_title)
                .setMessage(R.string.internal_backup_notice).setView(input)
                .setPositiveButton(R.string.button_save_internal_backup,
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) {
                                try {
                                    final String fileName = ActionSequenceFileManager.normalizeFileName(
                                            input.getText().toString());
                                    if (backupStore.exists(fileName)) {
                                        confirmOverwriteInternalBackup(fileName);
                                    } else {
                                        persistInternalBackup(fileName);
                                    }
                                } catch (IllegalArgumentException e) {
                                    status.setText(getString(R.string.internal_backup_error, e.getMessage()));
                                }
                            }
                        }).setNegativeButton(R.string.cancel, null).show();
    }

    private void confirmOverwriteInternalBackup(final String fileName) {
        new AlertDialog.Builder(this).setMessage(getString(R.string.internal_backup_overwrite, fileName))
                .setPositiveButton(R.string.button_save_internal_backup,
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) {
                                persistInternalBackup(fileName);
                            }
                        }).setNegativeButton(R.string.cancel, null).show();
    }

    private void persistInternalBackup(final String fileName) {
        setFileOperation(true);
        final List<ActionItem> snapshot = new ArrayList<>(actions);
        fileExecutor.execute(new Runnable() {
            @Override public void run() {
                try {
                    final String savedName = backupStore.save(fileName, snapshot);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (isFinishing() || isDestroyed()) return;
                            setFileOperation(false);
                            status.setText(getString(R.string.internal_backup_saved, savedName));
                        }
                    });
                } catch (IOException | JSONException | IllegalArgumentException e) {
                    Log.e(TAG, "Unable to save internal action backup", e);
                    postInternalBackupError(e);
                }
            }
        });
    }

    private void openInternalBackups() {
        if (fileOperation) return;
        setFileOperation(true);
        fileExecutor.execute(new Runnable() {
            @Override public void run() {
                try {
                    final List<ActionSequenceBackupStore.BackupInfo> backups = backupStore.list();
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (isFinishing() || isDestroyed()) return;
                            setFileOperation(false);
                            showInternalBackups(backups);
                        }
                    });
                } catch (IOException e) {
                    Log.e(TAG, "Unable to list internal action backups", e);
                    postInternalBackupError(e);
                }
            }
        });
    }

    private void showInternalBackups(final List<ActionSequenceBackupStore.BackupInfo> backups) {
        if (backups.isEmpty()) {
            status.setText(R.string.internal_backup_empty);
            return;
        }
        String[] labels = new String[backups.size()];
        for (int i = 0; i < backups.size(); i++) {
            ActionSequenceBackupStore.BackupInfo backup = backups.get(i);
            labels[i] = getString(R.string.internal_backup_list_item,
                    backup.getFileName(), backup.getSizeBytes());
        }
        new AlertDialog.Builder(this).setTitle(R.string.internal_backup_library_title)
                .setItems(labels, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        readInternalBackup(backups.get(which).getFileName());
                    }
                }).setNegativeButton(R.string.cancel, null).show();
    }

    private void readInternalBackup(final String fileName) {
        setFileOperation(true);
        fileExecutor.execute(new Runnable() {
            @Override public void run() {
                try {
                    final List<ActionItem> imported = backupStore.read(fileName);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (isFinishing() || isDestroyed()) return;
                            setFileOperation(false);
                            confirmInternalBackup(fileName, imported);
                        }
                    });
                } catch (IOException | JSONException | IllegalArgumentException e) {
                    Log.e(TAG, "Unable to read internal action backup", e);
                    postInternalBackupError(e);
                }
            }
        });
    }

    private void confirmInternalBackup(final String fileName, final List<ActionItem> imported) {
        String preview = imported.isEmpty() ? getString(R.string.import_empty_summary)
                : previewActions(imported);
        new AlertDialog.Builder(this).setTitle(R.string.confirm_import_action_sequence)
                .setMessage(getString(R.string.import_file_summary, fileName, imported.size(), preview,
                        getString(R.string.import_replaces_current)))
                .setPositiveButton(R.string.button_import, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        applyImportedActions(imported);
                    }
                }).setNeutralButton(R.string.button_delete_backup,
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) {
                                confirmDeleteInternalBackup(fileName);
                            }
                        }).setNegativeButton(R.string.cancel, null).show();
    }

    private void confirmDeleteInternalBackup(final String fileName) {
        new AlertDialog.Builder(this).setMessage(getString(R.string.delete_backup_confirmation, fileName))
                .setPositiveButton(R.string.button_delete_backup,
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) {
                                if (backupStore.delete(fileName)) status.setText(R.string.internal_backup_deleted);
                                else status.setText(R.string.internal_backup_delete_failed);
                            }
                        }).setNegativeButton(R.string.cancel, null).show();
    }

    private void postInternalBackupError(final Exception error) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (isFinishing() || isDestroyed()) return;
                setFileOperation(false);
                status.setText(getString(R.string.internal_backup_error,
                        error.getMessage() == null ? "" : error.getMessage()));
            }
        });
    }

    private void exportSequence() {
        if (fileOperation) return;
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText("action-sequence.json");
        input.setHint(R.string.export_file_name_hint);
        new AlertDialog.Builder(this).setTitle(R.string.export_file_name_title)
                .setMessage(actions.isEmpty() ? "[]" : null).setView(input)
                .setPositiveButton(R.string.button_export_action_sequence,
                        new DialogInterface.OnClickListener() {
                            @Override public void onClick(DialogInterface dialog, int which) {
                                try {
                                    pendingExportJson = ActionSequenceFileManager.serializeForExport(actions);
                                    setFileOperation(true);
                                    String fileName = ActionSequenceFileManager.normalizeFileName(
                                            input.getText().toString());
                                    if (findUsableHandler(Intent.ACTION_CREATE_DOCUMENT, "application/json") != null) {
                                        createDocumentLauncher.launch(fileName);
                                    } else {
                                        exportViaShare(fileName, pendingExportJson);
                                    }
                                } catch (JSONException | IllegalArgumentException e) {
                                    status.setText(getString(R.string.cannot_export_action_sequence));
                                }
                            }
                        }).setNegativeButton(R.string.cancel, null).show();
    }

    private void writeExport(final Uri uri) {
        if (uri == null || pendingExportJson == null) { pendingExportJson = null; setFileOperation(false); return; }
        final String json = pendingExportJson;
        pendingExportJson = null;
        fileExecutor.execute(new Runnable() {
            @Override public void run() {
                try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                    if (output == null) throw new IOException("Output stream unavailable");
                    output.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    output.flush();
                    postFileResult(R.string.exported_action_sequence, null);
                } catch (IOException | SecurityException e) {
                    Log.e(TAG, "Unable to export action sequence", e);
                    postFileResult(R.string.cannot_export_action_sequence, e);
                }
            }
        });
    }

    private void importSequence() {
        if (fileOperation) return;
        setFileOperation(true);
        if (findUsableHandler(Intent.ACTION_OPEN_DOCUMENT, "application/json") != null) {
            openDocumentLauncher.launch(new String[]{"application/json", "text/json", "text/plain", "application/octet-stream"});
        } else {
            ResolveInfo fallback = findUsableHandler(Intent.ACTION_GET_CONTENT, "application/json");
            if (fallback != null) {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT).setType("application/json")
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setPackage(fallback.activityInfo.packageName);
                getContentFallbackLauncher.launch(intent);
            } else {
                setFileOperation(false);
                status.setText(R.string.file_picker_unavailable);
            }
        }
    }

    private ResolveInfo findUsableHandler(String action, String mimeType) {
        Intent intent = new Intent(action).setType(mimeType);
        List<ResolveInfo> resolved = getPackageManager().queryIntentActivities(intent, 0);
        for (ResolveInfo candidate : resolved) {
            if (candidate.activityInfo != null
                    && !"com.google.android.tv.frameworkpackagestubs".equals(
                    candidate.activityInfo.packageName)) {
                return candidate;
            }
        }
        return null;
    }

    private void exportViaShare(final String fileName, final String json) {
        fileExecutor.execute(new Runnable() {
            @Override public void run() {
                try {
                    File directory = new File(getCacheDir(), "action-sequence-export");
                    if (!directory.exists() && !directory.mkdirs()) {
                        throw new IOException("Cannot create temporary export directory");
                    }
                    File output = new File(directory, fileName);
                    try (OutputStream stream = new java.io.FileOutputStream(output, false)) {
                        stream.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        stream.flush();
                    }
                    final Uri uri = FileProvider.getUriForFile(ActionSequenceEditorActivity.this,
                            getPackageName() + ".fileprovider", output);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (isFinishing() || isDestroyed()) return;
                            try {
                                ResolveInfo target = findUsableHandler(Intent.ACTION_SEND, "application/json");
                                if (target == null) {
                                    setFileOperation(false);
                                    status.setText(R.string.file_picker_unavailable);
                                    return;
                                }
                                Intent share = new Intent(Intent.ACTION_SEND).setType("application/json")
                                        .putExtra(Intent.EXTRA_STREAM, uri)
                                        .setPackage(target.activityInfo.packageName)
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                share.setClipData(android.content.ClipData.newRawUri("action-sequence", uri));
                                startActivity(share);
                                pendingExportJson = null;
                                setFileOperation(false);
                                status.setText(R.string.export_share_sequence);
                            } catch (android.content.ActivityNotFoundException e) {
                                Log.e(TAG, "No application can receive the exported JSON", e);
                                setFileOperation(false);
                                status.setText(R.string.file_picker_unavailable);
                            }
                        }
                    });
                } catch (IOException | IllegalArgumentException e) {
                    Log.e(TAG, "Unable to prepare exported action sequence", e);
                    postFileResult(R.string.cannot_export_action_sequence, e);
                }
            }
        });
    }

    private void readImport(final Uri uri) {
        if (uri == null) { setFileOperation(false); return; }
        final String displayName = getDisplayName(uri);
        fileExecutor.execute(new Runnable() {
            @Override public void run() {
                try {
                    final String json = readUtf8(uri);
                    final List<ActionItem> imported = ActionSequenceFileManager.parseImportJson(json);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            if (isFinishing() || isDestroyed()) return;
                            setFileOperation(false);
                            confirmImport(displayName, imported);
                        }
                    });
                } catch (IOException | JSONException | IllegalArgumentException | SecurityException e) {
                    Log.e(TAG, "Unable to import action sequence", e);
                    postImportError(e);
                }
            }
        });
    }

    private String readUtf8(Uri uri) throws IOException {
        try (InputStream input = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            if (input == null) throw new IOException("Input stream unavailable");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (bytes.size() + count > ActionSequenceFileManager.MAX_FILE_SIZE_BYTES) {
                    throw new FileTooLargeException();
                }
                bytes.write(buffer, 0, count);
            }
            return new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private void confirmImport(String fileName, final List<ActionItem> imported) {
        String preview = imported.isEmpty() ? getString(R.string.import_empty_summary)
                : previewActions(imported);
        new AlertDialog.Builder(this).setTitle(R.string.confirm_import_action_sequence)
                .setMessage(getString(R.string.import_file_summary, fileName, imported.size(), preview,
                        getString(R.string.import_replaces_current)))
                .setPositiveButton(R.string.button_import, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        applyImportedActions(imported);
                    }
                }).setNegativeButton(R.string.cancel, null).show();
    }

    private void applyImportedActions(List<ActionItem> imported) {
        actions.clear();
        actions.addAll(imported);
        changed();
        status.setText(R.string.imported_action_sequence);
    }

    private String previewActions(List<ActionItem> imported) {
        StringBuilder preview = new StringBuilder();
        int displayed = Math.min(imported.size(), 5);
        for (int i = 0; i < displayed; i++) {
            if (i > 0) preview.append('\n');
            preview.append(i + 1).append(". ").append(displayName(this, imported.get(i)));
        }
        if (imported.size() > displayed) {
            preview.append('\n').append(getString(R.string.import_more_actions, imported.size() - displayed));
        }
        return preview.toString();
    }

    private String getDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0 && !cursor.isNull(index)) return cursor.getString(index);
            }
        } catch (SecurityException ignored) {
            // A display name is optional and must not prevent import.
        }
        return getString(R.string.choose_action_sequence_file);
    }

    private void postImportError(final Exception error) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (isFinishing() || isDestroyed()) return;
                setFileOperation(false);
                if (error instanceof FileTooLargeException) status.setText(R.string.action_file_too_large);
                else status.setText(getString(R.string.cannot_import_action_sequence,
                        error.getMessage() == null ? "" : error.getMessage()));
            }
        });
    }

    private void postFileResult(final int stringRes, final Exception error) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                if (isFinishing() || isDestroyed()) return;
                setFileOperation(false);
                status.setText(stringRes);
            }
        });
    }

    private void setFileOperation(boolean working) {
        fileOperation = working;
        if (actionList == null) return;
        findViewById(R.id.button_export_action_sequence).setEnabled(!working);
        findViewById(R.id.button_import_action_sequence).setEnabled(!working);
        findViewById(R.id.button_save_internal_backup).setEnabled(!working);
        findViewById(R.id.button_open_internal_backups).setEnabled(!working);
    }

    private static final class FileTooLargeException extends IOException { }

    private void chooseGroup() {
        new AlertDialog.Builder(this).setTitle(R.string.action_group_title).setItems(new String[]{getString(R.string.group_navigation), getString(R.string.group_numbers), getString(R.string.group_control), getString(R.string.group_time), getString(R.string.group_text)}, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) { if (which == 3) chooseWait(); else if (which == 4) editText(-1); else chooseKey(which); }
        }).show();
    }
    private void chooseKey(int group) {
        final ActionItem.KeyCode[] keys = group == 0 ? new ActionItem.KeyCode[]{ActionItem.KeyCode.DPAD_UP,ActionItem.KeyCode.DPAD_DOWN,ActionItem.KeyCode.DPAD_LEFT,ActionItem.KeyCode.DPAD_RIGHT,ActionItem.KeyCode.DPAD_CENTER} : group == 1 ? new ActionItem.KeyCode[]{ActionItem.KeyCode.KEYCODE_0,ActionItem.KeyCode.KEYCODE_1,ActionItem.KeyCode.KEYCODE_2,ActionItem.KeyCode.KEYCODE_3,ActionItem.KeyCode.KEYCODE_4,ActionItem.KeyCode.KEYCODE_5,ActionItem.KeyCode.KEYCODE_6,ActionItem.KeyCode.KEYCODE_7,ActionItem.KeyCode.KEYCODE_8,ActionItem.KeyCode.KEYCODE_9} : new ActionItem.KeyCode[]{ActionItem.KeyCode.BACK,ActionItem.KeyCode.HOME,ActionItem.KeyCode.MENU,ActionItem.KeyCode.ENTER};
        String[] labels = new String[keys.length]; for (int i=0;i<keys.length;i++) labels[i]=keys[i].getAndroidKeyCode().replace("KEYCODE_DPAD_", "").replace("KEYCODE_", "");
        new AlertDialog.Builder(this).setTitle(R.string.button_add_action).setItems(labels, new DialogInterface.OnClickListener() { @Override public void onClick(DialogInterface d, int which) { actions.add(ActionItem.key(keys[which], defaultDelay(), 1)); changed(); } }).show();
    }
    private void chooseWait() { final long[] values={500,1000,2000,3000,5000,10000,30000}; String[] labels={"0.5 s","1 s","2 s","3 s","5 s","10 s","30 s",getString(R.string.custom_value)}; new AlertDialog.Builder(this).setTitle(R.string.group_time).setItems(labels,new DialogInterface.OnClickListener(){@Override public void onClick(DialogInterface d,int w){if(w==values.length) editWait(-1);else{actions.add(ActionItem.waitFor(values[w]));changed();}}}).show(); }
    private void edit(int index) { ActionItem item=actions.get(index); if(item.getType()==ActionItem.Type.WAIT) editWait(index); else if(item.getType()==ActionItem.Type.TEXT) editText(index); else editKey(index); }
    private void editWait(final int index) { final EditText input=new EditText(this); input.setInputType(2); if(index>=0) input.setText(String.valueOf(actions.get(index).getDurationMs())); new AlertDialog.Builder(this).setTitle(R.string.group_time).setView(input).setPositiveButton(R.string.save, new DialogInterface.OnClickListener(){@Override public void onClick(DialogInterface d,int w){try{ActionItem value=ActionItem.waitFor(Long.parseLong(input.getText().toString()));if(index<0)actions.add(value);else actions.set(index,value);changed();}catch(IllegalArgumentException e){status.setText(e.getMessage());}}}).setNegativeButton(R.string.cancel,null).show(); }
    private void editKey(final int index) { final EditText delay=new EditText(this), repeat=new EditText(this); delay.setInputType(2);repeat.setInputType(2); ActionItem old=actions.get(index);delay.setText(String.valueOf(old.getDelayAfterMs()));repeat.setText(String.valueOf(old.getRepeat())); LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.addView(delay);box.addView(repeat);new AlertDialog.Builder(this).setTitle(R.string.button_edit).setView(box).setPositiveButton(R.string.save,new DialogInterface.OnClickListener(){@Override public void onClick(DialogInterface d,int w){try{actions.set(index,ActionItem.key(actions.get(index).getKeyCode(),Long.parseLong(delay.getText().toString()),Integer.parseInt(repeat.getText().toString())));changed();}catch(IllegalArgumentException e){status.setText(e.getMessage());}}}).setNegativeButton(R.string.cancel,null).show(); }
    private void editText(final int index) { final EditText input=new EditText(this), delay=new EditText(this), repeat=new EditText(this); input.setSingleLine(false); delay.setInputType(2); repeat.setInputType(2); if(index>=0){ActionItem old=actions.get(index);input.setText(old.getText());delay.setText(String.valueOf(old.getDelayAfterMs()));repeat.setText(String.valueOf(old.getRepeat()));}else{delay.setText(String.valueOf(defaultDelay()));repeat.setText("1");} LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.addView(input);box.addView(delay);box.addView(repeat); new AlertDialog.Builder(this).setTitle(R.string.action_send_text).setMessage(R.string.text_focus_warning).setView(box).setPositiveButton(R.string.save,new DialogInterface.OnClickListener(){@Override public void onClick(DialogInterface d,int w){try{ActionItem value=ActionItem.text(input.getText().toString(),Long.parseLong(delay.getText().toString()),Integer.parseInt(repeat.getText().toString()));if(index<0)actions.add(value);else actions.set(index,value);changed();}catch(IllegalArgumentException e){status.setText(e.getMessage());}}}).setNegativeButton(R.string.cancel,null).show(); }
    private long defaultDelay() { try { return PreferenceManager.getDefaultSharedPreferences(this).getLong(SettingsManagerConstants.DEFAULT_ACTION_DELAY_MS,ActionSequenceStore.DEFAULT_ACTION_DELAY_MS); } catch(ClassCastException e){return ActionSequenceStore.DEFAULT_ACTION_DELAY_MS;} }
    private void save() { try { new ActionSequenceStore(this).save(actions); dirty=false; status.setText(R.string.action_sequence_saved); setResult(RESULT_OK); } catch(JSONException|IllegalArgumentException e){status.setText(e.getMessage());} }
    private void confirmClear(){new AlertDialog.Builder(this).setMessage(R.string.clear_sequence_confirmation).setPositiveButton(R.string.button_clear_action_sequence,new DialogInterface.OnClickListener(){@Override public void onClick(DialogInterface d,int w){actions.clear();changed();}}).setNegativeButton(R.string.cancel,null).show();}
    private void run(){ if(actions.isEmpty()){status.setText(R.string.action_sequence_empty);return;} try{injector=new AdbKeyInjector(new AdbConnectionManager(this));executor=new ActionSequenceExecutor(injector,defaultDelay());if(!executor.start(actions,new ActionSequenceExecutor.Listener(){@Override public void onStateChanged(ActionSequenceExecutor.State s){}@Override public void onActionStarted(final int i,final ActionItem a){runOnUiThread(new Runnable(){@Override public void run(){status.setText(getString(R.string.action_sequence_running,i+1,actions.size(),displayName(ActionSequenceEditorActivity.this,a)));}});}@Override public void onKeySendRequested(int i,int k,int r){}@Override public void onFinished(final ActionSequenceExecutor.Result r){runOnUiThread(new Runnable(){@Override public void run(){status.setText(getString(R.string.action_sequence_finished,r.name()));stop();}});}}))status.setText(R.string.action_sequence_busy);}catch(IllegalArgumentException e){status.setText(e.getMessage());}}
    private void stop(){if(executor!=null){executor.cancel();executor.close();executor=null;}if(injector!=null){injector.close();injector=null;}}
    @Override public void onBackPressed(){if(!dirty){super.onBackPressed();return;}new AlertDialog.Builder(this).setMessage(R.string.unsaved_changes).setPositiveButton(R.string.save_and_exit,new DialogInterface.OnClickListener(){@Override public void onClick(DialogInterface d,int w){save();finish();}}).setNegativeButton(R.string.discard,new DialogInterface.OnClickListener(){@Override public void onClick(DialogInterface d,int w){finish();}}).setNeutralButton(R.string.cancel,null).show();}
    @Override protected void onDestroy(){stop();fileExecutor.shutdownNow();super.onDestroy();}
}
