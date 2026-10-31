package com.local.glasshome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Calendar;

/** Weather from Open-Meteo (free, no API key): now, the next 8 two-hour slots and 7 days. */
final class Weather {
    static final int SUN = 0, MOON = 1, PARTLY = 2, CLOUD = 3, FOG = 4, RAIN = 5, STORM = 6, SNOW = 7;

    final long time;      // when it was fetched
    final String raw;     // the API response, kept for the cache

    // now
    float temp, hi, lo, feels, wind;
    int code, humidity, uv, rainMax;
    boolean day = true;
    String sunrise = "", sunset = "";

    // next hours (every 2 h, the first one is "now") and next days
    int nHours, nDays;
    final String[] hLabel = new String[8];
    final int[] hCode = new int[8], hTemp = new int[8], hRain = new int[8];
    final boolean[] hDay = new boolean[8];
    final String[] dLabel = new String[7];
    final int[] dCode = new int[7], dMax = new int[7], dMin = new int[7], dRain = new int[7];

    private Weather(String raw, long time) {
        this.raw = raw;
        this.time = time;
    }

    String encode() { return time + "\n" + raw; }

    static Weather decode(String s) {
        try {
            int nl = s.indexOf('\n');
            if (nl <= 0) return null;
            return parse(s.substring(nl + 1), Long.parseLong(s.substring(0, nl)));
        } catch (Exception e) {
            return null;
        }
    }

    private static String hhmm(String iso) {
        return iso != null && iso.length() >= 16 ? iso.substring(11, 16) : "";
    }

    private static String weekdayLabel(String ymd) {
        try {
            Calendar c = Calendar.getInstance();
            c.clear();
            c.set(Integer.parseInt(ymd.substring(0, 4)), Integer.parseInt(ymd.substring(5, 7)) - 1, Integer.parseInt(ymd.substring(8, 10)));
            int dow = c.get(Calendar.DAY_OF_WEEK);
            String[] n = { "CN", "T2", "T3", "T4", "T5", "T6", "T7" };
            return n[dow - 1] + " " + Integer.parseInt(ymd.substring(8, 10)) + "/" + Integer.parseInt(ymd.substring(5, 7));
        } catch (Exception e) {
            return ymd;
        }
    }

    static Weather parse(String json, long time) {
        try {
            Weather w = new Weather(json, time);
            JSONObject j = new JSONObject(json);
            JSONObject cur = j.getJSONObject("current");
            w.temp = (float) cur.getDouble("temperature_2m");
            w.code = cur.getInt("weather_code");
            w.day = cur.optInt("is_day", 1) == 1;
            w.feels = (float) cur.optDouble("apparent_temperature", w.temp);
            w.humidity = cur.optInt("relative_humidity_2m", 0);
            w.wind = (float) cur.optDouble("wind_speed_10m", 0);
            String curTime = cur.optString("time", "");

            JSONObject d = j.getJSONObject("daily");
            JSONArray dt = d.getJSONArray("time"), dmax = d.getJSONArray("temperature_2m_max"), dmin = d.getJSONArray("temperature_2m_min");
            JSONArray dcode = d.optJSONArray("weather_code"), drain = d.optJSONArray("precipitation_probability_max");
            JSONArray sr = d.optJSONArray("sunrise"), ss = d.optJSONArray("sunset"), uvA = d.optJSONArray("uv_index_max");
            w.hi = (float) dmax.getDouble(0);
            w.lo = (float) dmin.getDouble(0);
            w.rainMax = drain != null ? drain.optInt(0, 0) : 0;
            w.uv = uvA != null ? (int) Math.round(uvA.optDouble(0, 0)) : 0;
            w.sunrise = sr != null ? hhmm(sr.optString(0, "")) : "";
            w.sunset = ss != null ? hhmm(ss.optString(0, "")) : "";
            w.nDays = Math.min(7, dt.length());
            for (int i = 0; i < w.nDays; i++) {
                w.dLabel[i] = i == 0 ? "Hôm nay" : weekdayLabel(dt.getString(i));
                w.dCode[i] = dcode != null ? dcode.optInt(i, 0) : 0;
                w.dMax[i] = (int) Math.round(dmax.getDouble(i));
                w.dMin[i] = (int) Math.round(dmin.getDouble(i));
                w.dRain[i] = drain != null ? drain.optInt(i, 0) : 0;
            }

            JSONObject h = j.optJSONObject("hourly");
            if (h != null) {
                JSONArray ht = h.getJSONArray("time"), htemp = h.getJSONArray("temperature_2m");
                JSONArray hcode = h.optJSONArray("weather_code"), hrain = h.optJSONArray("precipitation_probability");
                String key = curTime.length() >= 13 ? curTime.substring(0, 13) + ":00" : "";
                int idx = 0;
                for (int i = 0; i < ht.length(); i++) {
                    if (ht.getString(i).equals(key)) { idx = i; break; }
                }
                for (int k = 0; k < 8; k++) {
                    int i = idx + 2 * k;
                    if (i >= ht.length()) break;
                    String t = ht.getString(i);
                    int hr = Integer.parseInt(t.substring(11, 13));
                    w.hLabel[k] = k == 0 ? "Bây giờ" : hr + "h";
                    w.hTemp[k] = (int) Math.round(htemp.getDouble(i));
                    w.hCode[k] = hcode != null ? hcode.optInt(i, 0) : 0;
                    w.hRain[k] = hrain != null ? hrain.optInt(i, 0) : 0;
                    w.hDay[k] = k == 0 ? w.day : (hr >= 6 && hr < 18);
                    w.nHours = k + 1;
                }
            }
            return w;
        } catch (Exception e) {
            return null;
        }
    }

