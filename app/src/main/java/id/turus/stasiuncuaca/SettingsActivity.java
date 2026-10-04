package id.turus.stasiuncuaca;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.text.InputType;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Switch;
import android.widget.Toast;

public class SettingsActivity extends Activity {
    private android.content.SharedPreferences prefs;
    private EditText title, channel, readKey, aiKey, aiModel, crop, plantAge;
    private Spinner plantAgeUnit;
    private Switch gpsEnabled, openWeatherEnabled, openWeatherFallback, et0Enabled;
    private EditText openWeatherKey;
    private Spinner openWeatherInterval;
    private TextView toggleReadKey, toggleAiKey, toggleOpenWeatherKey;
    private Spinner decimals, analysisDays;
    private EditText[] fieldNames = new EditText[8];
    private EditText[] fieldUnits = new EditText[8];

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        prefs = getSharedPreferences("thingspeak_config", MODE_PRIVATE);

        title = findViewById(R.id.appTitle);
        channel = findViewById(R.id.channel);
        readKey = findViewById(R.id.readKey);
        aiKey = findViewById(R.id.aiKey);
        aiModel = findViewById(R.id.aiModel);
        crop = findViewById(R.id.crop);
        plantAge = findViewById(R.id.plantAge);
        plantAgeUnit = findViewById(R.id.plantAgeUnit);
        gpsEnabled = findViewById(R.id.gpsEnabled);
        openWeatherEnabled = findViewById(R.id.openWeatherEnabled);
        openWeatherFallback = findViewById(R.id.openWeatherFallback);
        et0Enabled = findViewById(R.id.et0Enabled);
        openWeatherKey = findViewById(R.id.openWeatherKey);
        openWeatherInterval = findViewById(R.id.openWeatherInterval);
        toggleReadKey = findViewById(R.id.toggleKey);
        toggleAiKey = findViewById(R.id.toggleAiKey);
        toggleOpenWeatherKey = findViewById(R.id.toggleOpenWeatherKey);
        decimals = findViewById(R.id.decimals);
        analysisDays = findViewById(R.id.analysisDays);

