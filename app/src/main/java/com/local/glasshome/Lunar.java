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
}
