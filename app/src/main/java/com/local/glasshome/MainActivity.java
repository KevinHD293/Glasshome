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
    private static final String K_BGANIM = "bganim";
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
        view.setBgAnim(prefs.getBoolean(K_BGANIM, true));
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
        try { unregisterReceiver(pkgReceiver); } catch (Exception ignored) { }
        if (sfx != null) sfx.release();
    }

    @Override
    protected void onPause() {
        super.onPause();
        view.saveFocus();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleExtras(intent);
        sfx.configure(prefs.getBoolean(K_SOUND, true), prefs.getFloat(K_SFXVOL, 0.5f));
        if (view != null) view.setBgAnim(prefs.getBoolean(K_BGANIM, true));
        if (dirty) loadApps();
        view.goHome();
    }

    // adb shell am start -n com.local.glasshome/.MainActivity --es dock "pkg1,pkg2,pkg3,pkg4"
    private void handleExtras(Intent i) {
        if (i == null) return;
        if (i.hasExtra("sound")) prefs.edit().putBoolean(K_SOUND, i.getBooleanExtra("sound", true)).apply();
        if (i.hasExtra("sfxvol")) prefs.edit().putFloat(K_SFXVOL, i.getFloatExtra("sfxvol", 0.5f)).apply();
        if (i.hasExtra("bganim")) prefs.edit().putBoolean(K_BGANIM, i.getBooleanExtra("bganim", true)).apply();
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

    private List<AppEntry> queryApps() {
        PackageManager pm = getPackageManager();
        Map<String, AppEntry> map = new LinkedHashMap<>();
        String[] cats = { Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER };
        for (String cat : cats) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
                ActivityInfo ai = ri.activityInfo;
                if (ai == null || ai.packageName.equals(getPackageName())) continue;
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
                prefs.edit().putString(K_WLAT, r[0]).putString(K_WLON, r[1]).remove(K_WDATA).apply();
                weather = null;
                if (view != null) view.setWeather(null);
                Toast.makeText(this, "Thời tiết: " + r[2], Toast.LENGTH_SHORT).show();
                refreshWeather(true);
            });
        }).start();
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
