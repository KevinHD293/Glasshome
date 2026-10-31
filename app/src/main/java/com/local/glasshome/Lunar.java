package com.local.glasshome;

/** Vietnamese lunar calendar (Ho Ngoc Duc's algorithm, time zone UTC+7). Pure Java, no Android classes. */
final class Lunar {
    private static final String[] CAN = { "Giáp", "Ất", "Bính", "Đinh", "Mậu", "Kỷ", "Canh", "Tân", "Nhâm", "Quý" };
    private static final String[] CHI = { "Tý", "Sửu", "Dần", "Mão", "Thìn", "Tỵ", "Ngọ", "Mùi", "Thân", "Dậu", "Tuất", "Hợi" };
    private static final double TZ = 7.0;

    private Lunar() { }

    /** e.g. "14/9 Âm lịch · Bính Ngọ" (adds "nhuận" for a leap month). */
    static String describe(int yy, int mm, int dd) {
        try {
            int[] l = solarToLunar(dd, mm, yy);
            String year = CAN[((l[2] - 4) % 10 + 10) % 10] + " " + CHI[((l[2] - 4) % 12 + 12) % 12];
            return l[0] + "/" + l[1] + (l[3] == 1 ? " nhuận" : "") + " Âm lịch · " + year;
        } catch (Exception e) {
            return "";
        }
    }

    private static int INT(double d) { return (int) Math.floor(d); }

    private static int jdFromDate(int dd, int mm, int yy) {
        int a = INT((14 - mm) / 12.0);
        int y = yy + 4800 - a;
        int m = mm + 12 * a - 3;
        int jd = dd + INT((153 * m + 2) / 5.0) + 365 * y + INT(y / 4.0) - INT(y / 100.0) + INT(y / 400.0) - 32045;
        if (jd < 2299161) jd = dd + INT((153 * m + 2) / 5.0) + 365 * y + INT(y / 4.0) - 32083;
        return jd;
    }

    private static double newMoon(int k) {
        double T = k / 1236.85, T2 = T * T, T3 = T2 * T, dr = Math.PI / 180;
        double jd1 = 2415020.75933 + 29.53058868 * k + 0.0001178 * T2 - 0.000000155 * T3;
        jd1 += 0.00033 * Math.sin((166.56 + 132.87 * T - 0.009173 * T2) * dr);
        double M = 359.2242 + 29.10535608 * k - 0.0000333 * T2 - 0.00000347 * T3;
        double Mpr = 306.0253 + 385.81691806 * k + 0.0107306 * T2 + 0.00001236 * T3;
        double F = 21.2964 + 390.67050646 * k - 0.0016528 * T2 - 0.00000239 * T3;
        double c1 = (0.1734 - 0.000393 * T) * Math.sin(M * dr) + 0.0021 * Math.sin(2 * dr * M);
        c1 = c1 - 0.4068 * Math.sin(Mpr * dr) + 0.0161 * Math.sin(dr * 2 * Mpr);
        c1 = c1 - 0.0004 * Math.sin(dr * 3 * Mpr);
        c1 = c1 + 0.0104 * Math.sin(dr * 2 * F) - 0.0051 * Math.sin(dr * (M + Mpr));
        c1 = c1 - 0.0074 * Math.sin(dr * (M - Mpr)) + 0.0004 * Math.sin(dr * (2 * F + M));
        c1 = c1 - 0.0004 * Math.sin(dr * (2 * F - M)) - 0.0006 * Math.sin(dr * (2 * F + Mpr));
        c1 = c1 + 0.0010 * Math.sin(dr * (2 * F - Mpr)) + 0.0005 * Math.sin(dr * (2 * Mpr + M));
        double deltat;
        if (T < -11) deltat = 0.001 + 0.000839 * T + 0.0002261 * T2 - 0.00000845 * T3 - 0.000000081 * T * T3;
        else deltat = -0.000278 + 0.000265 * T + 0.000262 * T2;
        return jd1 + c1 - deltat;
    }

    private static double sunLongitude(double jdn) {
        double T = (jdn - 2451545.0) / 36525, T2 = T * T, dr = Math.PI / 180;
        double M = 357.52910 + 35999.05030 * T - 0.0001559 * T2 - 0.00000048 * T * T2;
        double L0 = 280.46645 + 36000.76983 * T + 0.0003032 * T2;
        double DL = (1.914600 - 0.004817 * T - 0.000014 * T2) * Math.sin(dr * M);
        DL += (0.019993 - 0.000101 * T) * Math.sin(dr * 2 * M) + 0.000290 * Math.sin(dr * 3 * M);
        double L = (L0 + DL) * dr;
        L -= Math.PI * 2 * INT(L / (Math.PI * 2));
        return L;
    }

