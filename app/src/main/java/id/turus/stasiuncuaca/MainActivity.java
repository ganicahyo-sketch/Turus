package id.turus.stasiuncuaca;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
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
    private static final long REFRESH_MS = 30_000L;
    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private android.content.SharedPreferences prefs;

    private TextView statusChip, lastAccess, deviceDate, deviceClock, dataTime, channelView, finalStatus;
    private TextView[] fieldValues = new TextView[8];
    private boolean requestRunning = false;
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
        findViewById(R.id.refresh).setOnClickListener(v -> loadThingSpeak(true));
        findViewById(R.id.downloadCsv).setOnClickListener(v -> startActivity(new Intent(this, CsvDownloadActivity.class)));
        updateClock();
        loadCachedData();
    }

    @Override protected void onResume() {
        super.onResume();
        updateHeaderFromConfig();
        loadCachedData();
        loadThingSpeak(false);
        main.removeCallbacks(clockTick);
        main.removeCallbacks(refreshTick);
        main.post(clockTick);
        main.postDelayed(refreshTick, REFRESH_MS);
    }

    @Override protected void onPause() {
        super.onPause();
        main.removeCallbacks(clockTick);
        main.removeCallbacks(refreshTick);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacksAndMessages(null);
        net.shutdownNow();
    }

    private void bindViews() {
        statusChip = findViewById(R.id.statusChip);
        lastAccess = findViewById(R.id.lastAccess);
        deviceDate = findViewById(R.id.deviceDate);
        deviceClock = findViewById(R.id.deviceClock);
        dataTime = findViewById(R.id.dataTime);
        channelView = findViewById(R.id.channelView);
        finalStatus = findViewById(R.id.finalStatus);
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
            label.setLetterSpacing(.12f);

            TextView value = new TextView(this);
            value.setText("--");
            value.setTextColor(Color.WHITE);
            value.setTextSize(24);
            value.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            value.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(-1, 0, 1f);
            value.setLayoutParams(vlp);

            TextView foot = new TextView(this);
            foot.setText("ThingSpeak");
            foot.setTextColor(Color.rgb(157,176,188));
            foot.setTextSize(10);

            card.addView(label);
            card.addView(value);
            card.addView(foot);
            grid.addView(card);
            fieldValues[i] = value;
        }
    }

    private void updateHeaderFromConfig() {
        String ch = prefs.getString("channel", DEFAULT_CHANNEL);
        channelView.setText(ch == null || ch.trim().isEmpty() ? "CHANNEL: --" : "CHANNEL: " + ch.trim());
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
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = r.readLine()) != null) body.append(line);
                r.close();
                JSONObject obj = new JSONObject(body.toString());
                lastAccessEpoch = System.currentTimeMillis();
                String[] vals = new String[8];
                for (int i = 0; i < 8; i++) vals[i] = obj.optString("field" + (i + 1), "");
                String createdAt = obj.optString("created_at", "");
                String thingStatus = obj.optString("status", "");
                prefs.edit()
                        .putString("cache_json", obj.toString())
                        .putLong("last_access", lastAccessEpoch)
                        .apply();
                runOnUiThread(() -> {
                    applyValues(vals);
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
                        applyValues(vals);
                        dataTime.setText("Data ThingSpeak: " + formatThingSpeakTime(finalCached.optString("created_at", "")));
                        lastAccess.setText(cacheAccess > 0 ? "Akses terakhir: " + formatLocal(cacheAccess) : "Akses terakhir: --");
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

    private void loadCachedData() {
        long last = prefs.getLong("last_access", 0L);
        String cached = prefs.getString("cache_json", "");
        if (last > 0) lastAccess.setText("Akses terakhir: " + formatLocal(last));
        if (cached.isEmpty()) return;
        try {
            JSONObject o = new JSONObject(cached);
            String[] vals = new String[8];
            for (int i = 0; i < 8; i++) vals[i] = o.optString("field" + (i + 1), "");
            applyValues(vals);
            dataTime.setText("Data ThingSpeak: " + formatThingSpeakTime(o.optString("created_at", "")));
            setUiStatus("CACHE", Color.rgb(243,182,74), "Menampilkan data terakhir sampai koneksi diperbarui.");
        } catch (Exception ignored) { }
    }

    private void applyValues(String[] vals) {
        for (int i = 0; i < 8; i++) fieldValues[i].setText(vals[i] == null || vals[i].isEmpty() ? "--" : vals[i]);
    }

    private void setUiStatus(String chip, int color, String msg) {
        statusChip.setText(chip);
        statusChip.setTextColor(Color.WHITE);
        statusChip.setBackgroundTintList(android.content.res.ColorStateList.valueOf(darken(color)));
        finalStatus.setText(msg);
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
