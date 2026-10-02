package id.turus.stasiuncuaca;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class SettingsActivity extends Activity {
    private android.content.SharedPreferences prefs;
    private EditText channel, readKey;
    private TextView toggle;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        prefs = getSharedPreferences("thingspeak_config", MODE_PRIVATE);
        channel = findViewById(R.id.channel);
        readKey = findViewById(R.id.readKey);
        toggle = findViewById(R.id.toggleKey);
        channel.setText(prefs.getString("channel", ""));
        readKey.setText(prefs.getString("read_key", ""));
        toggle.setOnClickListener(v -> toggleKey());
        findViewById(R.id.save).setOnClickListener(v -> save());
        findViewById(R.id.cancel).setOnClickListener(v -> finish());
    }

    private void toggleKey() {
        boolean visible = readKey.getInputType() == (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        readKey.setInputType(InputType.TYPE_CLASS_TEXT | (visible ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD));
        toggle.setText(visible ? "TAMPILKAN" : "SEMBUNYIKAN");
        readKey.setSelection(readKey.length());
    }

    private void save() {
        String ch = channel.getText().toString().trim();
        String key = readKey.getText().toString().trim();
        if (ch.isEmpty()) {
            channel.setError("Channel ID wajib diisi");
            return;
        }
        prefs.edit().putString("channel", ch).putString("read_key", key).apply();
        Toast.makeText(this, "Pengaturan tersimpan", Toast.LENGTH_SHORT).show();
        finish();
    }
}
