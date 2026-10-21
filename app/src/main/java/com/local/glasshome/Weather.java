package com.local.glasshome;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;

/** Current weather + today's high/low from Open-Meteo (free, no API key). */
final class Weather {
    static final int SUN = 0, MOON = 1, PARTLY = 2, CLOUD = 3, FOG = 4, RAIN = 5, STORM = 6, SNOW = 7;

    final float temp, hi, lo;
    final int code;
    final boolean day;
    final long time;

    Weather(float temp, float hi, float lo, int code, boolean day, long time) {
        this.temp = temp;
        this.hi = hi;
        this.lo = lo;
        this.code = code;
        this.day = day;
        this.time = time;
    }

    String encode() {
        return temp + "|" + hi + "|" + lo + "|" + code + "|" + (day ? 1 : 0) + "|" + time;
    }

    static Weather decode(String s) {
        try {
            String[] a = s.split("\\|");
            return new Weather(Float.parseFloat(a[0]), Float.parseFloat(a[1]), Float.parseFloat(a[2]),
                    Integer.parseInt(a[3]), "1".equals(a[4]), Long.parseLong(a[5]));
        } catch (Exception e) {
            return null;
        }
    }

    int kind() {
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

    String text() {
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

    /** Blocking network call: run it off the UI thread. Returns null on any failure. */
    static Weather fetch(double lat, double lon) {
        HttpURLConnection c = null;
        try {
            URL u = new URL("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                    + "&current=temperature_2m,weather_code,is_day"
                    + "&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1");
            c = (HttpURLConnection) u.openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            if (c.getResponseCode() != 200) return null;
            JSONObject j = new JSONObject(read(c.getInputStream()));
            JSONObject cur = j.getJSONObject("current");
            JSONObject d = j.getJSONObject("daily");
            return new Weather((float) cur.getDouble("temperature_2m"),
                    (float) d.getJSONArray("temperature_2m_max").getDouble(0),
                    (float) d.getJSONArray("temperature_2m_min").getDouble(0),
                    cur.getInt("weather_code"), cur.getInt("is_day") == 1, System.currentTimeMillis());
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
            if (sb.length() > 200000) break;
        }
        r.close();
        return sb.toString();
    }
}
