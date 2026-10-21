package com.local.glasshome;

import android.content.ComponentName;
import android.graphics.drawable.Drawable;

final class AppEntry {
    final String label;
    final ComponentName cn;
    Drawable banner;   // 16:9 banner if the app has one
    Drawable icon;     // fallback
    int glow;
    boolean glowDone;

    AppEntry(String label, ComponentName cn) {
        this.label = label;
        this.cn = cn;
    }
}
