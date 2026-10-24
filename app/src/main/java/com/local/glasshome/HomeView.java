package com.local.glasshome;

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
    private static final int COLS = 4, DOCK_MAX = 4;
    private static final float POP = 1.07f;
    private static final float HELD = 1.14f;
    private static final float[] GLOW_W = { 38f, 26f, 15f, 8f };
    private static final int[] GLOW_A = { 0x0E, 0x18, 0x28, 0x44 };
    private static final float[] FOG_Y = { -0.45f, -0.15f, 0.15f, 0.45f };
    private static final float[] FOG_W = { 0.7f, 0.9f, 0.9f, 0.6f };
    private static final int M_MOVE = 0, M_DEL = 1, M_NOTE = 2, M_CLOSE = 3;   // app menu rows
    private static final int P_ARR = 0, P_WIFI = 1, P_SET = 2, NP = 3;   // top-bar icon buttons
    private static final int P_IN = -1;                                  // Inputs button removed

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
    private Bitmap wall;
    private Bitmap barBlur;
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
    // unit-height vertical gradients (scaled to each panel): smooth sheen and rim, no hard light/dark seam
    private final Matrix gm = new Matrix();
    private final LinearGradient bodyShader = new LinearGradient(0, 0, 0, 1,
            new int[] { 0xFFFFFFFF, 0xB8FFFFFF, 0x80FFFFFF, 0x61FFFFFF }, new float[] { 0f, 0.35f, 0.7f, 1f }, Shader.TileMode.CLAMP);
    private final LinearGradient rimShader = new LinearGradient(0, 0, 0, 1,
            new int[] { 0xF0FFFFFF, 0x99FFFFFF, 0x59FFFFFF, 0x3DFFFFFF }, new float[] { 0f, 0.3f, 0.65f, 1f }, Shader.TileMode.CLAMP);

    private float W, H, mx, dockTop, bandH, slotW, slotH, padD, gapD;
    private float cellW, tileW, tileH, rowH, gridTop, pillH;
    private final RectF[] pill = { new RectF(), new RectF(), new RectF() };
    private final String[] pillText = { "Sắp xếp", "Wi-Fi", "Settings" };

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
        pop.snap(POP);

        // Fonts in app/src/main/assets/fonts/: the first file that exists wins, else the system font.
        light = font(ctx, "sans-serif-light", "fonts/MyriadPro-Light.otf", "fonts/Outfit-Light.ttf");
        regular = font(ctx, "sans-serif", "fonts/MyriadPro-Regular.otf", "fonts/Outfit-Regular.ttf");

        // Optional wallpaper: put wallpaper.jpg in app/src/main/assets/ (shown dimmed). Default is pure black.
        try {
            InputStream in = ctx.getAssets().open("wallpaper.jpg");
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 1;
            wall = BitmapFactory.decodeStream(in, null, o);
            in.close();
        } catch (Exception ignored) { wall = null; }
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
                if (e != null && used.add(s) && dock.size() < DOCK_MAX) dock.add(e);
            }
        }
        for (AppEntry e : all) {
            if (dock.size() >= DOCK_MAX) break;
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
        menuOpen = false; held = null; arrange = false; tempArrange = false; pillText[P_ARR] = "Sắp xếp";

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
        menuOpen = false;
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

    void setPlace(String name) { place = name == null ? "" : name; invalidate(); }

    void tick() { invalidate(); }

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
        tileW = (W - 2 * mx - (COLS - 1) * gapD) / COLS;
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
        scrimM.setScale(1f, H);
        scrimShader.setLocalMatrix(scrimM);
        scrimPaint.setShader(scrimShader);
        buildBarBlur();
    }

    private void slot(int i, RectF r) {
        float l = mx + i * cellW;
        r.set(l, dockTop + padD, l + slotW, dockTop + padD + slotH);
    }

    private void gridRect(int i, RectF r) {
        float l = mx + (i % COLS) * cellW;
        float t = gridTop + (i / COLS) * rowH;
        r.set(l, t, l + tileW, t + tileH);
    }

    private float scrollTarget() {
        if (zone != ZG) return 0;
        int row = gridIdx / COLS;
        return Math.max(0, gridTop + row * rowH + tileH + 64 * dp - H);
    }

    private void retarget(boolean snap) {
        if (W == 0) return;
        float infl, rad;
        if (zone == ZT) {
            tmp.set(pill[topIdx]); infl = 4 * dp; rad = pillH / 2 + infl;
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
            scroll.snap(scroll.t); gx.snap(gx.t); pop.snap(POP);
            glowCur = glowTgt;
        }
        invalidate();
    }

    private void focusMoved() {
        pop.v = 1f; pop.vel = 0f; pop.t = POP;
        retarget(false);
    }

    // ------------------------------------------------------------------ keys

    boolean onNavDown(int k, KeyEvent e) {
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
                if (zone != ZD && !dock.isEmpty()) { zone = ZD; focusMoved(); }
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
        int oz = zone, ot = topIdx, od = dockIdx, og = gridIdx;
        if (zone == ZT) {
            if (dx != 0) topIdx = Math.max(0, Math.min(NP - 1, topIdx + dx));   // ends do not fall through
            else if (dy > 0) {
                if (!dock.isEmpty()) zone = ZD;
                else if (!grid.isEmpty()) { zone = ZG; gridIdx = 0; }
            }
        } else if (zone == ZD) {
            if (dx != 0) dockIdx = Math.max(0, Math.min(dock.size() - 1, dockIdx + dx));
            else if (dy < 0) zone = ZT;
            else if (dy > 0 && !grid.isEmpty()) { zone = ZG; gridIdx = Math.min(dockIdx, grid.size() - 1); }
        } else {
            int row = gridIdx / COLS;
            if (dx != 0) {
                int n = gridIdx + dx;
                if (n >= 0 && n < grid.size() && n / COLS == row) gridIdx = n;
            } else if (dy > 0) {
                int n = gridIdx + COLS;
                if (n < grid.size()) gridIdx = n;
                else if ((row + 1) * COLS < grid.size()) gridIdx = grid.size() - 1;  // partial last row
            } else if (dy < 0) {
                if (row > 0) gridIdx -= COLS;
                else if (!dock.isEmpty()) { zone = ZD; dockIdx = Math.min(gridIdx % COLS, dock.size() - 1); }
                else zone = ZT;
            }
        }
        if (oz != zone || ot != topIdx || od != dockIdx || og != gridIdx) { act.sfx.move(); focusMoved(); }
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
            if (topIdx == P_ARR) toggleArrange();
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
                int n = Math.min(grid.size() - 1, i + COLS);
                if (n > i) { grid.remove(i); grid.add(n, held); gridIdx = n; changed = true; }
            } else if (dy < 0) {
                if (i >= COLS) {
                    grid.remove(i); grid.add(i - COLS, held); gridIdx = i - COLS; changed = true;
                } else {
                    int slotI = Math.min(i % COLS, dock.size());
                    if (dock.size() < DOCK_MAX) {
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
        rf.set(mx, 6 * dp, mx + tw + 28 * dp, 38 * dp);
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

        // one band behind the three top icons: frosted (blurred wallpaper) + glass sheen
        bandRect(rf);
        float bandRad = rf.height() / 2f;
        if (barBlur != null) {
            c.save();
            path.reset();
            path.addRoundRect(rf, bandRad, bandRad, Path.Direction.CW);
            c.clipPath(path);
            c.drawBitmap(barBlur, null, rf, blurPaint);
            c.restore();
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x26FFFFFF);               // frost tint
            c.drawRoundRect(rf, bandRad, bandRad, p);
        }
        glass(c, rf, bandRad, 0x1E, false);

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
            float sc = held != null ? HELD : pop.v;
            float sw = 3 * dp, d = 3 * dp + sw / 2f;
            float cx = hx.v + hw.v / 2f, cy = hy.v + hh.v / 2f;
            float w2 = (hw.v * sc + ex) / 2f + d, h2 = (hh.v * sc + ey) / 2f + d;
            rf.set(cx - w2, cy - h2, cx + w2, cy + h2);
            float rad = 18 * dp * sc + d;
            int col = glowCur & 0x00FFFFFF;
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
        if (ddOpen) drawDropdown(c);

        c.restore();
        drawHint(c);
        drawMenu(c);
        if (anim) postInvalidateOnAnimation(); else lastNs = 0;
    }

    /** The part of the wallpaper bitmap that sits behind a screen rectangle (centre-crop mapping). */
    private void wallSource(RectF scr, Rect out) {
        float bw = wall.getWidth(), bh = wall.getHeight();
        float sc = Math.max(W / bw, H / bh);
        float offX = (bw - W / sc) / 2f, offY = (bh - H / sc) / 2f;
        out.set((int) (offX + scr.left / sc), (int) (offY + scr.top / sc),
                (int) (offX + scr.right / sc), (int) (offY + scr.bottom / sc));
    }

    private void drawWall(Canvas c) {
        if (wall == null) return;
        wallDst.set(0, 0, W, H);
        wallSource(wallDst, wallSrc);
        c.drawBitmap(wall, wallSrc, wallDst, wallPaint);
        c.drawRect(wallDst, scrimPaint);   // faint dark scrim at the top and bottom keeps text readable
    }

    private void bandRect(RectF out) {
        out.set(pill[0].left - 14 * dp, pill[0].top - 8 * dp, pill[NP - 1].right + 14 * dp, pill[0].bottom + 8 * dp);
    }

    /** Frosted look for the top bar: a blurred copy of the wallpaper behind it, made once per layout (no per-frame blur). */
    private void buildBarBlur() {
        if (barBlur != null) { barBlur.recycle(); barBlur = null; }
        if (wall == null || W == 0) return;
        try {
            bandRect(rg);
            wallSource(rg, tmpSrc);
            tmpSrc.intersect(0, 0, wall.getWidth(), wall.getHeight());
            if (tmpSrc.width() < 4 || tmpSrc.height() < 4) return;
            Bitmap crop = Bitmap.createBitmap(wall, tmpSrc.left, tmpSrc.top, tmpSrc.width(), tmpSrc.height());
            Bitmap small = Bitmap.createScaledBitmap(crop, Math.max(2, crop.getWidth() / 14), Math.max(2, crop.getHeight() / 14), true);
            Bitmap tiny = Bitmap.createScaledBitmap(small, Math.max(2, small.getWidth() / 2), Math.max(2, small.getHeight() / 2), true);
            if (crop != wall) crop.recycle();
            if (small != tiny) small.recycle();
            barBlur = tiny;
        } catch (Throwable t) {
            barBlur = null;
        }
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
        greetStr = h < 5 ? "Chào buổi tối" : h < 12 ? "Chào buổi sáng" : h < 14 ? "Chào buổi trưa"
                : h < 18 ? "Chào buổi chiều" : "Chào buổi tối";
    }

    private void drawHeader(Canvas c) {
        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(regular);
        text.setTextSize(13 * dp);
        text.setLetterSpacing(0.28f);
        text.setColor(0xCCFFFFFF);
        c.drawText("XIN CHÀO", mx, 46 * dp, text);
        text.setLetterSpacing(0f);

        text.setTypeface(light);
        text.setTextSize(54 * dp);
        text.setColor(Color.WHITE);
        float base = 98 * dp;
        c.drawText(timeStr, mx, base, text);
        float cw = text.measureText(timeStr);

        float dx = mx + cw + 20 * dp;                 // thin divider between the clock and the info block
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x55FFFFFF);
        c.drawRect(dx, base - 34 * dp, dx + 1.5f * dp, base, p);
        drawInfo(c, dx + 20 * dp, base);
    }

    // --------------------------------------------------------------- weather

    /** Three compact lines: weather, date, lunar date. */
    private void drawInfo(Canvas c, float bx, float base) {
        float y1 = base - 48 * dp, y2 = base - 24 * dp, y3 = base;
        text.setTypeface(regular);
        Weather w = weather;
        if (w != null) {
            drawIcon(c, w, bx + 11 * dp, y1 - 6 * dp, 22 * dp);
            text.setTextSize(20 * dp);
            text.setColor(Color.WHITE);
            String l1 = Math.round(w.temp) + "°C " + w.text() + (place.length() > 0 ? " · " + place : "");
            c.drawText(l1, bx + 30 * dp, y1, text);
        }
        text.setTextSize(14.5f * dp);
        text.setLetterSpacing(0.14f);
        text.setColor(0xCCFFFFFF);
        c.drawText(dateStr, bx, y2, text);
        c.drawText(lunarStr, bx, y3, text);
        text.setLetterSpacing(0f);
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
            if (i == P_ARR) { if (arrange) iconCheck(c, cx, cy, u); else iconGrid(c, cx, cy, u); }
            else if (i == P_WIFI) iconWifi(c, cx, cy, u);
            else iconGear(c, cx, cy, u);
        }
        p.setStyle(Paint.Style.FILL);
        p.setStrokeCap(Paint.Cap.BUTT);
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
        if (dock.isEmpty()) return;
        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(light);
        text.setTextSize(52 * dp);
        text.setColor(Color.WHITE);
        CharSequence s = TextUtils.ellipsize(dock.get(dockIdx).label, text, W - 2 * mx, TextUtils.TruncateAt.END);
        c.drawText(s, 0, s.length(), mx, dockTop - 36 * dp, text);
    }

    private void drawDock(Canvas c) {
        for (int i = 0; i < dock.size(); i++) {
            slot(i, tmp);
            drawArt(c, dock.get(i), tmp, (zone == ZD && i == dockIdx) ? (held != null ? HELD : pop.v) : 1f);
        }
    }

    private void drawGrid(Canvas c) {
        text.setTypeface(regular);
        text.setTextSize(22 * dp);
        for (int i = 0; i < grid.size(); i++) {
            gridRect(i, tmp);
            if (tmp.bottom + 40 * dp - scroll.v < 0 || tmp.top - scroll.v > H) continue;
            boolean f = zone == ZG && i == gridIdx;
            float sc = f ? (held != null ? HELD : pop.v) : 1f;
            drawArt(c, grid.get(i), tmp, sc);
            if (f) {
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
