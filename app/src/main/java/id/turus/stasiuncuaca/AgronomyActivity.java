package id.turus.stasiuncuaca;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
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
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lahan + riwayat pemupukan + riwayat pengendalian OPT.
 *
 * Angka rekomendasi pupuk dihitung transparan dari target hara yang dimasukkan
 * pengguna, luas lahan, dan kredit hara dari riwayat pupuk. Data pH, N/P/K,
 * EC dan kelembapan dipakai sebagai pemeriksaan kondisi tanah dan bahan AI.
 * Aplikasi tidak mengarang kebutuhan kapur/pestisida tanpa dasar yang dicatat.
 */
public class AgronomyActivity extends Activity {
    private static final String PREFS = "thingspeak_config";
    private static final String KEY_FERT = "fert_history";
    private static final String KEY_OPT = "opt_history";
    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US);
    private static final String DEFAULT_AI_MODEL = "gpt-6-luna";

    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;

    private EditText crop, ageMonths, areaHa, soilPh, soilN, soilP, soilK, soilEc, soilMoisture;
    private EditText targetN, targetP, targetK, targetDolomite, targetManure, targetPoc;
    private TextView recommendation, fertHistory, optHistory, optAnalysis, aiStatus, aiAdvice;

    private String lastReport = "";
    private boolean aiRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_agronomy);
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        bindViews();
        loadFarmData();
        refreshHistoryViews();

        findViewById(R.id.back).setOnClickListener(v -> finish());
        findViewById(R.id.saveFarm).setOnClickListener(v -> {
            if (!validateFarmData()) return;
            saveFarmData();
            Toast.makeText(this, "Data lahan tersimpan.", Toast.LENGTH_SHORT).show();
        });
        findViewById(R.id.calculate).setOnClickListener(v -> calculateRecommendation());
        findViewById(R.id.addFertilizer).setOnClickListener(v -> showFertilizerDialog());
        findViewById(R.id.addOpt).setOnClickListener(v -> showOptDialog());
        findViewById(R.id.aiAgronomyButton).setOnClickListener(v -> requestAiAgronomyAdvice());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        net.shutdownNow();
    }

    private void bindViews() {
        crop = findViewById(R.id.cropA);
        ageMonths = findViewById(R.id.ageMonths);
        areaHa = findViewById(R.id.areaHa);
        soilPh = findViewById(R.id.soilPh);
        soilN = findViewById(R.id.soilN);
        soilP = findViewById(R.id.soilP);
        soilK = findViewById(R.id.soilK);
        soilEc = findViewById(R.id.soilEc);
        soilMoisture = findViewById(R.id.soilMoisture);
        targetN = findViewById(R.id.targetN);
        targetP = findViewById(R.id.targetP);
        targetK = findViewById(R.id.targetK);
        targetDolomite = findViewById(R.id.targetDolomite);
        targetManure = findViewById(R.id.targetManure);
        targetPoc = findViewById(R.id.targetPoc);
        recommendation = findViewById(R.id.recommendation);
        fertHistory = findViewById(R.id.fertHistory);
        optHistory = findViewById(R.id.optHistory);
        optAnalysis = findViewById(R.id.optAnalysis);
        aiStatus = findViewById(R.id.aiAgronomyStatus);
        aiAdvice = findViewById(R.id.aiAgronomyAdvice);
    }

    private void loadFarmData() {
        crop.setText(prefs.getString("crop", ""));
        ageMonths.setText(prefs.getString("farm_age_months", ""));
        areaHa.setText(prefs.getString("farm_area_ha", ""));
        soilPh.setText(prefs.getString("soil_ph", ""));
        soilN.setText(prefs.getString("soil_n", ""));
        soilP.setText(prefs.getString("soil_p", ""));
        soilK.setText(prefs.getString("soil_k", ""));
        soilEc.setText(prefs.getString("soil_ec_us_cm", ""));
        soilMoisture.setText(prefs.getString("soil_moisture_pct", ""));
        targetN.setText(prefs.getString("target_n_kg_ha", ""));
        targetP.setText(prefs.getString("target_p2o5_kg_ha", ""));
        targetK.setText(prefs.getString("target_k2o_kg_ha", ""));
        targetDolomite.setText(prefs.getString("target_dolomite_kg_ha", ""));
        targetManure.setText(prefs.getString("target_manure_kg_ha", ""));
        targetPoc.setText(prefs.getString("target_poc_l_ha", ""));
    }

    private void saveFarmData() {
        prefs.edit()
                .putString("crop", crop.getText().toString().trim())
                .putString("farm_age_months", ageMonths.getText().toString().trim())
                .putString("farm_area_ha", areaHa.getText().toString().trim())
                .putString("soil_ph", soilPh.getText().toString().trim())
                .putString("soil_n", soilN.getText().toString().trim())
                .putString("soil_p", soilP.getText().toString().trim())
                .putString("soil_k", soilK.getText().toString().trim())
                .putString("soil_ec_us_cm", soilEc.getText().toString().trim())
                .putString("soil_moisture_pct", soilMoisture.getText().toString().trim())
                .putString("target_n_kg_ha", targetN.getText().toString().trim())
                .putString("target_p2o5_kg_ha", targetP.getText().toString().trim())
                .putString("target_k2o_kg_ha", targetK.getText().toString().trim())
                .putString("target_dolomite_kg_ha", targetDolomite.getText().toString().trim())
                .putString("target_manure_kg_ha", targetManure.getText().toString().trim())
                .putString("target_poc_l_ha", targetPoc.getText().toString().trim())
                .apply();
    }

    private boolean validateFarmData() {
        String cropText = crop.getText().toString().trim();
        if (cropText.isEmpty()) {
            crop.setError("Jenis tanaman wajib diisi");
            crop.requestFocus();
            return false;
        }

        double area = optionalNumber(areaHa);
        if (Double.isNaN(area) || area <= 0) {
            areaHa.setError("Luas lahan harus lebih dari 0 ha");
            areaHa.requestFocus();
            return false;
        }

        double age = optionalNumber(ageMonths);
        if (!Double.isNaN(age) && age < 0) {
            ageMonths.setError("Umur tidak boleh negatif");
            ageMonths.requestFocus();
            return false;
        }

        if (!validateOptionalRange(soilPh, "pH tanah", 0, 14)) return false;
        if (!validateOptionalRange(soilN, "N tersedia", 0, Double.POSITIVE_INFINITY)) return false;
        if (!validateOptionalRange(soilP, "P tersedia", 0, Double.POSITIVE_INFINITY)) return false;
        if (!validateOptionalRange(soilK, "K tersedia", 0, Double.POSITIVE_INFINITY)) return false;
        if (!validateOptionalRange(soilEc, "EC tanah", 0, Double.POSITIVE_INFINITY)) return false;
        if (!validateOptionalRange(soilMoisture, "Kelembapan tanah", 0, 100)) return false;
        if (!validateOptionalRange(targetN, "Target N", 0, Double.POSITIVE_INFINITY)) return false;
        if (!validateOptionalRange(targetP, "Target P2O5", 0, Double.POSITIVE_INFINITY)) return false;
        if (!validateOptionalRange(targetK, "Target K2O", 0, Double.POSITIVE_INFINITY)) return false;
        if (!validateOptionalRange(targetDolomite, "Target dolomit", 0, Double.POSITIVE_INFINITY)) return false;
        if (!validateOptionalRange(targetManure, "Target pupuk kandang", 0, Double.POSITIVE_INFINITY)) return false;
        return validateOptionalRange(targetPoc, "Target POC", 0, Double.POSITIVE_INFINITY);
    }

    private boolean validateOptionalRange(EditText field, String label, double min, double max) {
        String text = field.getText().toString().trim();
        if (text.isEmpty()) return true;
        double v = numberOrNaN(text);
        if (Double.isNaN(v) || Double.isInfinite(v) || v < min || v > max) {
            String range = Double.isInfinite(max) ? ("minimal " + fmt(min, 0)) : (fmt(min, 2) + "-" + fmt(max, 2));
            field.setError(label + " harus " + range);
            field.requestFocus();
            return false;
        }
        return true;
    }

    private void calculateRecommendation() {
        if (!validateFarmData()) return;
        saveFarmData();
        double area = required(areaHa, "Luas lahan");
        if (Double.isNaN(area) || area <= 0) return;
        double nTarget = finiteOrZero(value(targetN));
        double pTarget = finiteOrZero(value(targetP));
        double kTarget = finiteOrZero(value(targetK));
        double dolTarget = finiteOrZero(value(targetDolomite));
        double manureTarget = finiteOrZero(value(targetManure));
        double pocTarget = finiteOrZero(value(targetPoc));
        if (nTarget == 0 && pTarget == 0 && kTarget == 0 && dolTarget == 0 && manureTarget == 0 && pocTarget == 0) {
            recommendation.setText("Belum ada target dosis. Isi minimal salah satu target N, P2O5, K2O, dolomit, pupuk kandang, atau POC dari rekomendasi tanaman/lokasi Anda.");
            return;
        }

        double[] credit = fertilizerNutrientCredit();
        double nRemain = Math.max(0, nTarget - credit[0]);
        double pRemain = Math.max(0, pTarget - credit[1]);
        double kRemain = Math.max(0, kTarget - credit[2]);
        double dolRemain = Math.max(0, dolTarget - categoryCredit("Dolomit", "kg/ha"));
        double manureRemain = Math.max(0, manureTarget - categoryCredit("Pupuk kandang", "kg/ha"));
        double pocRemain = Math.max(0, pocTarget - categoryCredit("POC", "L/ha"));

        double urea = nRemain / 0.46;
        double sp36 = pRemain / 0.36;
        double kcl = kRemain / 0.60;

        StringBuilder r = new StringBuilder();
        r.append("HASIL HITUNG PEMUPUKAN BERIKUTNYA\n\n");
        r.append("Tanaman: ").append(orDash(crop.getText().toString().trim())).append("\n");
        r.append("Umur: ").append(orDash(ageMonths.getText().toString().trim())).append(" bulan\n");
        r.append("Luas: ").append(fmt(area, 2)).append(" ha\n\n");
        r.append("Kebutuhan hara tersisa per ha:\n");
        r.append("• Nitrogen (N): ").append(fmt(nRemain, 2)).append(" kg/ha\n");
        r.append("• Fosfor sebagai P2O5: ").append(fmt(pRemain, 2)).append(" kg/ha\n");
        r.append("• Kalium sebagai K2O: ").append(fmt(kRemain, 2)).append(" kg/ha\n\n");
        r.append("Contoh konversi pupuk tunggal:\n");
        r.append("• Urea 46%: ").append(fmt(urea, 2)).append(" kg/ha (total ").append(fmt(urea * area, 2)).append(" kg)\n");
        r.append("• SP-36 36%: ").append(fmt(sp36, 2)).append(" kg/ha (total ").append(fmt(sp36 * area, 2)).append(" kg)\n");
        r.append("• KCl 60%: ").append(fmt(kcl, 2)).append(" kg/ha (total ").append(fmt(kcl * area, 2)).append(" kg)\n");
        if (!Double.isNaN(dolRemain)) r.append("• Dolomit: ").append(fmt(dolRemain, 2)).append(" kg/ha (total ").append(fmt(dolRemain * area, 2)).append(" kg)\n");
        if (!Double.isNaN(manureRemain)) r.append("• Pupuk kandang: ").append(fmt(manureRemain, 2)).append(" kg/ha (total ").append(fmt(manureRemain * area, 2)).append(" kg)\n");
        if (!Double.isNaN(pocRemain)) r.append("• POC: ").append(fmt(pocRemain, 2)).append(" L/ha (total ").append(fmt(pocRemain * area, 2)).append(" L)\n");

        r.append("\n4 T PEMUPUKAN:\n");
        r.append("1. Tepat jenis: pilih sumber hara sesuai kebutuhan, jangan mengganti pupuk tanpa menghitung ulang kandungan haranya.\n");
        r.append("2. Tepat dosis: gunakan angka hitung di atas dan periksa dosis yang benar-benar sudah diberikan.\n");
        r.append("3. Tepat waktu: sesuaikan dengan umur/fase tanaman dan kelembapan tanah; nitrogen biasanya lebih baik dibagi bila tanaman dan kondisi lahan memang memerlukannya.\n");
        r.append("4. Tepat cara: sebar/benamkan/larutkan sesuai jenis pupuk dan kondisi tanaman agar tidak banyak hilang.\n");

        r.append("\nPEMERIKSAAN TANAH:\n");
        String ph = soilPh.getText().toString().trim();
        String ec = soilEc.getText().toString().trim();
        String moisture = soilMoisture.getText().toString().trim();
        String nSoil = soilN.getText().toString().trim();
        String pSoil = soilP.getText().toString().trim();
        String kSoil = soilK.getText().toString().trim();
        r.append("pH: ").append(orDash(ph)).append("; N tersedia: ").append(orDash(nSoil)).append(" mg/kg; P tersedia: ").append(orDash(pSoil)).append(" mg/kg; K tersedia: ").append(orDash(kSoil)).append(" mg/kg; EC: ").append(orDash(ec)).append(" uS/cm; kelembapan: ").append(orDash(moisture)).append(" %.\n");
        if (numberOrNaN(ph) < 5.0) r.append("• pH rendah: kebutuhan dolomit sebaiknya ditentukan dari kebutuhan pengapuran/analisis tanah, bukan dari pH saja.\n");
        if (numberOrNaN(ph) > 7.5) r.append("• pH tinggi: jangan menambah dolomit tanpa dasar analisis.\n");
        if (numberOrNaN(ec) > 2000) r.append("• EC > 2.000 uS/cm: indikasi garam terlarut cukup tinggi; hindari memberi pupuk pekat sekaligus dan cek sumber air serta riwayat pupuk.\n");
        if (numberOrNaN(moisture) >= 0 && numberOrNaN(moisture) < 25) r.append("• Tanah terukur cukup kering; pastikan tanaman mendapat air sebelum pupuk larut diaplikasikan.\n");
        if (numberOrNaN(moisture) > 80) r.append("• Tanah sangat basah; pertimbangkan menunda pupuk yang mudah hilang sampai kondisi lebih baik.\n");
        r.append("\nCatatan: angka di atas adalah hasil hitung dari target hara yang Anda masukkan + riwayat pupuk. Aplikasi tidak mengarang target baru ketika acuan tanaman/lahan belum diisi. Untuk dolomit, pH saja tidak cukup untuk menentukan kebutuhan secara tepat.\n");

        lastReport = r.toString();
        recommendation.setText(lastReport);
    }

    private double required(EditText e, String label) {
        double v = value(e);
        if (Double.isNaN(v) || v <= 0) {
            e.setError(label + " wajib diisi");
            e.requestFocus();
            return Double.NaN;
        }
        return v;
    }

    private double value(EditText e) {
        return numberOrNaN(e.getText().toString());
    }

    private double finiteOrZero(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? 0.0 : Math.max(0.0, v);
    }

    private double numberOrNaN(String s) {
        if (s == null || s.trim().isEmpty()) return Double.NaN;
        try { return Double.parseDouble(s.trim().replace(',', '.')); }
        catch (Exception ignored) { return Double.NaN; }
    }

    private double[] fertilizerNutrientCredit() {
        double n = 0, p = 0, k = 0;
        try {
            JSONArray a = new JSONArray(prefs.getString(KEY_FERT, "[]"));
            LocalDate cutoff = LocalDate.now(WIB).minusDays(180);
            String currentCrop = crop.getText().toString().trim();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                if (!"kg/ha".equals(o.optString("unit", "kg/ha"))) continue;
                String recordCrop = o.optString("crop", "").trim();
                if (!currentCrop.isEmpty() && !currentCrop.equalsIgnoreCase(recordCrop)) continue;
                String dateText = o.optString("date", "").trim();
                if (!validHistoryDate(dateText)) continue;
                if (LocalDate.parse(dateText, DATE_FMT).isBefore(cutoff)) continue;
                double dose = o.optDouble("dose", 0);
                n += dose * o.optDouble("nPct", 0) / 100.0;
                p += dose * o.optDouble("pPct", 0) / 100.0;
                k += dose * o.optDouble("kPct", 0) / 100.0;
            }
        } catch (Exception ignored) { }
        return new double[]{n, p, k};
    }

    private double categoryCredit(String category, String unit) {
        double sum = 0;
        try {
            JSONArray a = new JSONArray(prefs.getString(KEY_FERT, "[]"));
            LocalDate cutoff = LocalDate.now(WIB).minusDays(180);
            String currentCrop = crop.getText().toString().trim();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                if (!category.equals(o.optString("category", ""))) continue;
                if (!unit.equals(o.optString("unit", ""))) continue;
                String recordCrop = o.optString("crop", "").trim();
                if (!currentCrop.isEmpty() && !currentCrop.equalsIgnoreCase(recordCrop)) continue;
                String dateText = o.optString("date", "").trim();
                if (!validHistoryDate(dateText)) continue;
                if (LocalDate.parse(dateText, DATE_FMT).isBefore(cutoff)) continue;
                sum += o.optDouble("dose", 0);
            }
        } catch (Exception ignored) { }
        return sum;
    }

    private void showFertilizerDialog() {
        final LinearLayout root = dialogRoot();
        final String[] cats = {"Pupuk N", "Pupuk P", "Pupuk K", "NPK", "Dolomit", "Pupuk kandang", "POC", "Lainnya"};
        final String[] units = {"kg/ha", "L/ha"};
        final EditText date = edit(root, "Tanggal (YYYY-MM-DD)", LocalDate.now(WIB).format(DATE_FMT));
        final EditText product = edit(root, "Nama pupuk", "");
        final Spinner category = spinner(root, "Jenis catatan", cats);
        final EditText dose = editNumeric(root, "Dosis produk per ha", "");
        final Spinner unit = spinner(root, "Satuan dosis", units);
        final EditText nPct = editNumeric(root, "Kandungan N (%)", "");
        final EditText pPct = editNumeric(root, "Kandungan P2O5 (%)", "");
        final EditText kPct = editNumeric(root, "Kandungan K2O (%)", "");
        final EditText method = edit(root, "Cara pemberian", "");
        final EditText stage = edit(root, "Umur/fase tanaman saat aplikasi", "");
        final EditText note = edit(root, "Catatan hasil/pengamatan", "");

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Tambah Riwayat Pemupukan")
                .setView(wrap(root))
                .setNegativeButton("BATAL", null)
                .setPositiveButton("SIMPAN", null)
                .create();
        d.setOnShowListener(v -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x -> {
            double dv = numberOrNaN(dose.getText().toString());
            double nv = safeNum(nPct);
            double pv = safeNum(pPct);
            double kv = safeNum(kPct);
            if (!validHistoryDate(date.getText().toString().trim())) {
                date.setError("Tanggal harus YYYY-MM-DD");
                return;
            }
            if (product.getText().toString().trim().isEmpty() || Double.isNaN(dv) || dv <= 0) {
                dose.setError("Masukkan dosis lebih dari 0");
                return;
            }
            if (nv > 100 || pv > 100 || kv > 100) {
                nPct.setError("Kadar harus 0-100%");
                pPct.setError("Kadar harus 0-100%");
                kPct.setError("Kadar harus 0-100%");
                return;
            }
            JSONObject o = new JSONObject();
            try {
                o.put("date", date.getText().toString().trim());
                o.put("product", product.getText().toString().trim());
                o.put("category", cats[category.getSelectedItemPosition()]);
                o.put("dose", dv);
                o.put("unit", units[unit.getSelectedItemPosition()]);
                o.put("nPct", nv);
                o.put("pPct", pv);
                o.put("kPct", kv);
                o.put("method", method.getText().toString().trim());
                o.put("stage", stage.getText().toString().trim());
                o.put("note", note.getText().toString().trim());
                o.put("crop", crop.getText().toString().trim());
                o.put("created", System.currentTimeMillis());
                appendHistory(KEY_FERT, o, 200);
                refreshHistoryViews();
                calculateRecommendation();
                d.dismiss();
            } catch (Exception ex) {
                Toast.makeText(this, "Data riwayat tidak dapat disimpan.", Toast.LENGTH_SHORT).show();
            }
        }));
        d.show();
    }

    private void showOptDialog() {
        final LinearLayout root = dialogRoot();
        final String[] methods = {"Manual", "Mekanis", "Kimia"};
        final String[] labelUnits = {"g/ha", "kg/ha", "ml/ha", "L/ha"};
        final EditText date = edit(root, "Tanggal (YYYY-MM-DD)", LocalDate.now(WIB).format(DATE_FMT));
        final EditText target = edit(root, "OPT sasaran / masalah di lapang", "");
        final EditText affected = editNumeric(root, "Luas/proporsi terserang (%)", "");
        final EditText observation = edit(root, "Gejala/pengamatan lapang", "");
        final Spinner method = spinner(root, "Metode pengendalian", methods);
        final EditText product = edit(root, "Nama produk (bila kimia)", "");
        final EditText active = edit(root, "Bahan aktif (bila kimia)", "");
        final EditText dose = editNumeric(root, "Dosis dari label", "");
        final Spinner doseUnit = spinner(root, "Satuan dosis label", labelUnits);
        final EditText concentration = editNumeric(root, "Konsentrasi (%)", "");
        final EditText water = editNumeric(root, "Volume air (L/ha)", "");
        final EditText labelInterval = editNumeric(root, "Interval minimum sesuai label (hari)", "");
        final Spinner registered = spinner(root, "Status label/produk", new String[]{"Sudah dicek sesuai label", "Belum dicek"});
        final EditText application = edit(root, "Cara aplikasi", "");
        final EditText result = edit(root, "Hasil pengendalian", "");
        final EditText note = edit(root, "Catatan", "");

        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("Tambah Riwayat Pengendalian OPT")
                .setView(wrap(root))
                .setNegativeButton("BATAL", null)
                .setPositiveButton("SIMPAN", null)
                .create();
        d.setOnShowListener(v -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x -> {
            if (target.getText().toString().trim().isEmpty()) {
                target.setError("OPT sasaran wajib diisi");
                return;
            }
            if (!validHistoryDate(date.getText().toString().trim())) {
                date.setError("Tanggal harus YYYY-MM-DD");
                return;
            }
            double affectedValue = optionalNumber(affected);
            double doseValue = optionalNumber(dose);
            double concentrationValue = optionalNumber(concentration);
            double waterValue = optionalNumber(water);
            double intervalValue = optionalNumber(labelInterval);
            if (!Double.isNaN(affectedValue) && (affectedValue < 0 || affectedValue > 100)) {
                affected.setError("Harus 0-100%");
                return;
            }
            if (!Double.isNaN(concentrationValue) && (concentrationValue < 0 || concentrationValue > 100)) {
                concentration.setError("Harus 0-100%");
                return;
            }
            if (!Double.isNaN(waterValue) && waterValue <= 0) {
                water.setError("Harus lebih dari 0");
                return;
            }
            if (!Double.isNaN(intervalValue) && intervalValue < 0) {
                labelInterval.setError("Tidak boleh negatif");
                return;
            }
            JSONObject o = new JSONObject();
            try {
                o.put("date", date.getText().toString().trim());
                o.put("target", target.getText().toString().trim());
                putOptionalNumber(o, "affectedPct", affected);
                o.put("observation", observation.getText().toString().trim());
                o.put("method", methods[method.getSelectedItemPosition()]);
                o.put("product", product.getText().toString().trim());
                o.put("active", active.getText().toString().trim());
                putOptionalNumber(o, "dose", dose);
                o.put("doseUnit", labelUnits[doseUnit.getSelectedItemPosition()]);
                putOptionalNumber(o, "concentrationPct", concentration);
                putOptionalNumber(o, "waterLHa", water);
                putOptionalNumber(o, "labelIntervalDays", labelInterval);
                o.put("registered", registered.getSelectedItem().toString());
                o.put("application", application.getText().toString().trim());
                o.put("result", result.getText().toString().trim());
                o.put("note", note.getText().toString().trim());
                o.put("crop", crop.getText().toString().trim());
                o.put("created", System.currentTimeMillis());
                appendHistory(KEY_OPT, o, 200);
                refreshHistoryViews();
                d.dismiss();
            } catch (Exception ex) {
                Toast.makeText(this, "Data riwayat OPT tidak dapat disimpan.", Toast.LENGTH_SHORT).show();
            }
        }));
        d.show();
    }

    private void refreshHistoryViews() {
        fertHistory.setText(formatFertilizerHistory());
        optHistory.setText(formatOptHistory());
        optAnalysis.setText(analyzeOptHistory());
    }

    private String formatFertilizerHistory() {
        StringBuilder s = new StringBuilder();
        try {
            JSONArray a = new JSONArray(prefs.getString(KEY_FERT, "[]"));
            if (a.length() == 0) return "Belum ada riwayat pemupukan.";
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                s.append(o.optString("date", "--")).append(" • ")
                        .append(o.optString("product", "--")).append(" • ")
                        .append(fmt(o.optDouble("dose", 0), 2)).append(" ").append(o.optString("unit", ""));
                s.append("\n  Jenis: ").append(o.optString("category", "-"));
                s.append(" | N ").append(fmt(o.optDouble("nPct", 0), 2)).append("% P2O5 ").append(fmt(o.optDouble("pPct", 0), 2)).append("% K2O ").append(fmt(o.optDouble("kPct", 0), 2)).append("%\n");
            }
        } catch (Exception ignored) { return "Riwayat pemupukan rusak/tidak terbaca."; }
        return s.toString().trim();
    }

    private String formatOptHistory() {
        StringBuilder s = new StringBuilder();
        try {
            JSONArray a = new JSONArray(prefs.getString(KEY_OPT, "[]"));
            if (a.length() == 0) return "Belum ada riwayat pengendalian OPT.";
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                s.append(o.optString("date", "--")).append(" • ")
                        .append(o.optString("target", "--")).append(" • ")
                        .append(o.optString("method", "--")).append("\n");
                if (!o.optString("product", "").isEmpty()) {
                    s.append("  Produk: ").append(o.optString("product"));
                    if (!o.optString("active", "").isEmpty()) s.append(" / ").append(o.optString("active"));
                    double dose = o.optDouble("dose", Double.NaN);
                    if (!Double.isNaN(dose)) s.append(" • dosis label ").append(fmt(dose, 2)).append(" ").append(o.optString("doseUnit", ""));
                    double c = o.optDouble("concentrationPct", Double.NaN);
                    if (!Double.isNaN(c)) s.append(" • konsentrasi ").append(fmt(c, 2)).append("%");
                    s.append("\n");
                }
                s.append("  Hasil: ").append(orDash(o.optString("result", ""))).append("\n");
            }
        } catch (Exception ignored) { return "Riwayat OPT rusak/tidak terbaca."; }
        return s.toString().trim();
    }

    private String analyzeOptHistory() {
        try {
            JSONArray a = new JSONArray(prefs.getString(KEY_OPT, "[]"));
            String currentCrop = crop.getText().toString().trim();
            JSONObject latest = null;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                String recordCrop = o.optString("crop", "").trim();
                if (!currentCrop.isEmpty() && !currentCrop.equalsIgnoreCase(recordCrop)) continue;
                latest = o;
            }
            if (latest == null) return "Analisis OPT: belum ada catatan untuk tanaman ini.";
            String target = latest.optString("target", "");
            String method = latest.optString("method", "");
            StringBuilder s = new StringBuilder("ANALISIS OPT TERAKHIR\n");
            s.append("Sasaran: ").append(orDash(target)).append("\n");
            s.append("Metode: ").append(orDash(method)).append("\n");
            if ("Kimia".equalsIgnoreCase(method)) {
                s.append("5 tepat:\n");
                s.append("1. Tepat sasaran: ").append(target.isEmpty() ? "BELUM" : "SUDAH dicatat").append("\n");
                s.append("2. Tepat jenis: ").append(latest.optString("active", "").isEmpty() && latest.optString("product", "").isEmpty() ? "BELUM" : "SUDAH dicatat").append("\n");
                s.append("3. Tepat dosis/konsentrasi: ");
                double dose = latest.optDouble("dose", Double.NaN);
                double conc = latest.optDouble("concentrationPct", Double.NaN);
                if (!Double.isNaN(dose) || !Double.isNaN(conc)) {
                    s.append("SUDAH dicatat");
                    double area = numberOrNaN(areaHa.getText().toString());
                    if (!Double.isNaN(dose) && !Double.isNaN(area) && area > 0) {
                        s.append("; kebutuhan untuk ").append(fmt(area, 2)).append(" ha = ").append(fmt(dose * area, 2)).append(" ").append(latest.optString("doseUnit", ""));
                    }
                    double waterLHa = latest.optDouble("waterLHa", Double.NaN);
                    if (!Double.isNaN(conc) && !Double.isNaN(waterLHa) && waterLHa > 0) {
                        double productLHa = conc / 100.0 * waterLHa;
                        s.append("; dari konsentrasi ").append(fmt(conc, 2)).append("% dan air ").append(fmt(waterLHa, 2)).append(" L/ha = ").append(fmt(productLHa, 2)).append(" L produk/ha");
                    }
                } else { s.append("BELUM"); }
                s.append("\n4. Tepat waktu: ").append(latest.optString("date", "").isEmpty() ? "BELUM" : "SUDAH dicatat").append("\n");
                s.append("5. Tepat cara: ").append(latest.optString("application", "").isEmpty() ? "BELUM" : "SUDAH dicatat").append("\n");
                s.append("Cek mutu/label: ").append(latest.optString("registered", "").contains("Sudah") ? "sudah dicatat dicek" : "PERLU dicek sebelum aplikasi").append("\n");
                if (!latest.optString("active", "").isEmpty() && usedActiveRecently(latest.optString("active", ""), target)) {
                    s.append("PERINGATAN: bahan aktif yang sama tercatat digunakan baru-baru ini. Jangan mengulang otomatis tanpa evaluasi hasil dan petunjuk label.\n");
                }
            } else {
                s.append("Utamakan pengamatan lapang dan nilai hasil pengendalian. Metode manual/mekanis dicoba lebih dulu bila sesuai kondisi.");
            }
            return s.toString();
        } catch (Exception ex) { return "Analisis OPT belum dapat dibuat."; }
    }

    private boolean usedActiveRecently(String active, String target) {
        try {
            JSONArray a = new JSONArray(prefs.getString(KEY_OPT, "[]"));
            LocalDate cutoff = LocalDate.now(WIB).minusDays(30);
            int count = 0;
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o == null) continue;
                if (!"Kimia".equalsIgnoreCase(o.optString("method", ""))) continue;
                if (!active.equalsIgnoreCase(o.optString("active", "").trim())) continue;
                if (!target.equalsIgnoreCase(o.optString("target", "").trim())) continue;
                try { if (LocalDate.parse(o.optString("date", ""), DATE_FMT).isBefore(cutoff)) continue; } catch (Exception ignored) { }
                count++;
            }
            return count > 0;
        } catch (Exception ignored) { return false; }
    }

    private void appendHistory(String key, JSONObject object, int max) throws Exception {
        JSONArray old = new JSONArray(prefs.getString(key, "[]"));
        JSONArray out = new JSONArray();
        int start = Math.max(0, old.length() - max + 1);
        for (int i = start; i < old.length(); i++) out.put(old.get(i));
        out.put(object);
        prefs.edit().putString(key, out.toString()).apply();
    }

    private void putOptionalNumber(JSONObject object, String key, EditText field) throws Exception {
        double v = optionalNumber(field);
        if (!Double.isNaN(v)) object.put(key, v);
    }

    private double optionalNumber(EditText field) {
        String text = field.getText().toString().trim();
        if (text.isEmpty()) return Double.NaN;
        double v = numberOrNaN(text);
        return (!Double.isNaN(v) && !Double.isInfinite(v) && v >= 0) ? v : Double.NaN;
    }

    private boolean validHistoryDate(String text) {
        if (text == null || text.trim().isEmpty()) return false;
        try { LocalDate.parse(text.trim(), DATE_FMT); return true; }
        catch (Exception ignored) { return false; }
    }

    private double safeNum(EditText e) {
        double v = value(e);
        return Double.isNaN(v) || Double.isInfinite(v) ? 0 : Math.max(0, v);
    }

    private void requestAiAgronomyAdvice() {
        if (aiRunning) return;
        String apiKey = prefs.getString("ai_api_key", "").trim();
        if (apiKey.isEmpty()) {
            aiStatus.setText("AI belum dikonfigurasi. Isi OpenAI API key di Pengaturan.");
            return;
        }
        calculateRecommendation();
        if (lastReport.isEmpty()) {
            aiStatus.setText("Data belum cukup untuk analisis AI.");
            return;
        }
        aiRunning = true;
        aiStatus.setText("AI sedang menganalisis lahan, riwayat pupuk, dan riwayat OPT...");
        findViewById(R.id.aiAgronomyButton).setEnabled(false);
        final String model = prefs.getString("ai_model", DEFAULT_AI_MODEL).trim().isEmpty()
                ? DEFAULT_AI_MODEL : prefs.getString("ai_model", DEFAULT_AI_MODEL).trim();
        final String snapshot = buildAgronomyInput();
        net.execute(() -> {
            try {
                String out = callOpenAI(apiKey, model, snapshot);
                runOnUiThread(() -> {
                    aiAdvice.setText(out);
                    aiStatus.setText("Analisis AI selesai.");
                    aiRunning = false;
                    findViewById(R.id.aiAgronomyButton).setEnabled(true);
                });
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    aiAdvice.setText("Analisis AI gagal. Periksa koneksi internet, API key, dan nama model.\n\nPesan: " + safeMessage(ex));
                    aiStatus.setText("AI tidak tersedia.");
                    aiRunning = false;
                    findViewById(R.id.aiAgronomyButton).setEnabled(true);
                });
            }
        });
    }

    private String buildAgronomyInput() {
        StringBuilder s = new StringBuilder();
        s.append("TANAMAN DAN LAHAN\n");
        s.append("Tanaman: ").append(orDash(crop.getText().toString().trim())).append("\n");
        s.append("Umur bulan: ").append(orDash(ageMonths.getText().toString().trim())).append("\n");
        s.append("Luas ha: ").append(orDash(areaHa.getText().toString().trim())).append("\n");
        s.append("pH: ").append(orDash(soilPh.getText().toString().trim())).append("\n");
        s.append("N tersedia mg/kg: ").append(orDash(soilN.getText().toString().trim())).append("\n");
        s.append("P tersedia mg/kg: ").append(orDash(soilP.getText().toString().trim())).append("\n");
        s.append("K tersedia mg/kg: ").append(orDash(soilK.getText().toString().trim())).append("\n");
        s.append("EC uS/cm: ").append(orDash(soilEc.getText().toString().trim())).append("\n");
        s.append("Kelembapan tanah %: ").append(orDash(soilMoisture.getText().toString().trim())).append("\n\n");
        s.append("DATA THINGSPEAK TERBARU\n");
        try {
            JSONObject weather = new JSONObject(prefs.getString("cache_json", "{}"));
            for (int i = 0; i < 8; i++) {
                String raw = weather.optString("field" + (i + 1), "");
                if (raw.isEmpty()) continue;
                String name = prefs.getString("field_name_" + (i + 1), "").trim();
                if (name.isEmpty()) name = prefs.getString("ts_field_name_" + (i + 1), "FIELD " + (i + 1)).trim();
                String unit = prefs.getString("field_unit_" + (i + 1), "").trim();
                s.append(i + 1).append(". ").append(orDash(name)).append(" = ").append(raw);
                if (!unit.isEmpty()) s.append(" ").append(unit);
                s.append("\n");
            }
            s.append("Waktu data: ").append(weather.optString("created_at", "--")).append("\n\n");
        } catch (Exception ignored) { s.append("Data ThingSpeak belum tersedia.\n\n"); }
        s.append("TARGET HARA YANG DIPAKAI APLIKASI\n");
        s.append("N kg/ha: ").append(orDash(targetN.getText().toString().trim())).append("\n");
        s.append("P2O5 kg/ha: ").append(orDash(targetP.getText().toString().trim())).append("\n");
        s.append("K2O kg/ha: ").append(orDash(targetK.getText().toString().trim())).append("\n");
        s.append("Dolomit kg/ha: ").append(orDash(targetDolomite.getText().toString().trim())).append("\n");
        s.append("Pupuk kandang kg/ha: ").append(orDash(targetManure.getText().toString().trim())).append("\n");
        s.append("POC L/ha: ").append(orDash(targetPoc.getText().toString().trim())).append("\n\n");
        s.append("HASIL HITUNG TERAKHIR\n").append(lastReport).append("\n\n");
        s.append("RIWAYAT PEMUPUKAN (maksimal 30 catatan terakhir)\n").append(historyForAi(KEY_FERT, 30)).append("\n\n");
        s.append("RIWAYAT OPT (maksimal 30 catatan terakhir)\n").append(historyForAi(KEY_OPT, 30)).append("\n");
        return s.toString();
    }

    private String historyForAi(String key, int max) {
        try {
            JSONArray all = new JSONArray(prefs.getString(key, "[]"));
            JSONArray out = new JSONArray();
            int start = Math.max(0, all.length() - max);
            for (int i = start; i < all.length(); i++) out.put(all.get(i));
            return out.toString();
        } catch (Exception ignored) { return "[]"; }
    }

    private String callOpenAI(String apiKey, String model, String input) throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("model", model);
        payload.put("instructions", 
                "Anda adalah asisten agronomi lapang. Jawaban harus untuk petani yang paham hal teknis praktis, bukan untuk peneliti. " +
                "Gunakan bahasa Indonesia sederhana. Hindari singkatan yang sulit. Jika memakai N, P, K, tulis kepanjangannya sekali: nitrogen, fosfor, kalium. " +
                "Gunakan data yang diberikan saja dan jangan mengarang hasil uji atau dosis pestisida. " +
                "Untuk pemupukan, ikuti 4 tepat: tepat jenis, tepat dosis, tepat waktu, tepat cara. " +
                "Angka dosis pupuk harus konsisten dengan hasil hitung aplikasi. Jangan mengubah angka hasil hitung tanpa alasan yang jelas. " +
                "Untuk dolomit, jangan membuat angka baru hanya dari pH; kebutuhan kapur memerlukan dasar analisis pengapuran/kemasaman yang memadai. " +
                "Untuk pengendalian OPT, ikuti 5 tepat: tepat sasaran, tepat jenis, tepat dosis atau konsentrasi, tepat waktu, tepat cara. " +
                "Selain itu, ingatkan pengguna agar mengecek mutu, label, dan status pendaftaran produk. " +
                "Jangan mengarang dosis pestisida. Jika data label belum ada, tulis bahwa dosis harus mengikuti label produk yang terdaftar. " +
                "Berikan tindakan yang dapat dilakukan hari ini, pemupukan berikutnya, pemeriksaan lapangan untuk OPT, dan kapan perlu evaluasi ulang. " +
                "Buat saran tegas tetapi jangan berpura-pura pasti bila datanya belum lengkap.");
        payload.put("input", input);
        payload.put("max_output_tokens", 1200);

        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL("https://api.openai.com/v1/responses").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            c.setReadTimeout(45000);
            c.setDoOutput(true);
            c.setUseCaches(false);
            c.setRequestProperty("Authorization", "Bearer " + apiKey);
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = c.getOutputStream()) { os.write(body); }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new Exception("HTTP " + code + " — " + extractApiError(readAll(c.getErrorStream())));
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
                        if (part == null || !"output_text".equals(part.optString("type", ""))) continue;
                        String t = part.optString("text", "").trim();
                        if (!t.isEmpty()) {
                            if (result.length() > 0) result.append("\n");
                            result.append(t);
                        }
                    }
                }
                if (result.length() > 0) return result.toString();
            }
            throw new Exception("Respons AI tidak berisi teks.");
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private String extractApiError(String json) {
        if (json == null) return "Kesalahan tidak diketahui";
        try {
            JSONObject o = new JSONObject(json);
            JSONObject e = o.optJSONObject("error");
            if (e != null) return e.optString("message", json);
        } catch (Exception ignored) { }
        return json.length() > 300 ? json.substring(0, 300) : json;
    }

    private String safeMessage(Throwable ex) {
        String m = ex == null ? null : ex.getMessage();
        return (m == null || m.isEmpty()) ? "Kesalahan tidak diketahui" : m;
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

    private LinearLayout dialogRoot() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(16), dp(4), dp(16), dp(8));
        return l;
    }

    private ScrollView wrap(View v) {
        ScrollView s = new ScrollView(this);
        s.addView(v);
        return s;
    }

    private EditText edit(LinearLayout root, String hint, String value) {
        EditText e = editBase(hint, value);
        root.addView(e);
        return e;
    }

    private EditText editNumeric(LinearLayout root, String hint, String value) {
        EditText e = editBase(hint, value);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        root.addView(e);
        return e;
    }

    private EditText editBase(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setTextColor(Color.WHITE);
        e.setHintTextColor(Color.rgb(157, 176, 188));
        e.setBackgroundResource(R.drawable.bg_edit);
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(48));
        lp.setMargins(0, dp(5), 0, 0);
        e.setLayoutParams(lp);
        return e;
    }

    private Spinner spinner(LinearLayout root, String title, String[] items) {
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextColor(Color.rgb(41, 198, 199));
        label.setTextSize(10);
        label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        label.setPadding(0, dp(7), 0, dp(3));
        root.addView(label);
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        root.addView(s, new LinearLayout.LayoutParams(-1, dp(48)));
        return s;
    }

    private String fmt(double v, int decimals) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "--";
        NumberFormat nf = NumberFormat.getNumberInstance(new Locale("id", "ID"));
        nf.setGroupingUsed(false);
        nf.setMinimumFractionDigits(decimals);
        nf.setMaximumFractionDigits(decimals);
        return nf.format(v);
    }

    private String orDash(String s) { return s == null || s.trim().isEmpty() ? "--" : s.trim(); }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
