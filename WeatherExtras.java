package id.turus.stasiuncuaca;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Locale;

/** Weather/location helpers kept independent of the UI so GitHub Actions can compile the app cleanly. */
public final class WeatherExtras {
    private WeatherExtras() {}

    public static final class WeatherData {
        // OpenWeather observation time, Unix seconds.
        public long dt = 0L;
        public double temp = Double.NaN;
        public double humidity = Double.NaN;
        public double pressure = Double.NaN;
        public double windMs = Double.NaN;
        public double gustMs = Double.NaN;
        public double windDeg = Double.NaN;
        public double rain1h = Double.NaN;
        public double cloudPct = Double.NaN;
        public double visibilityKm = Double.NaN;
        public double tempMin = Double.NaN;
        public double tempMax = Double.NaN;
        public String description = "";
        public long sunrise = 0L;
        public long sunset = 0L;

        public String summary() {
            if (Double.isNaN(temp) && Double.isNaN(humidity) && Double.isNaN(windMs)) return "OpenWeather belum tersedia.";
            StringBuilder s = new StringBuilder();
            if (!Double.isNaN(temp)) s.append(String.format(Locale.US, "Suhu %.1f °C", temp));
            if (!Double.isNaN(humidity)) append(s, String.format(Locale.US, "kelembapan %.0f %%", humidity));
            if (!Double.isNaN(windMs)) append(s, String.format(Locale.US, "angin %.1f m/s", windMs));
            if (!Double.isNaN(cloudPct)) append(s, String.format(Locale.US, "awan %.0f %%", cloudPct));
            if (!description.isEmpty()) append(s, description);
            return s.toString();
        }

        private static void append(StringBuilder s, String text) {
            if (s.length() > 0) s.append(" • ");
            s.append(text);
        }
    }

    public static WeatherData fetchOpenWeather(String apiKey, double lat, double lon) throws Exception {
        if (apiKey == null || apiKey.trim().isEmpty()) throw new Exception("OpenWeather API key belum diisi.");
        String q = "https://api.openweathermap.org/data/2.5/weather?lat=" +
                URLEncoder.encode(String.format(Locale.US, "%.7f", lat), "UTF-8") +
                "&lon=" + URLEncoder.encode(String.format(Locale.US, "%.7f", lon), "UTF-8") +
                "&appid=" + URLEncoder.encode(apiKey.trim(), "UTF-8") +
                "&units=metric&lang=id";

        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(q).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setUseCaches(false);
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("OpenWeather HTTP " + code);
            JSONObject root = new JSONObject(readAll(c.getInputStream()));
            JSONObject main = root.optJSONObject("main");
            JSONObject wind = root.optJSONObject("wind");
            JSONObject rain = root.optJSONObject("rain");
            JSONObject clouds = root.optJSONObject("clouds");
            JSONObject sys = root.optJSONObject("sys");
            JSONObject weather0 = root.optJSONArray("weather") == null ? null : root.optJSONArray("weather").optJSONObject(0);

            WeatherData d = new WeatherData();
            d.dt = root.optLong("dt", 0L);
            if (main != null) {
                d.temp = main.optDouble("temp", Double.NaN);
                d.humidity = main.optDouble("humidity", Double.NaN);
                d.pressure = main.optDouble("pressure", Double.NaN);
                d.tempMin = main.optDouble("temp_min", Double.NaN);
                d.tempMax = main.optDouble("temp_max", Double.NaN);
            }
            if (wind != null) {
                d.windMs = wind.optDouble("speed", Double.NaN);
                d.gustMs = wind.optDouble("gust", Double.NaN);
                d.windDeg = wind.optDouble("deg", Double.NaN);
            }
            if (rain != null) d.rain1h = rain.optDouble("1h", Double.NaN);
            if (clouds != null) d.cloudPct = clouds.optDouble("all", Double.NaN);
            double visibilityM = root.optDouble("visibility", Double.NaN);
            if (!Double.isNaN(visibilityM)) d.visibilityKm = visibilityM / 1000.0;
            if (weather0 != null) d.description = weather0.optString("description", "").trim();
            if (sys != null) {
                d.sunrise = sys.optLong("sunrise", 0L);
                d.sunset = sys.optLong("sunset", 0L);
            }
            return d;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * Daily reference evapotranspiration estimate using the Hargreaves-Samani form.
     * Output is mm/day. This is an estimate when full local weather observations are unavailable.
     */
    public static double estimateEt0(double latitudeDeg, double tempC, double tempMinC, double tempMaxC, int dayOfYear) {
        if (Double.isNaN(tempC)) return Double.NaN;
        double tmin = Double.isNaN(tempMinC) ? tempC - 3.0 : tempMinC;
        double tmax = Double.isNaN(tempMaxC) ? tempC + 3.0 : tempMaxC;
        if (tmax < tmin) { double t=tmax; tmax=tmin; tmin=t; }
        double dtr = Math.max(0.1, tmax - tmin);

        double phi = Math.toRadians(Math.max(-89.9, Math.min(89.9, latitudeDeg)));
        double dr = 1.0 + 0.033 * Math.cos(2.0 * Math.PI / 365.0 * dayOfYear);
        double delta = 0.409 * Math.sin(2.0 * Math.PI / 365.0 * dayOfYear - 1.39);
        double wsArg = -Math.tan(phi) * Math.tan(delta);
        wsArg = Math.max(-1.0, Math.min(1.0, wsArg));
        double ws = Math.acos(wsArg);
        double ra = (24.0 * 60.0 / Math.PI) * 0.0820 * dr *
                (ws * Math.sin(phi) * Math.sin(delta) + Math.cos(phi) * Math.cos(delta) * Math.sin(ws));

        double et0 = 0.0023 * (tempC + 17.8) * Math.sqrt(dtr) * ra;
        return Math.max(0.0, et0);
    }

    public static String formatLocation(double lat, double lon, double altM) {
        if (Double.isNaN(lat) || Double.isNaN(lon)) return "GPS belum tersedia.";
        String s = String.format(Locale.US, "Lintang %.6f • Bujur %.6f", lat, lon);
        if (!Double.isNaN(altM)) s += String.format(Locale.US, " • Ketinggian %.0f m", altM);
        return s;
    }

    public static Location lastKnown(Context context) {
        try {
            LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
            Location best = null;
            for (String provider : lm.getProviders(true)) {
                Location loc = lm.getLastKnownLocation(provider);
                if (loc == null) continue;
                if (best == null || loc.getTime() > best.getTime()) best = loc;
            }
            return best;
        } catch (SecurityException ignored) {
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }
}
