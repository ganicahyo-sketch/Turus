package id.turus.stasiuncuaca;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SoilActivity extends Activity {
    private static final String PREFS = "thingspeak_config";
    private static final int REQ_USB = 2407;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private android.content.SharedPreferences prefs;
    private EditText moisture, soilTemp, ph, ec, n, p, k, slaveAddress, startRegister;
    private Spinner source, baud, registerProfile;
    private TextView status, analysis;
    private UsbSerialPort activePort;
    private UsbDeviceConnection activeConnection;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_soil);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        moisture = findViewById(R.id.soilMoisture);
        soilTemp = findViewById(R.id.soilTemperature);
        ph = findViewById(R.id.soilPh);
        ec = findViewById(R.id.soilEc);
        n = findViewById(R.id.soilN);
        p = findViewById(R.id.soilP);
        k = findViewById(R.id.soilK);
        slaveAddress = findViewById(R.id.soilSlaveAddress);
        startRegister = findViewById(R.id.soilStartRegister);
        source = findViewById(R.id.soilSource);
        baud = findViewById(R.id.soilBaud);
        registerProfile = findViewById(R.id.soilRegisterProfile);
        status = findViewById(R.id.soilStatus);
        analysis = findViewById(R.id.soilAnalysis);

        setupSpinner(source, new String[]{"Input manual", "Sensor USB / RS485 melalui OTG"}, prefs.getInt("soil_source", 0));
        setupSpinner(baud, new String[]{"4800", "9600", "19200"}, prefs.getInt("soil_baud_index", 0));
        setupSpinner(registerProfile, new String[]{
                "Umum: register 0x0000–0x0006",
                "Umum: register 0x0030–0x0036"
        }, prefs.getInt("soil_reg_profile", 0));
        loadSaved();

        findViewById(R.id.readSoilUsb).setOnClickListener(v -> readUsbSensor());
        findViewById(R.id.saveSoil).setOnClickListener(v -> saveSoilAndAnalyze());
        findViewById(R.id.soilSettings).setOnClickListener(v -> showSimpleHelp());
    }

    private void setupSpinner(Spinner s, String[] items, int pos) {
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, items);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        s.setSelection(Math.max(0, Math.min(pos, items.length - 1)));
    }

    private void loadSaved() {
        moisture.setText(prefs.getString("soil_moisture", ""));
        soilTemp.setText(prefs.getString("soil_temp", ""));
        ph.setText(prefs.getString("soil_ph", ""));

        String savedUs = prefs.getString("soil_ec_us", "");
        if (savedUs.isEmpty()) {
            String oldMs = prefs.getString("soil_ec_ms", "");
            if (!oldMs.isEmpty()) {
                try {
                    savedUs = number(Double.parseDouble(oldMs.replace(',', '.')) * 1000.0, 0);
                    prefs.edit().putString("soil_ec_us", savedUs).apply();
                } catch (Exception ignored) { }
            }
        }
        ec.setText(savedUs);
        n.setText(prefs.getString("soil_n", ""));
        p.setText(prefs.getString("soil_p", ""));
        k.setText(prefs.getString("soil_k", ""));
        refreshAnalysis(false);
    }

    private void saveSoilAndAnalyze() {
        try {
            double mo = parseRequired(moisture, "Kelembapan tanah");
            double st = parseRequired(soilTemp, "Suhu tanah");
            double phv = parseRequired(ph, "pH tanah");
            double ecv = parseRequired(ec, "Kadar garam tanah");
            double nv = parseRequired(n, "Nitrogen");
            double pv = parseRequired(p, "Fosfor");
            double kv = parseRequired(k, "Kalium");
            if (mo < 0 || mo > 100 || phv < 0 || phv > 14 || ecv < 0 || nv < 0 || pv < 0 || kv < 0) {
                throw new IllegalArgumentException("Ada nilai di luar batas yang wajar.");
            }
            prefs.edit()
                    .putString("soil_moisture", number(mo,1))
                    .putString("soil_temp", number(st,1))
                    .putString("soil_ph", number(phv,2))
                    .putString("soil_ec_us", number(ecv,0))
                    .putString("soil_n", number(nv,0))
                    .putString("soil_p", number(pv,0))
                    .putString("soil_k", number(kv,0))
                    .putString("soil_slave_address", slaveAddress.getText().toString().trim())
                    .putString("soil_start_register", startRegister.getText().toString().trim())
                    .putInt("soil_source", source.getSelectedItemPosition())
                    .putInt("soil_baud_index", baud.getSelectedItemPosition())
                    .putInt("soil_reg_profile", registerProfile.getSelectedItemPosition())
                    .apply();
            refreshAnalysis(true);
            Toast.makeText(this, "Data tanah tersimpan dan dianalisis", Toast.LENGTH_SHORT).show();
        } catch (Exception ex) {
            Toast.makeText(this, ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private double parseRequired(EditText e, String name) {
        String s = e.getText().toString().trim().replace(',', '.');
        if (s.isEmpty()) throw new IllegalArgumentException(name + " belum diisi.");
        return Double.parseDouble(s);
    }

    private String number(double v, int d) { return String.format(Locale.US, "%." + d + "f", v); }

    private void refreshAnalysis(boolean saved) {
        try {
            double mo = Double.parseDouble(moisture.getText().toString().trim().replace(',','.'));
            double st = Double.parseDouble(soilTemp.getText().toString().trim().replace(',','.'));
            double phv = Double.parseDouble(ph.getText().toString().trim().replace(',','.'));
            double ecv = Double.parseDouble(ec.getText().toString().trim().replace(',','.'));
            double nv = Double.parseDouble(n.getText().toString().trim().replace(',','.'));
            double pv = Double.parseDouble(p.getText().toString().trim().replace(',','.'));
            double kv = Double.parseDouble(k.getText().toString().trim().replace(',','.'));

            StringBuilder sb = new StringBuilder();
            sb.append("HASIL PEMERIKSAAN TANAH\n");
            sb.append(soilMoistureAdvice(mo)).append("\n");
            sb.append(soilTempAdvice(st)).append("\n");
            sb.append(phAdvice(phv)).append("\n");
            sb.append(ecAdvice(ecv)).append("\n");
            sb.append(npkAdvice("Nitrogen", nv, 20, 40)).append("\n");
            sb.append(npkAdvice("Fosfor", pv, 15, 30)).append("\n");
            sb.append(npkAdvice("Kalium", kv, 120, 250)).append("\n\n");
            sb.append("TINDAKAN YANG DISARANKAN\n");
            if (mo < 25) sb.append("• Periksa tanah dan lakukan penyiraman bila tanaman membutuhkan air.\n");
            else if (mo > 80) sb.append("• Kurangi pemberian air dan periksa pembuangan air agar akar tidak terlalu basah.\n");
            else sb.append("• Kelembapan tanah cukup; lanjutkan pemantauan.\n");
            if (phv < 5.5) sb.append("• Tanah cenderung terlalu asam. Periksa rekomendasi pengapuran sesuai jenis tanaman dan hasil uji tanah.\n");
            else if (phv > 7.5) sb.append("• Tanah cenderung basa. Hindari menambah bahan pengubah pH tanpa pemeriksaan lanjutan.\n");
            if (ecv > 3000) sb.append("• Kadar garam tanah cukup tinggi; kurangi pemupukan sementara dan periksa sumber air/garam.\n");
            if (nv < 20 || pv < 15 || kv < 120) sb.append("• Ada unsur hara yang rendah. Jangan langsung memberi pupuk dalam jumlah besar; sesuaikan dengan kebutuhan tanaman dan, untuk keputusan pemupukan penting, konfirmasi dengan analisis tanah laboratorium.\n");
            if (saved) status.setText("Data tanah tersimpan • siap dipakai Saran AI");
            analysis.setText(sb.toString());
        } catch (Exception ignored) {
            analysis.setText("Isi 7 nilai tanah untuk melihat analisis dan rekomendasi.");
        }
    }

    private String soilMoistureAdvice(double v) {
        if (v < 25) return "Kelembapan tanah: RENDAH — tanah cenderung kering.";
        if (v > 80) return "Kelembapan tanah: TINGGI — tanah sangat basah.";
        return "Kelembapan tanah: CUKUP — kondisi air tanah masih perlu dipantau.";
    }
    private String soilTempAdvice(double v) {
        if (v < 15) return "Suhu tanah: RENDAH — aktivitas akar dapat melambat.";
        if (v > 35) return "Suhu tanah: TINGGI — akar dapat mengalami tekanan panas.";
        return "Suhu tanah: NORMAL untuk banyak tanaman.";
    }
    private String phAdvice(double v) {
        if (v < 5.5) return String.format(Locale.US, "Keasaman tanah (pH %.2f): TERLALU ASAM untuk banyak tanaman.", v);
        if (v > 7.5) return String.format(Locale.US, "Keasaman tanah (pH %.2f): CENDERUNG BASA.", v);
        return String.format(Locale.US, "Keasaman tanah (pH %.2f): UMUMNYA SESUAI untuk banyak tanaman.", v);
    }
    private String ecAdvice(double v) {
        if (v < 150) return "Kadar garam tanah: RENDAH — periksa kebutuhan pemupukan tanaman.";
        if (v > 3000) return "Kadar garam tanah: TINGGI — waspadai penumpukan garam.";
        return "Kadar garam tanah: dalam kisaran pemantauan umum.";
    }
    private String npkAdvice(String name, double v, double low, double high) {
        if (v < low) return String.format(Locale.US, "%s: RENDAH (%.0f mg/kg) — perlu perhatian.", name, v);
        if (v > high) return String.format(Locale.US, "%s: TINGGI (%.0f mg/kg) — jangan menambah pupuk unsur ini sebelum diperiksa lagi.", name, v);
        return String.format(Locale.US, "%s: CUKUP (%.0f mg/kg) menurut batas awal aplikasi.", name, v);
    }

    private void readUsbSensor() {
        UsbManager usbManager = (UsbManager)getSystemService(Context.USB_SERVICE);
        List<UsbSerialDriver> drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager);
        if (drivers.isEmpty()) {
            status.setText("USB tidak terdeteksi. Pastikan HP mendukung USB-OTG dan sensor/konverter tersambung.");
            return;
        }
        UsbSerialDriver driver = drivers.get(0);
        UsbDevice device = driver.getDevice();
        if (!usbManager.hasPermission(device)) {
            PendingUsbPermission.request(this, usbManager, device, REQ_USB, () -> {
                if (usbManager.hasPermission(device)) {
                    status.setText("Izin USB diterima • membaca sensor...");
                    readUsbSensor();
                } else {
                    status.setText("Izin USB tidak diberikan.");
                }
            });
            status.setText("Menunggu izin USB...");
            return;
        }
        io.execute(() -> {
            try {
                UsbDeviceConnection conn = usbManager.openDevice(device);
                if (conn == null) throw new Exception("Tidak dapat membuka perangkat USB.");
                UsbSerialPort port = driver.getPorts().get(0);
                port.open(conn);
                activePort = port;
                activeConnection = conn;
                int b = Integer.parseInt(baud.getSelectedItem().toString());
                port.setParameters(b, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
                int slave = parseIntFlexible(slaveAddress.getText().toString(), 1);
                int start = parseIntFlexible(startRegister.getText().toString(), registerProfile.getSelectedItemPosition() == 0 ? 0x0000 : 0x0030);
                if (slave < 1 || slave > 247) throw new Exception("Alamat sensor harus 1 sampai 247.");
                if (start < 0 || start > 0xFFFF) throw new Exception("Register awal tidak valid.");
                byte[] request = buildModbusRequest(slave, start, 7);
                port.write(request, 1000);
                byte[] frame = readModbusFrame(port, 19, 3000);
                SoilValues values = parseFrame(frame, frame.length);
                runOnUiThread(() -> fillSensorValues(values));
            } catch (Exception ex) {
                runOnUiThread(() -> status.setText("Gagal membaca sensor USB/RS485: " + ex.getMessage()));
            } finally {
                try { if (activePort != null) activePort.close(); } catch (Exception ignored) {}
                if (activeConnection != null) activeConnection.close();
                activePort = null; activeConnection = null;
            }
        });
    }

    private int parseIntFlexible(String value, int fallback) {
        String s = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return fallback;
        try {
            if (s.startsWith("0x")) return Integer.parseInt(s.substring(2), 16);
            return Integer.parseInt(s);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Nilai angka tidak valid: " + value);
        }
    }

    private byte[] readModbusFrame(UsbSerialPort port, int expectedLength, int timeoutMs) throws Exception {
        byte[] buffer = new byte[128];
        int used = 0;
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            byte[] chunk = new byte[64];
            int n = port.read(chunk, 700);
            if (n > 0) {
                if (used + n > buffer.length) throw new Exception("Respons sensor terlalu besar.");
                System.arraycopy(chunk, 0, buffer, used, n);
                used += n;
                if (used >= expectedLength) {
                    int byteCount = buffer[2] & 0xFF;
                    int total = byteCount + 5;
                    if (total >= 7 && used >= total) {
                        byte[] out = new byte[total];
                        System.arraycopy(buffer, 0, out, 0, total);
                        return out;
                    }
                }
            }
        }
        throw new Exception("Tidak menerima data sensor sampai batas waktu.");
    }

    private byte[] buildModbusRequest(int address, int start, int count) {
        byte[] b = new byte[]{(byte)address, 0x03, (byte)(start >> 8), (byte)start, (byte)(count >> 8), (byte)count, 0, 0};
        int crc = crc16(b, 6); b[6] = (byte)(crc & 0xff); b[7] = (byte)((crc >> 8) & 0xff); return b;
    }

    private int crc16(byte[] data, int len) {
        int crc=0xFFFF;
        for(int i=0;i<len;i++){ crc ^= (data[i]&0xFF); for(int j=0;j<8;j++) crc=((crc&1)!=0)?((crc>>1)^0xA001):(crc>>1); }
        return crc;
    }

    private SoilValues parseFrame(byte[] f, int len) throws Exception {
        if (len < 19) throw new Exception("Respons sensor terlalu pendek.");
        int expectedSlave = parseIntFlexible(slaveAddress.getText().toString(), 1);
        if ((f[0]&0xff) != expectedSlave || (f[1]&0xff) != 0x03) throw new Exception("Alamat atau format Modbus tidak sesuai.");
        if ((f[2]&0xff) < 14 || len < 19) throw new Exception("Data 7 parameter belum lengkap.");
        int expected = crc16(f, len-2);
        int got = (f[len-2]&0xff) | ((f[len-1]&0xff)<<8);
        if (expected != got) throw new Exception("CRC Modbus tidak cocok.");
        int[] r = new int[7];
        for(int i=0;i<7;i++) r[i]=((f[3+i*2]&0xff)<<8)|(f[4+i*2]&0xff);
        double tempSigned = (short)r[1] / 10.0;
        double moistureVal = r[0] / 10.0;
        double ecUs = r[2];
        double phVal = (registerProfile.getSelectedItemPosition() == 1) ? (r[3] / 100.0) : (r[3] / 10.0);
        return new SoilValues(moistureVal,tempSigned,phVal,ecUs,r[4],r[5],r[6]);
    }

    private void fillSensorValues(SoilValues v) {
        moisture.setText(number(v.moisture,1)); soilTemp.setText(number(v.temp,1)); ph.setText(number(v.ph,2));
        ec.setText(number(v.ecUs,0)); n.setText(number(v.n,0)); p.setText(number(v.p,0)); k.setText(number(v.k,0));
        status.setText("Sensor terbaca melalui USB/RS485 • data belum tersimpan sampai tombol SIMPAN ditekan.");
        refreshAnalysis(false);
    }

    private void showSimpleHelp() {
        status.setText("Satuan input: kelembapan %, suhu tanah °C, pH, kadar garam tanah µS/cm, N-P-K mg/kg. Gunakan langsung angka dari sensor, misalnya 1500 µS/cm.");
    }

    @Override protected void onDestroy() {
        super.onDestroy(); io.shutdownNow();
        try { if (activePort != null) activePort.close(); } catch (Exception ignored) {}
        if (activeConnection != null) activeConnection.close();
    }

    static class SoilValues { final double moisture,temp,ph,ecUs,n,p,k; SoilValues(double mo,double t,double ph,double ec,double n,double p,double k){this.moisture=mo;this.temp=t;this.ph=ph;this.ecUs=ec;this.n=n;this.p=p;this.k=k;} }
}