        title.setText(prefs.getString("app_title", "STASIUN CUACA"));
        channel.setText(prefs.getString("channel", ""));
        readKey.setText(prefs.getString("read_key", ""));
        aiKey.setText(prefs.getString("ai_api_key", ""));
        aiModel.setText(prefs.getString("ai_model", "gpt-6-luna"));
        crop.setText(prefs.getString("crop", "Tanaman pertanian"));
        plantAge.setText(prefs.getString("crop_age_value", ""));
        gpsEnabled.setChecked(prefs.getBoolean("gps_enabled", true));
        openWeatherEnabled.setChecked(prefs.getBoolean("openweather_enabled", false));
        openWeatherFallback.setChecked(prefs.getBoolean("openweather_fallback", true));
        et0Enabled.setChecked(prefs.getBoolean("et0_enabled", true));
        openWeatherKey.setText(prefs.getString("openweather_api_key", ""));
        ArrayAdapter<String> owIntervalAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"5 menit", "15 menit", "30 menit", "60 menit", "120 menit"});
        owIntervalAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        openWeatherInterval.setAdapter(owIntervalAdapter);
        int owMin = prefs.getInt("openweather_interval", 30);
        int owPos = owMin <= 5 ? 0 : (owMin <= 15 ? 1 : (owMin <= 30 ? 2 : (owMin <= 60 ? 3 : 4)));
        openWeatherInterval.setSelection(owPos);
        ArrayAdapter<String> ageAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"hari", "bulan", "tahun"});
        ageAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        plantAgeUnit.setAdapter(ageAdapter);
        String savedAgeUnit = prefs.getString("crop_age_unit", "hari");
        int agePos = "bulan".equals(savedAgeUnit) ? 1 : ("tahun".equals(savedAgeUnit) ? 2 : 0);
        plantAgeUnit.setSelection(agePos);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"0 angka", "1 angka", "2 angka"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        decimals.setAdapter(adapter);
        int d = Math.max(0, Math.min(2, prefs.getInt("display_decimals", 2)));
        decimals.setSelection(d);

        ArrayAdapter<String> analysisAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"1 hari", "3 hari", "7 hari"});
        analysisAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        analysisDays.setAdapter(analysisAdapter);
        int savedDays = prefs.getInt("analysis_days", 3);
        analysisDays.setSelection(savedDays <= 1 ? 0 : (savedDays <= 3 ? 1 : 2));

        for (int i = 0; i < 8; i++) {
            fieldNames[i] = findViewById(getResources().getIdentifier("fieldName" + (i + 1), "id", getPackageName()));
            fieldUnits[i] = findViewById(getResources().getIdentifier("fieldUnit" + (i + 1), "id", getPackageName()));
            fieldNames[i].setText(prefs.getString("field_name_" + (i + 1), ""));
            fieldUnits[i].setText(prefs.getString("field_unit_" + (i + 1), ""));
            String autoName = prefs.getString("ts_field_name_" + (i + 1), "").trim();
            fieldNames[i].setHint(autoName.isEmpty() ? "Nama tampilan (kosong = otomatis)" : "Otomatis: " + autoName);
        }

        toggleReadKey.setOnClickListener(v -> togglePassword(readKey, toggleReadKey));
        toggleAiKey.setOnClickListener(v -> togglePassword(aiKey, toggleAiKey));
        toggleOpenWeatherKey.setOnClickListener(v -> togglePassword(openWeatherKey, toggleOpenWeatherKey));
        findViewById(R.id.save).setOnClickListener(v -> save());
        findViewById(R.id.cancel).setOnClickListener(v -> finish());
        findViewById(R.id.openSoil).setOnClickListener(v -> startActivity(new Intent(this, SoilActivity.class)));
    }

    private void togglePassword(EditText field, TextView toggle) {
        boolean visible = field.getInputType() == (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        field.setInputType(InputType.TYPE_CLASS_TEXT | (visible ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD));
        toggle.setText(visible ? "TAMPILKAN" : "SEMBUNYIKAN");
        field.setSelection(field.length());
    }

    private void save() {
        String titleValue = title.getText().toString().trim();
        String ch = channel.getText().toString().trim();
        String key = readKey.getText().toString().trim();
        String newAiKey = aiKey.getText().toString().trim();
        String model = aiModel.getText().toString().trim();
        String cropValue = crop.getText().toString().trim();
        String ageValue = plantAge.getText().toString().trim().replace(',', '.');
        String ageUnit = String.valueOf(plantAgeUnit.getSelectedItem());
        String owKey = openWeatherKey.getText().toString().trim();
        int[] owMinutes = new int[]{5, 15, 30, 60, 120};
        if (titleValue.isEmpty()) titleValue = "STASIUN CUACA";
        if (ch.isEmpty()) {
            channel.setError("Channel ID wajib diisi");
            return;
        }
        if (model.isEmpty()) model = "gpt-6-luna";
        if (cropValue.isEmpty()) cropValue = "Tanaman pertanian";
        if (!ageValue.isEmpty()) {
            try {
                double ageNum = Double.parseDouble(ageValue);
                if (ageNum < 0 || ageNum > 10000) throw new NumberFormatException();
            } catch (Exception ex) {
                plantAge.setError("Masukkan umur tanaman yang wajar");
                return;
            }
        }

        String oldChannel = prefs.getString("channel", "");
        android.content.SharedPreferences.Editor e = prefs.edit()
                .putString("app_title", titleValue)
                .putString("channel", ch)
                .putString("read_key", key)
                .putString("ai_api_key", newAiKey)
                .putString("ai_model", model)
                .putString("crop", cropValue)
                .putString("crop_age_value", ageValue)
                .putString("crop_age_unit", ageUnit)
                .putBoolean("gps_enabled", gpsEnabled.isChecked())
                .putBoolean("openweather_enabled", openWeatherEnabled.isChecked())
                .putBoolean("openweather_fallback", openWeatherFallback.isChecked())
                .putBoolean("et0_enabled", et0Enabled.isChecked())
                .putString("openweather_api_key", owKey)
                .putInt("openweather_interval", owMinutes[openWeatherInterval.getSelectedItemPosition()])
                .putInt("display_decimals", decimals.getSelectedItemPosition())
                .putInt("analysis_days", new int[]{1,3,7}[analysisDays.getSelectedItemPosition()]);


        for (int i = 0; i < 8; i++) {
            e.putString("field_name_" + (i + 1), fieldNames[i].getText().toString().trim());
            e.putString("field_unit_" + (i + 1), fieldUnits[i].getText().toString().trim());
        }
        if (!ch.equals(oldChannel)) {
            e.putString("field_meta_channel", "");
            for (int i = 0; i < 8; i++) e.remove("ts_field_name_" + (i + 1));
        }
        e.apply();
        Toast.makeText(this, "Pengaturan tersimpan", Toast.LENGTH_SHORT).show();
        finish();
    }
}
