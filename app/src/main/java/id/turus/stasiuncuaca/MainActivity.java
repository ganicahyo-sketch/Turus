package id.turus.stasiuncuaca;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.content.pm.PackageManager;
import android.graphics.pdf.PdfDocument;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.net.HttpURLConnection;
import android.os.ParcelFileDescriptor;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String PREFS = "thingspeak_config";
    private static final String DEFAULT_CHANNEL = "";
    private static final String DEFAULT_READ_KEY = "";
    private static final String DEFAULT_TITLE = "STASIUN CUACA";
    private static final String DEFAULT_AI_MODEL = "gpt-6-luna";
    private static final long REFRESH_MS = 30_000L;
    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");
    private static final int REQ_SAVE_AI_PDF = 2710;
    private static final int REQ_LOCATION = 4812;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private android.content.SharedPreferences prefs;

    private TextView titleView, statusChip, lastAccess, deviceDate, deviceClock, dataTime,
            channelView, finalStatus, aiAdvice, aiStatus, soilSummary, gpsSummary, openWeatherSummary, et0Summary, historySummary;
    private LocationManager locationManager;
    private boolean locationActive = false;
    private WeatherExtras.WeatherData lastOpenWeather;
    private TextView[] fieldLabels = new TextView[8];
    private TextView[] fieldValues = new TextView[8];
    private TextView[] fieldUnits = new TextView[8];
    private String[] latestValues = new String[8];
    private String latestCreatedAt = "";
    private boolean requestRunning = false;
    private boolean aiRunning = false;
    private long lastAccessEpoch = 0L;

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            updateClock();
            main.postDelayed(this, 1000L);
        }
    };

    private final Runnable refreshTick = new Runnable() {
        @Override public void run() {
            loadThingSpeak(false);
            main.postDelayed(this, REFRESH_MS);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        bindViews();
        buildFieldCards();
        findViewById(R.id.settings).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.infoApp).setOnClickListener(v -> showAppInfo());
        findViewById(R.id.refresh).setOnClickListener(v -> loadThingSpeak(true));
        findViewById(R.id.downloadCsv).setOnClickListener(v -> startActivity(new Intent(this, CsvDownloadActivity.class)));
        findViewById(R.id.aiButton).setOnClickListener(v -> requestAiAdvice());
        findViewById(R.id.historyButton).setOnClickListener(v -> requestHistoryAnalysis());
        findViewById(R.id.aiPdfButton).setOnClickListener(v -> requestPdfSave());
        findViewById(R.id.soilOpen).setOnClickListener(v -> startActivity(new Intent(this, SoilActivity.class)));
        findViewById(R.id.extrasRefresh).setOnClickListener(v -> refreshLocationAndWeather(true));
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        updateHeaderFromConfig();
        updateFieldLabels();
        updateSoilSummary();
        updateClock();
        loadCachedData();
        String cachedHistory = prefs.getString("history_analysis", "").trim();
        if (!cachedHistory.isEmpty()) historySummary.setText(cachedHistory);
        updateExtraSummaries();
        refreshLocationAndWeather(false);
    }

    @Override protected void onResume() {
        super.onResume();
        updateHeaderFromConfig();
        updateFieldLabels();
        updateSoilSummary();
        loadCachedData();
        String cachedHistory2 = prefs.getString("history_analysis", "").trim();
        if (!cachedHistory2.isEmpty()) historySummary.setText(cachedHistory2);
        loadThingSpeak(false);
        refreshLocationAndWeather(false);
        main.removeCallbacks(clockTick);
        main.removeCallbacks(refreshTick);
        main.post(clockTick);
        main.postDelayed(refreshTick, REFRESH_MS);
    }

    @Override protected void onPause() {
        super.onPause();
        main.removeCallbacks(clockTick);
        main.removeCallbacks(refreshTick);
        stopLocationUpdates();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        net.shutdownNow();
    }

    private void bindViews() {
        titleView = findViewById(R.id.title);
        statusChip = findViewById(R.id.statusChip);
        lastAccess = findViewById(R.id.lastAccess);
        deviceDate = findViewById(R.id.deviceDate);
        deviceClock = findViewById(R.id.deviceClock);
        dataTime = findViewById(R.id.dataTime);
        channelView = findViewById(R.id.channelView);
        finalStatus = findViewById(R.id.finalStatus);
        aiAdvice = findViewById(R.id.aiAdvice);
        aiStatus = findViewById(R.id.aiStatus);
        soilSummary = findViewById(R.id.soilSummary);
        gpsSummary = findViewById(R.id.gpsSummary);
        openWeatherSummary = findViewById(R.id.openWeatherSummary);
        et0Summary = findViewById(R.id.et0Summary);
        historySummary = findViewById(R.id.historySummary);
        findViewById(R.id.aiButton).setEnabled(true);
    }

    private void buildFieldCards() {
        GridLayout grid = findViewById(R.id.grid);
        for (int i = 0; i < 8; i++) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(14), dp(13), dp(14), dp(13));
            card.setBackgroundResource(R.drawable.bg_panel);

            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = dp(112);
            lp.columnSpec = GridLayout.spec(i % 2, 1f);
            lp.rowSpec = GridLayout.spec(i / 2);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            card.setLayoutParams(lp);

            TextView label = new TextView(this);
            label.setText("FIELD " + (i + 1));
            label.setTextColor(Color.rgb(41, 198, 199));
            label.setTextSize(10);
            label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            label.setLetterSpacing(.08f);
            fieldLabels[i] = label;

            TextView value = new TextView(this);
            value.setText("--");
            value.setTextColor(Color.WHITE);
            value.setTextSize(24);
            value.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            value.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-1, 0, 1f);
            value.setLayoutParams(vlp);

            TextView foot = new TextView(this);
            foot.setText("");
            foot.setTextColor(Color.rgb(157,176,188));
            foot.setTextSize(10);

            card.addView(label);
            card.addView(value);
            card.addView(foot);
            grid.addView(card);
            fieldValues[i] = value;
            fieldUnits[i] = foot;
        }
    }

    private void updateHeaderFromConfig() {
        String ch = prefs.getString("channel", DEFAULT_CHANNEL);
        channelView.setText(ch == null || ch.trim().isEmpty() ? "CHANNEL: --" : "CHANNEL: " + ch.trim());
        String title = prefs.getString("app_title", DEFAULT_TITLE).trim();
        titleView.setText(title.isEmpty() ? DEFAULT_TITLE : title);
    }

    private void updateFieldLabels() {
        for (int i = 0; i < 8; i++) {
            String name = getDisplayFieldName(i);
            String unit = prefs.getString("field_unit_" + (i + 1), "").trim();
            fieldLabels[i].setText(name.toUpperCase(new Locale("id", "ID")));
            fieldUnits[i].setText(unit);
        }
        applyValues(latestValues);
    }

    private String getDisplayFieldName(int i) {
        String custom = prefs.getString("field_name_" + (i + 1), "").trim();
        if (!custom.isEmpty()) return custom;
        String ts = prefs.getString("ts_field_name_" + (i + 1), "").trim();
        return ts.isEmpty() ? "FIELD " + (i + 1) : ts;
    }

    private void updateSoilSummary() {
        String mo = prefs.getString("soil_moisture", "");
        String st = prefs.getString("soil_temp", "");
        String ph = prefs.getString("soil_ph", "");
        String ec = getSoilEcUs();
        String n = prefs.getString("soil_n", "");
        String p = prefs.getString("soil_p", "");
        String k = prefs.getString("soil_k", "");
        if (mo.isEmpty() && st.isEmpty() && ph.isEmpty() && ec.isEmpty() && n.isEmpty() && p.isEmpty() && k.isEmpty()) {
            soilSummary.setText("Belum ada data. Masukkan data dari sensor 7-in-1 atau isi manual.");
            return;
        }
        soilSummary.setText("Kelembapan " + displayOrDash(mo) + " %  •  Suhu tanah " + displayOrDash(st) + " °C\n" +
                "pH " + displayOrDash(ph) + "  •  Kadar garam " + displayOrDash(ec) + " µS/cm\n" +
                "Nitrogen " + displayOrDash(n) + "  •  Fosfor " + displayOrDash(p) + "  •  Kalium " + displayOrDash(k) + " mg/kg");
    }

    private String displayOrDash(String s) { return s == null || s.trim().isEmpty() ? "--" : s; }

    private String getSoilEcUs() {
        String us = prefs.getString("soil_ec_us", "").trim();
        if (!us.isEmpty()) return us;
        String oldMs = prefs.getString("soil_ec_ms", "").trim();
        if (oldMs.isEmpty()) return "";
        try {
            double v = Double.parseDouble(oldMs.replace(',', '.')) * 1000.0;
            String converted = String.format(Locale.US, "%.0f", v);
            prefs.edit().putString("soil_ec_us", converted).apply();
            return converted;
        } catch (Exception ignored) {
            return "";
        }
    }

    private String getSoilEcUsOrMissing() {
        String v = getSoilEcUs();
        return v.isEmpty() ? "tidak ada" : v;
    }

    private void updateEt0FromThingSpeak() {
        if (!prefs.getBoolean("et0_enabled", true)) return;
        double lat = parseDouble(prefs.getString("gps_lat", ""));
        if (Double.isNaN(lat)) return;
        double temp = Double.NaN;
        for (int i = 0; i < 8; i++) {
            String name = getDisplayFieldName(i).toLowerCase(Locale.ROOT);
            if (name.contains("suhu") || name.contains("temper") || name.contains("temp")) {
                temp = parseDouble(i < latestValues.length ? latestValues[i] : "");
                if (!Double.isNaN(temp)) break;
            }
        }
        if (Double.isNaN(temp)) return;
        String dayKey = java.time.LocalDate.now(WIB).toString();
        String savedDay = prefs.getString("et0_day", "");
        double min = parseDouble(prefs.getString("et0_tmin", ""));
        double max = parseDouble(prefs.getString("et0_tmax", ""));
        if (!dayKey.equals(savedDay) || Double.isNaN(min) || Double.isNaN(max)) {
            min = temp;
            max = temp;
        } else {
            min = Math.min(min, temp);
            max = Math.max(max, temp);
        }
        prefs.edit().putString("et0_day", dayKey)
                .putString("et0_tmin", String.format(Locale.US, "%.1f", min))
                .putString("et0_tmax", String.format(Locale.US, "%.1f", max)).apply();
        double et0 = WeatherExtras.estimateEt0(lat, temp, min, max, java.time.LocalDate.now(WIB).getDayOfYear());
        if (!Double.isNaN(et0)) prefs.edit().putString("et0_mm_day", String.format(Locale.US, "%.2f", et0)).apply();
    }

    private void refreshLocationAndWeather(boolean manual) {
        if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean("gps_enabled", true)) {
            requestLocationPermissionAndStart();
        } else {
            updateExtraSummaries();
        }
        if (manual) main.postDelayed(this::fetchOpenWeatherIfReady, 1200L);
        else fetchOpenWeatherIfReady();
    }

    private void requestLocationPermissionAndStart() {
        if (locationActive && locationListener != null) { updateExtraSummaries(); return; }
        if (android.os.Build.VERSION.SDK_INT >= 23 &&
                checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
            return;
        }
        try {
            Location cached = WeatherExtras.lastKnown(this);
            if (cached != null) saveLocation(cached);
            LocationListener listener = new LocationListener() {
                @Override public void onLocationChanged(Location location) { saveLocation(location); }
                @Override public void onProviderEnabled(String provider) { }
                @Override public void onProviderDisabled(String provider) { }
                @Override public void onStatusChanged(String provider, int status, android.os.Bundle extras) { }
            };
            locationManager.removeUpdates(listener);
            for (String provider : locationManager.getProviders(true)) {
                try { locationManager.requestLocationUpdates(provider, 30000L, 10f, listener, Looper.getMainLooper()); }
                catch (Exception ignored) { }
            }
            // Keep the listener reference so it can be removed on pause.
            locationListener = listener;
            locationActive = true;
            updateExtraSummaries();
        } catch (SecurityException ignored) {
            gpsSummary.setText("Izin lokasi belum diberikan.");
        }
    }

    private LocationListener locationListener;

    private void stopLocationUpdates() {
        if (!locationActive || locationManager == null || locationListener == null) return;
        try { locationManager.removeUpdates(locationListener); } catch (Exception ignored) { }
        locationActive = false;
    }

    private void saveLocation(Location loc) {
        if (loc == null) return;
        android.content.SharedPreferences.Editor e = prefs.edit()
                .putString("gps_lat", String.format(Locale.US, "%.7f", loc.getLatitude()))
                .putString("gps_lon", String.format(Locale.US, "%.7f", loc.getLongitude()));
        if (loc.hasAltitude()) e.putString("gps_alt_m", String.format(Locale.US, "%.1f", loc.getAltitude()));
        e.apply();
        updateExtraSummaries();
        fetchOpenWeatherIfReady();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION) {
            boolean ok = false;
            for (int g : grantResults) if (g == PackageManager.PERMISSION_GRANTED) ok = true;
            if (ok) requestLocationPermissionAndStart();
            else gpsSummary.setText("Lokasi belum diizinkan. Aktifkan izin lokasi di Pengaturan HP.");
        }
    }

    private void fetchOpenWeatherIfReady() {
        if (!prefs.getBoolean("openweather_enabled", false)) {
            updateExtraSummaries();
            return;
        }
        String key = prefs.getString("openweather_api_key", "").trim();
        double lat = parseDouble(prefs.getString("gps_lat", ""));
        double lon = parseDouble(prefs.getString("gps_lon", ""));
        if (key.isEmpty() || Double.isNaN(lat) || Double.isNaN(lon)) {
            updateExtraSummaries();
            return;
        }
        long now = System.currentTimeMillis();
        long last = prefs.getLong("openweather_last_fetch", 0L);
        long minutes = Math.max(5, Math.min(120, prefs.getInt("openweather_interval", 30)));
        if (last > 0 && now - last < minutes * 60_000L) {
            updateExtraSummaries();
            return;
        }
        net.execute(() -> {
            try {
                WeatherExtras.WeatherData d = WeatherExtras.fetchOpenWeather(key, lat, lon);
                lastOpenWeather = d;
                android.content.SharedPreferences.Editor e = prefs.edit().putLong("openweather_last_fetch", System.currentTimeMillis())
                        .putString("ow_summary", d.summary());
                if (!Double.isNaN(d.temp)) e.putString("ow_temp", String.format(Locale.US, "%.1f", d.temp));
                if (!Double.isNaN(d.humidity)) e.putString("ow_humidity", String.format(Locale.US, "%.0f", d.humidity));
                if (!Double.isNaN(d.pressure)) e.putString("ow_pressure", String.format(Locale.US, "%.0f", d.pressure));
                if (!Double.isNaN(d.windMs)) e.putString("ow_wind", String.format(Locale.US, "%.1f", d.windMs));
                if (!Double.isNaN(d.gustMs)) e.putString("ow_gust", String.format(Locale.US, "%.1f", d.gustMs));
                if (!Double.isNaN(d.rain1h)) e.putString("ow_rain1h", String.format(Locale.US, "%.1f", d.rain1h));
                if (!Double.isNaN(d.cloudPct)) e.putString("ow_cloud", String.format(Locale.US, "%.0f", d.cloudPct));
                if (!Double.isNaN(d.visibilityKm)) e.putString("ow_visibility_km", String.format(Locale.US, "%.1f", d.visibilityKm));
                if (!Double.isNaN(d.tempMin)) e.putString("ow_temp_min", String.format(Locale.US, "%.1f", d.tempMin));
                if (!Double.isNaN(d.tempMax)) e.putString("ow_temp_max", String.format(Locale.US, "%.1f", d.tempMax));
                e.apply();
                computeEt0(d);
                HistoryAnalysis.saveOpenWeatherSnapshot(prefs, d);
                runOnUiThread(() -> {
                    updateExtraSummaries();
                    updateFieldLabels();
                });
            } catch (Exception ex) {
                runOnUiThread(() -> openWeatherSummary.setText("OpenWeather: tidak tersedia • " + safeMessage(ex)));
            }
        });
    }

    private void computeEt0(WeatherExtras.WeatherData d) {
        double lat = parseDouble(prefs.getString("gps_lat", ""));
        double t = d != null ? d.temp : Double.NaN;
        double tmin = d != null ? d.tempMin : Double.NaN;
        double tmax = d != null ? d.tempMax : Double.NaN;
        if (Double.isNaN(lat) || Double.isNaN(t)) return;
        int day = java.time.LocalDate.now(WIB).getDayOfYear();
        double et0 = WeatherExtras.estimateEt0(lat, t, tmin, tmax, day);
        if (!Double.isNaN(et0)) prefs.edit().putString("et0_mm_day", String.format(Locale.US, "%.2f", et0)).apply();
    }

    private void updateExtraSummaries() {
        double lat = parseDouble(prefs.getString("gps_lat", ""));
        double lon = parseDouble(prefs.getString("gps_lon", ""));
        double alt = parseDouble(prefs.getString("gps_alt_m", ""));
        if (gpsSummary != null) gpsSummary.setText(WeatherExtras.formatLocation(lat, lon, alt));
        String ow = prefs.getString("ow_summary", "").trim();
        if (openWeatherSummary != null) openWeatherSummary.setText(ow.isEmpty() ?
                "OpenWeather belum digunakan sebagai data pelengkap." : "OpenWeather: " + ow);
        String et0 = prefs.getString("et0_mm_day", "").trim();
        if (et0Summary != null) et0Summary.setText(et0.isEmpty() ?
                "ET₀ estimasi: belum cukup data." : "ET₀ estimasi: " + et0 + " mm/hari");
    }

    private double parseDouble(String s) {
        if (s == null || s.trim().isEmpty()) return Double.NaN;
        try { return Double.parseDouble(s.trim().replace(',', '.')); } catch (Exception e) { return Double.NaN; }
    }

    private void updateClock() {
        var now = java.time.ZonedDateTime.now(WIB);
        deviceDate.setText(capitalize(now.format(DateTimeFormatter.ofPattern("EEEE, dd MMMM yyyy", new Locale("id", "ID")))));
        deviceClock.setText(now.format(DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)) + " WIB");
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void loadThingSpeak(boolean manual) {
        if (requestRunning) return;
        String channel = prefs.getString("channel", DEFAULT_CHANNEL).trim();
        String key = prefs.getString("read_key", DEFAULT_READ_KEY).trim();
        if (channel.isEmpty()) {
            setUiStatus("KONFIGURASI", Color.rgb(243,182,74), "Masukkan Channel ID pada Pengaturan.");
            return;
        }
        if (manual) finalStatus.setText("Mengambil data dari ThingSpeak...");
        requestRunning = true;
        net.execute(() -> {
            HttpURLConnection c = null;
            try {
                StringBuilder url = new StringBuilder("https://api.thingspeak.com/channels/")
                        .append(URLEncoder.encode(channel, "UTF-8"))
                        .append("/feeds/last.json?timezone=Asia%2FJakarta&status=true");
                if (!key.isEmpty()) url.append("&api_key=").append(URLEncoder.encode(key, "UTF-8"));
                c = (HttpURLConnection) new URL(url.toString()).openConnection();
                c.setRequestMethod("GET");
                c.setConnectTimeout(7000);
                c.setReadTimeout(7000);
                c.setUseCaches(false);
                int code = c.getResponseCode();
                if (code != 200) throw new Exception("HTTP " + code);
                String body = readAll(c.getInputStream());
                JSONObject obj = new JSONObject(body);
                lastAccessEpoch = System.currentTimeMillis();
                String[] vals = new String[8];
                for (int i = 0; i < 8; i++) vals[i] = obj.optString("field" + (i + 1), "");
                String createdAt = obj.optString("created_at", "");
                String thingStatus = obj.optString("status", "");
                latestValues = vals;
                latestCreatedAt = createdAt;
                prefs.edit()
                        .putString("cache_json", obj.toString())
                        .putLong("last_access", lastAccessEpoch)
                        .apply();

                updateEt0FromThingSpeak();
                boolean needMetadata = !channel.equals(prefs.getString("field_meta_channel", ""));
                if (needMetadata) {
                    try { fetchAndCacheChannelMetadata(channel, key); } catch (Exception ignored) { }
                }

                runOnUiThread(() -> {
                    updateFieldLabels();
                    dataTime.setText("Data ThingSpeak: " + formatThingSpeakTime(createdAt));
                    lastAccess.setText("Akses terakhir: " + formatLocal(lastAccessEpoch));
                    setUiStatus("ONLINE • DATA TERSEDIA", Color.rgb(69,212,131), thingStatus.isEmpty() ? "Data ThingSpeak berhasil dibaca." : thingStatus);
                    requestRunning = false;
                });
            } catch (Exception ex) {
                long cacheAccess = prefs.getLong("last_access", 0L);
                JSONObject cached = null;
                try { String s = prefs.getString("cache_json", ""); if (!s.isEmpty()) cached = new JSONObject(s); } catch (Exception ignored) {}
                JSONObject finalCached = cached;
                runOnUiThread(() -> {
                    if (finalCached != null) {
                        String[] vals = new String[8];
                        for (int i = 0; i < 8; i++) vals[i] = finalCached.optString("field" + (i + 1), "");
                        latestValues = vals;
                        latestCreatedAt = finalCached.optString("created_at", "");
                        updateFieldLabels();
                        dataTime.setText("Data ThingSpeak: " + formatThingSpeakTime(latestCreatedAt));
                        lastAccess.setText(cacheAccess > 0 ? "Akses terakhir: " + formatLocal(cacheAccess) : "Akses terakhir: --");
                        updateEt0FromThingSpeak();
                        updateExtraSummaries();
                        setUiStatus("OFFLINE • CACHE", Color.rgb(243,182,74), "Koneksi gagal. Menampilkan data terakhir yang tersimpan.");
                    } else {
                        setUiStatus("OFFLINE", Color.rgb(255,107,107), "Gagal mengakses ThingSpeak: " + ex.getMessage());
                    }
                    requestRunning = false;
                });
            } finally {
                if (c != null) c.disconnect();
            }
        });
    }

    private void fetchAndCacheChannelMetadata(String channel, String key) throws Exception {
        StringBuilder url = new StringBuilder("https://api.thingspeak.com/channels/")
                .append(URLEncoder.encode(channel, "UTF-8"))
                .append(".json");
        if (!key.isEmpty()) url.append("?api_key=").append(URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url.toString()).openConnection();
            c.setRequestMethod("GET");
            c.setConnectTimeout(7000);
            c.setReadTimeout(7000);
            c.setUseCaches(false);
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            JSONObject channelObj = new JSONObject(readAll(c.getInputStream()));
            android.content.SharedPreferences.Editor e = prefs.edit();
            for (int i = 0; i < 8; i++) {
                String n = channelObj.optString("field" + (i + 1), "").trim();
                e.putString("ts_field_name_" + (i + 1), n);
            }
            e.putString("field_meta_channel", channel);
            e.apply();
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void loadCachedData() {
        long last = prefs.getLong("last_access", 0L);
        String cached = prefs.getString("cache_json", "");
        if (last > 0) lastAccess.setText("Akses terakhir: " + formatLocal(last));
        if (cached.isEmpty()) return;
        try {
            JSONObject o = new JSONObject(cached);
            String[] vals = new String[8];
            for (int i = 0; i < 8; i++) vals[i] = o.optString("field" + (i + 1), "");
            latestValues = vals;
            latestCreatedAt = o.optString("created_at", "");
            updateFieldLabels();
            dataTime.setText("Data ThingSpeak: " + formatThingSpeakTime(latestCreatedAt));
            setUiStatus("CACHE", Color.rgb(243,182,74), "Menampilkan data terakhir sampai koneksi diperbarui.");
        } catch (Exception ignored) { }
    }

    private void applyValues(String[] vals) {
        if (vals == null) return;
        int decimals = getDecimals();
        boolean fallbackEnabled = prefs.getBoolean("openweather_fallback", true);
        for (int i = 0; i < 8; i++) {
            String raw = i < vals.length ? vals[i] : "";
            String sourceNote = "";
            if ((raw == null || raw.trim().isEmpty()) && fallbackEnabled) {
                String fallback = getOpenWeatherFallbackValue(getDisplayFieldName(i));
                if (!fallback.isEmpty()) {
                    raw = fallback;
                    sourceNote = " • OpenWeather";
                }
            }
            fieldValues[i].setText(formatDisplayValue(raw, decimals));
            String unit = prefs.getString("field_unit_" + (i + 1), "").trim();
            fieldUnits[i].setText(unit + sourceNote);
        }
    }

    private String getOpenWeatherFallbackValue(String fieldName) {
        String n = fieldName == null ? "" : fieldName.toLowerCase(Locale.ROOT);
        if (n.contains("suhu") || n.contains("temperature") || n.contains("temp"))
            return prefs.getString("ow_temp", "").trim();
        if (n.contains("kelembapan") || n.contains("humidity") || n.equals("rh") || n.contains("rh "))
            return prefs.getString("ow_humidity", "").trim();
        if (n.contains("tekanan") || n.contains("pressure"))
            return prefs.getString("ow_pressure", "").trim();
        if (n.contains("angin") || n.contains("wind"))
            return prefs.getString("ow_wind", "").trim();
        if (n.contains("hujan") || n.contains("rain") || n.contains("curah"))
            return prefs.getString("ow_rain1h", "").trim();
        if (n.contains("awan") || n.contains("cloud"))
            return prefs.getString("ow_cloud", "").trim();
        if (n.contains("jarak pandang") || n.contains("visibility") || n.contains("visibil"))
            return prefs.getString("ow_visibility_km", "").trim();
        return "";
    }

    private String formatDisplayValue(String raw, int decimals) {
        if (raw == null || raw.trim().isEmpty()) return "--";
        String s = raw.trim();
        try {
            double d = Double.parseDouble(s);
            NumberFormat nf = NumberFormat.getNumberInstance(new Locale("id", "ID"));
            nf.setGroupingUsed(false);
            nf.setMinimumFractionDigits(decimals);
            nf.setMaximumFractionDigits(decimals);
            return nf.format(d);
        } catch (Exception ignored) {
            return s;
        }
    }

    private int getDecimals() {
        int d = prefs.getInt("display_decimals", 2);
        return Math.max(0, Math.min(2, d));
    }

    private void setUiStatus(String chip, int color, String msg) {
        statusChip.setText(chip);
        statusChip.setTextColor(Color.WHITE);
        statusChip.setBackgroundTintList(android.content.res.ColorStateList.valueOf(darken(color)));
        finalStatus.setText(msg);
    }

    private void showAppInfo() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("INFO APLIKASI")
                .setMessage("STASIUN CUACA\n\nVersi aplikasi: 1.0\nPembuat: Gani Cahyo H\nAgroteknologi-Universitas Sebelas Maret\n\nCreated with OpenAI @2026\n\nAplikasi untuk membantu pemantauan cuaca, tanah, kebutuhan air, dan pengambilan keputusan pertanian.")
                .setPositiveButton("TUTUP", null)
                .show();
    }

    private void requestHistoryAnalysis() {
        String channel = prefs.getString("channel", "").trim();
        String key = prefs.getString("read_key", "").trim();
        historySummary.setText("Sedang membaca riwayat ThingSpeak dan OpenWeather...");
        final int days = Math.max(1, Math.min(7, prefs.getInt("analysis_days", 3)));
        net.execute(() -> {
            try {
                HistoryAnalysis.Result result = HistoryAnalysis.analyze(prefs, channel, key);
                prefs.edit().putString("history_analysis", result.summary).putLong("history_analysis_time", System.currentTimeMillis()).apply();
                runOnUiThread(() -> historySummary.setText(result.summary));
            } catch (Exception ex) {
                runOnUiThread(() -> historySummary.setText("Analisis riwayat gagal: " + safeMessage(ex)));
            }
        });
    }

    private void requestAiAdvice() {
        if (aiRunning) return;
        String apiKey = prefs.getString("ai_api_key", "").trim();
        if (apiKey.isEmpty()) {
            aiAdvice.setText("Masukkan API key AI pada Pengaturan terlebih dahulu.");
            aiStatus.setText("AI belum dikonfigurasi");
            return;
        }
        String channel = prefs.getString("channel", "").trim();
        if (channel.isEmpty()) {
            aiAdvice.setText("Atur Channel ID terlebih dahulu agar data cuaca dapat dibaca.");
            return;
        }
        aiRunning = true;
        aiStatus.setText("AI sedang membaca data pengukuran...");
        findViewById(R.id.aiButton).setEnabled(false);
        final String[] snapshot = latestValues.clone();
        final String createdAt = latestCreatedAt;
        final String title = prefs.getString("app_title", DEFAULT_TITLE);
        final String crop = prefs.getString("crop", "Tanaman pertanian").trim();
        final String plantAge = getPlantAgeText();
        final String model = prefs.getString("ai_model", DEFAULT_AI_MODEL).trim().isEmpty()
                ? DEFAULT_AI_MODEL : prefs.getString("ai_model", DEFAULT_AI_MODEL).trim();

        net.execute(() -> {
            try {
                String history = prefs.getString("history_analysis", "").trim();
                long historyTime = prefs.getLong("history_analysis_time", 0L);
                if (history.isEmpty() || System.currentTimeMillis() - historyTime > 10 * 60_000L) {
                    HistoryAnalysis.Result hr = HistoryAnalysis.analyze(prefs, channel, prefs.getString("read_key", "").trim());
                    history = hr.summary;
                    prefs.edit().putString("history_analysis", history).putLong("history_analysis_time", System.currentTimeMillis()).apply();
                    final String cachedHistory = history;
                    runOnUiThread(() -> historySummary.setText(cachedHistory));
                }
                final String historyContext = history;
                String advice = callOpenAI(apiKey, model, title, channel, crop, plantAge, createdAt, snapshot, historyContext);
                runOnUiThread(() -> {
                    aiAdvice.setText(advice);
                    aiStatus.setText("Saran dibuat dari data terakhir • " + formatThingSpeakTime(createdAt));
                    aiRunning = false;
                    findViewById(R.id.aiButton).setEnabled(true);
                });
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    aiAdvice.setText("Saran AI gagal dibuat. Periksa koneksi internet, API key, dan nama model AI pada Pengaturan.\n\nPesan: " + safeMessage(ex));
                    aiStatus.setText("AI tidak tersedia");
                    aiRunning = false;
                    findViewById(R.id.aiButton).setEnabled(true);
                });
            }
        });
    }

    private String callOpenAI(String apiKey, String model, String title, String channel,
                              String crop, String plantAge, String createdAt, String[] vals, String historyContext) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("model", model);
        payload.put("instructions",
                "Anda adalah asisten agronomi untuk membantu petani mengambil tindakan lapang. " +
                "Gunakan bahasa Indonesia yang sangat mudah dipahami. Hindari singkatan dan istilah teknis yang tidak perlu. " +
                "Jangan mengarang data yang tidak diberikan. Jika suatu kesimpulan membutuhkan data yang tidak tersedia, katakan dengan jelas. " +
                "Berikan saran praktis berdasarkan data cuaca yang tersedia, bukan diagnosis penyakit yang pasti. " +
                "Prioritaskan tindakan yang bisa dilakukan petani sekarang, kemudian 6-24 jam ke depan, dan apa yang perlu dipantau. " +
                "Bila data belum cukup, minta pengecekan lapangan secara singkat. " +
                "Jawaban maksimal sekitar 8 poin pendek, tanpa pembukaan panjang. " +
                "Bila menggunakan istilah teknis yang penting, langsung jelaskan dengan kata sederhana. " +
                "Analisis juga kemungkinan gangguan organisme pengganggu tanaman (OPT) berdasarkan pola cuaca, kelembapan, hujan, angin, dan arah angin. Ini hanya penilaian risiko, bukan diagnosis penyakit. Bila arah angin dominan diketahui, sebutkan sisi lahan yang perlu diperiksa terlebih dahulu. " +
                "Bila tidak ada data tanah sama sekali, KOSONGKAN bagian pemupukan: jangan memberi rekomendasi pupuk dan jangan menebak kebutuhan pupuk. Jika sebagian data tanah ada, hanya gunakan data yang tersedia dan sebutkan keterbatasannya.");

        StringBuilder input = new StringBuilder();
        input.append("Konteks stasiun: ").append(title).append("\n");
        input.append("Channel ThingSpeak: ").append(channel).append("\n");
        input.append("Nama tanaman: ").append(crop.isEmpty() ? "Tanaman pertanian" : crop).append("\n");
        input.append("Umur tanaman: ").append(plantAge.isEmpty() ? "tidak diisi" : plantAge).append("\n");
        input.append("Waktu data: ").append(formatThingSpeakTime(createdAt)).append("\n");
        input.append("Data terbaru:\n");
        for (int i = 0; i < 8; i++) {
            String value = vals[i];
            boolean fromFallback = false;
            if ((value == null || value.trim().isEmpty()) && prefs.getBoolean("openweather_fallback", true)) {
                String fallback = getOpenWeatherFallbackValue(getDisplayFieldName(i));
                if (!fallback.isEmpty()) { value = fallback; fromFallback = true; }
            }
            input.append(i + 1).append(". ").append(getDisplayFieldName(i)).append(" = ")
                    .append(formatDisplayValue(value, getDecimals()));
            String unit = prefs.getString("field_unit_" + (i + 1), "").trim();
            if (!unit.isEmpty()) input.append(" ").append(unit);
            if (fromFallback) input.append(" (sumber OpenWeather)");
            input.append("\n");
        }
        input.append("\nRIWAYAT CUACA YANG DIANALISIS:\n").append(historyContext == null || historyContext.trim().isEmpty() ? "Belum tersedia." : historyContext).append("\n");
        boolean hasSoil = hasAnySoilData();
        if (hasSoil) {
            input.append("\nData tanah terbaru:\n");
            input.append("Kelembapan tanah = ").append(prefs.getString("soil_moisture", "tidak ada")).append(" %\n");
            input.append("Suhu tanah = ").append(prefs.getString("soil_temp", "tidak ada")).append(" °C\n");
            input.append("pH tanah = ").append(prefs.getString("soil_ph", "tidak ada")).append("\n");
            input.append("Kadar garam tanah = ").append(getSoilEcUsOrMissing()).append(" µS/cm\n");
            input.append("Nitrogen = ").append(prefs.getString("soil_n", "tidak ada")).append(" mg/kg\n");
            input.append("Fosfor = ").append(prefs.getString("soil_p", "tidak ada")).append(" mg/kg\n");
            input.append("Kalium = ").append(prefs.getString("soil_k", "tidak ada")).append(" mg/kg\n");
        } else {
            input.append("\nDATA TANAH: TIDAK TERSEDIA. Jangan memberi rekomendasi pemupukan.\n");
        }
        input.append("Lokasi GPS = ").append(WeatherExtras.formatLocation(parseDouble(prefs.getString("gps_lat", "")), parseDouble(prefs.getString("gps_lon", "")), parseDouble(prefs.getString("gps_alt_m", "")))).append("\n");
        input.append("ET₀ estimasi = ").append(prefs.getString("et0_mm_day", "tidak ada")).append(" mm/hari\n");
        input.append("Data pelengkap OpenWeather = ").append(prefs.getString("ow_summary", "tidak ada")).append("\n");
        input.append("\nBuat rekomendasi tindakan petani berdasarkan data terbaru DAN riwayat di atas. Gunakan data yang paling kuat dan jelas sumbernya. Jelaskan dengan bahasa petani yang sederhana.");
        payload.put("input", input.toString());
        payload.put("max_output_tokens", 700);

        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("https://api.openai.com/v1/responses").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(12000);
            c.setReadTimeout(30000);
            c.setDoOutput(true);
            c.setUseCaches(false);
            c.setRequestProperty("Authorization", "Bearer " + apiKey);
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = c.getOutputStream()) { os.write(body); }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                String err = readAll(c.getErrorStream());
                throw new Exception("HTTP " + code + (err.isEmpty() ? "" : " — " + extractApiError(err)));
            }
            JSONObject response = new JSONObject(readAll(c.getInputStream()));
            String direct = response.optString("output_text", "").trim();
            if (!direct.isEmpty()) return direct;
            JSONArray output = response.optJSONArray("output");
            if (output != null) {
                StringBuilder result = new StringBuilder();
                for (int i = 0; i < output.length(); i++) {
                    JSONObject item = output.optJSONObject(i);
                    if (item == null) continue;
                    JSONArray content = item.optJSONArray("content");
                    if (content == null) continue;
                    for (int j = 0; j < content.length(); j++) {
                        JSONObject part = content.optJSONObject(j);
                        if (part == null) continue;
                        String type = part.optString("type", "");
                        if ("output_text".equals(type)) {
                            String t = part.optString("text", "").trim();
                            if (!t.isEmpty()) {
                                if (result.length() > 0) result.append("\n");
                                result.append(t);
                            }
                        }
                    }
                }
                if (result.length() > 0) return result.toString();
            }
            throw new Exception("Respons AI tidak berisi teks saran.");
        } finally {
            if (c != null) c.disconnect();
        }
    }


    private boolean hasAnySoilData() {
        return !prefs.getString("soil_moisture", "").trim().isEmpty()
                || !prefs.getString("soil_temp", "").trim().isEmpty()
                || !prefs.getString("soil_ph", "").trim().isEmpty()
                || !getSoilEcUsOrMissing().trim().isEmpty()
                || !prefs.getString("soil_n", "").trim().isEmpty()
                || !prefs.getString("soil_p", "").trim().isEmpty()
                || !prefs.getString("soil_k", "").trim().isEmpty();
    }

    private String getPlantAgeText() {
        String value = prefs.getString("crop_age_value", "").trim();
        String unit = prefs.getString("crop_age_unit", "hari").trim();
        if (value.isEmpty()) return "";
        if ("hari".equals(unit)) return value + " hari";
        if ("bulan".equals(unit)) return value + " bulan";
        if ("tahun".equals(unit)) return value + " tahun";
        return value + " " + unit;
    }

    private void requestPdfSave() {
        String advice = aiAdvice.getText().toString().trim();
        if (advice.isEmpty() || advice.startsWith("Belum ada saran.") || advice.startsWith("Masukkan API key")) {
            Toast.makeText(this, "Buat Saran AI terlebih dahulu.", Toast.LENGTH_SHORT).show();
            return;
        }
        String baseTitle = prefs.getString("app_title", DEFAULT_TITLE).trim();
        if (baseTitle.isEmpty()) baseTitle = DEFAULT_TITLE;
        String stamp = java.time.LocalDateTime.now(WIB).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.US));
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/pdf");
        intent.putExtra(Intent.EXTRA_TITLE, sanitizeFileName(baseTitle) + "_Saran-AI_" + stamp + ".pdf");
        startActivityForResult(intent, REQ_SAVE_AI_PDF);
    }

    private String sanitizeFileName(String value) {
        return value.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_SAVE_AI_PDF || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final android.net.Uri uri = data.getData();
        final String advice = aiAdvice.getText().toString();
        final String title = prefs.getString("app_title", DEFAULT_TITLE);
        final String plant = prefs.getString("crop", "Tanaman pertanian");
        final String plantAge = getPlantAgeText();
        final String createdAt = latestCreatedAt;
        final String[] snapshot = latestValues.clone();
        net.execute(() -> {
            try {
                writeAiPdf(uri, title, plant, plantAge, createdAt, snapshot, advice);
                runOnUiThread(() -> Toast.makeText(this, "PDF berhasil disimpan.", Toast.LENGTH_LONG).show());
            } catch (Exception ex) {
                runOnUiThread(() -> Toast.makeText(this, "Gagal menyimpan PDF: " + safeMessage(ex), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void writeAiPdf(android.net.Uri uri, String title, String plant, String plantAge,
                            String createdAt, String[] vals, String advice) throws Exception {
        PdfDocument doc = new PdfDocument();
        PdfDocument.Page page = null;
        Canvas canvas = null;
        Paint normal = new Paint(Paint.ANTI_ALIAS_FLAG);
        normal.setColor(Color.BLACK);
        normal.setTextSize(10.5f);
        Paint small = new Paint(Paint.ANTI_ALIAS_FLAG);
        small.setColor(Color.DKGRAY);
        small.setTextSize(8.5f);
        Paint heading = new Paint(Paint.ANTI_ALIAS_FLAG);
        heading.setColor(Color.BLACK);
        heading.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        heading.setTextSize(14f);
        Paint sub = new Paint(Paint.ANTI_ALIAS_FLAG);
        sub.setColor(Color.DKGRAY);
        sub.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        sub.setTextSize(10.5f);

        final int width = 595;
        final int height = 842;
        final float left = 38f;
        final float right = 38f;
        final float top = 42f;
        final float bottom = 42f;
        final float lineH = 15f;
        final float maxW = width - left - right;
        int pageNo = 0;
        float y = top;

        for (String section : buildPdfSections(title, plant, plantAge, createdAt, vals, advice)) {
            boolean isHeader = section.startsWith("@@H@@ ");
            boolean isNote = section.startsWith("@@S@@ ");
            String text = (isHeader || isNote) ? section.substring(7) : section;
            Paint paint = isHeader ? sub : normal;
            float extraBefore = isHeader ? 1f : 0f;
            float extraAfter = isHeader ? 5f : (isNote ? 3f : 1f);
            java.util.List<String> wrapped = wrapToLines(text, maxW, paint);

            for (String line : wrapped) {
                if (page == null || y > height - bottom - lineH) {
                    if (page != null) {
                        drawFooter(page.getCanvas(), small, pageNo, width, height);
                        doc.finishPage(page);
                    }
                    pageNo++;
                    PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(width, height, pageNo).create();
                    page = doc.startPage(info);
                    canvas = page.getCanvas();
                    y = top;
                    canvas.drawText(safeTitle(title), left, y, heading);
                    y += 22f;
                }
                if (extraBefore > 0) y += extraBefore;
                canvas.drawText(line, left, y, paint);
                y += lineH + (extraAfter > 0 ? (extraAfter / (wrapped.size() > 1 ? 3f : 1f)) : 0f);
            }
            if (isHeader) y += 3f;
        }
        if (page != null) {
            drawFooter(page.getCanvas(), small, pageNo, width, height);
            doc.finishPage(page);
        }
        try (ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "w")) {
            if (pfd == null) throw new Exception("Lokasi penyimpanan tidak dapat dibuka.");
            try (FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor())) {
                doc.writeTo(out);
                out.flush();
            }
        } finally {
            doc.close();
        }
    }

    private String[] buildPdfSections(String title, String plant, String plantAge, String createdAt,
                                      String[] vals, String advice) {
        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        lines.add("@@H@@ INFORMASI TANAMAN");
        lines.add("Nama tanaman: " + (plant == null || plant.trim().isEmpty() ? "Tanaman pertanian" : plant.trim()));
        lines.add("Umur tanaman: " + (plantAge == null || plantAge.isEmpty() ? "Belum diisi" : plantAge));
        lines.add("@@H@@ WAKTU DATA");
        lines.add("Data ThingSpeak: " + formatThingSpeakTime(createdAt));
        lines.add("@@H@@ DATA CUACA TERBARU");
        for (int i = 0; i < 8; i++) {
            String name = getDisplayFieldName(i);
            String raw = vals[i];
            boolean fromFallback = false;
            if ((raw == null || raw.trim().isEmpty()) && prefs.getBoolean("openweather_fallback", true)) {
                String fallback = getOpenWeatherFallbackValue(name);
                if (!fallback.isEmpty()) { raw = fallback; fromFallback = true; }
            }
            String value = formatDisplayValue(raw, getDecimals());
            String unit = prefs.getString("field_unit_" + (i + 1), "").trim();
            lines.add(name + ": " + value + (unit.isEmpty() ? "" : " " + unit) + (fromFallback ? " (OpenWeather)" : ""));
        }
        lines.add("@@H@@ DATA TANAH");
        lines.add("Kelembapan tanah: " + prefs.getString("soil_moisture", "tidak ada") + " %");
        lines.add("Suhu tanah: " + prefs.getString("soil_temp", "tidak ada") + " °C");
        lines.add("pH tanah: " + prefs.getString("soil_ph", "tidak ada"));
        lines.add("Kadar garam tanah: " + getSoilEcUsOrMissing() + " µS/cm");
        lines.add("Nitrogen: " + prefs.getString("soil_n", "tidak ada") + " mg/kg");
        lines.add("Fosfor: " + prefs.getString("soil_p", "tidak ada") + " mg/kg");
        lines.add("Kalium: " + prefs.getString("soil_k", "tidak ada") + " mg/kg");
        lines.add("@@H@@ LOKASI & DATA PELENGKAP");
        lines.add(WeatherExtras.formatLocation(parseDouble(prefs.getString("gps_lat", "")), parseDouble(prefs.getString("gps_lon", "")), parseDouble(prefs.getString("gps_alt_m", ""))));
        lines.add("ET₀ estimasi: " + prefs.getString("et0_mm_day", "tidak ada") + " mm/hari");
        lines.add("OpenWeather: " + prefs.getString("ow_summary", "tidak ada"));
        lines.add("@@H@@ ANALISIS RIWAYAT CUACA");
        String hs = prefs.getString("history_analysis", "").trim();
        if (!hs.isEmpty()) {
            for (String part : hs.replace("\r", "").split("\n")) {
                String t = part.trim(); if (!t.isEmpty()) lines.add(t);
            }
        }
        if (!hasAnySoilData()) lines.add("Pemupukan: tidak dianalisis karena data tanah tidak tersedia.");
        lines.add("@@H@@ HASIL ANALISIS DAN REKOMENDASI AI");
        if (advice != null && !advice.trim().isEmpty()) {
            for (String part : advice.replace("\r", "").split("\n")) {
                String t = part.trim();
                if (!t.isEmpty()) lines.add(t);
            }
        }
        lines.add("@@S@@ Catatan: Saran AI adalah bantuan pengambilan keputusan berdasarkan data yang tersedia. Periksa kondisi tanaman dan tanah secara langsung sebelum melakukan tindakan penting, terutama pemupukan.");
        return lines.toArray(new String[0]);
    }

    private String safeTitle(String value) {
        String s = value == null ? DEFAULT_TITLE : value.trim();
        return s.isEmpty() ? DEFAULT_TITLE : s;
    }

    private java.util.List<String> wrapToLines(String text, float maxWidth, Paint paint) {
        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        if (text == null || text.trim().isEmpty()) return lines;
        String[] words = text.trim().split("\\s+");
        StringBuilder line = new StringBuilder();
        for (String word : words) {
            if (line.length() == 0) {
                line.append(word);
                continue;
            }
            String candidate = line + " " + word;
            if (paint.measureText(candidate) <= maxWidth) {
                line.setLength(0);
                line.append(candidate);
            } else {
                lines.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }

    private void drawFooter(Canvas c, Paint paint, int pageNo, int width, int height) {
        c.drawText("STASIUN CUACA • Laporan Saran AI", 38, height - 24, paint);
        String p = "Halaman " + pageNo;
        c.drawText(p, width - 90, height - 24, paint);
    }

    private String extractApiError(String json) {
        try {
            JSONObject o = new JSONObject(json);
            JSONObject e = o.optJSONObject("error");
            if (e != null) return e.optString("message", json);
        } catch (Exception ignored) { }
        return json.length() > 300 ? json.substring(0, 300) : json;
    }

    private String safeMessage(Throwable ex) {
        String m = ex == null ? "Kesalahan tidak diketahui" : ex.getMessage();
        return m == null || m.isEmpty() ? "Kesalahan tidak diketahui" : m;
    }

    private String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }

    private int darken(int c) {
        float factor = .72f;
        return Color.rgb((int)(Color.red(c)*factor), (int)(Color.green(c)*factor), (int)(Color.blue(c)*factor));
    }

    private String formatThingSpeakTime(String utc) {
        if (utc == null || utc.isEmpty()) return "--";
        try {
            return Instant.parse(utc).atZone(WIB).format(DateTimeFormatter.ofPattern("EEEE, dd MMM yyyy HH:mm:ss", new Locale("id", "ID"))) + " WIB";
        } catch (Exception e) { return utc; }
    }

    private String formatLocal(long millis) {
        if (millis <= 0) return "--";
        return Instant.ofEpochMilli(millis).atZone(WIB).format(DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss", new Locale("id", "ID"))) + " WIB";
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
