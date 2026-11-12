package com.example.hercules;

import android.content.Context;
import android.content.res.AssetManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Filesystem helpers for the Android port.
 *
 * The psx-recomp runtime anchors game.toml / bios/ / disc.cfg / saves on the
 * directory of argv[0]; on Android SDL passes argv[0]="app_process", which makes
 * that the process CWD. {@link JniBridge#setDataDir(String)} chdir()s into the
 * directory returned by {@link #runtimeDir(Context)}, so everything the runtime
 * reads or writes stays in the app's private storage.
 */
final class RuntimeFiles {
    private static final String TAG = "HerculesRuntime";
    private static final String DISC_CFG = "disc.cfg";

    private RuntimeFiles() {}

    /** App-private runtime directory (external files when available, else internal). */
    static File runtimeDir(Context context) {
        File base = context.getExternalFilesDir(null);
        if (base == null) base = context.getFilesDir();
        File dir = new File(base, "runtime");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.e(TAG, "cannot create runtime dir " + dir);
        }
        return dir;
    }

    /**
     * Recursively copy {@code assets/<assetPath>} into {@code dest}. Runs on every
     * start: the payload is small (~600 KB: game.toml, OpenBIOS, AOT metadata) and
     * keeps the app in sync with the APK after an update.
     */
    static void extractAssets(Context context, String assetPath, File dest) throws IOException {
        AssetManager am = context.getAssets();
        copyAssetTree(am, assetPath, dest);
    }

    private static void copyAssetTree(AssetManager am, String path, File dest) throws IOException {
        String[] children = am.list(path);
        if (children == null || children.length == 0) {
            // A file (AssetManager.list returns empty for files).
            copyAssetFile(am, path, dest);
            return;
        }
        if (!dest.isDirectory() && !dest.mkdirs()) {
            throw new IOException("cannot create " + dest);
        }
        for (String child : children) {
            copyAssetTree(am, path + "/" + child, new File(dest, child));
        }
    }

    private static void copyAssetFile(AssetManager am, String assetPath, File dest) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        try (InputStream in = am.open(assetPath);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    /** True when disc.cfg names a file that still exists. */
    static boolean discReady(File runtimeDir) {
        File cfg = new File(runtimeDir, DISC_CFG);
        if (!cfg.isFile()) return false;
        String line = readFirstLine(cfg);
        return line != null && !line.isEmpty() && new File(line).exists();
    }

    /**
     * Write the runtime's disc.cfg sidecar. The path is absolute so the runtime
     * uses it verbatim (read_cached_path anchors only relative paths).
     */
    static void writeDiscCfg(File runtimeDir, File disc) {
        File cfg = new File(runtimeDir, DISC_CFG);
        try (OutputStream out = new FileOutputStream(cfg, false)) {
            out.write((disc.getAbsolutePath() + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            Log.e(TAG, "cannot write " + cfg, e);
        }
    }

    /**
     * A raw .bin image has no cue sheet, and the runtime validates the disc
     * through one. Emit the same MODE2/2352 sheet a Redump dump ships with.
     */
    static File writeCueForBin(File bin) {
        String name = bin.getName();
        String stem = name.toLowerCase(Locale.ROOT).endsWith(".bin")
                ? name.substring(0, name.length() - 4) : name;
        File cue = new File(bin.getParentFile(), stem + ".cue");
        String body = "FILE \"" + name + "\" BINARY\n"
                + "  TRACK 01 MODE2/2352\n"
                + "    INDEX 01 00:00:00\n";
        try (OutputStream out = new FileOutputStream(cue, false)) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
            return cue;
        } catch (IOException e) {
            Log.e(TAG, "cannot write " + cue, e);
            return null;
        }
    }

    /** Display name of a SAF document (falls back to the last path segment). */
    static String displayName(Context context, Uri uri) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(uri,
                    new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String name = cursor.getString(idx);
                    if (name != null && !name.isEmpty()) return name;
                }
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "displayName failed for " + uri, e);
        } finally {
            if (cursor != null) cursor.close();
        }
        String last = uri.getLastPathSegment();
        return last == null ? null : last.substring(last.lastIndexOf('/') + 1);
    }

    // ---------------------------------------------------------------------------
// settings.toml: the runtime persists display options there and they SUPERSEDE
// game.toml. Force fullscreen + a phone-friendly internal resolution while
// preserving everything else (audio, controller, launcher sections).
// ---------------------------------------------------------------------------
static void ensureSettings(File runtimeDir, String internalResolution) {
    File f = new File(runtimeDir, "settings.toml");
    String text = "";
    if (f.isFile()) {
        try {
            text = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.w(TAG, "cannot read " + f, e);
        }
    }

    if (text.trim().isEmpty()) {
        text = "[video]\nfullscreen = 1\ninternal_resolution = \""
                + internalResolution + "\"\n";
    } else {
        text = setVideoKey(text, "fullscreen", "1");
        text = setVideoKey(text, "internal_resolution", "\"" + internalResolution + "\"");
    }

    try (OutputStream out = new FileOutputStream(f, false)) {
        out.write(text.getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
        Log.e(TAG, "cannot write " + f, e);
    }
}

/** Replace {@code key = ...} in settings.toml, inserting it under [video] if absent. */
private static String setVideoKey(String text, String key, String value) {
    java.util.regex.Pattern line = java.util.regex.Pattern.compile(
            "(?m)^[ \\t]*" + java.util.regex.Pattern.quote(key) + "[ \\t]*=.*$");
    java.util.regex.Matcher m = line.matcher(text);
    String assignment = key + " = " + value;
    if (m.find()) {
        return m.replaceFirst(java.util.regex.Matcher.quoteReplacement(assignment));
    }
    java.util.regex.Matcher header = java.util.regex.Pattern
            .compile("(?m)^\\[video\\][ \\t]*$").matcher(text);
    if (header.find()) {
        return text.substring(0, header.end()) + "\n" + assignment + text.substring(header.end());
    }
    return text + "\n[video]\n" + assignment + "\n";
}

private static String readFirstLine(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) != -1 && c != '\n') {
                if (c != '\r') sb.append((char) c);
            }
            return sb.toString();
        } catch (IOException e) {
            return null;
        }
    }
}