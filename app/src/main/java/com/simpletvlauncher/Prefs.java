package com.simpletvlauncher;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Все настройки лаунчера в одном месте. */
public class Prefs {
    public static final String[] THEME_NAMES = {"Пурпур", "Океан", "Изумруд", "Графит", "Закат", "Индиго", "Бордо", "Бирюза"};
    public static final int[] THEME_COLORS = {0xFF9B2F7D, 0xFF2F6FB0, 0xFF2E9B6B, 0xFF6B6B6B, 0xFFC4572F, 0xFF4F46C8, 0xFF9C2A3A, 0xFF1F9AA8};

    private final SharedPreferences sp;
    private final Context ctx;

    public Prefs(Context c) {
        ctx = c.getApplicationContext();
        sp = ctx.getSharedPreferences("glasstv", Context.MODE_PRIVATE);
        // Кнопка источника сигнала теперь по умолчанию зависит от наличия HDMI-входов. Тем, кто обновился с прошлой версии
        // и ничего не меняло, кнопка остаётся видимой, как и была.
        if (!sp.contains("srcInit")) {
            SharedPreferences.Editor e = sp.edit().putBoolean("srcInit", true);
            try {
                android.content.pm.PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
                if (pi.lastUpdateTime - pi.firstInstallTime > 60000L && !sp.contains("srcHide")) e.putBoolean("srcHide", false);
            } catch (Throwable ignored) {}
            e.apply();
        }
        if (sp.contains("ambBlur")) sp.edit().remove("ambPat").remove("ambPat2").remove("ambPat3").remove("ambSpeed").remove("ambBlur").apply();    // настройки удалённого режима «Эмбиент»
        syncUnits();
    }

    public SharedPreferences raw() { return sp; }

    // ---- единицы измерения погоды ----
    /** 0 авто (по региону системы), 1 метрические, 2 США, 3 Великобритания. */
    public int unitsPref() { return Math.max(0, Math.min(3, sp.getInt("units", 0))); }
    public void setUnitsPref(int v) { sp.edit().putInt("units", v).apply(); syncUnits(); }
    /** Переносит выбор в Weather.units (0 метрические, 1 США, 2 Великобритания). */
    private void syncUnits() {
        int u = unitsPref();
        if (u == 0) {
            String cc = java.util.Locale.getDefault().getCountry();
            u = "US".equals(cc) || "LR".equals(cc) || "MM".equals(cc) ? 2 : "GB".equals(cc) ? 3 : 1;
        }
        Weather.units = u - 1;
    }

    public static final String[] BG_MODES = {"Градиент", "Изображение", "Видеофайл"};
    /** 0 градиент, 1 картинка, 2 видео. Настройки старых версий переносятся в новую нумерацию. */
    public int bgMode() {
        if (sp.contains("bgMode2")) return Math.max(0, Math.min(2, sp.getInt("bgMode2", 0)));
        int old = sp.getInt("bgMode", 0);
        return old == 2 ? 1 : old == 3 ? 2 : 0;
    }
    public void setBgMode(int v) { sp.edit().putInt("bgMode2", v).apply(); }
    /** Тот же фон (размытый) на экранах погоды, приложений и настроек. */
    public boolean bgAll() { return true; }      // тот же фон, размытый, на остальных экранах — всегда
    /** Кнопка «Домой» ведёт в этот лаунчер (через службу спецвозможностей; по умолчанию да). */
    public boolean homeKey() { return sp.getBoolean("homeKey", true); }
    public void setHomeKey(boolean v) { sp.edit().putBoolean("homeKey", v).apply(); }
    /** Открывать лаунчер при включении ТВ (по умолчанию да). */
    public boolean bootHome() { return sp.getBoolean("bootHome", true); }
    public void setBootHome(boolean v) { sp.edit().putBoolean("bootHome", v).apply(); }
    /** Приложение, которое запускается при включении устройства (пакет; пусто — не запускать). */
    public String autoApp() { return sp.getString("autoApp", ""); }
    public void setAutoApp(String v) { sp.edit().putString("autoApp", v).apply(); }
    /** Открывать приложение автозапуска и после выхода устройства из сна (по умолчанию нет). */
    public boolean showTitle() { return sp.getBoolean("showTitle", true); }
    public void setShowTitle(boolean v) { sp.edit().putBoolean("showTitle", v).apply(); }
    public boolean showHdrs() { return sp.getBoolean("showHdrs", true); }
    public void setShowHdrs(boolean v) { sp.edit().putBoolean("showHdrs", v).apply(); }
    public boolean wakeApp() { return sp.getBoolean("wakeApp", false); }
    public void setWakeApp(boolean v) { sp.edit().putBoolean("wakeApp", v).apply(); }
    /** 12-часовой формат времени. Пока пользователь не выбрал сам — включён с английским языком приложения и выключен с русским. */
    public boolean clock12() { return sp.contains("clock12") ? sp.getBoolean("clock12", false) : L.en(); }
    public void setClock12(boolean v) { sp.edit().putBoolean("clock12", v).apply(); }
    /** На какой загрузке устройства автозапуск уже отработал (повторный старт лаунчера на той же загрузке его не повторяет). */
    public String autoBootDone() { return sp.getString("autoBootDone", ""); }
    public void setAutoBootDone(String v) { sp.edit().putString("autoBootDone", v).apply(); }
    public int cols() { return Math.max(4, Math.min(10, sp.getInt("cols", 7))); }
    public void setCols(int v) { sp.edit().putInt("cols", v).apply(); }

