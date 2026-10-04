package id.turus.stasiuncuaca;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Historical weather analysis for farmer-friendly summaries. */
public final class HistoryAnalysis {
    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");
    private HistoryAnalysis() {}

    public static final class Result {
        public int days;
        public int thingSpeakCount;
        public int openWeatherCount;
        public boolean openWeatherRemoteUsed;
        public double tempAvg = Double.NaN, tempMax = Double.NaN;
        public double humidityAvg = Double.NaN;
        public double rainTotal = Double.NaN;
        public double windAvg = Double.NaN;
        public double windMax = Double.NaN;
        public double windDir = Double.NaN;
        public String windDirSource = "";
        public String summary = "";

        public boolean hasWeather() {
            return thingSpeakCount > 0 || openWeatherCount > 0;
        }
    }

    private static final class Point {
        long time;
        double temp = Double.NaN, humidity = Double.NaN, rain = Double.NaN,
                wind = Double.NaN, windDeg = Double.NaN;
        Point() {}
    }

    public static Result analyze(SharedPreferences prefs, String channel, String readKey) throws Exception {
        int days = Math.max(1, Math.min(7, prefs.getInt("analysis_days", 3)));
        Result r = new Result();
        r.days = days;
        List<Point> ts = new ArrayList<>();
        List<Point> ow = new ArrayList<>();

        if (channel != null && !channel.trim().isEmpty()) {
            ts = fetchThingSpeak(prefs, channel.trim(), readKey == null ? "" : readKey.trim(), days);
            r.thingSpeakCount = ts.size();
        }

        String owKey = prefs.getString("openweather_api_key", "").trim();
        double lat = parse(prefs.getString("gps_lat", ""));
        double lon = parse(prefs.getString("gps_lon", ""));
        if (prefs.getBoolean("openweather_enabled", false) && !owKey.isEmpty() && !Double.isNaN(lat) && !Double.isNaN(lon)) {
            try {
                ow = fetchOpenWeatherHistory(owKey, lat, lon, days);
                r.openWeatherRemoteUsed = !ow.isEmpty();
            } catch (Exception ignored) {
                ow = new ArrayList<>();
            }
        }
        if (ow.isEmpty()) ow = readLocalOpenWeatherHistory(prefs, days);
        r.openWeatherCount = ow.size();

        // Prefer field-level local station history for weather measurements; OpenWeather complements it.
        List<Point> combined = new ArrayList<>();
        combined.addAll(ts);
        if (combined.isEmpty()) combined.addAll(ow);
        else combined.addAll(ow);
        if (combined.isEmpty()) {
            r.summary = "Belum cukup data riwayat untuk dianalisis.";
            return r;
        }

        r.tempAvg = avg(combined, "temp");
        r.tempMax = max(combined, "temp");
        r.humidityAvg = avg(combined, "humidity");
        r.rainTotal = sumRain(combined);
        r.windAvg = avg(combined, "wind");
        r.windMax = max(combined, "wind");
        r.windDir = circularMean(combined);
        r.windDirSource = !ts.isEmpty() && containsWindDir(ts) ? "ThingSpeak" : (!ow.isEmpty() && containsWindDir(ow) ? "OpenWeather" : "");
        r.summary = buildSummary(prefs, r, ts, ow);
        return r;
    }