    // ---- icon kind and text for any code (now, hourly, daily)

    static int kindFor(int code, boolean day) {
        switch (code) {
            case 0: return day ? SUN : MOON;
            case 1:
            case 2: return PARTLY;
            case 3: return CLOUD;
            case 45:
            case 48: return FOG;
            case 71: case 73: case 75: case 77: case 85: case 86: return SNOW;
            case 95: case 96: case 99: return STORM;
            default:
                if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return RAIN;
                return CLOUD;
        }
    }

    static String textFor(int code) {
        switch (code) {
            case 0: return "Trời quang";
            case 1: return "Ít mây";
            case 2: return "Có mây";
            case 3: return "Nhiều mây";
            case 45:
            case 48: return "Sương mù";
            case 51: case 53: case 55: case 56: case 57: return "Mưa phùn";
            case 61: return "Mưa nhỏ";
            case 63: return "Mưa vừa";
            case 65: return "Mưa to";
            case 66: case 67: return "Mưa lạnh";
            case 71: case 73: case 75: case 77: return "Tuyết";
            case 80: case 81: case 82: return "Mưa rào";
            case 85: case 86: return "Mưa tuyết";
            case 95: case 96: case 99: return "Dông";
            default: return "Nhiều mây";
        }
    }

    int kind() { return kindFor(code, day); }

    String text() { return textFor(code); }

    String uvText() {
        if (uv < 3) return uv + " · thấp";
        if (uv < 6) return uv + " · trung bình";
        if (uv < 8) return uv + " · cao";
        if (uv < 11) return uv + " · rất cao";
        return uv + " · cực cao";
    }

    /** Blocking network call: run it off the UI thread. Returns null on any failure. */
    static Weather fetch(double lat, double lon) {
        HttpURLConnection c = null;
        try {
            URL u = new URL("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                    + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,is_day,wind_speed_10m"
                    + "&hourly=temperature_2m,weather_code,precipitation_probability"
                    + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset,uv_index_max"
                    + "&timezone=auto&forecast_days=7");
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            if (c.getResponseCode() != 200) return null;
            return parse(read(c.getInputStream()), System.currentTimeMillis());
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Looks a city name up. Returns {lat, lon, name} or null. Blocking: run off the UI thread. */
    static String[] geocode(String name) {
        HttpURLConnection c = null;
        try {
            URL u = new URL("https://geocoding-api.open-meteo.com/v1/search?count=1&language=vi&name="
                    + URLEncoder.encode(name, "UTF-8"));
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            if (c.getResponseCode() != 200) return null;
            JSONObject j = new JSONObject(read(c.getInputStream()));
            JSONArray r = j.optJSONArray("results");
            if (r == null || r.length() == 0) return null;
            JSONObject o = r.getJSONObject(0);
            return new String[] { String.valueOf(o.getDouble("latitude")),
                    String.valueOf(o.getDouble("longitude")), o.optString("name", name) };
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String read(InputStream in) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) {
            sb.append(line);
            if (sb.length() > 400000) break;
        }
        r.close();
        return sb.toString();
    }
}
