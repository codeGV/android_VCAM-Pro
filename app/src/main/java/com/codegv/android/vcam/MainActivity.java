package com.codegv.android.vcam;


import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.format.Formatter;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.codegv.android.vcam.editor.AspectFrameLayout;
import com.codegv.android.vcam.editor.VideoEditorActivity;
import com.codegv.android.vcam.editor.VideoPlayerView;
import com.codegv.android.vcam.editor.VirtualVideoStore;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private Switch force_show_switch;
    private Switch disable_switch;
    private Switch play_sound_switch;
    private Switch force_private_dir;
    private Switch disable_toast_switch;

    private static final int REQUEST_PICK_VIDEO = 10;
    private static final int REQUEST_EDIT_VIDEO = 11;

    private AspectFrameLayout media_preview_frame;
    private VideoPlayerView media_preview;
    private TextView media_empty;
    private TextView media_info;
    private Button media_remove;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_VIDEO && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Intent edit = new Intent(this, VideoEditorActivity.class);
            edit.setData(data.getData());
            edit.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(edit, REQUEST_EDIT_VIDEO);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        media_preview.release();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0) {
            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                Toast.makeText(MainActivity.this, R.string.permission_lack_warn, Toast.LENGTH_SHORT).show();
            }else {
                File camera_dir = new File (Environment.getExternalStorageDirectory().getAbsolutePath()+"/DCIM/Camera1/");
                if (!camera_dir.exists()){
                    camera_dir.mkdir();
                }
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        sync_statue_with_files();
        refresh_media_card();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button repo_button = findViewById(R.id.button);
        force_show_switch = findViewById(R.id.switch1);
        disable_switch = findViewById(R.id.switch2);
        play_sound_switch = findViewById(R.id.switch3);
        force_private_dir = findViewById(R.id.switch4);
        disable_toast_switch = findViewById(R.id.switch5);

        media_preview_frame = findViewById(R.id.media_preview_frame);
        media_preview = findViewById(R.id.media_preview);
        media_preview.setMuted(true);
        media_preview.setLooping(true);
        media_empty = findViewById(R.id.media_empty);
        media_info = findViewById(R.id.media_info);
        media_remove = findViewById(R.id.media_remove);

        findViewById(R.id.media_choose).setOnClickListener(v -> {
            if (!has_permission()) {
                request_permission();
                return;
            }
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("video/*");
            try {
                startActivityForResult(pick, REQUEST_PICK_VIDEO);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, R.string.media_no_picker, Toast.LENGTH_SHORT).show();
            }
        });

        media_remove.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(R.string.media_remove_confirm)
                .setNegativeButton(R.string.editor_cancel, null)
                .setPositiveButton(R.string.media_remove, (d, w) -> {
                    media_preview.release();
                    VirtualVideoStore.delete(this);
                    refresh_media_card();
                })
                .show());

        sync_statue_with_files();

        repo_button.setOnClickListener(v -> {

            Uri uri = Uri.parse("https://github.com/w2016561536/android_virtual_cam");
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            startActivity(intent);
        });

        Button repo_button_chinamainland = findViewById(R.id.button2);
        repo_button_chinamainland.setOnClickListener(view -> {
            Uri uri = Uri.parse("https://gitee.com/w2016561536/android_virtual_cam");
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            startActivity(intent);
        });

        disable_switch.setOnCheckedChangeListener((compoundButton, b) -> {
            if (compoundButton.isPressed()) {
                if (!has_permission()) {
                    request_permission();
                } else {
                    File disable_file = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/DCIM/Camera1/disable.jpg");
                    if (disable_file.exists() != b){
                        if (b){
                            try {
                                disable_file.createNewFile();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }else {
                            disable_file.delete();
                        }
                    }
                }
                sync_statue_with_files();
            }
        });

        force_show_switch.setOnCheckedChangeListener((compoundButton, b) -> {
            if (compoundButton.isPressed()) {
                if (!has_permission()) {
                    request_permission();
                } else {
                    File force_show_switch = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/DCIM/Camera1/force_show.jpg");
                    if (force_show_switch.exists() != b){
                        if (b){
                            try {
                                force_show_switch.createNewFile();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }else {
                            force_show_switch.delete();
                        }
                    }
                }
                sync_statue_with_files();
            }
        });

        play_sound_switch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                if (compoundButton.isPressed()) {
                    if (!has_permission()) {
                        request_permission();
                    } else {
                        File play_sound_switch = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/DCIM/Camera1/no-silent.jpg");
                        if (play_sound_switch.exists() != b){
                            if (b){
                                try {
                                    play_sound_switch.createNewFile();
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            }else {
                                play_sound_switch.delete();
                            }
                        }
                    }
                    sync_statue_with_files();
                }
            }
        });

        force_private_dir.setOnCheckedChangeListener((compoundButton, b) -> {
            if (compoundButton.isPressed()) {
                if (!has_permission()) {
                    request_permission();
                } else {
                    File force_private_dir = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/DCIM/Camera1/private_dir.jpg");
                    if (force_private_dir.exists() != b){
                        if (b){
                            try {
                                force_private_dir.createNewFile();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }else {
                            force_private_dir.delete();
                        }
                    }
                }
                sync_statue_with_files();
            }
        });


        disable_toast_switch.setOnCheckedChangeListener((compoundButton, b) -> {
            if (compoundButton.isPressed()) {
                if (!has_permission()) {
                    request_permission();
                } else {
                    File disable_toast_file = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/DCIM/Camera1/no_toast.jpg");
                    if (disable_toast_file.exists() != b){
                        if (b){
                            try {
                                disable_toast_file.createNewFile();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }else {
                            disable_toast_file.delete();
                        }
                    }
                }
                sync_statue_with_files();
            }
        });

    }

    private void request_permission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (this.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_DENIED
                    || this.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_DENIED) {
                AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                builder.setTitle(R.string.permission_lack_warn);
                builder.setMessage(R.string.permission_description);

                builder.setNegativeButton(R.string.negative, (dialogInterface, i) -> Toast.makeText(MainActivity.this, R.string.permission_lack_warn, Toast.LENGTH_SHORT).show());

                builder.setPositiveButton(R.string.positive, (dialogInterface, i) -> requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1));
                builder.show();
            }
        }
    }

    private boolean has_permission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return this.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_DENIED
                    && this.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_DENIED;
        }
        return true;
    }


    private void sync_statue_with_files() {
        Log.d(this.getApplication().getPackageName(), "[VCAM] [sync] syncing switch states");

        if (!has_permission()){
            request_permission();
        }else {
            File camera_dir = new File (Environment.getExternalStorageDirectory().getAbsolutePath()+"/DCIM/Camera1");
            if (!camera_dir.exists()){
                camera_dir.mkdir();
            }
        }

        File disable_file = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/DCIM/Camera1/disable.jpg");
        disable_switch.setChecked(disable_file.exists());

        File force_show_file = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/DCIM/Camera1/force_show.jpg");
        force_show_switch.setChecked(force_show_file.exists());

        File play_sound_file = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/DCIM/Camera1/no-silent.jpg");
        play_sound_switch.setChecked(play_sound_file.exists());

        File force_private_dir_file = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/DCIM/Camera1/private_dir.jpg");
        force_private_dir.setChecked(force_private_dir_file.exists());

        File disable_toast_file = new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/DCIM/Camera1/no_toast.jpg");
        disable_toast_switch.setChecked(disable_toast_file.exists());

    }

    /** Shows a looping preview and details of the virtual.mp4 the hook will play. */
    private void refresh_media_card() {
        File video = VirtualVideoStore.videoFile();
        int width = 0, height = 0;
        long duration_ms = 0;
        if (video.isFile()) {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(video.getAbsolutePath());
                width = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                height = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                String rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                if (rotation != null && Integer.parseInt(rotation) % 180 != 0) {
                    int t = width;
                    width = height;
                    height = t;
                }
                duration_ms = Long.parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            } catch (Exception e) {
                Log.w("VCAM", "[VCAM] cannot read " + video, e);
            } finally {
                try {
                    retriever.release();
                } catch (Exception ignored) {
                }
            }
        }

        if (width <= 0 || height <= 0) {
            media_preview.release();
            media_preview_frame.setVisibility(View.GONE);
            media_empty.setVisibility(View.VISIBLE);
            media_empty.setText(video.isFile() ? R.string.media_unreadable : R.string.media_empty);
            media_info.setText(getString(R.string.media_location, video.getAbsolutePath()));
            media_remove.setEnabled(video.isFile());
            return;
        }

        media_empty.setVisibility(View.GONE);
        media_preview_frame.setVisibility(View.VISIBLE);
        media_preview_frame.setAspectRatio((float) width / height);
        media_preview.setVideoUri(Uri.fromFile(video));
        media_preview.play();
        media_remove.setEnabled(true);

        String source = VirtualVideoStore.sourceName(this);
        long[] range = VirtualVideoStore.sourceRange(this);
        String source_line = source == null
                ? getString(R.string.media_source_unknown)
                : getString(R.string.media_source, source,
                        format_seconds(range[0]), format_seconds(range[1]));
        String details_line = getString(R.string.media_details, width, height,
                String.format(Locale.US, "%.1f", duration_ms / 1000f),
                Formatter.formatShortFileSize(this, video.length()));
        String updated_line = getString(R.string.media_updated,
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(video.lastModified())));
        media_info.setText(source_line + "\n" + details_line + "\n" + updated_line);
    }

    private static String format_seconds(long ms) {
        return String.format(Locale.US, "%.1fs", ms / 1000f);
    }


}