    public String vpnPkg() { return sp.getString("vpnPkg", ""); }
    public void setVpnPkg(String v) { sp.edit().putString("vpnPkg", v).apply(); }
    /**
     * Кнопка выбора источника сигнала. Пока пользователь не выбрал сам, она видна только там, где есть HDMI-входы
     * (на телевизорах); на приставках, у которых портов нет, скрыта.
     */
    public boolean showSourceBtn() {
        if (sp.contains("srcHide")) return !sp.getBoolean("srcHide", false);
        return hasHdmiInput(ctx);
    }
    public void setShowSourceBtn(boolean v) { sp.edit().putBoolean("srcHide", !v).apply(); }

    public boolean showInputsRow() { return sp.getBoolean("showInputsRow", false); }
    public void setShowInputsRow(boolean v) { sp.edit().putBoolean("showInputsRow", v).apply(); }

    private static long hdmiAt;
    private static boolean hdmiVal;

    /** Есть ли у устройства HDMI-вход (телевизор). Результат запоминается; «нет» перепроверяется раз в минуту — вход мог ещё не успеть появиться при загрузке. */
    static boolean hasHdmiInput(Context c) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (hdmiAt != 0 && (hdmiVal || now - hdmiAt < 60000L)) return hdmiVal;
        boolean r = false;
        try {
            android.media.tv.TvInputManager m = (android.media.tv.TvInputManager) c.getSystemService(Context.TV_INPUT_SERVICE);
            if (m != null) for (android.media.tv.TvInputInfo i : m.getTvInputList()) {
                if (i.getType() == android.media.tv.TvInputInfo.TYPE_HDMI) { r = true; break; }
            }
        } catch (Throwable ignored) {}
        hdmiAt = now;
        hdmiVal = r;
        return r;
    }

    /** Кнопки «Все приложения» и «Лаунчер» стоят вверху, нижней панели «Настройки» нет. */
    public boolean panelTop() { return sp.getBoolean("panelTop", false); }
    public void setPanelTop(boolean v) { sp.edit().putBoolean("panelTop", v).apply(); }

    // ---- автосмена обоев ----
    /** Интервалы смены, секунды: 10 с (для проверки), полчаса, час, 3, 6, 12, 24, 48 часов, неделя, две недели, месяц. */
    public static final long[] WP_SEC = {10, 1800, 3600, 10800, 21600, 43200, 86400, 172800, 604800, 1209600, 2592000};
    public static final String[] WP_NAMES = {"10 секунд (для проверки)", "30 минут", "1 час", "3 часа", "6 часов", "12 часов", "24 часа", "48 часов", "1 неделя", "2 недели", "1 месяц"};
    public int wpInterval() { return Math.max(0, Math.min(WP_SEC.length - 1, sp.getInt("wpInt2", 6))); }
    public void setWpInterval(int v) { sp.edit().putInt("wpInt2", v).apply(); }
    public long wpIntervalMs() { return WP_SEC[wpInterval()] * 1000L; }
    /** Папка с обоями для автосмены: идентификатор папки в MediaStore и её название; пусто — автосмена выключена. */
    public String wpBucket() { return sp.getString("wpBucket", ""); }
    public String wpName() { return sp.getString("wpName", ""); }
    public void setWpFolder(String bucket, String name) {
        sp.edit().putString("wpBucket", bucket).putString("wpName", name).remove("wpSeen").remove("wpCur").apply();
    }
    public void clearWpFolder() { sp.edit().remove("wpBucket").remove("wpName").remove("wpSeen").remove("wpCur").apply(); }
    /** Какие картинки папки уже были обоями в текущем круге (id через запятую) и какая стоит сейчас. */
    public String wpSeen() { return sp.getString("wpSeen", ""); }
    public void setWpSeen(String v) { sp.edit().putString("wpSeen", v).apply(); }
    public long wpCur() { return sp.getLong("wpCur", -1); }
    public void setWpCur(long v) { sp.edit().putLong("wpCur", v).apply(); }
    public long wpLast() { return sp.getLong("wpLast", 0); }
    public void setWpLast(long v) { sp.edit().putLong("wpLast", v).apply(); }
    /** Автосмена работает: папка выбрана и тип фона «Изображение». */
    public boolean wpActive() { return !wpBucket().isEmpty() && bgMode() == 1; }

    /** Файловый менеджер для кнопки флешки (пакет); пусто — спросить при нажатии. */

    public boolean showVpnBtn() { return sp.getBoolean("showVpn", true); }
    public void setShowVpnBtn(boolean v) { sp.edit().putBoolean("showVpn", v).apply(); }
    public boolean showNetBtn() { return sp.getBoolean("showNet", true); }
    public void setShowNetBtn(boolean v) { sp.edit().putBoolean("showNet", v).apply(); }
    public boolean showGearBtn() { return sp.getBoolean("showGear", true); }
    public void setShowGearBtn(boolean v) { sp.edit().putBoolean("showGear", v).apply(); }
    public boolean showWeather() { return sp.getBoolean("showWeather", true); }
    public void setShowWeather(boolean v) { sp.edit().putBoolean("showWeather", v).apply(); }
    public boolean showDate() { return sp.getBoolean("date", true); }
    public void setShowDate(boolean v) { sp.edit().putBoolean("date", v).apply(); }

    public int theme() { return Math.max(0, Math.min(THEME_COLORS.length - 1, sp.getInt("theme", 3))); }
    public void setTheme(int v) { sp.edit().putInt("theme", v).apply(); }
    public int themeColor() { return THEME_COLORS[theme()]; }

    // ---- приложения ----
    public Set<String> hidden() {
        return new HashSet<>(split(sp.getString("hidden", "")));
    }
    public void setHidden(Set<String> s) { sp.edit().putString("hidden", join(s)).apply(); }

    public List<String> order() { return split(sp.getString("order", "")); }
    public void setOrder(List<String> l) { sp.edit().putString("order", join(l)).apply(); }

    // ---- провайдер погоды ----
    public static final String[] W_NAMES = {"MET Norway", "7Timer!", "DWD (Brightsky)"};
    /** 0 MET Norway (по умолчанию), 1 7Timer!, 2 DWD через Brightsky. */
    public int wProvider() { return Math.max(0, Math.min(2, sp.getInt("wProv3", 0))); }
    public void setWProvider(int v) { sp.edit().putInt("wProv3", v).apply(); clearWeather(); }
    public String providerName() { return W_NAMES[wProvider()]; }

    public String city() { return sp.getString("city", ""); }
    public double lat() { return Double.longBitsToDouble(sp.getLong("lat", Double.doubleToLongBits(Double.NaN))); }
    public double lon() { return Double.longBitsToDouble(sp.getLong("lon", Double.doubleToLongBits(Double.NaN))); }
    public boolean hasLocation() { return !Double.isNaN(lat()) && !Double.isNaN(lon()); }
    public void setLocation(String name, double lat, double lon) {
        sp.edit().putString("city", name)
                .putLong("lat", Double.doubleToLongBits(lat))
                .putLong("lon", Double.doubleToLongBits(lon)).apply();
    }

    public String weatherJson(String key) { return sp.getString("w_" + key, ""); }
    public long weatherTime() { return sp.getLong("w_time", 0); }
    public void saveWeather(String cur, String h3, String h24) {
        sp.edit().putString("w_cur", cur).putString("w_h3", h3).putString("w_h24", h24)
                .putLong("w_time", System.currentTimeMillis()).apply();
    }
    public void clearWeather() { sp.edit().remove("w_cur").remove("w_h3").remove("w_h24").remove("w_time").apply(); }

    // ---- фон ----
    /** Затемнение картинки/видео на фоне, 0..80 %, по умолчанию 30 %. */
    public int bgDim() { return Math.max(0, Math.min(80, sp.getInt("bgDim", 30))); }
    public void setBgDim(int v) { sp.edit().putInt("bgDim", v).apply(); }
    /** Цвет фона самих настроек: при фиксированном градиенте — выбранный, иначе графит. */
    public int settingsAccent() { return bgMode() == 0 ? themeColor() : THEME_COLORS[3]; }

    /** Насыщенность картинки/видео на фоне, 0..100 %, по умолчанию 100 % (без изменений). */
    public int bgSat() { return Math.max(0, Math.min(100, sp.getInt("bgSat", 100))); }
    public void setBgSat(int v) { sp.edit().putInt("bgSat", v).apply(); }

    public int bgBlur() { return sp.getInt("bgBlur", 0); }
    public void setBgBlur(int v) { sp.edit().putInt("bgBlur", v).apply(); }

    // ---- язык: "auto" (по системе), "ru", "en" ----
    public String lang() { return sp.getString("lang", "auto"); }
    public void setLang(String v) { sp.edit().putString("lang", v).apply(); }

    // ---- поиск ----
    public boolean showSearchBtn() { return sp.getBoolean("showSearch", true); }
    public void setShowSearchBtn(boolean v) { sp.edit().putBoolean("showSearch", v).apply(); }
    /** Выбранное приложение для поиска: «пакет|действие»; пусто — спросить при нажатии. */
    public String searchApp() { return sp.getString("searchApp", ""); }
    public void setSearchApp(String v) { sp.edit().putString("searchApp", v).apply(); }

    // ---- родительский контроль ----
    /** Ключи, которые не попадают в резервную копию и не меняются при восстановлении из неё. */
    public static final String[] SECURE_KEYS = {"pinHash", "pinSalt", "lockedApps", "kidMode"};

    public boolean hasPin() { return !sp.getString("pinHash", "").isEmpty(); }
    public void setPin(String pin) {
        String salt = Long.toHexString(new java.security.SecureRandom().nextLong());
        sp.edit().putString("pinSalt", salt).putString("pinHash", hashPin(salt, pin)).apply();
    }
    public boolean checkPin(String pin) {
        return hasPin() && sp.getString("pinHash", "").equals(hashPin(sp.getString("pinSalt", ""), pin));
    }
    /** Удаляет PIN-код вместе со всеми защитами приложений. */
    public void clearPin() { sp.edit().remove("pinHash").remove("pinSalt").remove("lockedApps").apply(); }
    private static String hashPin(String salt, String pin) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest((salt + ":" + pin).getBytes("UTF-8"));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) { return pin; }
    }
    public Set<String> locked() { return new HashSet<>(split(sp.getString("lockedApps", ""))); }
    public void setLocked(Set<String> s) { sp.edit().putString("lockedApps", join(s)).apply(); }
    public boolean isLocked(String pkg) { return hasPin() && locked().contains(pkg); }

    /** «Режим для бабушек»: меню приложений, системные настройки и настройки лаунчера не открываются. */
    public boolean kid() { return sp.getBoolean("kidMode", false); }
    public void setKid(boolean v) { sp.edit().putBoolean("kidMode", v).apply(); }

    /** Переводит массив названий на текущий язык. */
    public static String[] tr(String[] a) {
        String[] r = new String[a.length];
        for (int i = 0; i < a.length; i++) r[i] = L.t(a[i]);
        return r;
    }

    private static List<String> split(String s) {
        List<String> r = new ArrayList<>();
        if (s == null || s.isEmpty()) return r;
        for (String x : s.split("\n")) if (!x.isEmpty()) r.add(x);
        return r;
    }

    private static String join(java.util.Collection<String> c) {
        StringBuilder b = new StringBuilder();
        for (String x : c) { if (b.length() > 0) b.append('\n'); b.append(x); }
        return b.toString();
    }
}
