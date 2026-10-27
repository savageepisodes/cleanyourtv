package com.gathin.tvhome;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lightweight, zero-dependency live weather fetcher using Open-Meteo.
 * Supports auto-locating via HTTPS IP lookup or manual city search.
 */
final class Weather {

    private static final String TAG = "TvHome.Weather";
    private static final int TIMEOUT_MS = 10000;
    private static final long CACHE_VALIDITY_MS = 30 * 60 * 1000L; // 30 mins

    interface Callback {
        void onWeather(int temp, int iconRes, String location);
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static long lastFetchTime = 0;
    private static int cachedTemp = 0;
    private static int cachedIcon = 0;
    private static String cachedLocation = "";

    static void fetch(String city, String units, Callback callback) {
        if (System.currentTimeMillis() - lastFetchTime < CACHE_VALIDITY_MS && cachedIcon != 0) {
            callback.onWeather(cachedTemp, cachedIcon, cachedLocation);
            return;
        }

        EXECUTOR.execute(() -> {
            try {
                double lat = 0, lon = 0;
                boolean hasCoords = false;
                String loc = "";

                if (city != null && !city.trim().isEmpty()) {
                    try {
                        String geoUrl = "https://geocoding-api.open-meteo.com/v1/search?count=1&format=json&name="
                                + URLEncoder.encode(city.trim(), "UTF-8");
                        JSONObject geoObj = get(geoUrl);
                        JSONArray results = geoObj.optJSONArray("results");
                        if (results != null && results.length() > 0) {
                            JSONObject first = results.getJSONObject(0);
                            lat = first.getDouble("latitude");
                            lon = first.getDouble("longitude");
                            hasCoords = true;
                            loc = first.optString("name", city.trim());
                            String cc = first.optString("country_code", "");
                            if (!cc.isEmpty()) loc += ", " + cc;
                            Log.i(TAG, "Geocoded city '" + city + "' -> " + lat + ", " + lon);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "City geocoding failed: " + e.getMessage());
                    }
                }

                if (!hasCoords) {
                    try {
                        JSONObject ipObj = get("https://ipwho.is/");
                        if (ipObj.optBoolean("success", false)) {
                            lat = ipObj.getDouble("latitude");
                            lon = ipObj.getDouble("longitude");
                            hasCoords = true;
                            String c = ipObj.optString("city", "");
                            String cc = ipObj.optString("country_code", "");
                            if (!c.isEmpty()) {
                                loc = !cc.isEmpty() ? c + ", " + cc : c;
                            }
                            Log.i(TAG, "IP auto-location (ipwho.is) -> " + lat + ", " + lon + " (" + loc + ")");
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "ipwho.is failed: " + e.getMessage());
                    }
                }

                if (!hasCoords) {
                    try {
                        JSONObject ipObj = get("https://freeipapi.com/api/json");
                        if (ipObj.has("latitude") && ipObj.has("longitude")) {
                            lat = ipObj.getDouble("latitude");
                            lon = ipObj.getDouble("longitude");
                            hasCoords = true;
                            String c = ipObj.optString("cityName", "");
                            String cc = ipObj.optString("countryCode", "");
                            if (!c.isEmpty()) {
                                loc = !cc.isEmpty() ? c + ", " + cc : c;
                            }
                            Log.i(TAG, "IP auto-location (freeipapi) -> " + lat + ", " + lon + " (" + loc + ")");
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "freeipapi failed: " + e.getMessage());
                    }
                }

                if (!hasCoords) {
                    Log.w(TAG, "Unable to determine location for weather.");
                    return;
                }

                String weatherUrl = "https://api.open-meteo.com/v1/forecast?latitude="
                        + lat + "&longitude=" + lon + "&current=temperature_2m,weather_code,is_day";
                JSONObject weatherObj = get(weatherUrl);
                JSONObject current = weatherObj.getJSONObject("current");

                double tempC = current.getDouble("temperature_2m");
                int code = current.getInt("weather_code");
                boolean day = current.optInt("is_day", 1) == 1;

                int finalTemp = "f".equalsIgnoreCase(units)
                        ? (int) Math.round(tempC * 9.0 / 5.0 + 32)
                        : (int) Math.round(tempC);

                int icon = getIcon(code, day);

                cachedTemp = finalTemp;
                cachedIcon = icon;
                cachedLocation = loc;
                lastFetchTime = System.currentTimeMillis();
                Log.i(TAG, "Weather fetched: " + finalTemp + "° (" + code + ", day=" + day + ") loc=" + loc);

                final String finalLoc = loc;
                MAIN_HANDLER.post(() -> callback.onWeather(finalTemp, icon, finalLoc));
            } catch (Exception e) {
                Log.w(TAG, "Weather fetch failed: " + e.getMessage(), e);
            }
        });
    }

    private static int getIcon(int code, boolean day) {
        switch (code) {
            case 0:
            case 1:
                return day ? R.drawable.anim_weather_sunny : R.drawable.anim_weather_clear_night;
            case 2:
                return R.drawable.anim_weather_partly_cloudy;
            case 3:
            case 45:
            case 48:
                return R.drawable.anim_weather_cloudy;
            case 51: case 53: case 55:
            case 56: case 57:
            case 61: case 63: case 65:
            case 66: case 67:
            case 80: case 81: case 82:
                return R.drawable.anim_weather_rain;
            case 71: case 73: case 75:
            case 77: case 85: case 86:
                return R.drawable.anim_weather_snow;
            default:
                return code >= 95 ? R.drawable.anim_weather_thunderstorm : R.drawable.anim_weather_cloudy;
        }
    }

    private static JSONObject get(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestProperty("User-Agent", "CleanYourTV/1.0 (Android TV)");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
        } finally {
            conn.disconnect();
        }
    }
}