    private static int getSunLongitude(int dayNumber) {
        return INT(sunLongitude(dayNumber - 0.5 - TZ / 24.0) / Math.PI * 6);
    }

    private static int getNewMoonDay(int k) {
        return INT(newMoon(k) + 0.5 + TZ / 24.0);
    }

    private static int getLunarMonth11(int yy) {
        int off = jdFromDate(31, 12, yy) - 2415021;
        int k = INT(off / 29.530588853);
        int nm = getNewMoonDay(k);
        if (getSunLongitude(nm) >= 9) nm = getNewMoonDay(k - 1);
        return nm;
    }

    private static int getLeapMonthOffset(int a11) {
        int k = INT((a11 - 2415021.076998695) / 29.530588853 + 0.5);
        int last, i = 1;
        int arc = getSunLongitude(getNewMoonDay(k + i));
        do {
            last = arc;
            i++;
            arc = getSunLongitude(getNewMoonDay(k + i));
        } while (arc != last && i < 14);
        return i - 1;
    }

    /** Returns {day, month, year, leap(0/1)} of the lunar date. */
    static int[] solarToLunar(int dd, int mm, int yy) {
        int dayNumber = jdFromDate(dd, mm, yy);
        int k = INT((dayNumber - 2415021.076998695) / 29.530588853);
        int monthStart = getNewMoonDay(k + 1);
        if (monthStart > dayNumber) monthStart = getNewMoonDay(k);
        int a11 = getLunarMonth11(yy);
        int b11 = a11;
        int lunarYear;
        if (a11 >= monthStart) {
            lunarYear = yy;
            a11 = getLunarMonth11(yy - 1);
        } else {
            lunarYear = yy + 1;
            b11 = getLunarMonth11(yy + 1);
        }
        int lunarDay = dayNumber - monthStart + 1;
        int diff = INT((monthStart - a11) / 29.0);
        int leap = 0;
        int lunarMonth = diff + 11;
        if (b11 - a11 > 365) {
            int leapMonthDiff = getLeapMonthOffset(a11);
            if (diff >= leapMonthDiff) {
                lunarMonth = diff + 10;
                if (diff == leapMonthDiff) leap = 1;
            }
        }
        if (lunarMonth > 12) lunarMonth -= 12;
        if (lunarMonth >= 11 && diff < 4) lunarYear -= 1;
        return new int[] { lunarDay, lunarMonth, lunarYear, leap };
    }

    // ------------------------------------------------------------------ day details (calendar widget)

    private static final String[] TERMS = { "Xuân phân", "Thanh minh", "Cốc vũ", "Lập hạ", "Tiểu mãn", "Mang chủng", "Hạ chí",
            "Tiểu thử", "Đại thử", "Lập thu", "Xử thử", "Bạch lộ", "Thu phân", "Hàn lộ", "Sương giáng", "Lập đông", "Tiểu tuyết",
            "Đại tuyết", "Đông chí", "Tiểu hàn", "Đại hàn", "Lập xuân", "Vũ thủy", "Kinh trập" };
    // the 12-day cycle of "Hoàng đạo / Hắc đạo" stars; the cycle starts at a chi that depends on the lunar month
    private static final String[] STARS = { "Thanh Long", "Minh Đường", "Thiên Hình", "Chu Tước", "Kim Quỹ", "Bảo Quang",
            "Bạch Hổ", "Ngọc Đường", "Thiên Lao", "Nguyên Vũ", "Tư Mệnh", "Câu Trần" };
    private static final boolean[] STAR_GOOD = { true, true, false, false, true, true, false, true, false, false, true, false };
    private static final int[] STAR_START = { 0, 2, 4, 6, 8, 10 };   // months 1/7, 2/8, 3/9, 4/10, 5/11, 6/12 -> Ty, Dan, Thin, Ngo, Than, Tuat
    private static final String[] MONTH_WORDS = { "Giêng", "Hai", "Ba", "Tư", "Năm", "Sáu", "Bảy", "Tám", "Chín", "Mười", "Mười Một", "Chạp" };
    private static final int[][] GOOD_HOURS = {
            { 0, 1, 3, 6, 8, 9 },     // day Ty / Ngo
            { 2, 3, 5, 8, 10, 11 },   // day Suu / Mui
            { 0, 1, 4, 5, 7, 10 },    // day Dan / Than
            { 0, 2, 3, 6, 7, 9 },     // day Mao / Dau
            { 2, 4, 5, 8, 9, 11 },    // day Thin / Tuat
            { 1, 4, 6, 7, 10, 11 } }; // day Ty / Hoi
    private static final String[] HOUR_RANGE = { "23–1", "1–3", "3–5", "5–7", "7–9", "9–11", "11–13", "13–15", "15–17", "17–19", "19–21", "21–23" };

