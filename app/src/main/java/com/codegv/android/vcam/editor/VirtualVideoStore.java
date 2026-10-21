package com.codegv.android.vcam.editor;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import java.io.File;
import java.io.IOException;

/**
 * Location of the shared virtual.mp4 the hook plays, plus what we know about
 * where the current one came from.
 */
public final class VirtualVideoStore {

    private static final String PREFS = "virtual_video";
    private static final String KEY_SOURCE_NAME = "source_name";
    private static final String KEY_START_MS = "start_ms";
    private static final String KEY_END_MS = "end_ms";
    private static final String KEY_FILE_MODIFIED = "file_modified";

    private VirtualVideoStore() {
    }

    public static File cameraDir() {
        return new File(Environment.getExternalStorageDirectory().getAbsolutePath() + "/DCIM/Camera1");
    }

    public static File videoFile() {
        return new File(cameraDir(), "virtual.mp4");
    }

    /** Scratch file in the same directory so the final rename is atomic. Hook only reads .bmp/virtual.mp4. */
    public static File tempFile() {
        return new File(cameraDir(), ".virtual.mp4.tmp");
    }

    /** Atomically replaces virtual.mp4 with {@code temp} and records its origin. */
    public static void commit(Context context, File temp, String sourceName, long startMs, long endMs)
            throws IOException {
        File target = videoFile();
        if (!temp.renameTo(target)) {
            // Some filesystems refuse to rename over an existing file.
            target.delete();
            if (!temp.renameTo(target)) {
                throw new IOException("cannot move " + temp + " to " + target);
            }
        }
        prefs(context).edit()
                .putString(KEY_SOURCE_NAME, sourceName)
                .putLong(KEY_START_MS, startMs)
                .putLong(KEY_END_MS, endMs)
                .putLong(KEY_FILE_MODIFIED, target.lastModified())
                .apply();
    }

    public static boolean delete(Context context) {
        prefs(context).edit().clear().apply();
        return videoFile().delete();
    }

    /**
     * Name of the media the current virtual.mp4 was made from, or null if the
     * file was put there some other way (or replaced since).
     */
    public static String sourceName(Context context) {
        SharedPreferences p = prefs(context);
        if (p.getLong(KEY_FILE_MODIFIED, -1) != videoFile().lastModified()) {
            return null;
        }
        return p.getString(KEY_SOURCE_NAME, null);
    }

    /** {startMs, endMs} of the clip taken from the source, or null if unknown. */
    public static long[] sourceRange(Context context) {
        if (sourceName(context) == null) {
            return null;
        }
        SharedPreferences p = prefs(context);
        return new long[]{p.getLong(KEY_START_MS, 0), p.getLong(KEY_END_MS, 0)};
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
