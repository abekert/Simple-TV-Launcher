package com.simpletvlauncher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Настройки лаунчера: внешний вид, фон, сетка, поиск, погода, приложения. */
public class SettingsActivity extends Activity {
    private Prefs prefs;
    private float s;
    private LinearLayout col;
    private final List<Runnable> refreshers = new ArrayList<>();
    private GlassView modeRow;
    private ScrollView scroll;
    private boolean weatherFocus;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        L.init(this);
        s = UI.scale(this);
        int W = getResources().getDisplayMetrics().widthPixels, H = getResources().getDisplayMetrics().heightPixels;
        weatherFocus = getIntent().getBooleanExtra("weather", false);

        FrameLayout root = new FrameLayout(this);
        bgView = new BgView(this, prefs.settingsAccent());
        root.addView(bgView, new FrameLayout.LayoutParams(-1, -1));
        TextView title = titleTv = UI.text(this, L.t("Настройки лаунчера"), 58 * s, 0xFFFFFFFF, GlassView.MED);
        FrameLayout.LayoutParams tl = new FrameLayout.LayoutParams(Math.round(W - 192 * s), Math.round(80 * s));
        tl.leftMargin = Math.round(96 * s);
        tl.topMargin = Math.round(50 * s);
        root.addView(title, tl);

        ScrollView sv = new ScrollView(this);
        scroll = sv;
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipChildren(false);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        sv.addView(col, new ViewGroup.LayoutParams(-1, -2));
        FrameLayout.LayoutParams svl = new FrameLayout.LayoutParams(Math.round(W - 192 * s), H - Math.round(170 * s));
        svl.leftMargin = Math.round(96 * s);
        svl.topMargin = Math.round(150 * s);
        root.addView(sv, svl);
        setContentView(root);
        rootView = root;

