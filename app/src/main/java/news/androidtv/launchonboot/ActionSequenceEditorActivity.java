package news.androidtv.launchonboot;

import android.content.DialogInterface;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;

/** TV-friendly, explicit editor for the internally stored action JSON. */
public final class ActionSequenceEditorActivity extends AppCompatActivity {
    private final List<ActionItem> actions = new ArrayList<>();
    private LinearLayout actionList;
    private TextView status;
    private boolean dirty;
    private ActionSequenceExecutor executor;
    private AdbKeyInjector injector;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_action_sequence_editor);
        actionList = findViewById(R.id.action_list_container);
        status = findViewById(R.id.text_editor_status);
        actions.addAll(new ActionSequenceStore(this).getActionSequence());
        render();
        findViewById(R.id.button_add_action).setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { chooseGroup(); } });
        findViewById(R.id.button_save_action_sequence).setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { save(); } });
        findViewById(R.id.button_clear_action_sequence).setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { confirmClear(); } });
        findViewById(R.id.button_run_editor_sequence).setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { run(); } });
        findViewById(R.id.button_stop_editor_sequence).setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { stop(); } });
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
    @Override protected void onDestroy(){stop();super.onDestroy();}
}
