package com.local.glasshome;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Environment;
import android.os.StatFs;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One custom view draws the whole home screen: black background, header, top pills,
 * dock band, app grid, a single springing "glass" highlight and the Inputs dropdown.
 * Focus is handled by our own model (zone + index), so D-pad movement is deterministic.
 * No blur, no video, no polling: it only redraws while a spring is moving or the minute changes.
 */
final class HomeView extends View {
    private static final int ZT = 0, ZD = 1, ZG = 2;   // zones: top bar, dock, grid
    private int cols = 4;                 // grid columns: 4 / 5 / 6 for large / medium / small icons
    private float popTarget = 1.07f;      // scale of the selected tile
    private float heldScale() { return Math.max(1.14f, popTarget + 0.07f); }
    private static final float[] GLOW_W = { 7f };
    private static final int[] GLOW_A = { 0x38 };
    private static final float[] FOG_Y = { -0.45f, -0.15f, 0.15f, 0.45f };
    private static final float[] FOG_W = { 0.7f, 0.9f, 0.9f, 0.6f };
    private static final int M_MOVE = 0, M_DEL = 1, M_NOTE = 2, M_CLOSE = 3, M_HIDE = 4;   // app menu rows
    private static final int P_IN = 0, P_CAST = 1, P_ARR = 2, P_WIFI = 3, P_SET = 4, NP = 5;   // top-bar icon buttons

    private static final class Spring {
        float v, t, vel, k = 520f, c = 34f, eps = 0.3f, veps = 2f;
        void snap(float x) { v = x; t = x; vel = 0; }
        boolean step(float dt) {
            if (v == t && vel == 0) return false;
            float a = k * (t - v) - c * vel;
            vel += a * dt;
            v += vel * dt;
            if (Math.abs(t - v) < eps && Math.abs(vel) < veps) { v = t; vel = 0; return false; }
            return true;
        }
    }

    private final MainActivity act;
    private final float dp;

    private final ArrayList<AppEntry> dock = new ArrayList<>();
    private final ArrayList<AppEntry> grid = new ArrayList<>();
    private int zone = ZD, topIdx = 0, dockIdx = 0, gridIdx = 0;
    private boolean centerDown = false, longHandled = false;
    private boolean arrange = false, tempArrange = false;   // arrange mode / entered by long-press
    private AppEntry held = null;                           // app currently picked up
    private boolean menuOpen = false;
    private boolean castOpen = false, castOff = false;
    private boolean phoneOpen = false;

    // user options (options panel on the TV, or the phone page)
    private boolean uiGlass = false, uiColor = true, colorFollow = true, optOpen = false;
    private int uiSize = 0, uiLabel = 2, uiPop = 1, optIdx = 0, topRow = 0;
    private float labelSp = 22f, nameSp = 52f;
    private final RectF ramRect = new RectF();
    private static final String[] OPT_TITLE = { "Khay kính ở dock", "Cỡ icon ứng dụng", "Tên ứng dụng",
            "Viền đổi màu theo app", "Phóng to khi chọn", "Tuỳ chỉnh từ điện thoại" };
    private static final String[][] OPT_VALUES = { { "Tắt", "Bật" }, { "Lớn", "Vừa", "Nhỏ" }, { "Ẩn", "Nhỏ", "Vừa", "Lớn" },
            { "Tắt", "Bật" }, { "Tắt", "Vừa", "Lớn" } };
    private String phoneUrl = "", phonePin = "";
    private String castName = "", castNet = "";
    private int menuIdx = 0;
    private AppEntry menuApp = null;
    private final ArrayList<String> menuLabels = new ArrayList<>();
    private final ArrayList<Integer> menuIds = new ArrayList<>();
    private ArrayList<AppEntry> snapDock = new ArrayList<>(), snapGrid = new ArrayList<>();

    private boolean ddOpen = false;
    private int ddIdx = 0;
    private List<InputItem> inputs = new ArrayList<>();

    private final Spring hx = new Spring(), hy = new Spring(), hw = new Spring(), hh = new Spring();
    private final Spring hr = new Spring(), scroll = new Spring(), pop = new Spring(), gx = new Spring();

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wallPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Path path = new Path(), path2 = new Path();
    private final RectF rf = new RectF(), tmp = new RectF();
    private Typeface light, regular;
    private Bitmap wall, wallNext;               // current wallpaper and the one fading in
    private Bitmap barBlur, barBlurNext;         // blurred copies of them behind the top bar
    private boolean wallOn = true;
    private long fadeStart = 0;
    private float wallFade = 1f;
    private boolean fading = false;
    private volatile int wallMaxW = 0;
    private String memStr = "", storStr = "";
    private final Paint scrimPaint = new Paint();
    private final Matrix scrimM = new Matrix();
    private final LinearGradient scrimShader = new LinearGradient(0, 0, 0, 1,
            new int[] { 0x44000000, 0x00000000, 0x00000000, 0x44000000 }, new float[] { 0f, 0.35f, 0.65f, 1f }, Shader.TileMode.CLAMP);
    private final Rect wallSrc = new Rect(), tmpSrc = new Rect();
    private final RectF wallDst = new RectF();
    private final Paint blurPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private Weather weather;
    private String place = "";
    private float clockW = 0;
    private final RectF rg = new RectF();
    private final Rect tb = new Rect();
    // unit-height vertical gradients (scaled to each panel): smooth sheen and rim, no hard light/dark seam
    private final Matrix gm = new Matrix();
    private final LinearGradient bodyShader = new LinearGradient(0, 0, 0, 1,
            new int[] { 0xFFFFFFFF, 0xB8FFFFFF, 0x80FFFFFF, 0x61FFFFFF }, new float[] { 0f, 0.35f, 0.7f, 1f }, Shader.TileMode.CLAMP);
    private final LinearGradient rimShader = new LinearGradient(0, 0, 0, 1,
            new int[] { 0xF0FFFFFF, 0x99FFFFFF, 0x59FFFFFF, 0x3DFFFFFF }, new float[] { 0f, 0.3f, 0.65f, 1f }, Shader.TileMode.CLAMP);

    private float W, H, mx, dockTop, bandH, slotW, slotH, padD, gapD;
    private float cellW, tileW, tileH, rowH, gridTop, pillH;
    private final RectF[] pill = { new RectF(), new RectF(), new RectF(), new RectF(), new RectF() };
    private final String[] pillText = { "Inputs", "Cast", "Sắp xếp", "Wi-Fi", "Settings" };

    private int glowCur = 0xFF8E8E93, glowTgt = 0xFF8E8E93, shaderColor = 0;
    private RadialGradient glowShader;

    private long lastNs = 0, minuteKey = -1;
    private String timeStr = "", dateStr = "", greetStr = "", lunarStr = "";
    private final SimpleDateFormat timeFmt, dateFmt;