        // из виджета погоды настройки открываются сразу в разделе «Погода», иначе — на списке разделов
        page = getIntent().getIntExtra("page", weatherFocus ? P_WEATHER : 0);
        build();
        if (col.getChildCount() > 0) col.getChildAt(0).requestFocus();
    }

    private static final int P_HOME = 1, P_BG = 2, P_WEATHER = 3, P_SAFE = 4, P_BACKUP = 5, P_SYS = 6, P_COUNT = 6;
    private int page;                      // 0 — список разделов, иначе номер раздела
    private TextView titleTv;
    private final List<GlassView> menuRows = new ArrayList<>();

    private String pageName(int p) {
        switch (p) {
            case P_HOME: return L.t("Главный экран и сторонние интеграции");
            case P_BG: return L.t("Дизайн");
            case P_WEATHER: return L.t("Погода");
            case P_SAFE: return L.t("Защита");
            case P_BACKUP: return L.t("Резервное копирование");
            case P_SYS: return L.t("Системные настройки");
            default: return L.t("Настройки лаунчера");
        }
    }

    /** Настройки разбиты на разделы: сначала список разделов, внутри — их строки. Список строк собирается заново при смене раздела и режима фона. */
    private void build() {
        col.removeAllViews();
        refreshers.clear();
        menuRows.clear();
        titleTv.setText(pageName(page));
        switch (page) {
            case P_HOME: buildHome(); break;
            case P_BG: buildBg(); break;
            case P_WEATHER: buildWeather(); break;
            case P_SAFE: buildSafe(); break;
            case P_BACKUP: buildBackup(); break;
            case P_SYS: buildSys(); break;
            default:
                for (int i = 1; i <= P_COUNT; i++) {
                    final int p = i;
                    menuRows.add(row(pageName(i), () -> "", () -> openPage(p)));
                }
        }
        refresh();
    }

    private void openPage(int p) {
        page = p;
        build();
        scroll.scrollTo(0, 0);
        if (col.getChildCount() > 0) col.getChildAt(0).requestFocus();
    }

    @Override public void onBackPressed() {
        if (page == 0) { super.onBackPressed(); return; }
        int p = page;
        page = 0;
        build();
        scroll.scrollTo(0, 0);
        if (p <= menuRows.size()) menuRows.get(p - 1).requestFocus();      // фокус возвращается на только что закрытый раздел
    }

    /** Тонкая разделительная линия между группами строк. */
    private void divider() {
        android.view.View v = new android.view.View(this);
        v.setBackgroundColor(0x2EFFFFFF);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, Math.round(2 * s)));
        lp.topMargin = Math.round(8 * s);
        lp.bottomMargin = Math.round(18 * s);
        lp.leftMargin = Math.round(28 * s);
        lp.rightMargin = Math.round(28 * s);
        col.addView(v, lp);
    }

    private void buildHome() {
        row(L.t("Язык приложения"), this::langLabel, this::askLang);
        row(L.t("Формат времени"), () -> prefs.clock12() ? L.t("12 часов") : L.t("24 часа"), () -> prefs.setClock12(!prefs.clock12()));
        divider();
        slider(L.t("Приложений в ряду"), 4, 10, 1, prefs::cols, prefs::setCols, "");
        toggle(L.t("Показывать дату и день недели"), prefs::showDate, prefs::setShowDate);
        toggle(L.t("Отображать название выбранного приложения"), prefs::showTitle, prefs::setShowTitle);
        toggle(L.t("Отображать подписи категорий"), prefs::showHdrs, prefs::setShowHdrs);
        toggle(L.t("Кнопка поиска вверху"), prefs::showSearchBtn, prefs::setShowSearchBtn);
        toggle(L.t("Кнопка выбора источника сигнала"), prefs::showSourceBtn, prefs::setShowSourceBtn);
        toggle(L.t("Ряд источников сигнала на главном экране"), prefs::showInputsRow, prefs::setShowInputsRow);
        toggle(L.t("Значок VPN вверху"), prefs::showVpnBtn, prefs::setShowVpnBtn);
        toggle(L.t("Значок сети вверху"), prefs::showNetBtn, prefs::setShowNetBtn);
        toggle(L.t("Шестерня системных настроек вверху"), prefs::showGearBtn, prefs::setShowGearBtn);
        toggle(L.t("Кнопки настроек вверху вместо нижней панели"), prefs::panelTop, prefs::setPanelTop);
        divider();
        row(L.t("Клиент для VPN"), () -> vpnLabel(), this::askVpn);
        row(L.t("Приложение для поиска"), () -> searchLabel(), this::askSearch);
        divider();
        row(L.t("Показать все скрытые приложения"), () -> prefs.hidden().size() + L.t(" скрыто"), this::askShowAll);
        row(L.t("Сбросить порядок плиток (по алфавиту)"), () -> "", this::askResetOrder);
    }

    private void buildBg() {
        int mode = prefs.bgMode();
        modeRow = row(L.t("Тип заднего фона"), () -> L.t(Prefs.BG_MODES[prefs.bgMode()]), this::askMode);
        if (mode == 0) {
            row(L.t("Цвет градиента"), () -> L.t(Prefs.THEME_NAMES[prefs.theme()]), this::askColor);
        } else if (mode == 1) {
            row(L.t("Выбрать изображение"), () -> Bg.has(this) ? L.t("задано") : L.t("не задано"), () ->
                    startActivity(new Intent(this, BgPickerActivity.class)));
            wpRow = row(L.t("Автосмена обоев: папка"), () -> prefs.wpBucket().isEmpty() ? L.t("Выкл") : prefs.wpName(), () ->
                    startActivity(new Intent(this, BgPickerActivity.class).putExtra("folders", true)));
            wpShown = prefs.wpActive();
            if (wpShown) {
                row(L.t("Интервал смены обоев"), () -> L.t(Prefs.WP_NAMES[prefs.wpInterval()]), this::askWpInterval);
                row(L.t("Следующее изображение"), () -> "", this::nextWallpaper);
            }
            slider(L.t("Затемнение"), 0, 80, 10, prefs::bgDim, prefs::setBgDim, " %");
            slider(L.t("Насыщенность"), 0, 100, 10, prefs::bgSat, prefs::setBgSat, " %");
            slider(L.t("Размытие"), 0, 100, 10, prefs::bgBlur, prefs::setBgBlur, " %");
        } else {
            row(L.t("Выбрать видеофайл (mp4)"), () -> Bg.hasVideo(this) ? L.t("задан") : L.t("не задан"), () ->
                    startActivity(new Intent(this, BgPickerActivity.class).putExtra("video", true)));
            slider(L.t("Затемнение"), 0, 80, 10, prefs::bgDim, prefs::setBgDim, " %");
            slider(L.t("Насыщенность"), 0, 100, 10, prefs::bgSat, prefs::setBgSat, " %");
        }
    }

    private void buildWeather() {
        toggle(L.t("Виджет погоды на главном экране"), prefs::showWeather, prefs::setShowWeather);
        row(L.t("Источник погоды"), () -> prefs.providerName(), this::askProvider);
        row(L.t("Единицы измерения"), () -> unitsLabel(), this::askUnits);
        row(L.t("Город"), () -> prefs.hasLocation() ? prefs.city() : L.t("не выбран"), this::askCity);
        row(L.t("Обновить погоду сейчас"), () -> "", this::refreshWeather);
        row(L.t("Сбросить данные погоды"), () -> "", () -> { prefs.clearWeather(); toast(L.t("Кэш погоды очищен")); });
    }

    private void buildSafe() {
        row(L.t("Родительский контроль"), () -> prefs.hasPin() ? L.t("PIN-код задан") : L.t("не задан"), this::askParental);
        toggle(L.t("Режим для бабушек"), prefs::kid, v -> { prefs.setKid(v); if (v) showKidInfo(); });
    }

    private void buildBackup() {
        row(L.t("Создать резервную копию"), () -> L.t("в «Загрузки»"), this::doBackup);
        row(L.t("Восстановить из копии"), () -> "", this::askRestore);
    }

    private void buildSys() {
        row(L.t("Сменить лаунчер по умолчанию"), () -> "", () -> { prepareVeil(); awaitHome = true; if (rootView != null) rootView.postDelayed(() -> awaitHome = false, 6000); UI.action(this, Settings.ACTION_HOME_SETTINGS); });
        row(L.t("Принудительно назначить Simple TV Launcher главным (через ADB или Root)"), () -> "", this::showForceHome);
        divider();
        // оба пункта включены по умолчанию, но без доступа к спецвозможностям не работают — пока он нужен, но не получен, рядом с «Вкл» жёлтый «!»
        warn(row(L.t("Кнопка домой ведёт в Simple TV Launcher (нужны спецвозможности)"), () -> prefs.homeKey() ? L.t("Вкл") : L.t("Выкл"), () -> prefs.setHomeKey(!prefs.homeKey())), prefs::homeKey);
        warn(row(L.t("Автозапуск лаунчера при включении устройства (нужны спецвозможности)"), () -> prefs.bootHome() ? L.t("Вкл") : L.t("Выкл"), () -> prefs.setBootHome(!prefs.bootHome())), prefs::bootHome);
        final GlassView access = row(L.t("Доступ к спецвозможностям"), () -> HomeFix.isOn(this) ? L.t("Получен") : L.t("Отсутствует"), () -> HomeFix.showHelp(this, this::refresh));
        refreshers.add(() -> { access.tint = HomeFix.needed(this) ? 0x70FFB020 : 0; access.invalidate(); });     // подсвечена, пока доступ нужен, но не получен
        divider();
        row(L.t("Запускать выбранное приложение при включении устройства"), this::autoLabel, this::askAutoApp);
        toggle(L.t("Запускать выбранное приложение при выходе из сна"), prefs::wakeApp, prefs::setWakeApp);
        divider();
        row(L.t("О приложении"), () -> L.t("версия ") + versionName(), this::showAbout);
    }

    interface Getter { boolean get(); }
    interface Setter { void set(boolean v); }
    interface Text { String get(); }

    private void toggle(String label, final Getter g, final Setter st) {
        row(label, () -> g.get() ? L.t("Вкл") : L.t("Выкл"), () -> st.set(!g.get()));
    }

    private GlassView row(String label, final Text value, final Runnable action) {
        final GlassView r = new GlassView(this);
        r.left = true;
        r.label = label;
        r.radius = 42 * s;
        r.setOnClickListener(v -> { action.run(); refresh(); });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.round(84 * s));
        lp.bottomMargin = Math.round(10 * s);
        lp.leftMargin = Math.round(12 * s);
        lp.rightMargin = Math.round(12 * s);
        col.addView(r, lp);
        refreshers.add(() -> { r.value = value.get(); r.invalidate(); });
        return r;
    }

    /** Жёлтый «!» в кружке слева, пока нет доступа к спецвозможностям. */
    private void warn(final GlassView r, final Getter on) {
        refreshers.add(() -> { r.warn = on.get() && !HomeFix.isOn(this); r.invalidate(); });
    }

    interface IntGet { int get(); }
    interface IntSet { void set(int v); }

    /** Строка-ползунок: ◄ ► меняют значение, применяется сразу. */
    private class Slider extends GlassView {
        final int min, max, step;
        final IntGet g;
        final IntSet st;
        final String unit;

        Slider(String label, int min, int max, int step, IntGet g, IntSet st, String unit) {
            super(SettingsActivity.this);
            this.min = min; this.max = max; this.step = step; this.g = g; this.st = st; this.unit = unit;
            this.label = label;
            left = true;
            value = g.get() + unit;
        }

        @Override public boolean onKeyDown(int k, android.view.KeyEvent e) {
            if (k == android.view.KeyEvent.KEYCODE_DPAD_LEFT || k == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
                int v = Math.max(min, Math.min(max, g.get() + (k == android.view.KeyEvent.KEYCODE_DPAD_RIGHT ? step : -step)));
                if (v != g.get()) { st.set(v); value = v + unit; invalidate(); bgView.syncLook(); }      // затемнение и насыщенность видны на самих настройках сразу
                return true;
            }
            return super.onKeyDown(k, e);
        }

        @Override protected void drawContent(android.graphics.Canvas c) {
            super.drawContent(c);
            float w = getWidth(), h = getHeight(), pad = h * 0.3f;
            float x0 = w * 0.46f, x1 = w - pad - h * 1.9f, y = h / 2f;
            boolean dk = isFocused();
            float f = (g.get() - min) / (float) (max - min);
            p.setShader(null);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            p.setStrokeWidth(h * 0.09f);
            p.setColor(dk ? 0x33000000 : 0x40FFFFFF);
            c.drawLine(x0, y, x1, y, p);
            p.setColor(dk ? 0xFF1B1B22 : 0xFFFFFFFF);
            c.drawLine(x0, y, x0 + (x1 - x0) * f, y, p);
            p.setStyle(android.graphics.Paint.Style.FILL);
            c.drawCircle(x0 + (x1 - x0) * f, y, h * 0.16f, p);
        }
    }

    private void slider(String label, int min, int max, int step, IntGet g, IntSet st, String unit) {
        final Slider r = new Slider(label, min, max, step, g, st, unit);
        r.radius = 42 * s;
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.round(84 * s));
        lp.bottomMargin = Math.round(10 * s);
        lp.leftMargin = Math.round(12 * s);
        lp.rightMargin = Math.round(12 * s);
        col.addView(r, lp);
        refreshers.add(() -> { r.value = g.get() + unit; r.invalidate(); });
    }

    private void refresh() {
        for (Runnable r : refreshers) r.run();
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == 72) doBackup();
    }

    private void doBackup() {
        // на Android 6–9 для записи в «Загрузки» нужно разрешение
        if (android.os.Build.VERSION.SDK_INT >= 23 && android.os.Build.VERSION.SDK_INT <= 28
                && checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 72);
            return;
        }
        try { toast(L.t("Сохранено: ") + Backup.save(this)); }
        catch (Exception e) { toast(L.t("Не удалось сохранить: ") + e.getMessage()); }
    }

    private void askRestore() { startActivity(new Intent(this, BackupPickerActivity.class)); }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }

    private FrameLayout rootView;
    private android.widget.ImageView veil;
    private boolean paused;

    private String versionName() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Throwable e) { return ""; }
    }

    /** Системное окно выбора лаунчера прозрачное и ложится поверх настроек: под ним размываем и затемняем экран, чтобы текст не сливался. */
    private android.graphics.Bitmap veilBmp;

    /** Готовим размытую копию заранее (в момент нажатия), а на экран выводим позже — когда системное окно действительно появилось. */
    private void prepareVeil() {
        veilBmp = null;
        try {
            android.view.View d = getWindow().getDecorView();
            int w = d.getWidth(), h = d.getHeight();
            if (w <= 0 || h <= 0) return;
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(Math.max(8, w / 8), Math.max(8, h / 8), android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(b);
            c.scale(b.getWidth() / (float) w, b.getHeight() / (float) h);
            d.draw(c);
            android.graphics.Bitmap bl = Backdrop.blur(b, 3);
            new android.graphics.Canvas(bl).drawColor(0x99000000);
            veilBmp = bl;
        } catch (Throwable ignored) {}
    }

    private void showVeil() {
        if (veilBmp == null || rootView == null) return;
        hideVeil();
        veil = new android.widget.ImageView(this);
        veil.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
        veil.setImageBitmap(veilBmp);
        rootView.addView(veil, new FrameLayout.LayoutParams(-1, -1));
    }

    /** Окно настроек потеряло фокус — значит, системное окно выбора лаунчера уже на экране: размываем фон под ним. */
    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus && awaitHome) { awaitHome = false; showVeil(); }
    }

    private boolean awaitHome;      // нажали «Сменить лаунчер по умолчанию», ждём, пока системное окно реально откроется

    private void hideVeil() {
        if (veil != null && rootView != null) rootView.removeView(veil);
        veil = null;
    }

    @Override protected void onPause() {
        super.onPause();
        paused = true;
    }

    @Override protected void onResume() {
        super.onResume();
        paused = false;
        awaitHome = false;
        hideVeil();
        bgView.refresh(prefs.settingsAccent());     // например, после выбора другой картинки или видео
        bgView.syncLook();
        // выбрали (или сбросили) папку автосмены — строки интервала и «Следующее изображение» появляются или исчезают
        if (page == P_BG && prefs.bgMode() == 1 && prefs.wpActive() != wpShown) {
            build();
            if (wpRow != null) wpRow.requestFocus();
        }
        if (!refreshers.isEmpty()) refresh();
    }

    private boolean wpShown;
    private GlassView wpRow;

    private String vpnLabel() {
        String p = prefs.vpnPkg();
        if (p.isEmpty()) return L.t("не выбран");
        try { return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(p, 0)).toString(); }
        catch (Throwable t) { return L.t("не установлен"); }
    }

    private String autoLabel() {
        String p = prefs.autoApp();
        if (p.isEmpty()) return L.t("не выбрано");
        try { return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(p, 0)).toString(); }
        catch (Throwable t) { return L.t("не установлено"); }
    }

    /** Приложение, которое откроется при включении устройства (не при каждом запуске лаунчера). */
    private void askAutoApp() {
        Weather.IO.execute(() -> {
            final List<AppRepo.AppItem> apps = new ArrayList<>();
            for (AppRepo.AppItem a : AppRepo.loadAll(this)) if (!a.pkg.equals(getPackageName())) apps.add(a);
            runOnUiThread(() -> {
                String[] names = new String[apps.size() + 1];
                names[0] = L.t("Не использовать");
                for (int i = 0; i < apps.size(); i++) names[i + 1] = apps.get(i).label;
                GlassDialog.menu(this, L.t("Запускать выбранное приложение при включении устройства"), names, i -> {
                    prefs.setAutoApp(i == 0 ? "" : apps.get(i - 1).pkg);
                    refresh();
                }).show();
            });
        });
    }

    private static final String SET_HOME = "cmd package set-home-activity com.simpletvlauncher/.MainActivity";

    /** Инструкция по принудительному назначению лаунчера главным: ADB с компьютера или, при наличии root, прямо на ТВ. */
    private void showForceHome() {
        String text = L.t("Если в настройках устройства нельзя выбрать лаунчер по умолчанию, его можно назначить принудительно.")
                + "\n\n" + L.t("ADB (включите отладку по ADB, устройство и компьютер в одной сети):")
                + "\nadb shell " + SET_HOME
                + "\n\n" + L.t("Если на устройстве есть root, это можно сделать без компьютера кнопкой ниже.")
                + "\n\n" + L.t("Этот способ не панацея. Если кнопка «Домой» всё равно открывает стоковый лаунчер, нужны спецвозможности (пункт «Доступ к спецвозможностям») или отключение стокового лаунчера.");
        GlassDialog.info(this, L.t("Назначить лаунчером по умолчанию"), text,
                new String[]{L.t("Назначить через Root"), L.t("Закрыть")}, i -> { if (i == 0) setHomeViaRoot(); }).show();
    }

    private void setHomeViaRoot() {
        Weather.IO.execute(() -> {
            boolean ok = false;
            try {
                final Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", SET_HOME});
                // менеджер root может ждать ответа пользователя; если его нет, не зависаем навсегда
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(p::destroy, 30000);
                ok = p.waitFor() == 0;
            } catch (Throwable ignored) {}
            final boolean res = ok;
            runOnUiThread(() -> toast(res ? L.t("Готово: Simple TV Launcher назначен главным") : L.t("Не удалось: root недоступен или команда отклонена")));
        });
    }

    private void askVpn() {
        Weather.IO.execute(() -> {
            final List<AppRepo.AppItem> all = AppRepo.loadAll(this);
            final List<AppRepo.AppItem> cl = VpnControl.clients(this, all);
            runOnUiThread(() -> {
                if (cl.isEmpty()) { toast(L.t("VPN-клиенты не найдены — установите приложение")); return; }
                String[] names = new String[cl.size() + 1];
                names[0] = L.t("Не использовать");
                for (int i = 0; i < cl.size(); i++) names[i + 1] = cl.get(i).label;
                GlassDialog.menu(this, L.t("Клиент для VPN"), names, i -> {
                    prefs.setVpnPkg(i == 0 ? "" : cl.get(i - 1).pkg);
                    refresh();
                }).show();
            });
        });
    }



    private BgView bgView;

    private void askMode() {
        GlassDialog.menu(this, L.t("Тип заднего фона"), Prefs.tr(Prefs.BG_MODES), i -> {
            prefs.setBgMode(i);
            if (i != 2) SoftVideo.forget(this);
            bgView.setAccent(prefs.settingsAccent());
            build();
            modeRow.requestFocus();
        }).show();
    }

    /** Ручная смена обоев: следующая картинка из выбранной папки, отсчёт интервала начинается заново. */
    private void nextWallpaper() {
        if (!prefs.wpActive()) { toast(L.t("Сначала выберите папку для автосмены")); return; }
        Weather.IO.execute(() -> {
            final boolean ok = Bg.wpNext(this, prefs);
            runOnUiThread(() -> {
                toast(ok ? L.t("Обои сменены") : L.t("Не удалось сменить обои"));
                if (ok) bgView.refresh(prefs.settingsAccent());
            });
        });
    }

    private void askWpInterval() {
        GlassDialog.menu(this, L.t("Интервал смены обоев"), Prefs.tr(Prefs.WP_NAMES), prefs.wpInterval(), i -> {
            prefs.setWpInterval(i);
            prefs.setWpLast(System.currentTimeMillis());        // новый интервал отсчитывается с этого момента
            refresh();
        }).show();
    }

    private void askColor() {
        GlassDialog.menu(this, L.t("Цвет градиента"), Prefs.tr(Prefs.THEME_NAMES), Prefs.THEME_COLORS, i -> {
            prefs.setTheme(i);
            bgView.setAccent(prefs.settingsAccent());
            refresh();
        }).show();
    }







    // ------------------------------------------------------------ язык
    private String langLabel() {
        String l = prefs.lang();
        if ("ru".equals(l)) return L.t("Русский");
        if ("en".equals(l)) return "English";
        return L.t("Авто") + " (" + (L.en() ? "English" : L.t("Русский")) + ")";
    }

    private void askLang() {
        String[] names = {L.t("Авто (по системе)"), L.t("Русский"), "English"};
        GlassDialog.menu(this, L.t("Язык приложения"), names, i -> {
            prefs.setLang(i == 0 ? "auto" : i == 1 ? "ru" : "en");
            L.init(this);
            getIntent().putExtra("page", page);         // после пересоздания открывается тот же раздел
            recreate();         // экран заново строится на выбранном языке
        }).show();
    }

    // ------------------------------------------------------------ поиск
    private String searchLabel() {
        String k = prefs.searchApp();
        if (k.isEmpty()) return L.t("спрашивать при нажатии");
        String pkg = k.contains("|") ? k.substring(0, k.indexOf('|')) : k;
        try { return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(pkg, 0)).toString(); }
        catch (Throwable t) { return L.t("не установлено"); }
    }

    private void askSearch() {
        final List<SearchHelper.Provider> ps = SearchHelper.find(this);
        if (ps.isEmpty()) { toast(L.t("Приложения для поиска не найдены. Установите приложение с поиском")); return; }
        String[] names = new String[ps.size() + 1];
        names[0] = L.t("Спрашивать при нажатии");
        System.arraycopy(SearchHelper.names(ps), 0, names, 1, ps.size());
        GlassDialog.menu(this, L.t("Приложение для поиска"), names, i -> {
            prefs.setSearchApp(i == 0 ? "" : ps.get(i - 1).key());
            refresh();
        }).show();
    }

    // ------------------------------------------------------------ защита
    private void askParental() {
        final boolean has = prefs.hasPin();
        String text = L.t("Защитите приложения PIN-кодом, и запустить их без кода будет нельзя. Защита включается в меню «Все приложения»: ")
                + L.t("выберите приложение и нажмите «Защитить PIN-кодом». PIN-код состоит из 4 цифр.");
        if (has) text += "\n\n" + L.t("Приложений под защитой: ") + prefs.locked().size();
        String[] btns = has ? new String[]{L.t("Изменить PIN-код"), L.t("Удалить PIN-код"), L.t("Закрыть")}
                : new String[]{L.t("Задать PIN-код"), L.t("Закрыть")};
        GlassDialog.info(this, L.t("Родительский контроль"), text, btns, i -> {
            if (!has) {
                if (i == 0) Parental.createPin(this, prefs, this::refresh).show();
                return;
            }
            if (i == 0) {
                Parental.askPin(this, prefs, L.t("Изменить PIN-код"), L.t("Введите текущий PIN-код"),
                        () -> Parental.createPin(this, prefs, this::refresh).show()).show();
            } else if (i == 1) {
                Parental.askPin(this, prefs, L.t("Удалить PIN-код"), L.t("Введите текущий PIN-код"), () ->
                        GlassDialog.menu(this, L.t("Удалить PIN-код и снять защиту со всех приложений?"), new String[]{L.t("ОК"), L.t("Отмена")}, 1, k -> {
                            if (k != 0) return;
                            prefs.clearPin();
                            refresh();
                        }).show()).show();
            }
        }).show();
    }

    private void showKidInfo() {
        GlassDialog.info(this, L.t("Режим для бабушек"),
                L.t("Меню «Все приложения», системные настройки, настройки лаунчера, сеть и меню приложений теперь не открываются: ")
                        + L.t("при нажатии появляется подсказка. Чтобы временно открыть их, нажмите на пульте три раза влево, затем три раза вправо. ")
                        + L.t("Выключить режим можно в настройках лаунчера, в разделе «Защита»."),
                new String[]{L.t("ОК")}, i -> {}).show();
    }

    // ------------------------------------------------------------ о приложении
    static final String GITHUB = "https://github.com/J0NGLES/Simple-TV-Launcher";
    static final String FORUM = "https://4pda.to/forum/index.php?showtopic=1129772";

    private void showAbout() {
        StringBuilder sb = new StringBuilder();
        sb.append(L.t("Версия: ")).append(versionName());
        sb.append("\n").append(L.t("Автор: ")).append("dns-25");
        if (!GITHUB.isEmpty()) sb.append("\nGitHub: ").append(GITHUB);
        sb.append("\n4PDA: ").append(FORUM);
        // ссылки только текстом: с телевизора их всё равно никто не откроет
        GlassDialog.info(this, "Simple TV Launcher", sb.toString(), new String[]{L.t("Закрыть")}, i -> {}).show();
    }

    private String[] unitNames() {
        return new String[]{L.t("Авто (по региону)"), L.t("Метрические: °C, м/с, мм рт. ст."), L.t("США: °F, mph, inHg"), L.t("Великобритания: °C, mph, hPa")};
    }

    private String unitsLabel() {
        int u = prefs.unitsPref();
        if (u == 0) return L.t("Авто") + " (" + new String[]{"°C", "°F", "°C"}[Weather.units] + ")";
        return new String[]{"", L.t("Метрические"), L.t("США"), L.t("Великобритания")}[u];
    }

    private void askUnits() {
        GlassDialog.menu(this, L.t("Единицы измерения"), unitNames(), i -> {
            prefs.setUnitsPref(i);
            refresh();
        }).show();
    }

    private void askProvider() {
        String[] names = new String[Prefs.W_NAMES.length];
        for (int i = 0; i < names.length; i++) names[i] = Prefs.W_NAMES[i];
        GlassDialog.menu(this, L.t("Источник погоды"), names, i -> {
            prefs.setWProvider(i);
            build();
            if (prefs.hasLocation()) refreshWeather();
        }).show();
    }

    private void askShowAll() {
        GlassDialog.menu(this, L.t("Показать все скрытые приложения?"), new String[]{L.t("ОК"), L.t("Отмена")}, 1, i -> {
            if (i != 0) return;
            prefs.setHidden(new HashSet<String>());
            toast(L.t("Все приложения снова на рабочем столе"));
            refresh();
        }).show();
    }
    private void askResetOrder() {
        GlassDialog.menu(this, L.t("Сбросить порядок приложений?"), new String[]{L.t("ОК"), L.t("Отмена")}, 1, i -> {
            if (i != 0) return;
            prefs.setOrder(new ArrayList<String>());
            toast(L.t("Порядок сброшен"));
        }).show();
    }

    private void askCity() {
        GlassDialog.input(this, L.t("Найти город"), L.t("Например: Казань"), prefs.city(), q -> {
            final String query = q.trim();
            if (query.isEmpty()) return;
            toast(L.t("Ищу…"));
            Weather.searchCity(query, new Weather.Callback<List<Weather.Place>>() {
                @Override public void ok(final List<Weather.Place> res) {
                    if (res.isEmpty()) { toast(L.t("Ничего не найдено")); return; }
                    String[] names = new String[res.size()];
                    for (int i = 0; i < names.length; i++) names[i] = res.get(i).title();
                    GlassDialog.menu(SettingsActivity.this, L.t("Выберите город"), names, i -> {
                        Weather.Place p = res.get(i);
                        prefs.setLocation(p.name, p.lat, p.lon);
                        prefs.clearWeather();
                        refresh();
                        refreshWeather();
                    }).show();
                }
                @Override public void fail(String m) { toast(m); }
            });
        }).show();
    }

    private void refreshWeather() {
        Weather.refresh(this, new Weather.Callback<Weather.Data>() {
            @Override public void ok(Weather.Data d) { toast(L.t("Погода обновлена: ") + Weather.temp(d.cur.t) + " " + d.city); }
            @Override public void fail(String m) { toast(m); }
        });
    }
}
