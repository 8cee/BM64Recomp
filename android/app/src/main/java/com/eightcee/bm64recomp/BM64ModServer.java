package com.eightcee.bm64recomp;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

public final class BM64ModServer {
    private static final String TAG = "BM64ModServer";
    public static final String DEFAULT_INDEX_URL =
            "https://raw.githubusercontent.com/8cee/BM64Recomp/android/mod-server/index.json";

    public static final class ModEntry {
        public String id;
        public String name;
        public String author;
        public String version;
        public String description;
        public String downloadUrl;
        public String sha256;
        public String filename;
        public boolean androidSupported;
    }

    private BM64ModServer() {}

    public static List<ModEntry> fetchIndex(String indexUrl) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(indexUrl).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept", "application/json");
        connection.connect();

        if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
            throw new IllegalStateException("Mod index HTTP " + connection.getResponseCode());
        }

        StringBuilder json = new StringBuilder();
        try (InputStream in = connection.getInputStream()) {
            byte[] buffer = new byte[32 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                json.append(new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }

        JSONObject root = new JSONObject(json.toString());
        if (root.optInt("schema_version", -1) != 1 || !"bm64".equals(root.optString("game"))) {
            throw new IllegalStateException("Unsupported BM64 mod index");
        }

        JSONArray mods = root.optJSONArray("mods");
        List<ModEntry> result = new ArrayList<>();
        if (mods == null) return result;

        for (int i = 0; i < mods.length(); i++) {
            JSONObject item = mods.getJSONObject(i);
            ModEntry mod = new ModEntry();
            mod.id = item.getString("id");
            mod.name = item.getString("name");
            mod.author = item.getString("author");
            mod.version = item.getString("version");
            mod.description = item.optString("description", "");
            mod.downloadUrl = item.getString("download_url");
            mod.sha256 = item.getString("sha256").toLowerCase(java.util.Locale.US);
            mod.filename = item.optString("filename", mod.id + "-" + mod.version + ".nrm");
            mod.androidSupported = item.optBoolean("android_supported", true);
            if (mod.androidSupported) result.add(mod);
        }
        return result;
    }

    public static File downloadVerified(ModEntry mod, File destinationDir) throws Exception {
        destinationDir.mkdirs();
        File partial = new File(destinationDir, mod.filename + ".part");
        File target = new File(destinationDir, mod.filename);

        HttpURLConnection connection = (HttpURLConnection) new URL(mod.downloadUrl).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(true);
        connection.connect();

        if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
            throw new IllegalStateException("Mod download HTTP " + connection.getResponseCode());
        }

        try (InputStream in = connection.getInputStream();
             FileOutputStream out = new FileOutputStream(partial)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
        } finally {
            connection.disconnect();
        }

        String actual = sha256(partial);
        if (!actual.equalsIgnoreCase(mod.sha256)) {
            partial.delete();
            throw new SecurityException("SHA-256 mismatch for " + mod.id);
        }

        if (target.exists() && !target.delete()) {
            partial.delete();
            throw new IllegalStateException("Could not replace existing mod package");
        }
        if (!partial.renameTo(target)) {
            partial.delete();
            throw new IllegalStateException("Could not finalize mod package");
        }
        Log.i(TAG, "Downloaded verified mod " + mod.id + " " + mod.version);
        return target;
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        StringBuilder out = new StringBuilder(64);
        for (byte b : digest.digest()) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }
}
