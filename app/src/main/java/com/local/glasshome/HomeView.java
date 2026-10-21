package com.local.glasshome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
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
    private boolean centerDown = false;

    private boolean ddOpen = false;
    private int ddIdx = 0;
    private List<InputItem> inputs = new ArrayList<>();

    private final Spring hx = new Spring(), hy = new Spring(), hw = new Spring(), hh = new Spring();
    private final Spring hr = new Spring(), scroll = new Spring(), pop = new Spring(), gx = new Spring();

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wallPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Path path = new Path();
    private final RectF rf = new RectF(), tmp = new RectF();
    private Typeface light, regular;
    private Bitmap wall;

    private float W, H, mx, dockTop, bandH, slotW, slotH, padD, gapD;
    private float cellW, tileW, tileH, rowH, gridTop, pillH;
    private final RectF[] pill = { new RectF(), new RectF(), new RectF() };
    private final String[] pillText = { "Inputs", "Wi-Fi", "Settings" };

    private int glowCur = 0xFF8E8E93, glowTgt = 0xFF8E8E93, shaderColor = 0;
    private RadialGradient glowShader;

    private long lastNs = 0, minuteKey = -1;
    private String timeStr = "", dateStr = "", greetStr = "";
    private final SimpleDateFormat timeFmt, dateFmt;

    HomeView(Context ctx) {
        super(ctx);
        act = (MainActivity) ctx;
        dp = ctx.getResources().getDisplayMetrics().density;
        setFocusable(true);
        pop.eps = 0.002f;
        pop.veps = 0.02f;
        pop.snap(POP);

        try { light = Typeface.createFromAsset(ctx.getAssets(), "fonts/Outfit-Light.ttf"); }
        catch (Exception e) { light = Typeface.create("sans-serif-light", Typeface.NORMAL); }
        try { regular = Typeface.createFromAsset(ctx.getAssets(), "fonts/Outfit-Regular.ttf"); }
        catch (Exception e) { regular = Typeface.create("sans-serif", Typeface.NORMAL); }

        // Optional wallpaper: put wallpaper.jpg in app/src/main/assets/ (shown dimmed). Default is pure black.
        try {
            InputStream in = ctx.getAssets().open("wallpaper.jpg");
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2;
            wall = BitmapFactory.decodeStream(in, null, o);
            in.close();
        } catch (Exception ignored) { wall = null; }
        wallPaint.setAlpha(120);

        boolean h24 = android.text.format.DateFormat.is24HourFormat(ctx);
        timeFmt = new SimpleDateFormat(h24 ? "HH:mm" : "h:mm", Locale.getDefault());
        dateFmt = new SimpleDateFormat("EEEE, d MMMM", Locale.getDefault());
        pillText[1] = act.wifiLabel();
    }

    // ------------------------------------------------------------------ data

    void setApps(List<AppEntry> all, String dockCsv, String last) {
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
        if (zone != ZD) { zone = ZD; fixZone(); }
        retarget(false);
    }

    void refreshWifi() {
        String s = act.wifiLabel();
        if (s.length() > 20) s = s.substring(0, 20) + "…";
        pillText[1] = s;
        relayout();
        retarget(false);
    }

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
        slotW = 200 * dp;
        slotH = slotW * 9f / 16f;
        padD = 24 * dp;
        gapD = 24 * dp;
        bandH = slotH + 2 * padD;
        dockTop = H - 56 * dp - bandH;
        cellW = (W - 2 * mx) / COLS;
        tileW = cellW - 24 * dp;
        tileH = tileW * 9f / 16f;
        rowH = tileH + 64 * dp;
        gridTop = H + 24 * dp;
        pillH = 44 * dp;

        text.setTypeface(regular);
        text.setTextSize(18 * dp);
        float right = W - mx;
        for (int i = 2; i >= 0; i--) {
            float w = text.measureText(pillText[i]) + 40 * dp;
            pill[i].set(right - w, 40 * dp, right, 40 * dp + pillH);
            right -= w + 14 * dp;
        }
    }

    private void slot(int i, RectF r) {
        float l = mx + padD + i * (slotW + gapD);
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
            slot(dockIdx, tmp); infl = 10 * dp; rad = 18 * dp + infl;
        } else if (zone == ZG && !grid.isEmpty()) {
            gridRect(gridIdx, tmp); infl = 14 * dp; rad = 18 * dp + infl;
        } else {
            tmp.set(pill[0]); infl = 4 * dp; rad = pillH / 2 + infl;
        }
        tmp.inset(-infl, -infl);
        hx.t = tmp.left; hy.t = tmp.top; hw.t = tmp.width(); hh.t = tmp.height(); hr.t = rad;
        scroll.t = scrollTarget();
        if (!dock.isEmpty()) {
            slot(dockIdx, rf);
            gx.t = rf.centerX();
            glowTgt = colorOf(dock.get(dockIdx));
        } else {
            gx.t = W / 2;
        }
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
        if (ddOpen) return ddDown(k, e);
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_LEFT: move(-1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: move(1, 0); return true;
            case KeyEvent.KEYCODE_DPAD_UP: move(0, -1); return true;
            case KeyEvent.KEYCODE_DPAD_DOWN: move(0, 1); return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (e.getRepeatCount() == 0) centerDown = true;
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (zone != ZD && !dock.isEmpty()) { zone = ZD; focusMoved(); }
                return true; // a launcher never exits on Back
        }
        return false;
    }

    boolean onNavUp(int k, KeyEvent e) {
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (centerDown) { centerDown = false; activate(); }
            return true;
        }
        return false;
    }

    private boolean ddDown(int k, KeyEvent e) {
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: ddIdx = Math.max(0, ddIdx - 1); break;
            case KeyEvent.KEYCODE_DPAD_DOWN: ddIdx = Math.min(Math.max(0, inputs.size() - 1), ddIdx + 1); break;
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
        int oz = zone, ot = topIdx, od = dockIdx, og = gridIdx;
        if (zone == ZT) {
            if (dx != 0) topIdx = Math.max(0, Math.min(2, topIdx + dx));   // ends do not fall through
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
        if (oz != zone || ot != topIdx || od != dockIdx || og != gridIdx) focusMoved();
    }

    private void activate() {
        if (ddOpen) {
            ddOpen = false;
            if (!inputs.isEmpty()) act.switchInput(inputs.get(ddIdx));
            invalidate();
            return;
        }
        if (zone == ZT) {
            if (topIdx == 0) { inputs = act.listInputs(); ddIdx = 0; ddOpen = true; invalidate(); }
            else if (topIdx == 1) act.openWifi();
            else act.openSettings();
        } else if (zone == ZD && !dock.isEmpty()) act.launch(dock.get(dockIdx));
        else if (zone == ZG && !grid.isEmpty()) act.launch(grid.get(gridIdx));
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

        // dock band
        if (!dock.isEmpty()) {
            float bw = dock.size() * slotW + (dock.size() - 1) * gapD + 2 * padD;
            rf.set(mx, dockTop, mx + bw, dockTop + bandH);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x24FFFFFF);
            c.drawRoundRect(rf, 36 * dp, 36 * dp, p);
        }
        // pill backgrounds
        for (int i = 0; i < 3; i++) {
            p.setStyle(Paint.Style.FILL);
            p.setColor(0x30FFFFFF);
            c.drawRoundRect(pill[i], pillH / 2, pillH / 2, p);
        }

        // the single glass highlight
        rf.set(hx.v, hy.v, hx.v + hw.v, hy.v + hh.v);
        p.setStyle(Paint.Style.FILL);
        p.setColor(0x40FFFFFF);
        c.drawRoundRect(rf, hr.v, hr.v, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(1.5f * dp);
        p.setColor(0x80FFFFFF);
        c.drawRoundRect(rf, hr.v, hr.v, p);
        p.setStyle(Paint.Style.FILL);

        drawPillText(c);
        drawDock(c);
        drawGrid(c);
        drawName(c);
        if (ddOpen) drawDropdown(c);

        c.restore();
        if (anim) postInvalidateOnAnimation(); else lastNs = 0;
    }

    private void drawWall(Canvas c) {
        if (wall == null) return;
        float bw = wall.getWidth(), bh = wall.getHeight();
        float s = Math.max(W / bw, H / bh);
        float sw = W / s, sh = H / s;
        Rect src = new Rect((int) ((bw - sw) / 2), (int) ((bh - sh) / 2), (int) ((bw + sw) / 2), (int) ((bh + sh) / 2));
        c.drawBitmap(wall, src, new RectF(0, 0, W, H), wallPaint);
    }

    private void refreshClock() {
        long m = System.currentTimeMillis() / 60000L;
        if (m == minuteKey) return;
        minuteKey = m;
        Date d = new Date();
        timeStr = timeFmt.format(d);
        String ds = dateFmt.format(d);
        dateStr = ds.length() > 0 ? Character.toUpperCase(ds.charAt(0)) + ds.substring(1) : ds;
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        greetStr = h < 5 ? "Chào buổi tối" : h < 12 ? "Chào buổi sáng" : h < 14 ? "Chào buổi trưa"
                : h < 18 ? "Chào buổi chiều" : "Chào buổi tối";
    }

    private void drawHeader(Canvas c) {
        text.setTextAlign(Paint.Align.LEFT);
        text.setTypeface(regular);
        text.setTextSize(26 * dp);
        text.setColor(0xCCFFFFFF);
        c.drawText(greetStr, mx, 76 * dp, text);
        text.setTypeface(light);
        text.setTextSize(104 * dp);
        text.setColor(Color.WHITE);
        c.drawText(timeStr, mx, 184 * dp, text);
        text.setTypeface(regular);
        text.setTextSize(26 * dp);
        text.setColor(0xAAFFFFFF);
        c.drawText(dateStr, mx, 226 * dp, text);
    }

    private void drawPillText(Canvas c) {
        text.setTypeface(regular);
        text.setTextSize(18 * dp);
        text.setColor(Color.WHITE);
        text.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = text.getFontMetrics();
        for (int i = 0; i < 3; i++) {
            float by = pill[i].centerY() - (fm.ascent + fm.descent) / 2f;
            c.drawText(pillText[i], pill[i].centerX(), by, text);
        }
        text.setTextAlign(Paint.Align.LEFT);
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
            drawArt(c, dock.get(i), tmp, (zone == ZD && i == dockIdx) ? pop.v : 1f);
        }
    }

    private void drawGrid(Canvas c) {
        text.setTypeface(regular);
        text.setTextSize(22 * dp);
        for (int i = 0; i < grid.size(); i++) {
            gridRect(i, tmp);
            if (tmp.bottom + 40 * dp - scroll.v < 0 || tmp.top - scroll.v > H) continue;
            boolean f = zone == ZG && i == gridIdx;
            drawArt(c, grid.get(i), tmp, f ? pop.v : 1f);
            if (f) {
                float extra = tmp.height() * (pop.v - 1f) / 2f;
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
        float x = pill[0].left, y = pill[0].bottom + 10 * dp, h = n * rowH2 + 16 * dp;
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
