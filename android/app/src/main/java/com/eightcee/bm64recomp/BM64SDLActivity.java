package com.eightcee.bm64recomp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.AssetManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import org.libsdl.app.SDLActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class BM64SDLActivity extends SDLActivity {
    private static final String TAG = "BM64Android";
    private static final int REQUEST_ROM = 1001;
    private static final int REQUEST_MODS = 1002;
    private static final int REQUEST_SAVE_IMPORT = 1003;
    private static final int REQUEST_SAVE_EXPORT = 1004;
    private static final long BM64_SAVE_SIZE = 0x20000L;
    private static final long MAX_ROM_BYTES = 64L * 1024L * 1024L;
    private static final long MAX_MOD_BYTES = 512L * 1024L * 1024L;

    public static native void nativeConfigurePaths(String programPath, String appPath);
    public static native void nativeOnRomSelected(String path);
    public static native void nativeOnModsSelected(String[] paths);

    @Override
    public void setOrientationBis(int w, int h, boolean resizable, String hint) {
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL2", "main" };
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        File programDir = new File(getFilesDir(), "program");
        File dataDir = new File(getFilesDir(), "data");
        File romDir = new File(getFilesDir(), "roms");
        File importDir = new File(getFilesDir(), "imports/mods");
        programDir.mkdirs();
        dataDir.mkdirs();
        romDir.mkdirs();
        importDir.mkdirs();

        try {
            extractAssetTree("program", programDir);
        } catch (IOException e) {
            Log.e(TAG, "Could not extract program assets", e);
        }

        super.onCreate(savedInstanceState);
        hideSystemUi();
        nativeConfigurePaths(programDir.getAbsolutePath(), dataDir.getAbsolutePath());

        VirtualPadView virtualPad = new VirtualPadView(this);
        virtualPad.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        addContentView(virtualPad, virtualPad.getLayoutParams());
        virtualPad.bringToFront();
    }

    private void hideSystemUi() {
        final View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
    }

    public void openRomFilePicker() {
        runOnUiThread(() -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                    "application/octet-stream", "application/x-n64-rom"
            });
            startActivityForResult(intent, REQUEST_ROM);
        });
    }

    public void openSaveImportPicker() {
        runOnUiThread(() -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/octet-stream");
            startActivityForResult(intent, REQUEST_SAVE_IMPORT);
        });
    }

    public void openSaveExportPicker() {
        runOnUiThread(() -> {
            File save = getPrimarySaveFile();
            if (!save.exists()) {
                new AlertDialog.Builder(this)
                        .setTitle("Export Save")
                        .setMessage("No Bomberman 64 save exists yet.")
                        .setPositiveButton("OK", null)
                        .show();
                return;
            }

            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/octet-stream");
            intent.putExtra(Intent.EXTRA_TITLE, "bm64_us.bin");
            startActivityForResult(intent, REQUEST_SAVE_EXPORT);
        });
    }

    private File getPrimarySaveFile() {
        return new File(new File(new File(getFilesDir(), "data"), "saves"), "bm64_us.bin");
    }

    private void importSaveUri(Uri uri) throws IOException {
        File save = getPrimarySaveFile();
        File saveDir = save.getParentFile();
        if (saveDir != null) saveDir.mkdirs();

        File temp = new File(save.getParentFile(), "bm64_us.bin.import");
        copyUriToFileAtomic(uri, temp, BM64_SAVE_SIZE);

        if (temp.length() != BM64_SAVE_SIZE) {
            temp.delete();
            throw new IOException("Invalid save size. Expected 131072 bytes, got " + temp.length() + ".");
        }

        File backup = new File(save.getParentFile(), "bm64_us.bin.bak");
        if (save.exists()) {
            copyFile(save, backup);
        }
        copyFile(temp, save);
        temp.delete();
    }

    private void exportSaveUri(Uri uri) throws IOException {
        File save = getPrimarySaveFile();
        if (!save.exists()) throw new IOException("No Bomberman 64 save exists yet.");
        try (InputStream in = new FileInputStream(save);
             OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new IOException("Could not open export destination.");
            copyStream(in, out);
        }
    }

    public void openModServerBrowser() {
        new Thread(() -> {
            try {
                List<BM64ModServer.ModEntry> mods =
                        BM64ModServer.fetchIndex(BM64ModServer.DEFAULT_INDEX_URL);
                runOnUiThread(() -> {
                    if (mods.isEmpty()) {
                        new AlertDialog.Builder(this)
                                .setTitle("BM64 Mod Server")
                                .setMessage("No mods are published on the server yet.")
                                .setPositiveButton("OK", null)
                                .show();
                        return;
                    }

                    String[] labels = new String[mods.size()];
                    for (int i = 0; i < mods.size(); i++) {
                        BM64ModServer.ModEntry mod = mods.get(i);
                        labels[i] = mod.name + "  v" + mod.version + "  — " + mod.author;
                    }

                    new AlertDialog.Builder(this)
                            .setTitle("BM64 Mod Server")
                            .setItems(labels, (dialog, which) -> downloadServerMod(mods.get(which)))
                            .setNegativeButton("Cancel", null)
                            .show();
                });
            } catch (Exception e) {
                Log.e(TAG, "Could not load mod server", e);
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("BM64 Mod Server")
                        .setMessage("Could not load the mod server: " + e.getMessage())
                        .setPositiveButton("OK", null)
                        .show());
            }
        }, "BM64-ModIndex").start();
    }

    private void downloadServerMod(BM64ModServer.ModEntry mod) {
        new Thread(() -> {
            try {
                File dir = new File(getFilesDir(), "imports/mods");
                File downloaded = BM64ModServer.downloadVerified(mod, dir);
                runOnUiThread(() -> nativeOnModsSelected(new String[] { downloaded.getAbsolutePath() }));
            } catch (Exception e) {
                Log.e(TAG, "Mod download failed", e);
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setTitle("Mod install failed")
                        .setMessage(e.getMessage())
                        .setPositiveButton("OK", null)
                        .show());
            }
        }, "BM64-ModDownload").start();
    }

    public void openModFilePicker() {
        runOnUiThread(() -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                    "application/octet-stream", "application/zip", "application/x-zip-compressed"
            });
            startActivityForResult(intent, REQUEST_MODS);
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_ROM) {
            String path = null;
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                try {
                    File target = new File(new File(getFilesDir(), "roms"), "Bomberman64-user-rom.z64");
                    copyUriToFileAtomic(data.getData(), target, MAX_ROM_BYTES);
                    path = target.getAbsolutePath();
                } catch (IOException e) {
                    Log.e(TAG, "ROM import failed", e);
                }
            }
            nativeOnRomSelected(path);
            return;
        }

        if (requestCode == REQUEST_SAVE_IMPORT) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                try {
                    importSaveUri(data.getData());
                    new AlertDialog.Builder(this)
                            .setTitle("Import Save")
                            .setMessage("Save imported successfully. It will be used the next time Bomberman 64 starts.")
                            .setPositiveButton("OK", null)
                            .show();
                } catch (IOException e) {
                    Log.e(TAG, "Save import failed", e);
                    new AlertDialog.Builder(this)
                            .setTitle("Import Save Failed")
                            .setMessage(e.getMessage())
                            .setPositiveButton("OK", null)
                            .show();
                }
            }
            return;
        }

        if (requestCode == REQUEST_SAVE_EXPORT) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                try {
                    exportSaveUri(data.getData());
                    new AlertDialog.Builder(this)
                            .setTitle("Export Save")
                            .setMessage("Save exported successfully.")
                            .setPositiveButton("OK", null)
                            .show();
                } catch (IOException e) {
                    Log.e(TAG, "Save export failed", e);
                    new AlertDialog.Builder(this)
                            .setTitle("Export Save Failed")
                            .setMessage(e.getMessage())
                            .setPositiveButton("OK", null)
                            .show();
                }
            }
            return;
        }

        if (requestCode == REQUEST_MODS) {
            ArrayList<String> imported = new ArrayList<>();
            if (resultCode == Activity.RESULT_OK && data != null) {
                try {
                    if (data.getClipData() != null) {
                        for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                            importModUri(data.getClipData().getItemAt(i).getUri(), imported);
                        }
                    } else if (data.getData() != null) {
                        importModUri(data.getData(), imported);
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Mod import failed", e);
                }
            }
            nativeOnModsSelected(imported.toArray(new String[0]));
            return;
        }

        super.onActivityResult(requestCode, resultCode, data);
    }

    private void importModUri(Uri uri, ArrayList<String> imported) throws IOException {
        File dir = new File(getFilesDir(), "imports/mods");
        dir.mkdirs();
        String name = sanitizeFileName(queryDisplayName(uri));
        if (name.isEmpty()) name = "mod-" + System.currentTimeMillis() + ".nrm";
        File target = new File(dir, name);
        copyUriToFileAtomic(uri, target, MAX_MOD_BYTES);
        imported.add(target.getAbsolutePath());
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri,
                new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String value = cursor.getString(0);
                if (value != null) return value;
            }
        } catch (Exception ignored) { }
        return "";
    }

    private static String sanitizeFileName(String name) {
        return name.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            out.write(buffer, 0, read);
        }
    }

    private static void copyFile(File source, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null) parent.mkdirs();
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(target)) {
            copyStream(in, out);
        }
    }

    private void copyUriToFileAtomic(Uri uri, File target, long maxBytes) throws IOException {
        File parent = target.getParentFile();
        if (parent != null) parent.mkdirs();

        File temp = new File(parent, target.getName() + ".tmp");
        try {
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(temp)) {
                if (in == null) throw new IOException("Could not open selected document");
                byte[] buffer = new byte[64 * 1024];
                long total = 0;
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    total += read;
                    if (total > maxBytes) {
                        throw new IOException("Selected file is too large.");
                    }
                    out.write(buffer, 0, read);
                }
            }

            if (target.exists() && !target.delete()) {
                throw new IOException("Could not replace existing file.");
            }
            if (!temp.renameTo(target)) {
                copyFile(temp, target);
                if (!temp.delete()) temp.deleteOnExit();
            }
        } finally {
            if (temp.exists() && !temp.equals(target)) temp.delete();
        }
    }

    private void extractAssetTree(String assetPath, File destination) throws IOException {
        AssetManager assets = getAssets();
        String[] children = assets.list(assetPath);
        if (children == null) return;
        if (children.length == 0) {
            File parent = destination.getParentFile();
            if (parent != null) parent.mkdirs();
            try (InputStream in = assets.open(assetPath);
                 OutputStream out = new FileOutputStream(destination)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
            }
            return;
        }
        destination.mkdirs();
        for (String child : children) {
            extractAssetTree(assetPath + "/" + child, new File(destination, child));
        }
    }
}