    public static void saveOpenWeatherSnapshot(SharedPreferences prefs, WeatherExtras.WeatherData d) {
        if (d == null) return;
        try {
            JSONArray a;
            String old = prefs.getString("ow_history_json", "");
            a = old.isEmpty() ? new JSONArray() : new JSONArray(old);
            JSONObject o = new JSONObject();
            o.put("dt", d.dt > 0 ? d.dt * 1000L : System.currentTimeMillis());
            put(o, "temp", d.temp); put(o, "humidity", d.humidity); put(o, "pressure", d.pressure);
            put(o, "wind", d.windMs); put(o, "windDeg", d.windDeg); put(o, "rain", d.rain1h); put(o, "cloud", d.cloudPct);
            a.put(o);
            long cutoff = System.currentTimeMillis() - 8L * 24L * 60L * 60L * 1000L;
            JSONArray keep = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                JSONObject x = a.optJSONObject(i); if (x == null) continue;
                long t = x.optLong("dt", 0L);
                if (t >= cutoff) keep.put(x);
            }
            while (keep.length() > 500) {
                JSONArray newer = new JSONArray();
                for (int i = Math.max(0, keep.length() - 500); i < keep.length(); i++) newer.put(keep.get(i));
                keep = newer;
            }
            prefs.edit().putString("ow_history_json", keep.toString()).apply();
        } catch (Exception ignored) { }
    }

    private static List<Point> fetchThingSpeak(SharedPreferences prefs, String channel, String key, int days) throws Exception {
        StringBuilder u = new StringBuilder("https://api.thingspeak.com/channels/")
                .append(URLEncoder.encode(channel, "UTF-8"))
                .append("/feeds.json?days=").append(days)
                .append("&results=8000&timezone=Asia%2FJakarta&status=true");
        if (!key.isEmpty()) u.append("&api_key=").append(URLEncoder.encode(key, "UTF-8"));
        JSONObject root = getJson(u.toString(), 9000, 12000);
        JSONArray feeds = root.optJSONArray("feeds");
        List<Point> list = new ArrayList<>();
        if (feeds == null) return list;
        for (int i = 0; i < feeds.length(); i++) {
            JSONObject f = feeds.optJSONObject(i); if (f == null) continue;
            Point p = new Point();
            try { p.time = Instant.parse(f.optString("created_at", "")).toEpochMilli(); } catch (Exception ignored) {}
            for (int field = 1; field <= 8; field++) {
                String name = fieldName(prefs, field);
                double v = parse(f.optString("field" + field, ""));
                if (Double.isNaN(v)) continue;
                String n = normalize(name);
                if (Double.isNaN(p.temp) && looks(n, "suhu", "temperature", "temp")) p.temp = v;
                else if (Double.isNaN(p.humidity) && looks(n, "kelembapan", "humidity", "rh")) p.humidity = v;
                else if (Double.isNaN(p.rain) && looks(n, "hujan", "rain", "curah", "precip")) p.rain = v;
                else if (Double.isNaN(p.windDeg) && (looks(n, "arahangin", "winddirection", "direction") || n.contains("derajatangin"))) p.windDeg = v;
                else if (Double.isNaN(p.wind) && looks(n, "kecepatanangin", "windspeed", "angin")) p.wind = v;
            }
            // avoid common false match where plain "angin" is wind direction; direction wins above.
            list.add(p);
        }
        return list;
    }

    private static List<Point> fetchOpenWeatherHistory(String key, double lat, double lon, int days) throws Exception {
        long end = System.currentTimeMillis() / 1000L;
        long start = end - Math.min(days, 7) * 86400L;
        String u = "https://history.openweathermap.org/data/2.5/history/city?lat=" + lat + "&lon=" + lon
                + "&type=hour&start=" + start + "&end=" + end
                + "&units=metric&appid=" + URLEncoder.encode(key, "UTF-8");
        JSONObject root = getJson(u, 9000, 12000);
        JSONArray arr = root.optJSONArray("list");
        if (arr == null) arr = root.optJSONArray("data");
        List<Point> out = new ArrayList<>();
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject x = arr.optJSONObject(i); if (x == null) continue;
            Point p = new Point(); p.time = x.optLong("dt", 0L) * 1000L;
            JSONObject m = x.optJSONObject("main"); JSONObject w = x.optJSONObject("wind"); JSONObject rain = x.optJSONObject("rain");
            if (m != null) { p.temp = m.optDouble("temp", Double.NaN); p.humidity = m.optDouble("humidity", Double.NaN); }
            if (w != null) { p.wind = w.optDouble("speed", Double.NaN); p.windDeg = w.optDouble("deg", Double.NaN); }
            if (rain != null) p.rain = rain.optDouble("1h", Double.NaN);
            out.add(p);
        }
        return out;
    }

    private static List<Point> readLocalOpenWeatherHistory(SharedPreferences prefs, int days) throws Exception {
        List<Point> out = new ArrayList<>();
        String old = prefs.getString("ow_history_json", ""); if (old.isEmpty()) return out;
        JSONArray a = new JSONArray(old); long cutoff = System.currentTimeMillis() - days * 86400L * 1000L;
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i); if (x == null || x.optLong("dt", 0L) < cutoff) continue;
            Point p = new Point(); p.time = x.optLong("dt", 0L); p.temp = x.optDouble("temp", Double.NaN);
            p.humidity = x.optDouble("humidity", Double.NaN); p.wind = x.optDouble("wind", Double.NaN);
            p.windDeg = x.optDouble("windDeg", Double.NaN); p.rain = x.optDouble("rain", Double.NaN); out.add(p);
        }
        return out;
    }

    private static String buildSummary(SharedPreferences prefs, Result r, List<Point> ts, List<Point> ow) {
        StringBuilder s = new StringBuilder();
        s.append("RIWAYAT ").append(r.days).append(" HARI\n");
        s.append("Data ThingSpeak: ").append(r.thingSpeakCount).append(" titik");
        if (r.openWeatherCount > 0) s.append(" • OpenWeather: ").append(r.openWeatherCount).append(" titik");
        if (r.openWeatherRemoteUsed) s.append(" (riwayat OpenWeather dari layanan)");
        else if (r.openWeatherCount > 0) s.append(" (riwayat OpenWeather tersimpan di HP)");
        s.append("\n");
        if (!Double.isNaN(r.tempAvg)) s.append(String.format(Locale.US, "Suhu rata-rata %.1f °C", r.tempAvg));
        if (!Double.isNaN(r.tempMax)) s.append(String.format(Locale.US, " • tertinggi %.1f °C", r.tempMax));
        s.append("\n");
        if (!Double.isNaN(r.humidityAvg)) s.append(String.format(Locale.US, "Kelembapan udara rata-rata %.0f %%\n", r.humidityAvg));
        if (!Double.isNaN(r.rainTotal)) s.append(String.format(Locale.US, "Total nilai hujan yang terbaca %.1f mm\n", Math.max(0, r.rainTotal)));
        if (!Double.isNaN(r.windAvg)) s.append(String.format(Locale.US, "Angin rata-rata %.1f m/s", r.windAvg));
        if (!Double.isNaN(r.windMax)) s.append(String.format(Locale.US, " • tertinggi %.1f m/s", r.windMax));
        s.append("\n");
        if (!Double.isNaN(r.windDir)) s.append(String.format(Locale.US, "Arah angin dominan %s (%.0f°) — arah menunjukkan dari mana angin datang.\n", directionText(r.windDir), r.windDir));

        s.append("\nPENILAIAN RISIKO OPT (indikasi umum, bukan diagnosis)\n");
        boolean humid = !Double.isNaN(r.humidityAvg) && r.humidityAvg >= 80;
        boolean wet = !Double.isNaN(r.rainTotal) && r.rainTotal >= Math.max(2, r.days * 1.0);
        boolean windy = !Double.isNaN(r.windAvg) && r.windAvg >= 2.5;
        boolean strongWind = !Double.isNaN(r.windMax) && r.windMax >= 6.0;
        if ((humid && wet) || (humid && windy)) s.append("• Risiko kondisi yang mendukung penyakit tanaman: SEDANG–TINGGI. Periksa daun, bunga, dan buah lebih sering, terutama setelah hujan.\n");
        else if (humid || wet) s.append("• Risiko kondisi basah/lembap: SEDANG. Periksa gejala pada bagian tanaman yang sering basah.\n");
        else s.append("• Risiko dari pola basah/lembap: RENDAH berdasarkan data yang tersedia.\n");
        if (windy && !Double.isNaN(r.windDir)) s.append("• Potensi penyebaran melalui angin: MENINGKAT. Arah angin dominan dari ").append(directionText(r.windDir)).append("; periksa sisi lahan dari arah tersebut terlebih dahulu.\n");
        else if (windy) s.append("• Angin cukup aktif. Pemantauan hama yang dapat berpindah melalui angin perlu ditingkatkan.\n");
        if (strongWind) s.append("• Angin kencang terdeteksi. Periksa kerusakan daun/cabang dan jangan melakukan penyemprotan saat angin kuat.\n");
        if ((!Double.isNaN(r.tempMax) && r.tempMax >= 34) && (!Double.isNaN(r.humidityAvg) && r.humidityAvg < 60))
            s.append("• Pola panas dan kering: tanaman berisiko kekurangan air dan beberapa hama lebih mudah berkembang. Periksa daun dan kondisi tanah.\n");
        s.append("Catatan: AI akan menggabungkan pola riwayat ini dengan nama/umur tanaman dan data tanah yang tersedia sebelum memberikan tindakan.");
        return s.toString();
    }

    private static double avg(List<Point> a, String kind) { double sum=0; int n=0; for(Point p:a){double v=value(p,kind); if(!Double.isNaN(v)){sum+=v;n++;}} return n==0?Double.NaN:sum/n; }
    private static double max(List<Point> a, String kind) { double m=Double.NEGATIVE_INFINITY; boolean ok=false; for(Point p:a){double v=value(p,kind); if(!Double.isNaN(v)){m=Math.max(m,v);ok=true;}} return ok?m:Double.NaN; }
    private static double value(Point p,String k){ switch(k){case "temp":return p.temp;case "humidity":return p.humidity;case "wind":return p.wind;default:return Double.NaN;} }
    private static double sumRain(List<Point> a) {double s=0;boolean ok=false;for(Point p:a)if(!Double.isNaN(p.rain)){s+=Math.max(0,p.rain);ok=true;}return ok?s:Double.NaN;}
    private static boolean containsWindDir(List<Point> a){for(Point p:a)if(!Double.isNaN(p.windDeg))return true;return false;}
    private static double circularMean(List<Point> a){double x=0,y=0;int n=0;for(Point p:a)if(!Double.isNaN(p.windDeg)){double r=Math.toRadians(p.windDeg);x+=Math.cos(r);y+=Math.sin(r);n++;}if(n==0)return Double.NaN;double d=Math.toDegrees(Math.atan2(y,x));return d<0?d+360:d;}

    public static String directionText(double deg) {
        String[] dirs={"Utara","Timur Laut","Timur","Tenggara","Selatan","Barat Daya","Barat","Barat Laut"};
        int idx=(int)Math.floor((deg+22.5)/45.0)%8; return dirs[idx];
    }

    private static String fieldName(SharedPreferences prefs,int field){
        String n=prefs.getString("field_name_"+field,"").trim();
        if(n.isEmpty()) n=prefs.getString("ts_field_name_"+field,"").trim();
        return n.isEmpty()?"Field "+field:n;
    }
    private static String normalize(String s){return s==null?"":s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]","");}
    private static boolean looks(String s,String... terms){for(String t:terms)if(s.contains(t))return true;return false;}
    private static double parse(String s){if(s==null||s.trim().isEmpty())return Double.NaN;try{return Double.parseDouble(s.trim().replace(',','.'));}catch(Exception e){return Double.NaN;}}
    private static void put(JSONObject o,String k,double v)throws Exception{if(!Double.isNaN(v))o.put(k,v);}
    private static JSONObject getJson(String url,int connect,int read)throws Exception{HttpURLConnection c=null;try{c=(HttpURLConnection)new URL(url).openConnection();c.setRequestMethod("GET");c.setConnectTimeout(connect);c.setReadTimeout(read);c.setUseCaches(false);int code=c.getResponseCode();InputStream in=code>=200&&code<300?c.getInputStream():c.getErrorStream();String body=readAll(in);if(code<200||code>=300)throw new Exception("HTTP "+code+(body.isEmpty()?"":" — "+body));return new JSONObject(body);}finally{if(c!=null)c.disconnect();}}
    private static String readAll(InputStream in)throws Exception{if(in==null)return "";StringBuilder s=new StringBuilder();try(BufferedReader r=new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))){String line;while((line=r.readLine())!=null)s.append(line);}return s.toString();}
}
