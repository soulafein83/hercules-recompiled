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
                + internalResolution + "\"\naspect_ratio = \"" + BuildConfig.ASPECT_RATIO + "\"\n"
                + "\n[audio]\nfrequency = 48000\n";
    } else {
        text = setSectionKey(text, "video", "fullscreen", "1");
        text = setSectionKey(text, "video", "internal_resolution", "\"" + internalResolution + "\"");
        text = setSectionKey(text, "video", "aspect_ratio", "\"" + BuildConfig.ASPECT_RATIO + "\"");
        // The phone's audio device runs natively at 48 kHz; requesting 48 kHz
        // removes SDL's 44.1->48 conversion (the SPU is resampled by the DRC).
        text = setSectionKey(text, "audio", "frequency", "48000");
    }

    try (OutputStream out = new FileOutputStream(f, false)) {
        out.write(text.getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
        Log.e(TAG, "cannot write " + f, e);
    }

    ensureModState(runtimeDir);
}

/**
 * First-run mod state. The runtime scans <runtime>/mods/bundled for the mod
 * packages shipped in assets and applies <runtime>/mods/state.toml. We enable
 * frame-blend (the presentation crossfade that takes the PAL 50 fps picture to
 * the display refresh rate) — the GL renderer is required for it, see the
 * renderer argument in HerculesActivity. Existing state is left alone so the
 * in-game menu can change it.
 */
static void ensureModState(File runtimeDir) {
    File mods = new File(runtimeDir, "mods");
    File state = new File(mods, "state.toml");
    if (state.isFile()) return;
    if (!mods.isDirectory() && !mods.mkdirs()) {
        Log.e(TAG, "cannot create " + mods);
        return;
    }
    String t = "format_version = 2\n"
            + "\n[[package]]\nid = \"hercules.hd-sprites\"\n"
            + "\n[[package]]\nid = \"psx.enhancement.hd-textures\"\n"
            + "\n[[package]]\nid = \"hercules.level-select\"\n"
            + "\n[[package]]\nid = \"hercules.widescreen\"\n"
            + "\n[[package]]\nid = \"hercules.skip-intro\"\n"
            + "\n[[package]]\nid = \"psx.enhancement.cd-speed\"\n"
            + "\n[[package]]\nid = \"hercules.frame-blend\"\n"
            + "\n[[package]]\nid = \"hercules.ntsc-speed\"\n"
            + "\n[[feature]]\npackage_id = \"hercules.hd-sprites\"\nid = \"hd-sprites\"\nenabled = false\n"
            + "\n[[feature]]\npackage_id = \"psx.enhancement.hd-textures\"\nid = \"hd-textures\"\nenabled = false\n"
            + "\n[[feature]]\npackage_id = \"hercules.level-select\"\nid = \"level-select\"\nenabled = false\n"
            + "\n[feature.values]\nlevel = \"off\"\n"
            + "\n[[feature]]\npackage_id = \"hercules.widescreen\"\nid = \"widescreen\"\nenabled = true\n"
            + "\n[feature.values]\naspect = \"4:3\"\n"
            + "\n[[feature]]\npackage_id = \"hercules.skip-intro\"\nid = \"skip-intro\"\nenabled = false\n"
            + "\n[[feature]]\npackage_id = \"hercules.skip-intro\"\nid = \"skip-memcard\"\nenabled = false\n"
            + "\n[[feature]]\npackage_id = \"hercules.skip-intro\"\nid = \"skip-fmv\"\nenabled = false\n"
            + "\n[[feature]]\npackage_id = \"hercules.skip-intro\"\nid = \"direct-start\"\nenabled = false\n"
            + "\n[feature.values]\nlevel = \"1\"\n"
            + "\n[[feature]]\npackage_id = \"hercules.ntsc-speed\"\nid = \"ntsc-speed\"\nenabled = false\n"
            + "\n[[feature]]\npackage_id = \"psx.enhancement.cd-speed\"\nid = \"cd-speed\"\nenabled = false\n"
            + "\n[feature.values]\nmultiplier = \"4\"\n"
            + "\n[[feature]]\npackage_id = \"hercules.frame-blend\"\nid = \"frame-blend\"\nenabled = true\n"
            + "\n[feature.values]\nblend = \"linear\"\n";
    try (OutputStream out = new FileOutputStream(state, false)) {
        out.write(t.getBytes(StandardCharsets.UTF_8));
    } catch (IOException e) {
        Log.e(TAG, "cannot write " + state, e);
    }
}

/** Replace {@code key = ...} in the given TOML section, creating the section if absent. */
private static String setSectionKey(String text, String section, String key, String value) {
    String assignment = key + " = " + value;
    java.util.regex.Matcher header = java.util.regex.Pattern
            .compile("(?m)^\\[" + java.util.regex.Pattern.quote(section) + "\\][ \\t]*$")
            .matcher(text);
    if (!header.find()) {
        String base = text.isEmpty() || text.endsWith("\n") ? text : text + "\n";
        return base + "\n[" + section + "]\n" + assignment + "\n";
    }
    int start = header.end();
    java.util.regex.Matcher next = java.util.regex.Pattern.compile("(?m)^\\[").matcher(text);
    int end = next.find(start) ? next.start() : text.length();
    String body = text.substring(start, end);
    java.util.regex.Matcher line = java.util.regex.Pattern
            .compile("(?m)^[ \\t]*" + java.util.regex.Pattern.quote(key) + "[ \\t]*=.*$")
            .matcher(body);
    if (line.find()) {
        body = line.replaceFirst(java.util.regex.Matcher.quoteReplacement(assignment));
    } else {
        body = "\n" + assignment + body;
    }
    return text.substring(0, start) + body + text.substring(end);
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