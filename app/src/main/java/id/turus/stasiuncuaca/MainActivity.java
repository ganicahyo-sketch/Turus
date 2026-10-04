package id.turus.stasiuncuaca;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
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
import java.net.HttpURLConnection;
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

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private android.content.SharedPreferences prefs;

    private TextView titleView, statusChip, lastAccess, deviceDate, deviceClock, dataTime,
            channelView, finalStatus, aiAdvice, aiStatus;
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
        findViewById(R.id.refresh).setOnClickListener(v -> loadThingSpeak(true));
        findViewById(R.id.downloadCsv).setOnClickListener(v -> startActivity(new Intent(this, CsvDownloadActivity.class)));
        findViewById(R.id.agronomyButton).setOnClickListener(v -> startActivity(new Intent(this, AgronomyActivity.class)));
        findViewById(R.id.aiButton).setOnClickListener(v -> requestAiAdvice());
        updateHeaderFromConfig();
        updateFieldLabels();
        updateClock();
        loadCachedData();
    }

    @Override protected void onResume() {
        super.onResume();
        updateHeaderFromConfig();
        updateFieldLabels();
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

                long metaAge = System.currentTimeMillis() - prefs.getLong("field_meta_epoch", 0L);
                boolean needMetadata = !channel.equals(prefs.getString("field_meta_channel", "")) || metaAge > 6L * 60L * 60L * 1000L;
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
            e.putLong("field_meta_epoch", System.currentTimeMillis());
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
        for (int i = 0; i < 8; i++) {
            String v = i < vals.length ? vals[i] : "";
            fieldValues[i].setText(formatDisplayValue(v, decimals));
        }
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
        final String model = prefs.getString("ai_model", DEFAULT_AI_MODEL).trim().isEmpty()
                ? DEFAULT_AI_MODEL : prefs.getString("ai_model", DEFAULT_AI_MODEL).trim();

        net.execute(() -> {
            try {
                String advice = callOpenAI(apiKey, model, title, channel, crop, createdAt, snapshot);
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
                              String crop, String createdAt, String[] vals) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("model", model);
        payload.put("store", false);
        payload.put("instructions",
                "Anda adalah asisten agronomi untuk membantu petani mengambil tindakan lapang. " +
                "Gunakan bahasa Indonesia yang sangat mudah dipahami. Hindari singkatan dan istilah teknis yang tidak perlu. " +
                "Jangan mengarang data yang tidak diberikan. Jika suatu kesimpulan membutuhkan data yang tidak tersedia, katakan dengan jelas. " +
                "Berikan saran praktis berdasarkan data cuaca yang tersedia, bukan diagnosis penyakit yang pasti. " +
                "Prioritaskan tindakan yang bisa dilakukan petani sekarang, kemudian 6-24 jam ke depan, dan apa yang perlu dipantau. " +
                "Bila data belum cukup, minta pengecekan lapangan secara singkat. " +
                "Jawaban maksimal sekitar 8 poin pendek, tanpa pembukaan panjang. " +
                "Bila menggunakan istilah teknis yang penting, langsung jelaskan dengan kata sederhana. " +
                "Jika data lahan, riwayat pupuk, atau riwayat OPT tersedia, gabungkan data tersebut ke dalam saran dan jangan mengabaikannya.");

        StringBuilder input = new StringBuilder();
        input.append("Konteks stasiun: ").append(title).append("\n");
        input.append("Channel ThingSpeak: ").append(channel).append("\n");
        input.append("Komoditas/tanaman: ").append(crop.isEmpty() ? "Tanaman pertanian" : crop).append("\n");
        input.append("Waktu data: ").append(formatThingSpeakTime(createdAt)).append("\n");
        input.append("Data terbaru:\n");
        for (int i = 0; i < 8; i++) {
            input.append(i + 1).append(". ").append(getDisplayFieldName(i)).append(" = ")
                    .append(formatDisplayValue(vals[i], getDecimals()));
            String unit = prefs.getString("field_unit_" + (i + 1), "").trim();
            if (!unit.isEmpty()) input.append(" ").append(unit);
            input.append("\n");
        }
        input.append("\nDATA LAHAN & RIWAYAT AGRONOMI (bila tersedia):\n");
        input.append(agronomyContextForMain());
        input.append("\nBuat rekomendasi tindakan petani berdasarkan seluruh data di atas. Jangan mengganti atau mengarang angka.");
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

    private String agronomyContextForMain() {
        StringBuilder s = new StringBuilder();
        s.append("Tanaman: ").append(prefs.getString("crop", "--")).append("\n");
        s.append("Umur bulan: ").append(prefs.getString("farm_age_months", "--")).append("\n");
        s.append("Luas ha: ").append(prefs.getString("farm_area_ha", "--")).append("\n");
        s.append("pH: ").append(prefs.getString("soil_ph", "--")).append("; N tersedia mg/kg: ").append(prefs.getString("soil_n", "--"));
        s.append("; P tersedia mg/kg: ").append(prefs.getString("soil_p", "--")).append("; K tersedia mg/kg: ").append(prefs.getString("soil_k", "--"));
        s.append("; EC uS/cm: ").append(prefs.getString("soil_ec_us_cm", "--")).append("; kelembapan %: ").append(prefs.getString("soil_moisture_pct", "--")).append("\n");
        s.append("Target N kg/ha: ").append(prefs.getString("target_n_kg_ha", "--"));
        s.append("; P2O5 kg/ha: ").append(prefs.getString("target_p2o5_kg_ha", "--"));
        s.append("; K2O kg/ha: ").append(prefs.getString("target_k2o_kg_ha", "--"));
        s.append("; Dolomit kg/ha: ").append(prefs.getString("target_dolomite_kg_ha", "--"));
        s.append("; Pupuk kandang kg/ha: ").append(prefs.getString("target_manure_kg_ha", "--"));
        s.append("; POC L/ha: ").append(prefs.getString("target_poc_l_ha", "--")).append("\n");
        s.append("Riwayat pupuk (20 terbaru): ").append(historyForMain("fert_history", 20)).append("\n");
        s.append("Riwayat OPT (20 terbaru): ").append(historyForMain("opt_history", 20));
        return s.toString();
    }

    private String historyForMain(String key, int max) {
        try {
            JSONArray all = new JSONArray(prefs.getString(key, "[]"));
            JSONArray out = new JSONArray();
            int start = Math.max(0, all.length() - max);
            for (int i = start; i < all.length(); i++) out.put(all.get(i));
            return out.toString();
        } catch (Exception ignored) { return "[]"; }
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
