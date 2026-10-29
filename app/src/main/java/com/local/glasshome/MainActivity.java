package com.local.glasshome;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {
    private static final String PREFS = "glasshome";
    private static final String K_DOCK = "dock";
    private static final String K_GRID = "grid";
    private static final String K_SOUND = "sound";
    private static final String K_SFXVOL = "sfxvol";
    private static final String K_WLAT = "w_lat";
    private static final String K_WLON = "w_lon";
    private static final String K_WDATA = "w_data";
    private static final String K_WNAME = "w_name";
    private static final String K_WALL = "wallpaper";
    private static final String K_UIGLASS = "ui_glass", K_UISIZE = "ui_size", K_UILABEL = "ui_label", K_UICOLOR = "ui_color", K_UIPOP = "ui_pop";
    private static final String K_WALLSEL = "wall_sel";   // "builtin" or the id of an uploaded photo (w_<time>.jpg)
    private static final String K_HIDDEN = "hidden";   // apps hidden from the home screen (comma separated packages)
    private static final String K_LAST = "last";
    private static final String K_ASKED = "asked_location";

    // Used until you choose your own dock (see README: set-dock.bat).
    private static final String DEFAULT_DOCK =
            "com.google.android.youtube.tv,com.netflix.ninja,com.android.vending,com.android.tv.settings";

    private HomeView view;
    Sfx sfx;
    private Weather weather;
    private boolean weatherBusy = false;
    private SharedPreferences prefs;
    private boolean dirty = true;

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { dirty = true; }
    };
    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { if (view != null) view.tick(); }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        handleExtras(getIntent());
        getWindow().setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        sfx = new Sfx(this, prefs.getBoolean(K_SOUND, true), prefs.getFloat(K_SFXVOL, 0.5f));
        view = new HomeView(this);
        setContentView(view);
        weather = Weather.decode(prefs.getString(K_WDATA, ""));
        view.setWeather(weather);
        view.setPlace(prefs.getString(K_WNAME, "Buôn Ma Thuột"));
        view.setWallpaper(prefs.getBoolean(K_WALL, true));
        applyUiToView();
        showWallpaper();
        requestLocationOnce();
        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
        pf.addDataScheme("package");
        registerReceiver(pkgReceiver, pf);
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter tf = new IntentFilter();
        tf.addAction(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(timeReceiver, tf);
    }

    @Override
    protected void onStop() {
        super.onStop();
        try { unregisterReceiver(timeReceiver); } catch (Exception ignored) { }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (dirty) loadApps(); else checkForChanges();
        refreshWeather(false);
        view.refreshWifi();
        view.tick();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        phone.stop();
        try { unregisterReceiver(pkgReceiver); } catch (Exception ignored) { }
        if (sfx != null) sfx.release();
    }

    @Override
    protected void onPause() {
        super.onPause();
        view.saveFocus();
        view.closePhone();   // the phone settings server never keeps running in the background
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleExtras(intent);
        sfx.configure(prefs.getBoolean(K_SOUND, true), prefs.getFloat(K_SFXVOL, 0.5f));
        if (view != null) view.setWallpaper(prefs.getBoolean(K_WALL, true));
        if (wallpaperOn() && !view.hasWallpaper()) showWallpaper();
        if (dirty) loadApps();
        view.goHome();
    }

    // adb shell am start -n com.local.glasshome/.MainActivity --es dock "pkg1,pkg2,pkg3,pkg4"
    private void handleExtras(Intent i) {
        if (i == null) return;
        if (i.hasExtra("sound")) prefs.edit().putBoolean(K_SOUND, i.getBooleanExtra("sound", true)).apply();
        if (i.hasExtra("sfxvol")) prefs.edit().putFloat(K_SFXVOL, i.getFloatExtra("sfxvol", 0.5f)).apply();
        if (i.hasExtra("wallpaper")) prefs.edit().putBoolean(K_WALL, i.getBooleanExtra("wallpaper", true)).apply();
        String hiddenList = i.getStringExtra("hiddenlist");   // replaces the whole hidden set
        if (hiddenList != null) {
            java.util.Set<String> hs = new java.util.HashSet<>();
            for (String p : hiddenList.split(",")) {
                p = p.trim();
                if (p.length() > 0) hs.add(p);
            }
            saveHidden(hs);
            dirty = true;
        }
        String hide = i.getStringExtra("hide");
        if (hide != null && hide.trim().length() > 0) {
            java.util.Set<String> s = hiddenSet();
            s.add(hide.trim());
            saveHidden(s);
            dirty = true;
        }
        String unhide = i.getStringExtra("unhide");
        if (unhide != null && unhide.trim().length() > 0) {
            if (unhide.trim().equals("all")) prefs.edit().remove(K_HIDDEN).apply();
            else { java.util.Set<String> s = hiddenSet(); s.remove(unhide.trim()); saveHidden(s); }
            dirty = true;
        }
        String city = i.getStringExtra("city");
        if (city != null && city.trim().length() > 0) setCity(city.trim());
        String d = i.getStringExtra("dock");
        if (d != null) {
            prefs.edit().putString(K_DOCK, d).apply();
            dirty = true;
        }
    }

    private void loadApps() {
        dirty = false;
        final String dock = prefs.getString(K_DOCK, DEFAULT_DOCK);
        final String gridOrder = prefs.getString(K_GRID, "");
        final String last = prefs.getString(K_LAST, null);
        new Thread(() -> {
            final List<AppEntry> list = queryApps();
            final String sig = signature();
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                lastSig = sig;
                view.setApps(list, dock, gridOrder, last);
            });
        }).start();
    }

    private String lastSig = "";

    /** Safety net: apps installed or removed while Glass Home was in the background trigger a reload. */
    private void checkForChanges() {
        new Thread(() -> {
            final String sig = signature();
            runOnUiThread(() -> {
                if (!isDestroyed() && !sig.equals(lastSig)) loadApps();
            });
        }).start();
    }

    private String signature() {
        PackageManager pm = getPackageManager();
        ArrayList<String> keys = new ArrayList<>();
        String[] cats = { Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER };
        for (String cat : cats) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                if (ri.activityInfo != null) keys.add(ri.activityInfo.packageName + "/" + ri.activityInfo.name);
            }
        }
        Collections.sort(keys);
        return keys.toString();
    }

    private java.util.Set<String> hiddenSet() {
        java.util.Set<String> s = new java.util.HashSet<>();
        for (String p : prefs.getString(K_HIDDEN, "").split(",")) {
            p = p.trim();
            if (p.length() > 0) s.add(p);
        }
        return s;
    }

    private void saveHidden(java.util.Set<String> s) {
        StringBuilder sb = new StringBuilder();
        for (String p : s) {
            if (sb.length() > 0) sb.append(',');
            sb.append(p);
        }
        prefs.edit().putString(K_HIDDEN, sb.toString()).apply();
    }

    /** Hides an app from the home screen only (the app itself stays installed and enabled). */
    void hideApp(AppEntry e) {
        java.util.Set<String> s = hiddenSet();
        s.add(e.cn.getPackageName());
        saveHidden(s);
        Toast.makeText(this, "Đã ẩn " + e.label + ". Hiện lại: hidden-apps.bat show all", Toast.LENGTH_LONG).show();
        dirty = true;
        loadApps();
    }

    private List<AppEntry> queryApps() {
        PackageManager pm = getPackageManager();
        Map<String, AppEntry> map = new LinkedHashMap<>();
        java.util.Set<String> hiddenPkgs = hiddenSet();
        String[] cats = { Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER };
        for (String cat : cats) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                ActivityInfo ai = ri.activityInfo;
                if (ai == null || ai.packageName.equals(getPackageName())) continue;
                if (hiddenPkgs.contains(ai.packageName)) continue;
                if (map.containsKey(ai.packageName)) continue;
                AppEntry e = new AppEntry(String.valueOf(ri.loadLabel(pm)),
                        new ComponentName(ai.packageName, ai.name));
                Drawable banner = null;
                try {
                    banner = ai.loadBanner(pm);
                    if (banner == null && ai.applicationInfo != null) banner = ai.applicationInfo.loadBanner(pm);
                } catch (Exception ignored) { }
                e.banner = banner;
                if (banner == null) e.icon = ri.loadIcon(pm);
                map.put(ai.packageName, e);
            }
        }
        List<AppEntry> out = new ArrayList<>(map.values());
        Collections.sort(out, new Comparator<AppEntry>() {
            @Override public int compare(AppEntry a, AppEntry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return out;
    }

    void launch(AppEntry e) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.setComponent(e.cn);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(this, "Không mở được " + e.label, Toast.LENGTH_SHORT).show();
        }
    }

    // ---- wallpaper album: built-in pictures + your own photos, changed on a timer with a cross-fade ----

    private boolean wallpaperOn() { return prefs.getBoolean(K_WALL, true); }

    /** The wallpaper is the single picture assets/wallpaper.jpg. */
    private void showWallpaper() {
        if (view == null || !wallpaperOn()) return;
        String sel = prefs.getString(K_WALLSEL, "builtin");
        java.io.File f = sel.startsWith("w_") ? new java.io.File(new java.io.File(getFilesDir(), "walls"), sel) : null;
        if (f != null && f.isFile()) view.showWallpaper("file:" + f.getAbsolutePath());
        else view.showWallpaper("asset:wallpaper.jpg");
    }

    // ---- photos added from the phone page ----

    private java.io.File wallDir() { return new java.io.File(getFilesDir(), "walls"); }

    private String thumbData(java.io.File f) {
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int s = 1;
            while (o.outWidth / (s * 2) >= 240) s *= 2;
            android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
            o2.inSampleSize = s;
            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
            if (b == null) return "";
            int w = 200, h = Math.max(1, b.getHeight() * w / b.getWidth());
            android.graphics.Bitmap t = android.graphics.Bitmap.createScaledBitmap(b, w, h, true);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            t.compress(android.graphics.Bitmap.CompressFormat.JPEG, 70, bo);
            if (t != b) t.recycle();
            b.recycle();
            return "data:image/jpeg;base64," + android.util.Base64.encodeToString(bo.toByteArray(), android.util.Base64.NO_WRAP);
        } catch (Throwable e) {
            return "";
        }
    }

    /** A photo uploaded from the phone page (already shrunk by the browser). Runs on the server's worker thread. */
    private String saveUpload(byte[] data) {
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return "{\"error\":\"bad image\"}";
            int s = 1;
            while (o.outWidth / (s * 2) >= 2048) s *= 2;
            android.graphics.BitmapFactory.Options o2 = new android.graphics.BitmapFactory.Options();
            o2.inSampleSize = s;
            android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, o2);
            if (b == null) return "{\"error\":\"bad image\"}";
            java.io.File dir = wallDir();
            dir.mkdirs();
            java.io.File[] old = dir.listFiles();
            if (old != null && old.length >= 12) {   // keep at most 12 photos
                java.util.Arrays.sort(old);
                for (int i = 0; i < old.length - 11; i++) old[i].delete();
            }
            String id = "w_" + System.currentTimeMillis() + ".jpg";
            java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(dir, id));
            b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out);
            out.close();
            b.recycle();
            prefs.edit().putString(K_WALLSEL, id).putBoolean(K_WALL, true).apply();
            runOnUiThread(new Runnable() {
                @Override public void run() {
                    view.setWallpaper(true);
                    showWallpaper();
                }
            });
            return "{\"ok\":true}";
        } catch (Throwable t) {
            return "{\"error\":\"failed\"}";
        }
    }

    // ---- weather (Open-Meteo): fetched on return to the home screen, at most every 30 minutes ----

    private double pd(String key, double def) {
        try { return Double.parseDouble(prefs.getString(key, String.valueOf(def))); }
        catch (Exception e) { return def; }
    }

    void refreshWeather(final boolean force) {
        if (weatherBusy) return;
        if (!force && weather != null && System.currentTimeMillis() - weather.time < 30L * 60L * 1000L) return;
        weatherBusy = true;
        final double lat = pd(K_WLAT, 12.6667);   // default: Buon Ma Thuot
        final double lon = pd(K_WLON, 108.05);
        new Thread(() -> {
            final Weather w = Weather.fetch(lat, lon);
            runOnUiThread(() -> {
                weatherBusy = false;
                if (isDestroyed() || w == null) return;
                weather = w;
                prefs.edit().putString(K_WDATA, w.encode()).apply();
                if (view != null) view.setWeather(w);
            });
        }).start();
    }

    // adb shell am start -n com.local.glasshome/.MainActivity --es city "'Da Nang'"
    private void setCity(final String name) {
        new Thread(() -> {
            final String[] r = Weather.geocode(name);
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (r == null) {
                    Toast.makeText(this, "Không tìm thấy thành phố: " + name, Toast.LENGTH_LONG).show();
                    return;
                }
                prefs.edit().putString(K_WLAT, r[0]).putString(K_WLON, r[1]).putString(K_WNAME, r[2]).remove(K_WDATA).apply();
                weather = null;
                if (view != null) { view.setWeather(null); view.setPlace(r[2]); }
                Toast.makeText(this, "Thời tiết: " + r[2], Toast.LENGTH_SHORT).show();
                refreshWeather(true);
            });
        }).start();
    }

    // ---- look-and-feel options (the options panel on the TV and the phone page both use these) ----

    void saveUi(boolean glass, int size, int label, boolean color, int pop) {
        prefs.edit().putBoolean(K_UIGLASS, glass).putInt(K_UISIZE, size).putInt(K_UILABEL, label)
                .putBoolean(K_UICOLOR, color).putInt(K_UIPOP, pop).apply();
    }

    private void applyUiToView() {
        view.setUi(prefs.getBoolean(K_UIGLASS, false), prefs.getInt(K_UISIZE, 0), prefs.getInt(K_UILABEL, 2),
                prefs.getBoolean(K_UICOLOR, true), prefs.getInt(K_UIPOP, 1));
    }

    // ---- "Dọn RAM": asks Android to kill the background processes of the other apps ----

    private long freeRamMb(android.app.ActivityManager am) {
        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem / (1024 * 1024);
    }

    /** Kills the background processes of every launchable app except this launcher and the system services. */
    void cleanRam() {
        final android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        final long before = freeRamMb(am);
        PackageManager pm = getPackageManager();
        java.util.Set<String> pkgs = new java.util.HashSet<>();
        String[] cats = { Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER };
        for (String cat : cats) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                if (ri.activityInfo != null) pkgs.add(ri.activityInfo.packageName);
            }
        }
        int n = 0;
        for (String p : pkgs) {
            if (p.equals(getPackageName()) || p.startsWith("com.realtek") || p.startsWith("com.google.android.inputmethod")
                    || p.equals("com.android.systemui") || p.equals("android")) continue;
            try { am.killBackgroundProcesses(p); n++; } catch (Exception ignored) { }
        }
        Toast.makeText(this, "Đang dọn RAM (" + n + " app)...", Toast.LENGTH_SHORT).show();
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() {
                long after = freeRamMb(am), gain = after - before;
                Toast.makeText(MainActivity.this, "Đã dọn RAM: còn trống " + after + " MB" + (gain > 0 ? " (+" + gain + " MB)" : ""),
                        Toast.LENGTH_LONG).show();
                if (view != null) view.tick();
            }
        }, 1500);
    }

    // ---- customize from the phone browser (see PhoneServer and assets/phone.html) ----

    private final PhoneServer phone = new PhoneServer();

    /** Opens the phone settings server. Returns {address, PIN} or null when no port could be opened. */
    String[] startPhone() {
        String url = phone.start(new PhoneServer.Bridge() {
            @Override public String stateJson() { return phoneState(); }
            @Override public void apply(final String kind, final String json) {
                runOnUiThread(new Runnable() {
                    @Override public void run() { applyFromPhone(kind, json); }
                });
            }
            @Override public String page() { return readAsset("phone.html"); }
            @Override public String upload(byte[] image) { return saveUpload(image); }
        });
        return url == null ? null : new String[] { url, phone.pin() };
    }

    void stopPhone() { phone.stop(); }

    private String readAsset(String name) {
        try {
            java.io.InputStream in = getAssets().open(name);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
            return bo.toString("UTF-8");
        } catch (Exception e) {
            return "<h1>phone.html missing</h1>";
        }
    }

    /** Builds the settings JSON on the UI thread (the view's lists are not thread-safe). */
    private String phoneState() {
        final String[] out = new String[1];
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        runOnUiThread(new Runnable() {
            @Override public void run() {
                try { out[0] = buildState(); } catch (Exception e) { out[0] = "{}"; }
                latch.countDown();
            }
        });
        try { latch.await(3, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        return out[0] == null ? "{}" : out[0];
    }

    private String buildState() throws Exception {
        org.json.JSONObject o = new org.json.JSONObject();
        org.json.JSONArray apps = new org.json.JSONArray();
        PackageManager pm = getPackageManager();
        Map<String, String> labels = new LinkedHashMap<>();
        String[] cats = { Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER };
        for (String cat : cats) {
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0)) {
                if (ri.activityInfo == null || ri.activityInfo.packageName.equals(getPackageName())) continue;
                if (!labels.containsKey(ri.activityInfo.packageName)) labels.put(ri.activityInfo.packageName, String.valueOf(ri.loadLabel(pm)));
            }
        }
        List<Map.Entry<String, String>> list = new ArrayList<>(labels.entrySet());
        Collections.sort(list, new Comparator<Map.Entry<String, String>>() {
            @Override public int compare(Map.Entry<String, String> a, Map.Entry<String, String> b) {
                return a.getValue().compareToIgnoreCase(b.getValue());
            }
        });
        for (Map.Entry<String, String> e : list) {
            org.json.JSONObject j = new org.json.JSONObject();
            j.put("p", e.getKey());
            j.put("l", e.getValue());
            apps.put(j);
        }
        o.put("apps", apps);
        o.put("dock", new org.json.JSONArray(view.dockPackages()));
        o.put("hidden", new org.json.JSONArray(new ArrayList<String>(hiddenSet())));
        o.put("sound", prefs.getBoolean(K_SOUND, true));
        o.put("vol", (double) prefs.getFloat(K_SFXVOL, 0.5f));
        o.put("wallpaper", prefs.getBoolean(K_WALL, true));
        o.put("place", prefs.getString(K_WNAME, "Buôn Ma Thuột"));
        o.put("tv", castInfo()[0]);
        org.json.JSONObject ui = new org.json.JSONObject();
        ui.put("glass", prefs.getBoolean(K_UIGLASS, false) ? 1 : 0);
        ui.put("size", prefs.getInt(K_UISIZE, 0));
        ui.put("label", prefs.getInt(K_UILABEL, 2));
        ui.put("color", prefs.getBoolean(K_UICOLOR, true) ? 1 : 0);
        ui.put("pop", prefs.getInt(K_UIPOP, 1));
        o.put("ui", ui);
        o.put("dockMax", view.dockMax());
        org.json.JSONObject walls = new org.json.JSONObject();
        walls.put("sel", prefs.getString(K_WALLSEL, "builtin"));
        org.json.JSONArray items = new org.json.JSONArray();
        java.io.File[] files = wallDir().listFiles();
        if (files != null) {
            java.util.Arrays.sort(files, java.util.Collections.reverseOrder());   // newest first
            for (java.io.File f : files) {
                org.json.JSONObject j = new org.json.JSONObject();
                j.put("id", f.getName());
                j.put("t", thumbData(f));
                items.put(j);
            }
        }
        walls.put("items", items);
        o.put("walls", walls);
        return o.toString();
    }

    private static String joinArray(org.json.JSONArray a) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length(); i++) {
            if (sb.length() > 0) sb.append(',');
            sb.append(a.getString(i));
        }
        return sb.toString();
    }

    /** A change made from the phone page. It goes through the same extras the adb scripts use. */
    private void applyFromPhone(String kind, String json) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            if (kind.equals("ui")) {
                saveUi(o.getInt("glass") == 1, o.getInt("size"), o.getInt("label"), o.getInt("color") == 1, o.getInt("pop"));
                applyUiToView();
                return;
            }
            if (kind.equals("wallsel")) {
                String id = o.getString("id");
                if (id.equals("builtin") || (id.matches("w_[0-9]+\\.jpg") && new java.io.File(wallDir(), id).isFile())) {
                    prefs.edit().putString(K_WALLSEL, id).putBoolean(K_WALL, true).apply();
                    view.setWallpaper(true);
                    showWallpaper();
                }
                return;
            }
            if (kind.equals("walldel")) {
                String id = o.getString("id");
                if (id.matches("w_[0-9]+\\.jpg")) {
                    new java.io.File(wallDir(), id).delete();
                    if (id.equals(prefs.getString(K_WALLSEL, "builtin"))) {
                        prefs.edit().putString(K_WALLSEL, "builtin").apply();
                        showWallpaper();
                    }
                }
                return;
            }
            Intent i = new Intent();
            if (kind.equals("dock")) i.putExtra("dock", joinArray(o.getJSONArray("dock")));
            else if (kind.equals("hidden")) i.putExtra("hiddenlist", joinArray(o.getJSONArray("hidden")));
            else if (kind.equals("city")) i.putExtra("city", o.getString("city"));
            else if (kind.equals("sound")) {
                i.putExtra("sound", o.getBoolean("on"));
                i.putExtra("sfxvol", (float) o.getDouble("vol"));
            } else if (kind.equals("wallpaper")) i.putExtra("wallpaper", o.getBoolean("on"));
            else return;
            handleExtras(i);
            sfx.configure(prefs.getBoolean(K_SOUND, true), prefs.getFloat(K_SFXVOL, 0.5f));
            view.setWallpaper(prefs.getBoolean(K_WALL, true));
            if (wallpaperOn() && !view.hasWallpaper()) showWallpaper();
            if (dirty) loadApps();
        } catch (Exception ignored) { }
    }

    /** {name of the TV, Wi-Fi label, "off" if the built-in Chromecast receiver is disabled else "ok"} */
    String[] castInfo() {
        String name = null;
        try { name = Settings.Global.getString(getContentResolver(), "device_name"); } catch (Exception ignored) { }
        if (name == null || name.trim().length() == 0) name = Build.MODEL;
        String state = "ok";
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo("com.google.android.apps.mediashell", 0);
            if (!ai.enabled) state = "off";
        } catch (Exception ignored) { }   // not found / not visible: say nothing
        return new String[] { name, wifiLabel(), state };
    }

    /** Only apps the user installed can be removed; system apps cannot. */
    boolean canUninstall(AppEntry e) {
        try {
            ApplicationInfo ai = getPackageManager().getApplicationInfo(e.cn.getPackageName(), 0);
            return (ai.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (Exception ex) {
            return false;
        }
    }

    /** Opens Android's own uninstall confirmation dialog. */
    void uninstall(AppEntry e) {
        try {
            Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + e.cn.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(this, "Không mở được hộp thoại gỡ cài đặt", Toast.LENGTH_SHORT).show();
        }
    }

    void openSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ex) {
            Toast.makeText(this, "Không mở được Settings", Toast.LENGTH_SHORT).show();
        }
    }

    void openWifi() {
        try {
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception ex) {
            openSettings();
        }
    }

    // ---- TV inputs (TV Input Framework) ----

    List<InputItem> listInputs() {
        List<InputItem> out = new ArrayList<>();
        try {
            TvInputManager m = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            if (m == null) return out;
            boolean tuner = false;
            for (TvInputInfo info : m.getTvInputList()) {
                try { if (info.isHidden(this)) continue; } catch (Exception ignored) { }
                int t = info.getType();
                if (info.isPassthroughInput()) {
                    CharSequence cl = info.loadCustomLabel(this);
                    CharSequence l = (cl != null && cl.length() > 0) ? cl : info.loadLabel(this);
                    out.add(new InputItem(String.valueOf(l), info.getId(), true,
                            t == TvInputInfo.TYPE_HDMI ? 1 : 2));
                } else if (t == TvInputInfo.TYPE_TUNER && !tuner) {
                    out.add(new InputItem("Live TV", info.getId(), false, 0));
                    tuner = true;
                }
            }
        } catch (Exception ignored) { }
        Collections.sort(out, new Comparator<InputItem>() {
            @Override public int compare(InputItem a, InputItem b) {
                if (a.rank != b.rank) return a.rank - b.rank;
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return out;
    }

    void switchInput(InputItem it) {
        try {
            Intent i = it.passthrough
                    ? new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(it.id))
                    : new Intent(Intent.ACTION_VIEW, TvContract.Channels.CONTENT_URI);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(this, "Không chuyển được sang " + it.label, Toast.LENGTH_SHORT).show();
        }
    }

    // ---- Wi-Fi label (read once on resume, no polling) ----

    String wifiLabel() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = cm.getActiveNetwork();
            if (n == null) return "Offline";
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "Ethernet";
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo wi = wm.getConnectionInfo();
            String s = wi == null ? null : wi.getSSID();
            if (s != null && !s.equals("<unknown ssid>") && !s.equals("0x") && s.length() > 0) {
                if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) s = s.substring(1, s.length() - 1);
                return s;
            }
        } catch (Exception ignored) { }
        return "Wi-Fi";
    }

    private void requestLocationOnce() {
        if (Build.VERSION.SDK_INT < 23) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) return;
        if (prefs.getBoolean(K_ASKED, false)) return;
        prefs.edit().putBoolean(K_ASKED, true).apply();
        requestPermissions(new String[] { Manifest.permission.ACCESS_FINE_LOCATION }, 1);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (view != null) view.refreshWifi();
    }

    void saveLayout(String dockCsv, String gridCsv) {
        prefs.edit().putString(K_DOCK, dockCsv).putString(K_GRID, gridCsv).apply();
    }

    void saveLast(String s) {
        if (s != null) prefs.edit().putString(K_LAST, s).apply();
    }

    @Override
    public boolean onKeyDown(int k, KeyEvent e) {
        if (view != null && view.onNavDown(k, e)) return true;
        return super.onKeyDown(k, e);
    }

    @Override
    public boolean onKeyUp(int k, KeyEvent e) {
        if (view != null && view.onNavUp(k, e)) return true;
        return super.onKeyUp(k, e);
    }
}
