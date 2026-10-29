package com.local.glasshome;

import android.content.Context;
import android.content.Intent;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;

public class HomeView extends FrameLayout {

    private View calendarWidget;
    private View weatherWidget;

    public HomeView(Context context) {
        super(context);
        init(context);
    }

    public HomeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public HomeView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        // Inflate layout hoặc tích hợp widget_calendar_weather
        // Thắt chặt ánh xạ 2 Widget mới thay thế phần thời tiết cũ ở góc phải
        setupWidgets();
    }

    private void setupWidgets() {
        calendarWidget = findViewById(R.id.widget_calendar_root);
        weatherWidget = findViewById(R.id.widget_weather_root);

        // Bật tính năng Focus cho remote Android TV
        if (calendarWidget != null) {
            calendarWidget.setFocusable(true);
            calendarWidget.setFocusableInTouchMode(true);

            // Bấm OK/Click trên Widget Lịch -> Mở Activity Lịch âm dương chi tiết
            calendarWidget.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(getContext(), Lunar.class);
                    getContext().startActivity(intent);
                }
            });
        }

        if (weatherWidget != null) {
            weatherWidget.setFocusable(true);
            weatherWidget.setFocusableInTouchMode(true);

            // Bấm OK/Click trên Widget Thời tiết -> Mở Activity Thời tiết chi tiết
            weatherWidget.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent intent = new Intent(getContext(), Weather.class);
                    getContext().startActivity(intent);
                }
            });
        }
    }
}