    /** Everything the calendar screen shows about one solar date. */
    static final class Info {
        int day, month, year;          // lunar date
        boolean leap;
        String dayCanChi = "", monthCanChi = "", yearCanChi = "", term = "", star = "", xung = "", monthWord = "";
        boolean good;                  // Hoàng đạo day
        String hours1 = "", hours2 = "";   // good hours, three per line
        String holiday = "";           // name of a public holiday / festival, or ""
    }

    private static int mod(int a, int n) { return ((a % n) + n) % n; }

    private static String holidayOf(int dd, int mm, int ld, int lm, boolean leap) {
        if (dd == 1 && mm == 1) return "Tết Dương lịch";
        if (dd == 8 && mm == 3) return "Quốc tế Phụ nữ";
        if (dd == 30 && mm == 4) return "Giải phóng miền Nam";
        if (dd == 1 && mm == 5) return "Quốc tế Lao động";
        if (dd == 1 && mm == 6) return "Quốc tế Thiếu nhi";
        if (dd == 2 && mm == 9) return "Quốc khánh";
        if (dd == 20 && mm == 10) return "Phụ nữ Việt Nam";
        if (dd == 20 && mm == 11) return "Nhà giáo Việt Nam";
        if (dd == 24 && mm == 12) return "Đêm Giáng sinh";
        if (dd == 25 && mm == 12) return "Giáng sinh";
        if (!leap) {
            if (lm == 1 && ld <= 3) return "Tết Nguyên đán";
            if (lm == 1 && ld == 15) return "Tết Nguyên tiêu";
            if (lm == 3 && ld == 10) return "Giỗ Tổ Hùng Vương";
            if (lm == 4 && ld == 15) return "Phật Đản";
            if (lm == 5 && ld == 5) return "Tết Đoan ngọ";
            if (lm == 7 && ld == 15) return "Vu Lan";
            if (lm == 8 && ld == 15) return "Tết Trung thu";
            if (lm == 12 && ld == 23) return "Ông Táo chầu trời";
        }
        return "";
    }

    static Info info(int yy, int mm, int dd) {
        Info o = new Info();
        try {
            int[] l = solarToLunar(dd, mm, yy);
            o.day = l[0]; o.month = l[1]; o.year = l[2]; o.leap = l[3] == 1;
            int jd = jdFromDate(dd, mm, yy);
            int dayCan = mod(jd + 9, 10), dayChi = mod(jd + 1, 12);
            int yCan = mod(l[2] - 4, 10), yChi = mod(l[2] - 4, 12);
            o.dayCanChi = CAN[dayCan] + " " + CHI[dayChi];
            o.yearCanChi = CAN[yCan] + " " + CHI[yChi];
            o.monthCanChi = CAN[mod(yCan * 2 + l[1] + 1, 10)] + " " + CHI[mod(l[1] + 1, 12)];
            o.monthWord = MONTH_WORDS[mod(l[1] - 1, 12)];
            o.term = TERMS[mod(INT(sunLongitude(jd - 0.5 - TZ / 24.0) / Math.PI * 12), 24)];
            int pos = mod(dayChi - STAR_START[(l[1] - 1) % 6], 12);
            o.star = STARS[pos];
            o.good = STAR_GOOD[pos];
            o.xung = CHI[mod(dayChi + 6, 12)];
            int[] gh = GOOD_HOURS[dayChi % 6];
            StringBuilder a = new StringBuilder(), b = new StringBuilder();
            for (int i = 0; i < gh.length; i++) {
                StringBuilder t = i < 3 ? a : b;
                if (t.length() > 0) t.append("  ·  ");
                t.append(CHI[gh[i]]).append(" (").append(HOUR_RANGE[gh[i]]).append(")");
            }
            o.hours1 = a.toString();
            o.hours2 = b.toString();
            o.holiday = holidayOf(dd, mm, l[0], l[1], o.leap);
        } catch (Exception ignored) { }
        return o;
    }
}
