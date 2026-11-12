package com.example.hercules;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * First-run bootstrap for the Hercules port.
 *
 * The psx-recomp runtime needs its data next to the "exe" before SDL_main runs:
 * game.toml, the bundled OpenBIOS and the AOT metadata. It also needs a disc
 * image. This activity stages the data, asks for the player's disc through the
 * Storage Access Framework (ACTION_OPEN_DOCUMENT), records the chosen path in
 * disc.cfg, and only then launches {@link HerculesActivity}.
 *
 * Doing the picker here (instead of inside the SDL activity) matters: the
 * runtime resolves the disc while SDL_main initialises, before any window
 * exists, so the file must already be on disk when SDL starts.
 */
public class BootstrapActivity extends Activity {
    private static final String TAG = "HerculesBootstrap";
    private static final int REQ_DISC = 0x4845; // "HE"

    private File mRuntimeDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mRuntimeDir = RuntimeFiles.runtimeDir(this);
        try {
            RuntimeFiles.extractAssets(this, "runtime", mRuntimeDir);
        } catch (IOException e) {
            Log.e(TAG, "cannot extract runtime assets", e);
        }

        // chdir() is process-global: do it before HerculesActivity starts SDL.
        JniBridge.setDataDir(mRuntimeDir.getAbsolutePath());

        if (RuntimeFiles.discReady(mRuntimeDir)) {
            startGame();
        } else {
            pickDisc();
        }
    }

    private void pickDisc() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(intent, REQ_DISC);
        } catch (Exception e) {
            Log.e(TAG, "no document picker available", e);
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_DISC) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (resultCode != RESULT_OK || data == null) {
            finish();
            return;
        }

        File discDir = new File(mRuntimeDir, "disc");
        if (!discDir.isDirectory() && !discDir.mkdirs()) {
            Log.e(TAG, "cannot create " + discDir);
            finish();
            return;
        }

        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                uris.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }

        File cue = null;
        File image = null;
        for (Uri uri : uris) {
            File out = copyTo(discDir, uri);
            if (out == null) continue;
            String name = out.getName().toLowerCase(Locale.ROOT);
            if (name.endsWith(".cue")) {
                cue = out;
            } else if (name.endsWith(".bin") || name.endsWith(".img")
                    || name.endsWith(".iso") || name.endsWith(".chd")) {
                image = out;
            }
        }

        File disc = cue;
        if (disc == null && image != null) {
            // A raw .bin needs a cue sheet; the other single-file formats do not.
            disc = image.getName().toLowerCase(Locale.ROOT).endsWith(".bin")
                    ? RuntimeFiles.writeCueForBin(image) : image;
        }
        if (disc == null) {
            Log.e(TAG, "no usable disc image selected");
            finish();
            return;
        }

        RuntimeFiles.writeDiscCfg(mRuntimeDir, disc);
        startGame();
    }

    private File copyTo(File dir, Uri uri) {
        String name = RuntimeFiles.displayName(this, uri);
        if (name == null || name.isEmpty()) {
            name = "disc-" + System.currentTimeMillis();
        }
        File out = new File(dir, name);
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream os = new FileOutputStream(out)) {
            if (in == null) return null;
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            return out;
        } catch (IOException e) {
            Log.e(TAG, "copy failed: " + uri, e);
            // Do not leave a truncated image behind for next launch.
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            return null;
        }
    }

    private void startGame() {
        Intent intent = new Intent(this, HerculesActivity.class);
        startActivity(intent);
        finish();
    }
}