    HomeView(Context ctx) {
        super(ctx);
        act = (MainActivity) ctx;
        dp = ctx.getResources().getDisplayMetrics().density;
        setFocusable(true);
        pop.eps = 0.002f;
        pop.veps = 0.02f;
        pop.snap(popTarget);

        // Fonts in app/src/main/assets/fonts/: the first file that exists wins, else the system font.
        light = font(ctx, "sans-serif-light", "fonts/MyriadPro-Light.otf", "fonts/Outfit-Light.ttf");
        regular = font(ctx, "sans-serif", "fonts/MyriadPro-Regular.otf", "fonts/Outfit-Regular.ttf");

        wallPaint.setAlpha(255);

        text.setShadowLayer(3 * dp, 0, 1.5f * dp, 0x66000000);
        boolean h24 = android.text.format.DateFormat.is24HourFormat(ctx);
        timeFmt = new SimpleDateFormat(h24 ? "HH:mm" : "h:mm", Locale.getDefault());
        dateFmt = new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault());
        pillText[P_WIFI] = act.wifiLabel();
    }

    private static Typeface font(Context ctx, String systemFamily, String... files) {
        for (String f : files) {
            try { return Typeface.createFromAsset(ctx.getAssets(), f); } catch (Exception ignored) { }
        }
        return Typeface.create(systemFamily, Typeface.NORMAL);
    }

    // ------------------------------------------------------------------ data

    void setApps(List<AppEntry> all, String dockCsv, String gridCsv, String last) {
        String cur = focusKey();
        if (cur == null) cur = last;
        dock.clear();
        grid.clear();
        Map<String, AppEntry> by = new HashMap<>();
        for (AppEntry e : all) by.put(e.cn.getPackageName(), e);
        Set<String> used = new HashSet<>();
        if (dockCsv != null) {
            for (String s : dockCsv.split(",")) {
                s = s.trim();
                AppEntry e = by.get(s);
                if (e != null && used.add(s) && dock.size() < cols) dock.add(e);
            }
        }
        for (AppEntry e : all) {
            if (dock.size() >= cols) break;
            if (used.add(e.cn.getPackageName())) dock.add(e);
        }
        for (AppEntry e : all) if (!used.contains(e.cn.getPackageName())) grid.add(e);
        // saved grid order first, then any other apps in A-Z order
        if (gridCsv != null && gridCsv.length() > 0) {
            ArrayList<AppEntry> ordered = new ArrayList<>();
            Set<String> in = new HashSet<>();
            for (String s2 : gridCsv.split(",")) {
                s2 = s2.trim();
                AppEntry e = by.get(s2);
                if (e != null && !used.contains(s2) && in.add(s2)) ordered.add(e);
            }
            for (AppEntry e : grid) if (!in.contains(e.cn.getPackageName())) ordered.add(e);
            grid.clear();
            grid.addAll(ordered);
        }
        menuOpen = false; castOpen = false; held = null; arrange = false; tempArrange = false; pillText[P_ARR] = "Sắp xếp";

        // restore focus
        zone = ZD; dockIdx = 0; gridIdx = 0;
        if (cur != null && cur.length() > 2) {
            String pkg = cur.substring(2);
            if (cur.startsWith("G:")) {
                for (int i = 0; i < grid.size(); i++)
                    if (grid.get(i).cn.getPackageName().equals(pkg)) { zone = ZG; gridIdx = i; }
            } else {
                for (int i = 0; i < dock.size(); i++)
                    if (dock.get(i).cn.getPackageName().equals(pkg)) dockIdx = i;
            }
        }
        fixZone();
        relayout();
        retarget(true);
    }

    private void fixZone() {
        if (zone == ZD && dock.isEmpty()) zone = grid.isEmpty() ? ZT : ZG;
        if (zone == ZG && grid.isEmpty()) zone = dock.isEmpty() ? ZT : ZD;
        dockIdx = Math.max(0, Math.min(dockIdx, Math.max(0, dock.size() - 1)));
        gridIdx = Math.max(0, Math.min(gridIdx, Math.max(0, grid.size() - 1)));
    }

    private String focusKey() {
        if (zone == ZG && !grid.isEmpty()) return "G:" + grid.get(gridIdx).cn.getPackageName();
        if (!dock.isEmpty()) return "D:" + dock.get(dockIdx).cn.getPackageName();
        return null;
    }

    void saveFocus() { act.saveLast(focusKey()); }

    void goHome() {
        ddOpen = false;
        optOpen = false;
        menuOpen = false;
        castOpen = false;
        closePhone();
        if (held != null) cancelMove();
        if (arrange) exitArrange();
        if (zone != ZD) { zone = ZD; fixZone(); }
        retarget(false);
    }

    void refreshWifi() {
        String s = act.wifiLabel();
        if (s.length() > 20) s = s.substring(0, 20) + "…";
        pillText[P_WIFI] = s;
        relayout();
        retarget(false);
    }

    void setWeather(Weather w) { weather = w; invalidate(); }

    void setWallpaper(boolean on) {
        if (wallOn == on) return;
        wallOn = on;
        rebuildBlurs();
        invalidate();
    }

    boolean hasWallpaper() { return wall != null || wallNext != null; }

    void setPlace(String name) { place = name == null ? "" : name; invalidate(); }

    void tick() { refreshMemory(); invalidate(); }

    /** RAM line shown under the clock; refreshed once a minute and when the launcher comes back, never per frame. */
    private void refreshMemory() {
        try {
            ActivityManager am = (ActivityManager) act.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            long totalMb = mi.totalMem / (1024 * 1024), freeMb = mi.availMem / (1024 * 1024);
            memStr = "Bộ nhớ " + (totalMb - freeMb) + " / " + totalMb + " MB  ·  Còn trống "
                    + String.format(new Locale("vi", "VN"), "%.1f", freeMb / 1024f) + " GB";
        } catch (Exception e) {
            memStr = "";
        }
        try {   // internal storage (the /data partition): used / total and what is left
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            double total = fs.getTotalBytes() / 1073741824.0, free = fs.getAvailableBytes() / 1073741824.0;
            Locale vi = new Locale("vi", "VN");
            storStr = "Lưu trữ " + String.format(vi, "%.1f", total - free) + " / " + String.format(vi, "%.1f", total)
                    + " GB  ·  Còn trống " + String.format(vi, "%.1f", free) + " GB";
        } catch (Exception e) {
            storStr = "";
        }
    }

    // ---------------------------------------------------------------- layout

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        relayout();
        retarget(true);
    }

    private void relayout() {
        if (getWidth() == 0) return;
        W = getWidth();
        H = getHeight();
        mx = 64 * dp;
        padD = 24 * dp;
        gapD = 24 * dp;
        // dock and grid share one tile size and the same four columns, edge to edge between the margins
        tileW = (W - 2 * mx - (cols - 1) * gapD) / cols;
        tileH = tileW * 9f / 16f;
        cellW = tileW + gapD;
        slotW = tileW;
        slotH = tileH;
        bandH = slotH + 2 * padD;
        dockTop = H - 56 * dp - bandH;
        rowH = tileH + 64 * dp;
        gridTop = H + 24 * dp;
        pillH = 48 * dp;

        float right = W - mx;
        for (int i = NP - 1; i >= 0; i--) {
            pill[i].set(right - pillH, 40 * dp, right, 40 * dp + pillH);
            right -= pillH + 16 * dp;
        }
        bandRect(rg);
        text.setTypeface(regular);
        text.setTextSize(17 * dp);
        text.setLetterSpacing(0f);
        float rw = 16 * dp + 22 * dp + 8 * dp + text.measureText("Dọn RAM") + 18 * dp, rh = 40 * dp;
        ramRect.set(rg.right - rw, rg.bottom + 12 * dp, rg.right, rg.bottom + 12 * dp + rh);
        scrimM.setScale(1f, H);
        scrimShader.setLocalMatrix(scrimM);
        scrimPaint.setShader(scrimShader);
        rebuildBlurs();
    }

    private void slot(int i, RectF r) {
        float l = mx + i * cellW;
        r.set(l, dockTop + padD, l + slotW, dockTop + padD + slotH);
    }

    private void gridRect(int i, RectF r) {
        float l = mx + (i % cols) * cellW;
        float t = gridTop + (i / cols) * rowH;
        r.set(l, t, l + tileW, t + tileH);
    }

    private float scrollTarget() {
        if (zone != ZG) return 0;
        int row = gridIdx / cols;
        return Math.max(0, gridTop + row * rowH + tileH + 64 * dp - H);
    }

    private void retarget(boolean snap) {
        if (W == 0) return;
        float infl, rad;
        if (zone == ZT) {
            if (topRow == 1) { tmp.set(ramRect); infl = 4 * dp; rad = ramRect.height() / 2 + infl; }
            else { tmp.set(pill[topIdx]); infl = 4 * dp; rad = pillH / 2 + infl; }
        } else if (zone == ZD && !dock.isEmpty()) {
            slot(dockIdx, tmp); infl = 0; rad = 18 * dp;
        } else if (zone == ZG && !grid.isEmpty()) {
            gridRect(gridIdx, tmp); infl = 0; rad = 18 * dp;
        } else {
            tmp.set(pill[0]); infl = 4 * dp; rad = pillH / 2 + infl;
        }
        tmp.inset(-infl, -infl);
        hx.t = tmp.left; hy.t = tmp.top; hw.t = tmp.width(); hh.t = tmp.height(); hr.t = rad;
        scroll.t = scrollTarget();
        if (!dock.isEmpty()) {
            slot(dockIdx, rf);
            gx.t = rf.centerX();
        } else {
            gx.t = W / 2;
        }
        AppEntry focused = null;
        if (zone == ZG && !grid.isEmpty()) focused = grid.get(gridIdx);
        else if (!dock.isEmpty()) focused = dock.get(dockIdx);
        if (focused != null) glowTgt = colorOf(focused);
        if (snap) {
            hx.snap(hx.t); hy.snap(hy.t); hw.snap(hw.t); hh.snap(hh.t); hr.snap(hr.t);
            scroll.snap(scroll.t); gx.snap(gx.t); pop.snap(popTarget);
            glowCur = glowTgt;
        }
        invalidate();
    }

    private void focusMoved() {
        pop.v = 1f; pop.vel = 0f; pop.t = popTarget;
        retarget(false);
    }

    // ------------------------------------------------------------------ keys

    boolean onNavDown(int k, KeyEvent e) {
        if (optOpen) return optDown(k, e);
        if (phoneOpen) {   // only Back or a fresh OK press closes it (OK repeats from the long press are ignored)
            boolean ok = (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER) && e.getRepeatCount() == 0;
            if (k == KeyEvent.KEYCODE_BACK || ok) closePhone();
            return true;
        }
        if (castOpen) { castOpen = false; invalidate(); return true; }   // any key closes the Cast panel
        if (menuOpen) return menuDown(k, e);
        if (ddOpen) return ddDown(k, e);
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT: move(-1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: move(1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_UP: move(0, -1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: move(0, 1); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (e.getRepeatCount() == 0) { centerDown = true; longHandled = false; }
                else if (e.getRepeatCount() == 1 && centerDown && !longHandled) { longHandled = true; longPress(); }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (held != null) { cancelMove(); return true; }
                if (arrange) { exitArrange(); return true; }
                if (zone != ZD && !dock.isEmpty()) { zone = ZD; topRow = 0; focusMoved(); }
                return true; // a launcher never exits on Back
        }
        return false;
    }

    boolean onNavUp(int k, KeyEvent e) {
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (centerDown) { centerDown = false; if (!longHandled) activate(); longHandled = false; }
            return true;
        }
        return false;
    }

    private boolean ddDown(int k, KeyEvent e) {
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: {
                int o = ddIdx;
                ddIdx = Math.max(0, ddIdx - 1);
                if (o != ddIdx) act.sfx.move();
                break;
            }
            case KeyEvent.KEYCODE_DPAD_DOWN: {
                int o = ddIdx;
                ddIdx = Math.min(Math.max(0, inputs.size() - 1), ddIdx + 1);
                if (o != ddIdx) act.sfx.move();
                break;
            }
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_BACK: ddOpen = false; break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (e.getRepeatCount() == 0) centerDown = true;
                break;
        }
        invalidate();
        return true;
    }

    private void move(int dx, int dy) {
        if (held != null) { moveHeld(dx, dy); return; }
        int oz = zone, ot = topIdx, od = dockIdx, og = gridIdx, orow = topRow;
        if (zone == ZT) {
            if (topRow == 0) {
                if (dx != 0) topIdx = Math.max(0, Math.min(NP - 1, topIdx + dx));   // ends do not fall through
                else if (dy > 0) topRow = 1;                                        // down to the "Dọn RAM" button
            } else if (dy < 0) {
                topRow = 0;
            } else if (dy > 0) {
                if (!dock.isEmpty()) zone = ZD;
                else if (!grid.isEmpty()) { zone = ZG; gridIdx = 0; }
            }
        } else if (zone == ZD) {
            if (dx != 0) dockIdx = Math.max(0, Math.min(dock.size() - 1, dockIdx + dx));
            else if (dy < 0) { zone = ZT; topRow = 1; }
            else if (dy > 0 && !grid.isEmpty()) { zone = ZG; gridIdx = Math.min(dockIdx, grid.size() - 1); }
        } else {
            int row = gridIdx / cols;
            if (dx != 0) {
                int n = gridIdx + dx;
                if (n >= 0 && n < grid.size() && n / cols == row) gridIdx = n;
            } else if (dy > 0) {
                int n = gridIdx + cols;
                if (n < grid.size()) gridIdx = n;
                else if ((row + 1) * cols < grid.size()) gridIdx = grid.size() - 1;  // partial last row
            } else if (dy < 0) {
                if (row > 0) gridIdx -= cols;
                else if (!dock.isEmpty()) { zone = ZD; dockIdx = Math.min(gridIdx % cols, dock.size() - 1); }
                else zone = ZT;
            }
        }
        if (oz != zone || ot != topIdx || od != dockIdx || og != gridIdx || orow != topRow) { act.sfx.move(); focusMoved(); }
    }

    private void activate() {
        if (menuOpen) { menuSelect(); return; }
        if (ddOpen) {
            ddOpen = false;
            act.sfx.open();
            if (!inputs.isEmpty()) act.switchInput(inputs.get(ddIdx));
            invalidate();
            return;
        }
        if (held != null) { drop(); return; }
        if (!(arrange && (zone == ZD || zone == ZG))) act.sfx.open();
        if (zone == ZT) {
            if (topRow == 1) act.cleanRam();
            else if (topIdx == P_CAST) openCast();
            else if (topIdx == P_ARR) toggleArrange();
            else if (topIdx == P_IN) { inputs = act.listInputs(); ddIdx = 0; ddOpen = true; invalidate(); }
            else if (topIdx == P_WIFI) act.openWifi();
            else act.openSettings();
        } else if (arrange && (zone == ZD || zone == ZG)) pickUp();
        else if (zone == ZD && !dock.isEmpty()) act.launch(dock.get(dockIdx));
        else if (zone == ZG && !grid.isEmpty()) act.launch(grid.get(gridIdx));
    }


    // ------------------------------------------------------------ app menu

    /** Holding OK on a dock or grid tile opens a small menu: move / uninstall / close. */
    private void openMenu() {
        AppEntry cur = null;
        if (zone == ZD && !dock.isEmpty()) cur = dock.get(dockIdx);
        else if (zone == ZG && !grid.isEmpty()) cur = grid.get(gridIdx);
        if (cur == null) return;
        menuApp = cur;
        menuLabels.clear();
        menuIds.clear();
        menuLabels.add("Di chuyển");
        menuIds.add(M_MOVE);
        if (act.canUninstall(cur)) { menuLabels.add("Gỡ cài đặt"); menuIds.add(M_DEL); }
        else { menuLabels.add("App hệ thống, không gỡ được"); menuIds.add(M_NOTE); }
        menuLabels.add("Ẩn khỏi màn hình");
        menuIds.add(M_HIDE);
        menuLabels.add("Đóng");
        menuIds.add(M_CLOSE);
        menuIdx = 0;
        menuOpen = true;
        act.sfx.pick();
        invalidate();
    }

    private boolean menuDown(int k, KeyEvent e) {
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: stepMenu(-1); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: stepMenu(1); break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_BACK: menuOpen = false; break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (e.getRepeatCount() == 0) { centerDown = true; longHandled = false; }
                break;
        }
        invalidate();
        return true;
    }

    private void stepMenu(int dir) {
        int n = menuIds.size();
        int i = menuIdx;
        do { i += dir; } while (i >= 0 && i < n && menuIds.get(i) == M_NOTE);
        if (i >= 0 && i < n) { menuIdx = i; act.sfx.move(); }
    }

    private void menuSelect() {
        int id = menuIds.get(menuIdx);
        if (id == M_NOTE) return;
        menuOpen = false;
        if (id == M_MOVE) {
            if (!arrange) { arrange = true; tempArrange = true; setArrangeLabel(); }
            pickUp();
        } else if (id == M_DEL) {
            act.sfx.open();
            act.uninstall(menuApp);
        } else if (id == M_HIDE) {
            act.sfx.open();
            act.hideApp(menuApp);
        }
        invalidate();
    }

    private void drawMenu(Canvas c) {
        if (!menuOpen || menuApp == null) return;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xAA000000);
        c.drawRect(0, 0, W, H, p);

        float w = 440 * dp, rowH3 = 56 * dp, titleH = 68 * dp;
        float h = titleH + menuIds.size() * rowH3 + 16 * dp;
        float x = (W - w) / 2f, y = (H - h) / 2f;
        rf.set(x, y, x + w, y + h);
        p.setColor(0xF2222226);
        c.drawRoundRect(rf, 28 * dp, 28 * dp, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f * dp);
        p.setColor(0x50FFFFFF);
        c.drawRoundRect(rf, 28 * dp, 28 * dp, p);
        p.setStyle(Paint.Style.FILL);

        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(light);
        text.setTextSize(26 * dp);
        text.setColor(Color.WHITE);
        CharSequence t = TextUtils.ellipsize(menuApp.label, text, w - 56 * dp, TextUtils.TruncateAt.END);
        c.drawText(t, 0, t.length(), x + 28 * dp, y + 44 * dp, text);

        text.setTypeface(regular);
        text.setTextSize(22 * dp);
        Paint.FontMetrics fm = text.getFontMetrics();
        for (int i = 0; i < menuIds.size(); i++) {
            float top = y + titleH + i * rowH3;
            int id = menuIds.get(i);
            if (i == menuIdx && id != M_NOTE) {
                tmp.set(x + 10 * dp, top + 4 * dp, x + w - 10 * dp, top + rowH3 - 4 * dp);
                p.setColor(0x55FFFFFF);
                c.drawRoundRect(tmp, 18 * dp, 18 * dp, p);
            }
            text.setColor(id == M_NOTE ? 0x88FFFFFF : id == M_DEL ? 0xFFFF6B6B : Color.WHITE);
            c.drawText(menuLabels.get(i), x + 28 * dp, top + rowH3 / 2f - (fm.ascent + fm.descent) / 2f, text);
        }
    }

    // ------------------------------------------------------- arrange / move

    /** Holding OK on a dock or grid tile starts arrange mode and picks the app up. */
    private void longPress() {
        if (ddOpen || menuOpen || held != null) return;
        if (zone == ZT) {                       // long press on the gear: customize from the phone
            if (topIdx == P_SET && topRow == 0) openOptions();   // long press on the gear: options
            return;
        }
        if (zone != ZD && zone != ZG) return;
        openMenu();
    }

    private void setArrangeLabel() {
        pillText[P_ARR] = arrange ? "Xong" : "Sắp xếp";
        relayout();
        retarget(false);
    }

    private void toggleArrange() {
        arrange = !arrange;
        tempArrange = false;
        setArrangeLabel();
    }

    private void exitArrange() {
        arrange = false;
        tempArrange = false;
        setArrangeLabel();
    }

    private void pickUp() {
        AppEntry cur = null;
        if (zone == ZD && !dock.isEmpty()) cur = dock.get(dockIdx);
        else if (zone == ZG && !grid.isEmpty()) cur = grid.get(gridIdx);
        if (cur == null) return;
        held = cur;
        act.sfx.pick();
        snapDock = new ArrayList<>(dock);
        snapGrid = new ArrayList<>(grid);
        focusMoved();
    }

    private void drop() {
        held = null;
        act.sfx.drop();
        saveLayoutNow();
        if (tempArrange) exitArrange(); else focusMoved();
    }

    private void cancelMove() {
        AppEntry h = held;
        held = null;
        act.sfx.drop();
        dock.clear(); dock.addAll(snapDock);
        grid.clear(); grid.addAll(snapGrid);
        int di = dock.indexOf(h);
        if (di >= 0) { zone = ZD; dockIdx = di; }
        else { zone = ZG; gridIdx = Math.max(0, grid.indexOf(h)); }
        fixZone();
        if (tempArrange) exitArrange(); else focusMoved();
    }

    private void moveHeld(int dx, int dy) {
        boolean changed = false;
        if (zone == ZD) {
            int i = dockIdx;
            if (dx != 0) {
                int n = i + dx;
                if (n >= 0 && n < dock.size()) { dock.remove(i); dock.add(n, held); dockIdx = n; changed = true; }
            } else if (dy > 0 && dock.size() > 1) {
                if (grid.isEmpty()) {
                    dock.remove(i); grid.add(held); zone = ZG; gridIdx = 0; changed = true;
                } else {
                    // the grid app under this slot takes the dock slot, the held app goes to the grid
                    int g = Math.min(i, grid.size() - 1);
                    AppEntry other = grid.get(g);
                    dock.set(i, other); grid.set(g, held);
                    zone = ZG; gridIdx = g; changed = true;
                }
            }
        } else if (zone == ZG) {
            int i = gridIdx;
            if (dx != 0) {
                int n = i + dx;
                if (n >= 0 && n < grid.size()) { grid.remove(i); grid.add(n, held); gridIdx = n; changed = true; }
            } else if (dy > 0) {
                int n = Math.min(grid.size() - 1, i + cols);
                if (n > i) { grid.remove(i); grid.add(n, held); gridIdx = n; changed = true; }
            } else if (dy < 0) {
                if (i >= cols) {
                    grid.remove(i); grid.add(i - cols, held); gridIdx = i - cols; changed = true;
                } else {
                    int slotI = Math.min(i % cols, dock.size());
                    if (dock.size() < cols) {
                        grid.remove(i);
                        dock.add(slotI, held);
                        zone = ZD; dockIdx = slotI; changed = true;
                    } else {
                        AppEntry other = dock.get(slotI);
                        dock.set(slotI, held); grid.set(i, other);
                        zone = ZD; dockIdx = slotI; changed = true;
                    }
                }
            }
        }
        if (changed) { act.sfx.move(); fixZone(); focusMoved(); }
    }

    private void saveLayoutNow() {
        StringBuilder d = new StringBuilder(), g = new StringBuilder();
        for (AppEntry e : dock) { if (d.length() > 0) d.append(','); d.append(e.cn.getPackageName()); }
        for (AppEntry e : grid) { if (g.length() > 0) g.append(','); g.append(e.cn.getPackageName()); }
        act.saveLayout(d.toString(), g.toString());
    }

    private void drawHint(Canvas c) {
        String msg;
        if (held != null) msg = "Đang di chuyển " + held.label + "  ·  Mũi tên: di chuyển  ·  OK: thả  ·  Back: huỷ";
        else if (arrange) msg = "Chế độ sắp xếp  ·  OK: nhấc app lên  ·  Back: thoát";
        else return;
        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(regular);
        text.setTextSize(18 * dp);
        float maxW = Math.max(240 * dp, pill[0].left - mx - 24 * dp);
        CharSequence s = TextUtils.ellipsize(msg, text, maxW - 28 * dp, TextUtils.TruncateAt.END);
        float tw = text.measureText(s, 0, s.length());
        rf.set(mx, 4 * dp, mx + tw + 28 * dp, 32 * dp);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xEE2A2A2E);
        c.drawRoundRect(rf, 16 * dp, 16 * dp, p);
        Paint.FontMetrics fm = text.getFontMetrics();
        text.setColor(Color.WHITE);
        c.drawText(s, 0, s.length(), mx + 14 * dp, rf.centerY() - (fm.ascent + fm.descent) / 2f, text);
    }

    // ------------------------------------------------------------------ draw

    private boolean step(float dt) {
        boolean a = false;
        a |= hx.step(dt); a |= hy.step(dt); a |= hw.step(dt); a |= hh.step(dt); a |= hr.step(dt);
        a |= scroll.step(dt); a |= pop.step(dt); a |= gx.step(dt);
        if (glowCur != glowTgt) { glowCur = lerpC(glowCur, glowTgt); a = true; }
        return a;
    }

    private static int lerpC(int a, int b) {
        return 0xFF000000 | (ch(a, b, 16) << 16) | (ch(a, b, 8) << 8) | ch(a, b, 0);
    }

    private static int ch(int a, int b, int sh) {
        int x = (a >> sh) & 0xFF, y = (b >> sh) & 0xFF, d = y - x;
        if (Math.abs(d) <= 3) return y;
        int s = (int) (d * 0.18f);
        if (s == 0) s = d > 0 ? 1 : -1;
        return x + s;
    }

    @Override
    protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        float dt = lastNs == 0 ? 0.016f : Math.min((now - lastNs) / 1e9f, 0.033f);
        lastNs = now;
        boolean anim = step(dt);

        c.drawColor(Color.BLACK);
        if (W == 0) return;
        drawWall(c);
        refreshClock();

        c.save();
        c.translate(0, -scroll.v);

        drawHeader(c);
        drawGlow(c);

        if (uiGlass && !dock.isEmpty()) {   // optional glass dock
            rf.set(mx - padD, dockTop, mx + (dock.size() - 1) * cellW + tileW + padD, dockTop + bandH);
            glass(c, rf, 36 * dp, 0x1E, false);
        }
        // one band behind the three top icons: frosted (blurred wallpaper) + glass sheen
        bandRect(rf);
        float bandRad = rf.height() / 2f;
        if (wallOn && (barBlur != null || barBlurNext != null)) {
            c.save();
            path.reset();
            path.addRoundRect(rf, bandRad, bandRad, Path.Direction.CW);
            c.clipPath(path);
            if (barBlur != null) { blurPaint.setAlpha(255); c.drawBitmap(barBlur, null, rf, blurPaint); }
            if (barBlurNext != null) { blurPaint.setAlpha((int) (255 * wallFade)); c.drawBitmap(barBlurNext, null, rf, blurPaint); }
            c.restore();
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x26FFFFFF);               // frost tint
            c.drawRoundRect(rf, bandRad, bandRad, p);
        }
        glass(c, rf, bandRad, 0x1E, false);
        glass(c, ramRect, ramRect.height() / 2f, 0x26, false);   // the "Dọn RAM" button

        // the single glass highlight
        // liquid stretch: the highlight elongates a little along its direction of travel
        float ex = Math.min(0.14f, Math.abs(hx.vel) / 14000f) * hw.v;
        float ey = Math.min(0.14f, Math.abs(hy.vel) / 14000f) * hh.v;
        if (zone == ZT) {
            rf.set(hx.v - ex / 2f, hy.v - ey / 2f, hx.v + hw.v + ex / 2f, hy.v + hh.v + ey / 2f);
            glass(c, rf, hr.v, 0x34, false);  // the top icons keep a plain glass circle (no glint)
        } else {
            // Selected tile: an even outline in the app's colour, concentric with the (scaled) tile, so the gap
            // and the corner radius stay proportional to the tile on every side.
            float sc = held != null ? heldScale() : pop.v;
            float sw = 3 * dp, d = 3 * dp + sw / 2f;
            float cx = hx.v + hw.v / 2f, cy = hy.v + hh.v / 2f;
            float w2 = (hw.v * sc + ex) / 2f + d, h2 = (hh.v * sc + ey) / 2f + d;
            rf.set(cx - w2, cy - h2, cx + w2, cy + h2);
            float rad = 18 * dp * sc + d;
            int col = colorFollow ? (glowCur & 0x00FFFFFF) : 0x00FFFFFF;
            p.setStyle(Paint.Style.STROKE);
            for (int i = 0; i < GLOW_W.length; i++) {      // glow spreading outwards (tiles are drawn on top of the inner half)
                p.setStrokeWidth(GLOW_W[i] * dp);
                p.setColor((GLOW_A[i] << 24) | col);
                c.drawRoundRect(rf, rad, rad, p);
            }
            p.setStrokeWidth(sw);
            p.setColor(0xFF000000 | col);
            c.drawRoundRect(rf, rad, rad, p);
            p.setStyle(Paint.Style.FILL);
        }

        drawPillText(c);
        drawDock(c);
        drawGrid(c);
        drawName(c);
        drawWeatherRow(c);
        if (ddOpen) drawDropdown(c);

        c.restore();
        drawHint(c);
        drawMenu(c);
        drawCast(c);
        drawPhone(c);
        drawOptions(c);
        if (anim || fading) postInvalidateOnAnimation(); else lastNs = 0;
    }

    /** The part of a wallpaper bitmap that sits behind a screen rectangle (centre-crop mapping). */
    private void wallSource(Bitmap b, RectF scr, Rect out) {
        float bw = b.getWidth(), bh = b.getHeight();
        float sc = Math.max(W / bw, H / bh);
        float offX = (bw - W / sc) / 2f, offY = (bh - H / sc) / 2f;
        out.set((int) (offX + scr.left / sc), (int) (offY + scr.top / sc),
                (int) (offX + scr.right / sc), (int) (offY + scr.bottom / sc));
    }

    private void drawBmp(Canvas c, Bitmap b, int alpha) {
        wallSource(b, wallDst, wallSrc);
        wallPaint.setAlpha(alpha);
        c.drawBitmap(b, wallSrc, wallDst, wallPaint);
    }

    private void drawWall(Canvas c) {
        fading = false;
        if (!wallOn || (wall == null && wallNext == null)) return;
        if (wallMaxW == 0) {   // first frame: learn the widest bitmap this GPU can draw
            wallMaxW = Math.max(1, c.getMaximumBitmapWidth());
            wall = cap(wall);
            wallNext = cap(wallNext);
            rebuildBlurs();
        }
        wallDst.set(0, 0, W, H);
        wallFade = 1f;
        if (wallNext != null) {
            wallFade = Math.min(1f, (System.nanoTime() - fadeStart) / 1.2e9f);
            fading = wallFade < 1f;
        }
        if (wall != null) drawBmp(c, wall, 255);
        if (wallNext != null) drawBmp(c, wallNext, (int) (255 * wallFade));
        c.drawRect(wallDst, scrimPaint);   // faint dark scrim at the top and bottom keeps text readable
        if (wallNext != null && wallFade >= 1f) finishFade();
    }

    private void finishFade() {
        Bitmap oldWall = wall, oldBlur = barBlur;
        wall = wallNext;
        barBlur = barBlurNext;
        wallNext = null;
        barBlurNext = null;
        wallFade = 1f;
        fading = false;
        if (oldWall != wall) recycleLater(oldWall);
        recycleLater(oldBlur);
    }

    private void recycleLater(final Bitmap b) {   // wait a moment: the last frame may still be using it
        if (b == null) return;
        postDelayed(new Runnable() {
            @Override public void run() { if (!b.isRecycled()) b.recycle(); }
        }, 1500);
    }

    private Bitmap cap(Bitmap b) {
        if (b == null || wallMaxW <= 0 || b.getWidth() <= wallMaxW) return b;
        Bitmap smaller = Bitmap.createScaledBitmap(b, wallMaxW, Math.max(1, b.getHeight() * wallMaxW / b.getWidth()), true);
        recycleLater(b);
        return smaller;
    }

    private void bandRect(RectF out) {
        out.set(pill[0].left - 14 * dp, pill[0].top - 8 * dp, pill[NP - 1].right + 14 * dp, pill[0].bottom + 8 * dp);
    }

    /** Frosted look for the top bar: a blurred copy of the wallpaper behind it, made once (no per-frame blur). */
    private Bitmap blurOf(Bitmap src) {
        if (src == null || W == 0) return null;
        try {
            bandRect(rg);
            wallSource(src, rg, tmpSrc);
            tmpSrc.intersect(0, 0, src.getWidth(), src.getHeight());
            if (tmpSrc.width() < 4 || tmpSrc.height() < 4) return null;
            Bitmap crop = Bitmap.createBitmap(src, tmpSrc.left, tmpSrc.top, tmpSrc.width(), tmpSrc.height());
            Bitmap small = Bitmap.createScaledBitmap(crop, Math.max(2, crop.getWidth() / 14), Math.max(2, crop.getHeight() / 14), true);
            Bitmap tiny = Bitmap.createScaledBitmap(small, Math.max(2, small.getWidth() / 2), Math.max(2, small.getHeight() / 2), true);
            if (crop != src && crop != small) crop.recycle();
            if (small != tiny) small.recycle();
            return tiny;
        } catch (Throwable t) {
            return null;
        }
    }

    private void rebuildBlurs() {
        Bitmap o1 = barBlur, o2 = barBlurNext;
        barBlur = null;
        barBlurNext = null;
        if (wallOn && W != 0) {
            barBlur = blurOf(wall);
            barBlurNext = blurOf(wallNext);
        }
        recycleLater(o1);
        recycleLater(o2);
    }

    /** Loads a wallpaper off the UI thread, then fades it in. token: "asset:path" or "file:/path". */
    void showWallpaper(final String token) {
        if (token == null) return;
        new Thread(new Runnable() {
            @Override public void run() {
                final Bitmap bm = decodeWall(token);
                if (bm == null) return;
                post(new Runnable() {
                    @Override public void run() {
                        if (wallNext != null) finishFade();   // a fade was still running: finish it first
                        wallNext = cap(bm);
                        barBlurNext = blurOf(wallNext);
                        fadeStart = System.nanoTime();
                        invalidate();
                    }
                });
            }
        }).start();
    }

    /** Decodes at the smallest size that still covers the screen (sharp on 4K, light on 1080p). */
    private Bitmap decodeWall(String token) {
        try {
            int screenW = act.getResources().getDisplayMetrics().widthPixels;
            BitmapFactory.Options b = new BitmapFactory.Options();
            b.inJustDecodeBounds = true;
            InputStream in0 = openWall(token);
            BitmapFactory.decodeStream(in0, null, b);
            in0.close();
            int sample = 1;
            while (b.outWidth / (sample * 2) >= screenW) sample *= 2;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            InputStream in = openWall(token);
            Bitmap bm = BitmapFactory.decodeStream(in, null, o);
            in.close();
            if (bm != null && token.startsWith("file:")) {   // phone photos carry their rotation in EXIF
                int rot = exifRotation(token.substring(5));
                if (rot != 0) {
                    Matrix m = new Matrix();
                    m.postRotate(rot);
                    Bitmap r = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
                    if (r != bm) bm.recycle();
                    bm = r;
                }
            }
            return bm;
        } catch (Throwable t) {
            return null;
        }
    }

    private InputStream openWall(String token) throws java.io.IOException {
        if (token.startsWith("asset:")) return act.getAssets().open(token.substring(6));
        return new java.io.FileInputStream(token.substring(5));
    }

    private static int exifRotation(String path) {
        try {
            android.media.ExifInterface ei = new android.media.ExifInterface(path);
            int o = ei.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL);
            if (o == android.media.ExifInterface.ORIENTATION_ROTATE_90) return 90;
            if (o == android.media.ExifInterface.ORIENTATION_ROTATE_180) return 180;
            if (o == android.media.ExifInterface.ORIENTATION_ROTATE_270) return 270;
        } catch (Throwable ignored) { }
        return 0;
    }

    private void refreshClock() {
        long m = System.currentTimeMillis() / 60000L;
        if (m == minuteKey) return;
        minuteKey = m;
        Date d = new Date();
        timeStr = timeFmt.format(d);
        String ds = dateFmt.format(d);
        dateStr = ds.toUpperCase(Locale.getDefault());
        Calendar cal = Calendar.getInstance();
        int h = cal.get(Calendar.HOUR_OF_DAY);
        lunarStr = Lunar.describe(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
                .toUpperCase(Locale.getDefault());
        greetStr = h < 5 ? "CHÀO BUỔI TỐI" : h < 11 ? "CHÀO BUỔI SÁNG" : h < 13 ? "CHÀO BUỔI TRƯA"
                : h < 18 ? "CHÀO BUỔI CHIỀU" : "CHÀO BUỔI TỐI";
    }

    /** Draws text so that the ink of its first glyph starts exactly at x (cancels the font's left side bearing),
     *  which lines the greeting, the clock and the RAM / storage lines up on one vertical edge. Returns the ink width. */
    private float drawFlush(Canvas c, String str, float x, float y) {
        if (str == null || str.length() == 0) return 0f;
        text.getTextBounds(str, 0, str.length(), tb);
        c.drawText(str, x - tb.left, y, text);
        return tb.width();
    }

    private void drawHeader(Canvas c) {
        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(regular);
        text.setTextSize(13 * dp);
        text.setLetterSpacing(0.28f);
        text.setColor(0xCCFFFFFF);
        drawFlush(c, greetStr, mx, 44 * dp);
        text.setLetterSpacing(0f);

        text.setTypeface(light);
        text.setTextSize(54 * dp);
        text.setColor(Color.WHITE);
        float base = 106 * dp;
        float cw = drawFlush(c, timeStr, mx, base);   // ink width of the clock

        float gap = 24 * dp, bar = 1.5f * dp;          // the same gap on both sides of the divider
        float dx = mx + cw + gap;                      // thin divider between the clock and the info block
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x55FFFFFF);
        c.drawRect(dx, base - 40 * dp, dx + bar, base + 1 * dp, p);   // as tall as the clock digits
        drawInfo(c, dx + bar + gap, base);

        text.setTypeface(regular);
        text.setTextSize(14 * dp);
        text.setLetterSpacing(0.03f);
        text.setColor(0xCCFFFFFF);
        drawFlush(c, memStr, mx, base + 38 * dp);
        drawFlush(c, storStr, mx, base + 58 * dp);
        text.setLetterSpacing(0f);
    }

    // --------------------------------------------------------------- weather

    /** Two compact lines: date and lunar date (the weather now lives on the app-name row). */
    private void drawInfo(Canvas c, float bx, float base) {
        float y2 = base - 27 * dp, y3 = base;   // spans the height of the clock digits
        text.setTypeface(regular);
        text.setTextSize(17 * dp);
        text.setLetterSpacing(0.14f);
        text.setColor(0xCCFFFFFF);
        c.drawText(dateStr, bx, y2, text);
        c.drawText(lunarStr, bx, y3, text);
        text.setLetterSpacing(0f);
    }

    private String weatherSub(Weather w) {
        return w.text() + (place.length() > 0 ? " · " + place : "");
    }

    /** Width of the weather block (icon + text), 0 when there is no weather yet. */
    private float weatherWidth() {
        Weather w = weather;
        if (w == null) return 0f;
        text.setLetterSpacing(0f);
        text.setTypeface(light);
        text.setTextSize(34 * dp);
        float a = text.measureText(Math.round(w.temp) + "°C");
        text.setTypeface(regular);
        text.setTextSize(15 * dp);
        float b = text.measureText(weatherSub(w));
        return 36 * dp + 14 * dp + Math.max(a, b);
    }

    /** Weather at the right edge, vertically level with the big app name. */
    private void drawWeatherRow(Canvas c) {
        Weather w = weather;
        if (w == null) return;
        float y0 = dockTop - 36 * dp;   // baseline of the big app name
        float rx = W - mx;
        text.setLetterSpacing(0f);
        text.setTextAlign(Paint.Align.RIGHT);
        text.setTypeface(light);
        text.setTextSize(34 * dp);
        text.setColor(Color.WHITE);
        String t = Math.round(w.temp) + "°C";
        float aw = text.measureText(t);
        c.drawText(t, rx, y0 - 12 * dp, text);
        text.setTypeface(regular);
        text.setTextSize(15 * dp);
        text.setColor(0xCCFFFFFF);
        String sub = weatherSub(w);
        float bw = text.measureText(sub);
        c.drawText(sub, rx, y0 + 8 * dp, text);
        text.setTextAlign(Paint.Align.LEFT);
        drawIcon(c, w, rx - Math.max(aw, bw) - 14 * dp - 18 * dp, y0 - 14 * dp, 36 * dp);
    }

    private void stroke(float width, int color) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeWidth(width);
        p.setColor(color);
    }

    private void drawIcon(Canvas c, Weather w, float cx, float cy, float s) {
        float u = s / 2f;
        switch (w.kind()) {
            case Weather.SUN:
                sun(c, cx, cy, 0.55f * u);
                break;
            case Weather.MOON:
                moon(c, cx, cy, 0.62f * u);
                break;
            case Weather.PARTLY:
                if (w.day) sun(c, cx - 0.28f * u, cy - 0.28f * u, 0.38f * u);
                else moon(c, cx - 0.28f * u, cy - 0.28f * u, 0.42f * u);
                cloud(c, cx + 0.12f * u, cy + 0.2f * u, 0.8f * u);
                break;
            case Weather.FOG:
                stroke(Math.max(1.5f * dp, 0.1f * u), 0xFFB0B6C0);
                for (int i = 0; i < 4; i++) {
                    float y = cy + FOG_Y[i] * u;
                    c.drawLine(cx - FOG_W[i] * u, y, cx + FOG_W[i] * u, y, p);
                }
                break;
            case Weather.RAIN:
                cloud(c, cx, cy - 0.25f * u, 0.8f * u);
                stroke(Math.max(1.5f * dp, 0.12f * u), 0xFF4FC3F7);
                for (int i = -1; i <= 1; i++) {
                    float x = cx + i * 0.45f * u;
                    c.drawLine(x + 0.1f * u, cy + 0.45f * u, x - 0.1f * u, cy + 0.85f * u, p);
                }
                break;
            case Weather.STORM:
                cloud(c, cx, cy - 0.25f * u, 0.8f * u);
                path.reset();
                path.moveTo(cx + 0.08f * u, cy + 0.3f * u);
                path.lineTo(cx - 0.22f * u, cy + 0.65f * u);
                path.lineTo(cx - 0.02f * u, cy + 0.65f * u);
                path.lineTo(cx - 0.14f * u, cy + 1.0f * u);
                path.lineTo(cx + 0.26f * u, cy + 0.55f * u);
                path.lineTo(cx + 0.04f * u, cy + 0.55f * u);
                path.close();
                p.setStyle(Paint.Style.FILL);
                p.setColor(0xFFFFC107);
                c.drawPath(path, p);
                break;
            case Weather.SNOW:
                cloud(c, cx, cy - 0.25f * u, 0.8f * u);
                p.setStyle(Paint.Style.FILL);
                p.setColor(Color.WHITE);
                for (int i = -1; i <= 1; i++) {
                    c.drawCircle(cx + i * 0.45f * u, cy + (i == 0 ? 0.8f : 0.6f) * u, 0.08f * u, p);
                }
                break;
            default:
                cloud(c, cx, cy, 0.9f * u);
                break;
        }
        p.setStyle(Paint.Style.FILL);
        p.setStrokeCap(Paint.Cap.BUTT);
    }

    /** (cx, cy) is the centre of the cloud's bounding box, r is half its width. */
    private void cloud(Canvas c, float cx, float cy, float r) {
        float b = cy + 0.2f * r;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFE3E6EC);
        rf.set(cx - r, b - 0.1f * r, cx + r, b + 0.5f * r);
        c.drawRoundRect(rf, 0.3f * r, 0.3f * r, p);
        c.drawCircle(cx - 0.45f * r, b - 0.1f * r, 0.4f * r, p);
        c.drawCircle(cx + 0.05f * r, b - 0.35f * r, 0.55f * r, p);
        c.drawCircle(cx + 0.55f * r, b - 0.05f * r, 0.38f * r, p);
    }

    private void sun(Canvas c, float cx, float cy, float r) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFFFC107);
        c.drawCircle(cx, cy, r, p);
        stroke(Math.max(1.5f * dp, 0.2f * r), 0xFFFFC107);
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
            c.drawLine(cx + ca * 1.35f * r, cy + sa * 1.35f * r, cx + ca * 1.75f * r, cy + sa * 1.75f * r, p);
        }
    }

    private void moon(Canvas c, float cx, float cy, float r) {
        path.reset();
        path.addCircle(cx, cy, r, Path.Direction.CW);
        path2.reset();
        path2.addCircle(cx + 0.5f * r, cy - 0.35f * r, 0.85f * r, Path.Direction.CW);
        path.op(path2, Path.Op.DIFFERENCE);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xFFE8EAF6);
        c.drawPath(path, p);
    }

    // ------------------------------------------------- liquid glass + background

    /** Glass panel: smooth vertical sheen (no hard seam), bright top rim fading downwards, optional glint. */
    private void glass(Canvas c, RectF r, float rad, int a, boolean glint) {
        gm.setScale(1f, Math.max(1f, r.height()));
        gm.postTranslate(0f, r.top);
        bodyShader.setLocalMatrix(gm);
        rimShader.setLocalMatrix(gm);

        p.setStyle(Paint.Style.FILL);
        p.setShader(bodyShader);
        p.setAlpha(Math.min(255, a * 2));
        c.drawRoundRect(r, rad, rad, p);

        rg.set(r);
        rg.inset(0.75f * dp, 0.75f * dp);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f * dp);
        p.setShader(rimShader);
        p.setAlpha(255);
        c.drawRoundRect(rg, rad, rad, p);
        p.setShader(null);
        p.setStyle(Paint.Style.FILL);

        if (glint) {
            p.setColor(0x40FFFFFF);
            rg.set(r.left + r.width() * 0.14f, r.top + r.height() * 0.10f,
                    r.left + r.width() * 0.46f, r.top + r.height() * 0.24f);
            c.drawOval(rg, p);
        }
    }


    // ------------------------------------------------------------- top-bar icons

    private void drawPillText(Canvas c) {
        float u = pillH * 0.5f;   // icon box
        for (int i = 0; i < NP; i++) {
            float cx = pill[i].centerX(), cy = pill[i].centerY();
            if (i == P_IN) iconInput(c, cx, cy, u);
            else if (i == P_CAST) iconCast(c, cx, cy, u);
            else if (i == P_ARR) { if (arrange) iconCheck(c, cx, cy, u); else iconGrid(c, cx, cy, u); }
            else if (i == P_WIFI) iconWifi(c, cx, cy, u);
            else iconGear(c, cx, cy, u);
        }
        // "Dọn RAM" button: rocket + label
        iconRocket(c, ramRect.left + 16 * dp + 11 * dp, ramRect.centerY(), 22 * dp);
        text.setTypeface(regular);
        text.setTextSize(17 * dp);
        text.setLetterSpacing(0f);
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(Color.WHITE);
        Paint.FontMetrics rfm = text.getFontMetrics();
        c.drawText("Dọn RAM", ramRect.left + 16 * dp + 22 * dp + 8 * dp, ramRect.centerY() - (rfm.ascent + rfm.descent) / 2f, text);
        p.setStyle(Paint.Style.FILL);
        p.setStrokeCap(Paint.Cap.BUTT);
        p.setStrokeJoin(Paint.Join.MITER);
    }

    /** Rocket: pointed body with a round window, two fins and a flame. */
    private void iconRocket(Canvas c, float cx, float cy, float sz) {
        float u = sz / 2f;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xF2FFFFFF);
        path.reset();
        path.setFillType(Path.FillType.EVEN_ODD);
        path.moveTo(cx, cy - 1.0f * u);
        path.quadTo(cx + 0.62f * u, cy - 0.45f * u, cx + 0.42f * u, cy + 0.45f * u);
        path.lineTo(cx - 0.42f * u, cy + 0.45f * u);
        path.quadTo(cx - 0.62f * u, cy - 0.45f * u, cx, cy - 1.0f * u);
        path.close();
        path.addCircle(cx, cy - 0.25f * u, 0.2f * u, Path.Direction.CCW);
        c.drawPath(path, p);
        path.reset();
        path.setFillType(Path.FillType.WINDING);
        path.moveTo(cx - 0.42f * u, cy);
        path.lineTo(cx - 0.9f * u, cy + 0.75f * u);
        path.lineTo(cx - 0.42f * u, cy + 0.45f * u);
        path.close();
        path.moveTo(cx + 0.42f * u, cy);
        path.lineTo(cx + 0.9f * u, cy + 0.75f * u);
        path.lineTo(cx + 0.42f * u, cy + 0.45f * u);
        path.close();
        path.moveTo(cx - 0.2f * u, cy + 0.62f * u);
        path.lineTo(cx, cy + 1.05f * u);
        path.lineTo(cx + 0.2f * u, cy + 0.62f * u);
        path.close();
        c.drawPath(path, p);
    }

    /** HDMI plug: solid body with chamfered bottom corners and four pin slits. */
    private void iconInput(Canvas c, float cx, float cy, float u) {
        float a = 0.46f * u, b = 0.28f * u, top = cy - 0.30f * u, mid = cy + 0.06f * u, bot = cy + 0.34f * u;
        path.reset();
        path.setFillType(Path.FillType.EVEN_ODD);
        path.moveTo(cx - a, top);
        path.lineTo(cx + a, top);
        path.lineTo(cx + a, mid);
        path.lineTo(cx + b, bot);
        path.lineTo(cx - b, bot);
        path.lineTo(cx - a, mid);
        path.close();
        for (int i = 0; i < 4; i++) {
            float sx = cx - 0.27f * u + i * 0.18f * u;
            path.addRect(sx - 0.035f * u, top + 0.09f * u, sx + 0.035f * u, top + 0.31f * u, Path.Direction.CCW);
        }
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xF2FFFFFF);
        c.drawPath(path, p);
        path.setFillType(Path.FillType.WINDING);
    }

    /** Cast glyph: screen outline open at the lower-left corner, with two waves and a dot. */
    private void iconCast(Canvas c, float cx, float cy, float u) {
        float a = 0.44f * u, top = cy - 0.34f * u, bot = cy + 0.34f * u;
        float ox = cx - a, oy = bot, outer = 0.46f * u, inner = 0.24f * u;
        stroke(2.8f * dp, 0xF2FFFFFF);
        p.setStrokeJoin(Paint.Join.ROUND);
        path.reset();
        path.moveTo(ox, oy - outer);
        path.lineTo(ox, top);
        path.lineTo(cx + a, top);
        path.lineTo(cx + a, bot);
        path.lineTo(ox + outer, bot);
        c.drawPath(path, p);
        rg.set(ox - outer, oy - outer, ox + outer, oy + outer);
        c.drawArc(rg, 270f, 90f, false, p);
        rg.set(ox - inner, oy - inner, ox + inner, oy + inner);
        c.drawArc(rg, 270f, 90f, false, p);
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(ox, oy, 0.08f * u, p);
    }

    // -------------------------------------------------------------- options panel

    private int optGet(int i) {
        switch (i) {
            case 0: return uiGlass ? 1 : 0;
            case 1: return uiSize;
            case 2: return uiLabel;
            case 3: return uiColor ? 1 : 0;
            default: return uiPop;
        }
    }

    private void optSet(int i, int v) {
        switch (i) {
            case 0: uiGlass = v == 1; break;
            case 1: uiSize = v; break;
            case 2: uiLabel = v; break;
            case 3: uiColor = v == 1; break;
            default: uiPop = v; break;
        }
        applyUi(true);
    }

    private void optCycle(int d) {
        int n = OPT_VALUES[optIdx].length;
        optSet(optIdx, (optGet(optIdx) + d + n) % n);
        act.sfx.move();
    }

    private void openOptions() {
        optOpen = true;
        optIdx = 0;
        act.sfx.open();
        invalidate();
    }

    private boolean optDown(int k, KeyEvent e) {
        int n = OPT_TITLE.length;
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: optIdx = Math.max(0, optIdx - 1); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: optIdx = Math.min(n - 1, optIdx + 1); break;
            case KeyEvent.KEYCODE_DPAD_LEFT: if (optIdx < n - 1) optCycle(-1); break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: if (optIdx < n - 1) optCycle(1); break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (e.getRepeatCount() == 0) {
                    if (optIdx == n - 1) { optOpen = false; openPhone(); }
                    else optCycle(1);
                }
                break;
            case KeyEvent.KEYCODE_BACK: optOpen = false; break;
        }
        invalidate();
        return true;
    }

    int dockMax() { return cols; }

    /** The dock holds one app per column: pull apps in from the grid when it grows, push the last ones back when it shrinks. */
    private void rebalanceDock(boolean save) {
        String key = focusKey();
        while (dock.size() > cols) grid.add(0, dock.remove(dock.size() - 1));
        while (dock.size() < cols && !grid.isEmpty()) dock.add(grid.remove(0));
        if (key != null && key.length() > 2) {
            String pkg = key.substring(2);
            for (int i = 0; i < dock.size(); i++) if (dock.get(i).cn.getPackageName().equals(pkg)) { zone = ZD; dockIdx = i; }
            for (int i = 0; i < grid.size(); i++) if (grid.get(i).cn.getPackageName().equals(pkg)) { zone = ZG; gridIdx = i; }
        }
        fixZone();
        if (save) saveLayoutNow();
    }

    /** Recomputes everything derived from the options; persist = also save them. */
    private void applyUi(boolean persist) {
        int oldCols = cols;
        cols = uiSize == 1 ? 5 : uiSize == 2 ? 6 : 4;
        if (cols != oldCols) rebalanceDock(persist);
        labelSp = uiLabel == 1 ? 16f : uiLabel == 3 ? 28f : 22f;
        nameSp = uiLabel == 1 ? 34f : uiLabel == 3 ? 68f : 52f;
        popTarget = uiPop == 0 ? 1f : uiPop == 2 ? 1.15f : 1.07f;
        colorFollow = uiColor;
        pop.t = popTarget;
        relayout();
        retarget(false);
        if (persist) act.saveUi(uiGlass, uiSize, uiLabel, uiColor, uiPop);
        invalidate();
    }

    /** Called by the activity with the saved options. */
    void setUi(boolean glass, int size, int label, boolean color, int popMode) {
        uiGlass = glass;
        uiSize = Math.max(0, Math.min(2, size));
        uiLabel = Math.max(0, Math.min(3, label));
        uiColor = color;
        uiPop = Math.max(0, Math.min(2, popMode));
        applyUi(false);
        retarget(true);
    }

    private void drawOptions(Canvas c) {
        if (!optOpen) return;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xB0000000);
        c.drawRect(0, 0, W, H, p);

        int n = OPT_TITLE.length;
        float w = Math.min(W - 2 * mx, 600 * dp), rowH4 = 50 * dp, titleH = 70 * dp;
        float h = titleH + n * rowH4 + 18 * dp;
        float x = (W - w) / 2f, y = (H - h) / 2f, pad = 26 * dp;
        rf.set(x, y, x + w, y + h);
        p.setColor(0xF2222226);
        c.drawRoundRect(rf, 28 * dp, 28 * dp, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f * dp);
        p.setColor(0x50FFFFFF);
        c.drawRoundRect(rf, 28 * dp, 28 * dp, p);
        p.setStyle(Paint.Style.FILL);

        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(light);
        text.setTextSize(26 * dp);
        text.setColor(Color.WHITE);
        c.drawText("Tuỳ chỉnh giao diện", x + pad, y + 46 * dp, text);

        text.setTypeface(regular);
        text.setTextSize(20 * dp);
        Paint.FontMetrics fm = text.getFontMetrics();
        for (int i = 0; i < n; i++) {
            float top = y + titleH + i * rowH4;
            float by = top + rowH4 / 2f - (fm.ascent + fm.descent) / 2f;
            if (i == optIdx) {
                tmp.set(x + 10 * dp, top + 3 * dp, x + w - 10 * dp, top + rowH4 - 3 * dp);
                p.setColor(0x55FFFFFF);
                c.drawRoundRect(tmp, 16 * dp, 16 * dp, p);
            }
            text.setTextAlign(Paint.Align.LEFT);
            text.setColor(Color.WHITE);
            c.drawText(OPT_TITLE[i], x + pad, by, text);
            text.setTextAlign(Paint.Align.RIGHT);
            if (i < n - 1) {
                String v = OPT_VALUES[i][optGet(i)];
                text.setColor(i == optIdx ? Color.WHITE : 0xB3FFFFFF);
                c.drawText(i == optIdx ? "‹  " + v + "  ›" : v, x + w - pad, by, text);
            } else {
                text.setColor(0xB3FFFFFF);
                c.drawText("›", x + w - pad, by, text);
            }
        }
        text.setTextAlign(Paint.Align.LEFT);
    }

    // ----------------------------------------------------- phone settings panel

    List<String> dockPackages() {
        List<String> l = new ArrayList<>();
        for (AppEntry e : dock) l.add(e.cn.getPackageName());
        return l;
    }

    private void openPhone() {
        String[] r = act.startPhone();
        phoneUrl = r == null ? "" : r[0];
        phonePin = r == null ? "" : r[1];
        phoneOpen = true;
        act.sfx.open();
        invalidate();
    }

    void closePhone() {
        if (!phoneOpen) return;
        phoneOpen = false;
        act.stopPhone();
        invalidate();
    }

    private void drawPhone(Canvas c) {
        if (!phoneOpen) return;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xB0000000);
        c.drawRect(0, 0, W, H, p);

        float w = Math.min(W - 2 * mx, 640 * dp), h = 340 * dp;
        float x = (W - w) / 2f, y = (H - h) / 2f, pad = 28 * dp, maxW = w - 2 * pad;
        rf.set(x, y, x + w, y + h);
        p.setColor(0xF2222226);
        c.drawRoundRect(rf, 28 * dp, 28 * dp, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f * dp);
        p.setColor(0x50FFFFFF);
        c.drawRoundRect(rf, 28 * dp, 28 * dp, p);
        p.setStyle(Paint.Style.FILL);

        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(light);
        text.setTextSize(26 * dp);
        text.setColor(Color.WHITE);
        castLine(c, "Tuỳ chỉnh từ điện thoại", x + pad, y + 46 * dp, maxW);

        text.setTypeface(regular);
        if (phoneUrl.length() == 0) {
            text.setTextSize(18 * dp);
            text.setColor(0xFFFF8A80);
            castLine(c, "Không mở được cổng mạng. Kiểm tra TV đã nối Wi-Fi hay mạng dây chưa.", x + pad, y + 110 * dp, maxW);
            return;
        }
        text.setTextSize(17 * dp);
        text.setColor(0xCCFFFFFF);
        castLine(c, "1. Nối điện thoại vào cùng mạng Wi-Fi với TV.", x + pad, y + 88 * dp, maxW);
        castLine(c, "2. Mở trình duyệt trên điện thoại và vào địa chỉ:", x + pad, y + 116 * dp, maxW);

        text.setTypeface(regular);
        text.setTextSize(28 * dp);
        text.setColor(Color.WHITE);
        castLine(c, phoneUrl, x + pad, y + 160 * dp, maxW);

        text.setTextSize(17 * dp);
        text.setColor(0xCCFFFFFF);
        castLine(c, "3. Nhập mã PIN này khi trang hỏi:", x + pad, y + 204 * dp, maxW);

        text.setTypeface(light);
        text.setTextSize(52 * dp);
        text.setLetterSpacing(0.25f);
        text.setColor(Color.WHITE);
        castLine(c, phonePin, x + pad, y + 262 * dp, maxW);
        text.setLetterSpacing(0f);

        text.setTypeface(regular);
        text.setTextSize(14 * dp);
        text.setColor(0x99FFFFFF);
        castLine(c, "Bấm Back hoặc OK để đóng. Máy chủ chỉ chạy khi bảng này đang mở.", x + pad, y + 312 * dp, maxW);
    }

    // ---------------------------------------------------------------- cast panel

    private void openCast() {
        String[] info = act.castInfo();
        castName = info[0];
        castNet = info[1];
        castOff = "off".equals(info[2]);
        castOpen = true;
        invalidate();
    }

    private void castLine(Canvas c, String str, float x, float y, float maxW) {
        CharSequence t = TextUtils.ellipsize(str, text, maxW, TextUtils.TruncateAt.END);
        c.drawText(t, 0, t.length(), x, y, text);
    }

    private void drawCast(Canvas c) {
        if (!castOpen) return;
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xAA000000);
        c.drawRect(0, 0, W, H, p);

        float w = Math.min(W - 2 * mx, 620 * dp), h = 300 * dp;
        float x = (W - w) / 2f, y = (H - h) / 2f, pad = 28 * dp, maxW = w - 2 * pad;
        rf.set(x, y, x + w, y + h);
        p.setColor(0xF2222226);
        c.drawRoundRect(rf, 28 * dp, 28 * dp, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f * dp);
        p.setColor(0x50FFFFFF);
        c.drawRoundRect(rf, 28 * dp, 28 * dp, p);
        p.setStyle(Paint.Style.FILL);

        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(light);
        text.setTextSize(26 * dp);
        text.setColor(Color.WHITE);
        castLine(c, "Truyền từ điện thoại lên TV (Cast)", x + pad, y + 46 * dp, maxW);

        text.setTypeface(regular);
        text.setTextSize(20 * dp);
        castLine(c, "Tên TV:  " + castName, x + pad, y + 92 * dp, maxW);
        castLine(c, "Mạng:  " + castNet, x + pad, y + 122 * dp, maxW);

        text.setTextSize(17 * dp);
        text.setColor(0xCCFFFFFF);
        castLine(c, "1. Nối điện thoại vào cùng mạng Wi-Fi với TV.", x + pad, y + 170 * dp, maxW);
        castLine(c, "2. Mở YouTube, Netflix hoặc Google Home trên điện thoại.", x + pad, y + 198 * dp, maxW);
        castLine(c, "3. Bấm biểu tượng Cast rồi chọn \"" + castName + "\".", x + pad, y + 226 * dp, maxW);

        text.setTextSize(15 * dp);
        if (castOff) {
            text.setColor(0xFFFF8A80);
            castLine(c, "Chromecast tích hợp đang bị tắt trên TV nên điện thoại không thấy TV.", x + pad, y + 266 * dp, maxW);
        } else {
            text.setColor(0x99FFFFFF);
            castLine(c, "Bấm phím bất kỳ để đóng.", x + pad, y + 266 * dp, maxW);
        }
    }

    private void iconGrid(Canvas c, float cx, float cy, float u) {
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xF2FFFFFF);
        float pitch = 0.3f * u, h = 0.1f * u;
        for (int ix = -1; ix <= 1; ix++) {
            for (int iy = -1; iy <= 1; iy++) {
                rg.set(cx + ix * pitch - h, cy + iy * pitch - h, cx + ix * pitch + h, cy + iy * pitch + h);
                c.drawRoundRect(rg, 0.04f * u, 0.04f * u, p);
            }
        }
    }

    private void iconCheck(Canvas c, float cx, float cy, float u) {
        stroke(2.8f * dp, 0xF2FFFFFF);
        path.reset();
        path.moveTo(cx - 0.32f * u, cy + 0.02f * u);
        path.lineTo(cx - 0.08f * u, cy + 0.26f * u);
        path.lineTo(cx + 0.34f * u, cy - 0.24f * u);
        c.drawPath(path, p);
    }

    private void iconWifi(Canvas c, float cx, float cy, float u) {
        float oy = cy + 0.32f * u;
        stroke(2.8f * dp, 0xF2FFFFFF);
        float[] rad = { 0.36f * u, 0.62f * u, 0.88f * u };
        for (int i = 0; i < 3; i++) {
            rg.set(cx - rad[i], oy - rad[i], cx + rad[i], oy + rad[i]);
            c.drawArc(rg, 225f, 90f, false, p);
        }
        p.setStyle(Paint.Style.FILL);
        c.drawCircle(cx, oy, 0.09f * u, p);
    }

    private void iconGear(Canvas c, float cx, float cy, float u) {
        float rin = 0.36f * u, rout = 0.52f * u;
        double base = Math.toRadians(14), tip = Math.toRadians(9);
        path.reset();
        path.setFillType(Path.FillType.EVEN_ODD);
        boolean first = true;
        for (int i = 0; i < 8; i++) {
            double th = i * Math.PI / 4;
            double[] ang = { th - base, th - tip, th + tip, th + base };
            float[] rad = { rin, rout, rout, rin };
            for (int k = 0; k < 4; k++) {
                float x = cx + (float) Math.cos(ang[k]) * rad[k];
                float y = cy + (float) Math.sin(ang[k]) * rad[k];
                if (first) { path.moveTo(x, y); first = false; } else path.lineTo(x, y);
            }
        }
        path.close();
        path.addCircle(cx, cy, 0.16f * u, Path.Direction.CCW);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xF2FFFFFF);
        c.drawPath(path, p);
        path.setFillType(Path.FillType.WINDING);
    }

    private void drawGlow(Canvas c) {
        if (dock.isEmpty()) return;
        if (glowShader == null || shaderColor != glowCur) {
            shaderColor = glowCur;
            int rgb = glowCur & 0x00FFFFFF;
            glowShader = new RadialGradient(0, 0, 300 * dp, new int[] { 0x70000000 | rgb, rgb }, null, Shader.TileMode.CLAMP);
            glowPaint.setShader(glowShader);
        }
        c.save();
        c.translate(gx.v, dockTop - 70 * dp);
        c.drawCircle(0, 0, 300 * dp, glowPaint);
        c.restore();
    }

    private void drawName(Canvas c) {
        if (dock.isEmpty() || uiLabel == 0) return;
        float maxW = W - 2 * mx - weatherWidth() - 30 * dp;   // measured first: it changes the paint
        text.setTextAlign(Paint.Align.LEFT);
        text.setLetterSpacing(0f);
        text.setTypeface(light);
        text.setTextSize(nameSp * dp);
        text.setColor(Color.WHITE);
        CharSequence s = TextUtils.ellipsize(dock.get(dockIdx).label, text, maxW, TextUtils.TruncateAt.END);
        c.drawText(s, 0, s.length(), mx, dockTop - 36 * dp, text);
    }

    private void drawDock(Canvas c) {
        for (int i = 0; i < dock.size(); i++) {
            slot(i, tmp);
            drawArt(c, dock.get(i), tmp, (zone == ZD && i == dockIdx) ? (held != null ? heldScale() : pop.v) : 1f);
        }
    }

    private void drawGrid(Canvas c) {
        text.setTypeface(regular);
        text.setTextSize(labelSp * dp);
        for (int i = 0; i < grid.size(); i++) {
            gridRect(i, tmp);
            if (tmp.bottom + 40 * dp - scroll.v < 0 || tmp.top - scroll.v > H) continue;
            boolean f = zone == ZG && i == gridIdx;
            float sc = f ? (held != null ? heldScale() : pop.v) : 1f;
            drawArt(c, grid.get(i), tmp, sc);
            if (f && uiLabel > 0) {
                float extra = tmp.height() * (sc - 1f) / 2f;
                text.setTextAlign(Paint.Align.CENTER);
                text.setColor(Color.WHITE);
                CharSequence s = TextUtils.ellipsize(grid.get(i).label, text, tileW, TextUtils.TruncateAt.END);
                c.drawText(s, 0, s.length(), tmp.centerX(), tmp.bottom + extra + 32 * dp, text);
                text.setTextAlign(Paint.Align.LEFT);
            }
        }
    }

    private void drawArt(Canvas c, AppEntry e, RectF r, float scale) {
        c.save();
        if (scale != 1f) c.scale(scale, scale, r.centerX(), r.centerY());
        path.reset();
        path.addRoundRect(r, 18 * dp, 18 * dp, Path.Direction.CW);
        c.clipPath(path);
        if (e.banner != null) {
            e.banner.setBounds((int) r.left, (int) r.top, (int) r.right, (int) r.bottom);
            e.banner.draw(c);
        } else {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF2A2A2E);
            c.drawRect(r, p);
            if (e.icon != null) {
                float s = r.height() * 0.6f;
                int cx = (int) r.centerX(), cy = (int) r.centerY();
                e.icon.setBounds((int) (cx - s / 2), (int) (cy - s / 2), (int) (cx + s / 2), (int) (cy + s / 2));
                e.icon.draw(c);
            }
        }
        c.restore();
    }

    private void drawDropdown(Canvas c) {
        float w = 300 * dp, rowH2 = 48 * dp;
        int n = Math.max(1, inputs.size());
        float x = pill[P_IN].left, y = pill[P_IN].bottom + 10 * dp, h = n * rowH2 + 16 * dp;
        rf.set(x, y, x + w, y + h);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0xF2222226);
        c.drawRoundRect(rf, 24 * dp, 24 * dp, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f * dp);
        p.setColor(0x50FFFFFF);
        c.drawRoundRect(rf, 24 * dp, 24 * dp, p);
        p.setStyle(Paint.Style.FILL);

        text.setTypeface(regular);
        text.setTextSize(20 * dp);
        text.setTextAlign(Paint.Align.LEFT);
        Paint.FontMetrics fm = text.getFontMetrics();
        if (inputs.isEmpty()) {
            text.setColor(0xAAFFFFFF);
            c.drawText("Không tìm thấy input", x + 22 * dp, y + 8 * dp + rowH2 / 2 - (fm.ascent + fm.descent) / 2f, text);
            return;
        }
        for (int i = 0; i < inputs.size(); i++) {
            float top = y + 8 * dp + i * rowH2;
            if (i == ddIdx) {
                tmp.set(x + 8 * dp, top + 2 * dp, x + w - 8 * dp, top + rowH2 - 2 * dp);
                p.setColor(0x55FFFFFF);
                c.drawRoundRect(tmp, 16 * dp, 16 * dp, p);
            }
            text.setColor(Color.WHITE);
            c.drawText(inputs.get(i).label, x + 22 * dp, top + rowH2 / 2 - (fm.ascent + fm.descent) / 2f, text);
        }
    }

    // ---------------------------------------------------------- glow colour

    private int colorOf(AppEntry e) {
        if (!e.glowDone) { e.glow = sample(e); e.glowDone = true; }
        return e.glow;
    }

    /** Average colour of the banner (weighted towards saturated pixels), brightened for the glow. */
    private int sample(AppEntry e) {
        Drawable d = e.banner != null ? e.banner : e.icon;
        if (d == null) return 0xFF8E8E93;
        Bitmap b = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Rect old = d.copyBounds();
        d.setBounds(0, 0, 16, 9);
        d.draw(cv);
        d.setBounds(old);
        float r = 0, g = 0, bl = 0, ws = 0;
        for (int y = 0; y < 9; y++) {
            for (int x = 0; x < 16; x++) {
                int px = b.getPixel(x, y);
                if (Color.alpha(px) < 128) continue;
                float R = Color.red(px), G = Color.green(px), B = Color.blue(px);
                float w = (Math.max(R, Math.max(G, B)) - Math.min(R, Math.min(G, B))) + 8f;
                r += R * w; g += G * w; bl += B * w; ws += w;
            }
        }
        b.recycle();
        if (ws == 0) return 0xFF8E8E93;
        float[] hsv = new float[3];
        Color.RGBToHSV((int) (r / ws), (int) (g / ws), (int) (bl / ws), hsv);
        hsv[1] = Math.min(1f, hsv[1] * 1.15f);
        hsv[2] = Math.max(hsv[2], 0.85f);
        return Color.HSVToColor(hsv);
    }
}
