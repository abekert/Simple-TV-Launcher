package com.simpletvlauncher;

import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Главный экран: единственное место со «стеклянным» оформлением. */
public class MainActivity extends Activity {
    static final int SET_COUNT = 3;

    private Prefs prefs;
    private float s;
    private int rw, rh;
    private float clockTextW, botGap;
    private boolean recreating;
    private int W, H;
    private float mx, gap;
    private float pitch, rowPitch, slotW, slotH, tw, th, vy;
    private int cols;
    private int contentH;

    private FrameLayout root, content;
    private ScrollView sv;
    private BgView bg;
    private VideoBg video;
    private HighlightView hlTop, hlGrid, hlTopR, hlGridR;     // под плитками (таблетка, тень) и поверх (кольцо)
    private HighlightView[] hls() { return new HighlightView[]{hlTop, hlGrid, hlTopR, hlGridR}; }
    private GlassView weatherPill, capsule, btnVpn, btnNet, btnGear, btnSource, btnSearch, btnApps, btnLauncher;
    private int langV;                  // версия языка, с которой построен экран
    private long lastHome = -10000;
    private static final long UNLOCK_MS = 3 * 60 * 1000L;
    private static long unlockUntil;    // общее для всего приложения время, а не число входов: после жеста всё открыто ровно 3 минуты          // «режим для бабушек»: до этого момента меню временно разблокировано
    private final long[] seqT = new long[6];
    private final int[] seqK = new int[6];
    private android.widget.Toast kidToast;
    private FrostPanel frost;
    private final List<android.media.tv.TvInputInfo> inputs = new ArrayList<>();

    /** ScrollView, у которого верхний край прокрутки плавно уходит в прозрачность (маска DST_OUT). */
    static class FadeScroll extends ScrollView {
        private final Paint fp = new Paint();
        private final int fade;
        FadeScroll(Context c, int fade) {
            super(c);
            this.fade = fade;
            fp.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT));
            fp.setShader(new android.graphics.LinearGradient(0, 0, 0, fade, 0xFF000000, 0x00000000, android.graphics.Shader.TileMode.CLAMP));
        }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) { return 0; }   // прокруткой при фокусе управляет лаунчер (ensureVisible), с отступами
        /** Фокус двигает только лаунчер (FocusFinder + ensureVisible): собственные «прыжки» ScrollView съедали нижний отступ. */
        @Override public boolean arrowScroll(int direction) { return false; }
        @Override protected void dispatchDraw(Canvas c) {
            int sy = getScrollY();
            if (sy <= 0) { super.dispatchDraw(c); return; }
            int w = getWidth(), hh = getHeight();
            // основная часть рисуется как обычно, без верхней полосы
            c.save();
            c.clipRect(0, sy + fade, w, sy + hh);
            super.dispatchDraw(c);
            c.restore();
            // плавное затухание сверху: отдельный небольшой слой только под саму полосу (дешевле, чем слой на весь список)
            int sc = c.saveLayer(0, sy, w, sy + fade, null);
            c.clipRect(0, sy, w, sy + fade);
            super.dispatchDraw(c);
            c.save();
            c.translate(0, sy);
            fp.setAlpha(Math.round(255 * Math.min(1f, sy / (float) fade)));
            c.drawRect(0, 0, w, fade, fp);
            c.restore();
            c.restoreToCount(sc);
        }
    }

    /** Матовая панель за сеткой приложений (как «dock» в tvOS). */
    static class FrostPanel extends View {
        private final Paint pt = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        float radius = 40;
        FrostPanel(Context c) { super(c); setFocusable(false); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            r.set(0, 0, w, h);
            Frost.draw(this, c, w, h, radius);
            pt.setShader(null);
            pt.setStyle(Paint.Style.STROKE);
            pt.setStrokeWidth(Math.max(1.5f, h / 260f));
            pt.setColor(0x38FFFFFF);
            r.inset(pt.getStrokeWidth() / 2, pt.getStrokeWidth() / 2);
            c.drawRoundRect(r, radius, radius, pt);
        }
    }
    private final GlassView[] setTiles = new GlassView[SET_COUNT];
    private TextView clock, dateTv, shelfTitle, appsHdr, inputsHdr, setHdr;

    private List<AppRepo.AppItem> all = new ArrayList<>(), visible = new ArrayList<>();
    private final List<AppTile> tiles = new ArrayList<>();
    private final List<GlassView> inputTiles = new ArrayList<>();
    private android.media.tv.TvInputManager observedInputManager;
    private final android.media.tv.TvInputManager.TvInputCallback inputCallback =
            new android.media.tv.TvInputManager.TvInputCallback() {
                @Override public void onInputAdded(String id) { updateInputsRow(); }
                @Override public void onInputRemoved(String id) { updateInputsRow(); }
                @Override public void onInputUpdated(String id) { updateInputsRow(); }
                @Override public void onInputStateChanged(String id, int state) { updateInputsRow(); }
            };
    private final Map<View, RectF> slots = new HashMap<>();
    private final Handler h = new Handler(Looper.getMainLooper());

    private boolean moving;
    private int pendingFocus = -1;
    private String deletePkg;           // приложение, для которого открыт системный диалог удаления
    private int deleteIdx;              // его место в сетке: после удаления выбор переходит на соседнее приложение
    private long deleteAt;
    private AppTile movingTile;
    private boolean vpn, eth, wifi;
    private boolean noInet;                 // сеть подключена, но интернета нет (показываем перечёркнутый значок)
    private long badSince;                  // с какого момента система считает интернет недоступным (0 — доступен)
    private Weather.Data wd;
    private String sig = "";
    private Dialog dialog;
    private ConnectivityManager.NetworkCallback netCb;
    private boolean resumed;

    // ------------------------------------------------------------ плитка приложения
    static class AppTile extends GlassView {
        final AppRepo.AppItem item;
        final int fallback;

        AppTile(Context c, AppRepo.AppItem it, int fallback) {
            super(c);
            item = it;
            this.fallback = fallback;
            floating = true;
        }

        @Override protected void drawContent(Canvas c) {
            int w = getWidth(), hh = getHeight();
            if (item.banner != null) {
                cover(c, item.banner, w, hh);
                return;
            }
            if (item.icon != null) drawIconCard(c, w, hh);
        }

        /** Мобильная иконка без баннера: фон иконки растягивается на всю карточку, значок — по центру. */
        private void drawIconCard(Canvas c, int w, int hh) {
            android.graphics.drawable.Drawable ic = item.icon;
            if (item.color == 0) item.color = ColorUtil.dominant(ic, fallback);
            p.setStyle(Paint.Style.FILL);
            p.setShader(null);
            if (android.os.Build.VERSION.SDK_INT >= 26 && ic instanceof android.graphics.drawable.AdaptiveIconDrawable) {
                android.graphics.drawable.AdaptiveIconDrawable ad = (android.graphics.drawable.AdaptiveIconDrawable) ic;
                android.graphics.drawable.Drawable back = ad.getBackground(), fore = ad.getForeground();
                int side = Math.max(w, hh);
                if (back != null) {
                    back.setBounds((w - side) / 2, (hh - side) / 2, (w + side) / 2, (hh + side) / 2);
                    back.draw(c);
                } else {
                    p.setColor(item.color | 0xFF000000);
                    c.drawRect(0, 0, w, hh, p);
                }
                if (fore != null) {
                    int fs = (int) (hh * 1.25f);
                    fore.setBounds((w - fs) / 2, (hh - fs) / 2 - (int) (hh * 0.04f), (w + fs) / 2, (hh + fs) / 2 - (int) (hh * 0.04f));
                    fore.draw(c);
                }
            } else {
                p.setColor(0xFF000000);     // кисть общая с рамкой/бликами: без этого градиент рисовался с их прозрачностью и плитка «выцветала» после первого фокуса
                p.setShader(new android.graphics.LinearGradient(0, 0, 0, hh, ColorUtil.darker(item.color, 1f), ColorUtil.darker(item.color, 0.55f), android.graphics.Shader.TileMode.CLAMP));
                c.drawRect(0, 0, w, hh, p);
                p.setShader(null);
                int sz = (int) (hh * 0.7f);
                ic.setBounds((w - sz) / 2, (hh - sz) / 2 - (int) (hh * 0.04f), (w + sz) / 2, (hh + sz) / 2 - (int) (hh * 0.04f));
                ic.draw(c);
            }
        }

        private void cover(Canvas c, android.graphics.drawable.Drawable d, int w, int hh) {
            int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
            if (iw <= 0 || ih <= 0) { iw = w; ih = hh; }
            float sc = Math.max(w / (float) iw, hh / (float) ih);
            c.save();
            c.translate((w - iw * sc) / 2f, (hh - ih * sc) / 2f);
            c.scale(sc, sc);
            d.setBounds(0, 0, iw, ih);
            d.draw(c);
            c.restore();
        }
    }

    // ------------------------------------------------------------ жизненный цикл
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = new Prefs(this);
        L.init(this);
        langV = L.version;
        Icons.init(this);
        android.util.DisplayMetrics m = getResources().getDisplayMetrics();
        W = m.widthPixels;
        H = m.heightPixels;
        screenChanged();        // запоминаем реальный размер экрана
        s = UI.scale(this);
        mx = 96 * s;
        gap = 24 * s;
        geom();             // верх списка (vy) и отступ до нижних кнопок зависят от того, показаны ли название и подписи категорий

        root = new FrameLayout(this);
        video = new VideoBg(this);
        video.setVisibility(View.GONE);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1));
        bg = new BgView(this, prefs.themeColor());
        bg.setLive(video);
        Frost.bg = bg;
        root.addView(bg, new FrameLayout.LayoutParams(-1, -1));
        hlTop = new HighlightView(this);
        hlGrid = new HighlightView(this);
        hlTopR = new HighlightView(this);
        hlGridR = new HighlightView(this);
        hlTopR.over = true;
        hlGridR.over = true;
        root.addView(hlTop, new FrameLayout.LayoutParams(-1, -1));      // рамка фокуса лежит под кнопками

        content = new FrameLayout(this);
        content.setClipChildren(false);
        frost = new FrostPanel(this);
        frost.setVisibility(View.INVISIBLE);       // до размещения плиток панель была бы крошечным «пузырьком» у левого края
        content.addView(frost, new FrameLayout.LayoutParams(10, 10));
        content.addView(hlGrid, new FrameLayout.LayoutParams(-1, -1));
        content.addView(hlGridR, new FrameLayout.LayoutParams(-1, -1));
        sv = new FadeScroll(this, Math.round(56 * s));    // при прокрутке верх списка плавно гаснет, а не обрезается по линии
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipChildren(false);
        sv.setClipToPadding(false);
        sv.setFocusable(false);
        sv.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        sv.addView(content, new ViewGroup.LayoutParams(-1, H));
        sv.setOnScrollChangeListener((v, x, y, ox, oy) -> invalidateFrost());

        computeGrid();
        buildTop();
        buildTexts();
        buildPanel();
        place(sv, 0, vy, W, H - vy);
        root.addView(hlTopR, new FrameLayout.LayoutParams(-1, -1));      // кольцо верхних кнопок — поверх них
        setContentView(root);
        // реальная высота окна может быть меньше экрана (системные панели): прокрутка должна доходить до конца
        root.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> {
            if (!recreating && (r - l != or - ol || bt - t != ob - ot) && ol != or && screenChanged()) { recreating = true; recreate(); return; }
            int want = Math.round((bt - t) - vy);
            ViewGroup.LayoutParams lp = sv.getLayoutParams();
            if (want > 0 && lp.height != want) { lp.height = want; sv.setLayoutParams(lp); }
        });

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_REPLACED);
        f.addDataScheme("package");
        registerReceiver(pkgReceiver, f);
        registerReceiver(screenOnRx, new IntentFilter(Intent.ACTION_SCREEN_ON));

        applyPrefs();
        loadApps();
        if (b == null) maybeAutostartApp();
    }

    /**
     * Запускает выбранное в настройках приложение, когда устройство только что включили. Срабатывает один раз за загрузку:
     * если лаунчер потом выгрузился из памяти и стартовал заново, приложение повторно не открывается.
     */
    private void maybeAutostartApp() {
        final String pkg = prefs.autoApp();
        if (pkg.isEmpty()) return;
        long up = android.os.SystemClock.elapsedRealtime();
        if (up > 5 * 60 * 1000L) return;            // включили давно — это уже не старт устройства
        int boots = -1;
        try { boots = Settings.Global.getInt(getContentResolver(), "boot_count", -1); } catch (Throwable ignored) {}
        String id = boots != -1 ? "b" + boots : "t" + (System.currentTimeMillis() - up) / 60000L;     // номер загрузки, а если его нет — время старта системы
        if (id.equals(prefs.autoBootDone())) return;
        prefs.setAutoBootDone(id);                  // отмечаем до запуска, чтобы сбой приложения не вызвал повтор
        h.postDelayed(() -> Wake.openAuto(this, pkg), 800);
    }

    // ---- выход из сна: по желанию открывается приложение автозапуска ----
    private final BroadcastReceiver screenOnRx = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            Wake.wokeAt = android.os.SystemClock.elapsedRealtime();
            if (resumed) wakeCheck();       // лаунчер уже на экране; иначе проверка пройдёт в onResume (если он случится сразу после пробуждения)
        }
    };

    private void wakeCheck() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (Wake.wokeAt == 0 || now - Wake.wokeAt > 4000 || Wake.afterBoot()) return;       // не пробуждение, а давний возврат на экран или загрузка
        Wake.wokeAt = 0;
        final String pkg = prefs.autoApp();
        if (prefs.wakeApp() && !pkg.isEmpty()) h.postDelayed(() -> Wake.openAuto(this, pkg), 1500);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(pkgReceiver); } catch (Throwable ignored) {}
        try { unregisterReceiver(screenOnRx); } catch (Throwable ignored) {}
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration cfg) {
        super.onConfigurationChanged(cfg);
        L.init(this);           // язык системы сменился, а в лаунчере выбрано «Авто»
        if (L.version != langV || screenChanged()) recreate();
    }

    /** Разрешение экрана (или плотность) изменилось, пока лаунчер жив: масштаб считается один раз при создании, поэтому экран строится заново. */
    private boolean screenChanged() {
        android.util.DisplayMetrics m = getResources().getDisplayMetrics();
        android.util.DisplayMetrics r = new android.util.DisplayMetrics();
        try { getWindowManager().getDefaultDisplay().getRealMetrics(r); } catch (Throwable t) { r = m; }
        if (rw == 0) { rw = r.widthPixels; rh = r.heightPixels; }
        return m.widthPixels != W || m.heightPixels != H || r.widthPixels != rw || r.heightPixels != rh;
    }

    @Override protected void onResume() {
        super.onResume();
        L.init(this);
        if (L.version != langV || screenChanged()) { recreate(); return; }      // сменили язык или разрешение — экран строится заново
        resumed = true;
        wakeCheck();
        Frost.bg = bg;      // другие экраны подменяют его своим фоном
        if (!sigOf().equals(sig)) {
            applyPrefs();
            if (!all.isEmpty()) applyApps();
        }
        if (prefs.showInputsRow()) {
            observedInputManager = (android.media.tv.TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            if (observedInputManager != null) observedInputManager.registerCallback(inputCallback, h);
        }
        if (prefs.showInputsRow() || !inputTiles.isEmpty()) updateInputsRow();
        applyBackground();
        bg.syncLook();
        h.removeCallbacks(tick);        // onPause останавливает часы, а onStart после короткой паузы не вызывается — запускаем их здесь
        h.post(tick);
        layoutTop();
        updateNotice();
        // всё тяжёлое (сеть, разбор погоды) — уже после первого кадра, чтобы рабочий стол появлялся мгновенно
        h.removeCallbacks(lateResume);
        h.postDelayed(lateResume, 80);
        // после возврата из другого экрана подсветка могла остаться на старом месте — ставим её заново без анимации
        h.post(() -> {
            invalidateFrost();
            View f = getCurrentFocus();
            if (!(f instanceof GlassView)) return;
            for (HighlightView hv : hls()) hv.hide();
            moveHighlight(f);
            ensureVisible(f);
        });
    }

    // ---- автосмена обоев ----
    // Ничего не крутится в фоне: пока рабочий стол на экране, ставится ОДИН отложенный вызов на остаток интервала,
    // а при уходе с экрана он снимается. Если срок вышел, пока лаунчер был закрыт, обои меняются при возврате на рабочий стол.
    private final Runnable wpTick = new Runnable() {
        @Override public void run() { rotateWallpaper(); }
    };

    private void scheduleWallpaper() {
        h.removeCallbacks(wpTick);
        if (!resumed || !prefs.wpActive()) return;
        long every = prefs.wpIntervalMs();
        long left = prefs.wpLast() + every - System.currentTimeMillis();
        if (left <= 0 || left > every) rotateWallpaper();     // срок вышел (или часы переведены назад)
        else h.postDelayed(wpTick, left);
    }

    private boolean wpBusy;

    private void rotateWallpaper() {
        if (wpBusy || !resumed) return;
        // обои меняются только пока лаунчер на экране и в фокусе (не закрыт меню или другим окном); иначе чуть позже проверяем снова
        if (!hasWindowFocus() || (dialog != null && dialog.isShowing())) {
            h.removeCallbacks(wpTick);
            h.postDelayed(wpTick, 3000);
            return;
        }
        wpBusy = true;
        Weather.IO.execute(() -> {
            final boolean changed = Bg.wpNext(this, prefs);
            runOnUiThread(() -> {
                wpBusy = false;
                if (changed && resumed) {
                    bg.fadeNext();          // новая картинка проявляется плавно поверх прежней
                    applyBackground();
                }
                scheduleWallpaper();
            });
        });
    }

    private final Runnable lateResume = new Runnable() {
        @Override public void run() {
            scheduleWallpaper();
            updateNet();
            startNetPoll();
            registerNet();
            Weather.IO.execute(() -> {
                final Weather.Data d = Weather.load(prefs);
                runOnUiThread(() -> {
                    if (d != null || wd == null) wd = d;
                    weatherPill.invalidate();
                    maybeRefreshWeather();
                });
            });
        }
    };

    @Override protected void onPause() {
        super.onPause();
        resumed = false;
        if (observedInputManager != null) {
            observedInputManager.unregisterCallback(inputCallback);
            observedInputManager = null;
        }
        video.pause();
        h.removeCallbacks(netPoll);
        h.removeCallbacks(lateResume);
        h.removeCallbacks(wpTick);
        h.removeCallbacks(tick);
        unregisterNet();
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        // одно нажатие Home на некоторых ТВ приходит дважды (от службы и от системы) — повтор в течение 2 секунд не обрабатываем,
        // иначе выбор плитки, сделанный сразу после Home, сбрасывался бы обратно на первую
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastHome < 1000) return;
        lastHome = now;
        // нажатие Home: закрыть всё лишнее и вернуться к первой плитке
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        if (moving) endMove();
        goFirst();
    }

    /** Выбор и прокрутка возвращаются к первой плитке (Home и Back). */
    private void goFirst() {
        sv.scrollTo(0, 0);
        for (AppTile t : tiles) { t.setScaleX(1f); t.setScaleY(1f); }
        for (HighlightView hv : hls()) hv.hide();
        if (!tiles.isEmpty()) {
            AppTile first = tiles.get(0);
            first.requestFocus();
            // если первая плитка уже была в фокусе, requestFocus ничего не делает — возвращаем ей масштаб выбранной
            // (раньше она «поджималась» до обычного размера)
            if (first.isFocused()) first.applyFocusScale();
            moveHighlight(first);
        }
    }

    @Override public void onBackPressed() {
        if (moving) { endMove(); return; }
        goFirst();      // лаунчер не закрывается кнопкой «Назад»: она возвращает выбор на первую плитку, как «Домой»
    }

    private final BroadcastReceiver pkgReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            h.removeCallbacks(reloadRun);
            h.postDelayed(reloadRun, 800);
        }
    };
    private final Runnable reloadRun = new Runnable() { @Override public void run() { loadApps(); } };

    // ------------------------------------------------------------ геометрия сетки
    /** Матовые панели берут кусок фона по своему положению на экране; после прокрутки их надо перерисовать, иначе остаётся полоса от прежнего места. */
    private void invalidateFrost() {
        for (int i = 0; i < content.getChildCount(); i++) {
            View c = content.getChildAt(i);
            if (c instanceof FrostPanel || (c instanceof GlassView && ((GlassView) c).frosted)) c.invalidate();
        }
    }

    private void computeGrid() {
        cols = prefs.cols();
        gap = (cols >= 10 ? 12 : cols == 9 ? 15 : cols == 8 ? 19 : 24) * s;     // на плотной сетке интервал меньше
        // плитки прижаты к левому краю; при 100 % ряд занимает ширину до правой линии часов
        float availW = W - 2 * mx;
        tw = (availW - (cols - 1) * gap) / cols;
        th = tw * 9f / 16f;      // баннеры Android TV — 16:9
        // два ряда вместе с нижней частью обязаны помещаться без прокрутки на любом экране: при нехватке высоты плитки уменьшаются,
        // а свободная ширина уходит в интервал между ними
        float below = prefs.panelTop() ? 34 * s + END_PAD * s : 34 * s + botGap + 104 * s + BOTTOM_PAD * s;
        // при самой крупной сетке (4 плитки в ряду) с нижней панелью «Настройки» без прокрутки помещается один ряд, с панелью наверху — два
        int fit = cols == 4 && !prefs.panelTop() ? 1 : 2;
        float maxTh = (H - vy - gridTop() - below - (fit - 1) * gap) / fit;
        if (maxTh > 0 && th > maxTh) { th = maxTh; tw = th * 16f / 9f; }
        slotW = tw;
        pitch = cols > 1 ? (availW - tw) / (cols - 1) : tw + gap;
        rowPitch = th + gap;
    }

    private static final float PANEL_TILE_WIDTH = 300f;
    private static final float PANEL_TILE_HEIGHT = 104f;
    private static final float PANEL_TILE_GAP = 24f;
    private static final float BOTTOM_PAD = 50f;     // фиксированный отступ под «Настройками», в условных пикселях
    private static final float END_PAD = 40f;        // отступ под матовой панелью, когда нижней панели «Настройки» нет

    private float gridTop() { return 100 * s; }
    private float slotX(int i) { return mx + (i % cols) * pitch; }
    private float slotY(int i) { return gridTop() + (i / cols) * rowPitch; }

    // ------------------------------------------------------------ построение интерфейса
    private <T extends View> T place(T v, float x, float y, float w, float hh) {
        return placeIn(root, hlTop, v, x, y, w, hh);
    }

    private <T extends View> T placeIn(ViewGroup parent, View hl, T v, float x, float y, float w, float hh) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(Math.round(w), Math.round(hh));
        // позиция задаётся отступами, а не translation: FocusFinder translation не учитывает
        lp.leftMargin = Math.round(x);
        lp.topMargin = Math.round(y);
        parent.addView(v, lp);
        slots.put(v, new RectF(x, y, x + w, y + hh));
        return v;
    }

    private <T extends View> T placeC(T v, float x, float y, float w, float hh) {
        return placeIn(content, hlGrid, v, x, y, w, hh);
    }

    private void moveTo(View v, float x, float y) {
        RectF r = slots.get(v);
        float w = r == null ? v.getWidth() : r.width(), hh = r == null ? v.getHeight() : r.height();
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
        slots.put(v, new RectF(x, y, x + w, y + hh));
        lp.leftMargin = Math.round(x);
        lp.topMargin = Math.round(y);
        v.setLayoutParams(lp);
    }

    private final View.OnFocusChangeListener focusL = new View.OnFocusChangeListener() {
        @Override public void onFocusChange(View v, boolean has) { if (has) onFocused(v); }
    };

    private GlassView glass(float radius, int icon, String label) {
        GlassView g = new GlassView(this);
        g.radius = radius;
        g.icon = icon;
        g.label = label;
        g.floating = true;
        g.solidFocus = true;
        g.setOnFocusChangeListener(focusL);
        return g;
    }

    private void buildTop() {
        float topY = 46 * s, ph = 84 * s;

        // часы справа (24-часовой формат)
        clock = UI.text(this, "00:00", 96 * s, 0xFFFFFFFF, GlassView.LIGHT);
        clock.setLetterSpacing(-0.02f);
        clock.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        float cw = clock.getPaint().measureText("88:88") * 1.04f + 8 * s;
        place(clock, W - mx - cw, 31 * s, cw, 104 * s);

        dateTv = UI.text(this, "", 23 * s, 0xA6FFFFFF, GlassView.REG);
        dateTv.setLetterSpacing(0.12f);
        dateTv.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        place(dateTv, W - mx - 700 * s, 140 * s, 700 * s, 36 * s);

        // капсула с кнопками слева от часов
        capsule = new GlassView(this);
        capsule.setFocusable(false);
        capsule.setClickable(false);
        capsule.frosted = true;
        capsule.radius = ph / 2;
        place(capsule, 0, topY, 100 * s, ph);
        root.removeView(hlTop);     // white focus pill must lie ABOVE the frosted capsule, but under the buttons
        root.addView(hlTop, new FrameLayout.LayoutParams(-1, -1));

        btnSearch = glass(ph / 2, Icons.SEARCH, null);
        btnSource = glass(ph / 2, Icons.INPUT, null);
        btnVpn = glass(ph / 2, Icons.VPN, null);
        btnNet = glass(ph / 2, Icons.WIFI, null);
        btnGear = glass(ph / 2, Icons.GEAR, null);
        btnApps = glass(ph / 2, Icons.GRID, null);
        btnLauncher = glass(ph / 2, Icons.TUNE, null);
        for (GlassView g : new GlassView[]{btnSearch, btnSource, btnVpn, btnNet, btnApps, btnGear, btnLauncher}) { g.plain = true; place(g, 0, topY, ph, ph); }
        // кнопки нижней панели «Настройки», когда она перенесена наверх (см. «Кнопки настроек вверху» в настройках)
        btnApps.title = L.t("Все приложения");
        btnApps.setOnClickListener(v -> onPanelTile(0));
        btnLauncher.title = L.t("Настройки лаунчера");
        btnLauncher.setOnClickListener(v -> onPanelTile(2));
        btnSearch.title = L.t("Поиск");
        btnSearch.setOnClickListener(v -> onSearchClick(false));
        btnSearch.setOnLongClickListener(v -> { onSearchClick(true); return true; });
        btnSource.title = L.t("Источник сигнала");
        btnSource.setOnClickListener(v -> pickSource());
        btnVpn.setOnClickListener(v -> onVpnClick());
        btnVpn.setOnLongClickListener(v -> { pickVpnClient(); return true; });
        btnGear.title = L.t("Настройки системы");
        btnNet.setOnClickListener(v -> { if (!kidBlocked()) UI.action(this, Settings.ACTION_WIFI_SETTINGS); });
        btnNet.setOnLongClickListener(v -> { if (!kidBlocked()) UI.action(this, Settings.ACTION_WIRELESS_SETTINGS); return true; });
        btnGear.setOnClickListener(v -> { if (!kidBlocked()) UI.actionFresh(this, Settings.ACTION_SETTINGS); });
        btnVpn.iconColor = 0x66FFFFFF;

        layoutTop();

        // погода слева
        weatherPill = glass(ph / 2, 0, null);
        weatherPill.title = L.t("Погода");
        weatherPill.frosted = true;
        weatherPill.focusScale = 1f;        // виджет погоды при наведении не увеличивается — только обводка
        weatherPill.solidFocus = false;     // цветные значки погоды плохо видны на белом: фокус — кольцо
        place(weatherPill, mx, topY, 660 * s, ph);
        weatherPill.painter = this::paintWeatherPill;
        weatherPill.setOnClickListener(v -> {
            if (!prefs.hasLocation()) { if (!kidBlocked()) startActivity(new Intent(this, SettingsActivity.class).putExtra("weather", true)); }
            else startActivity(new Intent(this, WeatherActivity.class));
        });
        weatherPill.setOnLongClickListener(v -> { refreshWeather(true); return true; });

        // ненавязчивое напоминание под датой (только в версии с фиксом «Домой»): служба спецвозможностей не включена или слетела
        final String msg = L.t("Дайте доступ к спецвозможностям для корректной работы приложения");
        float nh = 44 * s;
        android.graphics.Paint mp = new android.graphics.Paint();
        mp.setTextSize(nh * 0.46f);
        mp.setTypeface(GlassView.REG);      // тем же шрифтом, каким текст рисуется в рамке
        float nw = Math.min(W - 2 * mx, mp.measureText(msg) + nh * 0.9f);
        notice = glass(nh / 2, 0, null);
        notice.title = L.t("Нужны спецвозможности");
        notice.frosted = true;
        notice.focusScale = 1f;
        notice.solidFocus = false;
        place(notice, W - mx - nw, 182 * s, nw, nh);
        noticeW = nw;
        placeNotice();
        notice.painter = (c, v) -> v.text(c, msg, v.getWidth() / 2f, v.getHeight() * 0.66f, v.getHeight() * 0.46f, 0xE6FFFFFF, 1, Paint.Align.CENTER, v.getWidth() - v.getHeight() * 0.3f);
        notice.setOnClickListener(v -> { if (!kidBlocked()) HomeFix.showHelp(this, this::updateNotice); });
        updateNotice();
    }

    private GlassView notice;
    private float noticeW, frostTop;

    /**
     * Обычно плашка стоит справа под датой. Если название и подписи скрыты, панель с плитками поднимается вплотную к дате
     * и плашка легла бы на неё: тогда она встаёт слева, в просвете между виджетом погоды и панелью.
     */
    private void placeNotice() {
        if (notice == null) return;
        boolean roomy = frostTop >= (182 + 44 + 10) * s;
        moveTo(notice, roomy ? W - mx - noticeW : mx, roomy ? 182 * s : frostTop - 54 * s);
    }

    /** Показывает напоминание, если служба спецвозможностей (фикс «Домой», автозапуск) не включена. */
    private void updateNotice() {
        if (notice == null) return;
        boolean on = HomeFix.needed(this);
        if (!on && notice.hasFocus() && !tiles.isEmpty()) tiles.get(0).requestFocus();
        show(notice, on);
    }

    private static void show(View v, boolean on) {
        v.setVisibility(on ? View.VISIBLE : View.GONE);
        v.setFocusable(on);
    }

    private static final float CLOCK_AP = 0.34f;      // размер «AM/PM» относительно цифр часов

    /** Ширина часов: в 12-часовом формате добавляется место под мелкое «AM/PM»; правый край остаётся на месте. */
    private float clockWidth(boolean c12) {
        android.graphics.Paint pt = clock.getPaint();
        float w = pt.measureText("88:88") * 1.04f + 8 * s;
        if (c12) w += pt.measureText(" PM") * CLOCK_AP * 1.1f;
        return w;
    }

    private void layoutTop() {
        float ph = 84 * s, topY = 46 * s;
        float cw0 = clockWidth(prefs.clock12()), cx0 = W - mx - cw0;
        FrameLayout.LayoutParams cl = (FrameLayout.LayoutParams) clock.getLayoutParams();
        if (cl.width != Math.round(cw0)) {
            cl.width = Math.round(cw0);
            cl.leftMargin = Math.round(cx0);
            clock.setLayoutParams(cl);
            slots.put(clock, new RectF(cx0, 31 * s, cx0 + cw0, 31 * s + 104 * s));
        }
        float clockLeft = slots.get(clock).left;
        if (prefs.clock12()) {
            // часы прижаты вправо, поэтому капсула идёт вплотную к реальному тексту («9:05 AM» уже, чем «10:05 AM»), а не к запасу под самую широкую запись
            clockTextW = android.text.Layout.getDesiredWidth(clock.getText(), clock.getPaint());
            clockLeft = cx0 + cw0 - clockTextW - 12 * s;
        }
        List<GlassView> bs = new ArrayList<>();
        boolean src = showSource();
        boolean srch = prefs.showSearchBtn();
        show(btnSearch, srch);
        if (srch) bs.add(btnSearch);
        btnSource.setVisibility(src ? View.VISIBLE : View.GONE);
        btnSource.setFocusable(src);
        if (src) bs.add(btnSource);
        // с кнопками настроек наверху (нижней панели нет) шестерня остаётся всегда: иначе до системных настроек не добраться
        boolean pt = prefs.panelTop();
        boolean sv2 = prefs.showVpnBtn(), sn = prefs.showNetBtn(), sg = prefs.showGearBtn() || pt;
        show(btnVpn, sv2); show(btnNet, sn); show(btnGear, sg);
        show(btnApps, pt); show(btnLauncher, pt);
        if (sv2) bs.add(btnVpn);
        if (sn) bs.add(btnNet);        if (pt) { bs.add(btnApps); bs.add(btnLauncher); }
        if (sg) bs.add(btnGear);
        capsule.setVisibility(bs.isEmpty() ? View.GONE : View.VISIBLE);
        float pad = 0;      // края капсулы совпадают с краями белого кружка
        float cw = bs.size() * ph + 2 * pad;
        float x0 = clockLeft - 34 * s - cw;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) capsule.getLayoutParams();
        lp.width = Math.round(cw);
        lp.leftMargin = Math.round(x0);
        lp.topMargin = Math.round(topY);
        capsule.setLayoutParams(lp);
        slots.put(capsule, new RectF(x0, topY, x0 + cw, topY + ph));
        for (int i = 0; i < bs.size(); i++) moveTo(bs.get(i), x0 + pad + i * ph, topY);
    }

    private void buildTexts() {
        shelfTitle = UI.text(this, "", 68 * s, 0xFFFFFFFF, GlassView.LIGHT);
        shelfTitle.setLetterSpacing(-0.02f);
        place(shelfTitle, mx, 197 * s, W - 2 * mx, 90 * s);

        appsHdr = UI.text(this, L.t("ПРИЛОЖЕНИЯ"), 21 * s, 0x99FFFFFF, GlassView.MED);
        appsHdr.setLetterSpacing(0.2f);
        placeC(appsHdr, mx, 14 * s, 600 * s, 36 * s);
        inputsHdr = UI.text(this, L.t("Источник сигнала").toUpperCase(L.locale()), 21 * s, 0x99FFFFFF, GlassView.MED);
        inputsHdr.setLetterSpacing(0.2f);
        inputsHdr.setVisibility(View.GONE);
        placeC(inputsHdr, mx, 0, 600 * s, 36 * s);
        setHdr = UI.text(this, L.t("НАСТРОЙКИ"), 21 * s, 0x99FFFFFF, GlassView.MED);
        setHdr.setLetterSpacing(0.2f);
        placeC(setHdr, mx, 500 * s, 600 * s, 36 * s);
    }

    private void buildPanel() {
        String[] labels = {L.t("Все приложения"), L.t("Система"), L.t("Лаунчер")};
        String[] titles = {L.t("Все приложения"), L.t("Настройки системы"), L.t("Настройки лаунчера")};
        int[] icons = {Icons.GRID, Icons.GEAR, Icons.TUNE};
        for (int i = 0; i < SET_COUNT; i++) {
            final int idx = i;
            GlassView g = glass(22 * s, icons[i], labels[i]);
            g.title = titles[i];
            g.frosted = true;
            placeC(g, mx + i * (PANEL_TILE_WIDTH + PANEL_TILE_GAP) * s, 550 * s, PANEL_TILE_WIDTH * s, PANEL_TILE_HEIGHT * s);
            g.setOnClickListener(v -> onPanelTile(idx));
            setTiles[i] = g;
        }
    }

    /**
     * «Режим для бабушек»: пока он включён и не разблокирован, меню приложений, системные настройки и настройки лаунчера
     * не открываются, а нажатие показывает подсказку. Возвращает true, если действие нужно отменить.
     */
    /** Режим для бабушек включён и сейчас не разблокирован (для других экранов, например погоды). */
    static boolean kidLocked(Prefs p) { return p.kid() && System.currentTimeMillis() >= unlockUntil; }

    private boolean kidBlocked() {
        if (!prefs.kid() || System.currentTimeMillis() < unlockUntil) return false;
        if (kidToast != null) kidToast.cancel();
        kidToast = android.widget.Toast.makeText(this, L.t("←←← →→→ для разблокировки"), android.widget.Toast.LENGTH_SHORT);
        kidToast.show();
        return true;
    }

    /** Следим за кнопками: «влево ×3, вправо ×3» подряд (в пределах 8 секунд) временно разблокирует меню на 3 минуты. */
    private void trackUnlockKeys(KeyEvent e) {
        if (!prefs.kid() || e.getAction() != KeyEvent.ACTION_DOWN || e.getRepeatCount() != 0) return;
        long now = System.currentTimeMillis();
        if (now < unlockUntil) return;       // уже разблокировано — время не продлевается
        int kc = e.getKeyCode();
        if (kc != KeyEvent.KEYCODE_DPAD_LEFT && kc != KeyEvent.KEYCODE_DPAD_RIGHT) { java.util.Arrays.fill(seqK, 0); return; }
        System.arraycopy(seqK, 1, seqK, 0, 5);
        System.arraycopy(seqT, 1, seqT, 0, 5);
        seqK[5] = kc;
        seqT[5] = now;
        int kl = KeyEvent.KEYCODE_DPAD_LEFT, kr = KeyEvent.KEYCODE_DPAD_RIGHT;
        if (seqK[0] == kl && seqK[1] == kl && seqK[2] == kl && seqK[3] == kr && seqK[4] == kr && seqK[5] == kr && now - seqT[0] <= 8000) {
            java.util.Arrays.fill(seqK, 0);
            unlockUntil = now + UNLOCK_MS;
            android.widget.Toast.makeText(this, L.t("Меню разблокировано на 3 минуты"), android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void onPanelTile(int i) {
        if (kidBlocked()) return;
        switch (i) {
            case 0: startActivity(new Intent(this, ManageActivity.class)); break;
            case 1: UI.actionFresh(this, Settings.ACTION_SETTINGS); break;
            case 2: startActivity(new Intent(this, SettingsActivity.class)); break;
        }
    }

    /** Нижняя панель следует за сеткой: чем больше рядов, тем ниже она уезжает. */
    private void layoutContent() {
        int n = visible.size();
        int rows = Math.max(1, (n + cols - 1) / cols);
        float sectionsBottom = gridTop() + rows * rowPitch - gap + 34 * s;
        // матовая панель за сеткой приложений
        float px = mx - 30 * s, py = 62 * s, pw = W - 2 * mx + 60 * s;
        FrameLayout.LayoutParams fl = (FrameLayout.LayoutParams) frost.getLayoutParams();
        fl.leftMargin = Math.round(px);
        fl.topMargin = Math.round(py);
        fl.width = Math.round(pw);
        fl.height = Math.round(sectionsBottom - py);
        frost.radius = 44 * s;
        frost.setLayoutParams(fl);
        frost.setVisibility(View.VISIBLE);

        float panelPitch = (PANEL_TILE_WIDTH + PANEL_TILE_GAP) * s;
        float panelH = PANEL_TILE_HEIGHT * s;
        boolean showInputs = !inputTiles.isEmpty();
        boolean showInputHeader = showInputs && prefs.showHdrs();
        inputsHdr.setVisibility(showInputHeader ? View.VISIBLE : View.GONE);
        if (showInputs) {
            float tileTop = sectionsBottom + botGap;
            if (showInputHeader) moveTo(inputsHdr, mx, tileTop - 46 * s);
            int inputCols = Math.max(1, (int) ((W - 2 * mx + PANEL_TILE_GAP * s) / panelPitch));
            float rowSpacing = panelH + PANEL_TILE_GAP * s;
            for (int i = 0; i < inputTiles.size(); i++) {
                moveTo(inputTiles.get(i), mx + (i % inputCols) * panelPitch, tileTop + (i / inputCols) * rowSpacing);
            }
            sectionsBottom = tileTop + ((inputTiles.size() - 1) / inputCols) * rowSpacing + panelH;
        }

        float panelY = sectionsBottom + botGap, hdrY = panelY - 46 * s;
        boolean pt = prefs.panelTop();
        setHdr.setVisibility(pt || !prefs.showHdrs() ? View.GONE : View.VISIBLE);
        for (GlassView g : setTiles) show(g, !pt);
        if (pt) {
            // нижней панели нет: ни заголовка, ни кнопок, ни отступа под ними — ниже матовой панели остаётся лишь небольшой край
            contentH = Math.round(sectionsBottom + END_PAD * s);
        } else {
            moveTo(setHdr, mx, hdrY);
            for (int i = 0; i < SET_COUNT; i++) moveTo(setTiles[i], mx + i * panelPitch, panelY);
            contentH = Math.round(panelY + panelH + BOTTOM_PAD * s);     // фиксированный отступ снизу после «Настроек»
        }
        // ScrollView меряет содержимое без учёта заданной высоты (по самому нижнему элементу), поэтому нижний отступ задаём минимальной высотой
        content.setMinimumHeight(contentH);
        ViewGroup.LayoutParams lp = content.getLayoutParams();
        lp.height = Math.max(contentH, Math.round(H - vy));
        content.setLayoutParams(lp);
        content.requestLayout();
    }

    private void show(Dialog d) {
        dialog = d;
        d.show();
    }

    // ------------------------------------------------------------ настройки / данные
    private String sigOf() {
        return prefs.hidden().toString() + prefs.order() + prefs.theme() + prefs.showDate()
                + prefs.cols() + prefs.bgMode() + prefs.showSourceBtn() + prefs.showInputsRow() + prefs.showSearchBtn() + prefs.showVpnBtn() + prefs.showNetBtn() + prefs.showGearBtn() + prefs.showWeather() + prefs.panelTop()
                + prefs.showTitle() + prefs.showHdrs() + prefs.clock12();
    }

    /**
     * Вертикальная раскладка зависит от того, показаны ли название приложения и подписи категорий: скрытое место не остаётся пустым.
     * Без названия и подписей отступ от виджета погоды до матовой панели равен отступу от неё до нижних кнопок.
     */
    private void geom() {
        boolean ti = prefs.showTitle(), hd = prefs.showHdrs();
        float top = ti ? (hd ? 335 : 325) : (194 + (hd ? 48 : 0));       // верх матовой панели от верха экрана, в условных пикселях
        vy = (top - 62) * s;
        frostTop = top * s;
        placeNotice();
        botGap = (hd ? 131 : 64) * s;                                    // от низа панели до нижних кнопок
        if (sv != null) {
            ViewGroup.LayoutParams lp = sv.getLayoutParams();
            lp.height = Math.round((root.getHeight() > 0 ? root.getHeight() : H) - vy);
            moveTo(sv, 0, vy);
        }
    }

    /** Название выбранного приложения и подписи категорий — по настройкам. */
    private void applyLabels() {
        shelfTitle.setVisibility(prefs.showTitle() ? View.VISIBLE : View.INVISIBLE);
        boolean hd = prefs.showHdrs();
        appsHdr.setVisibility(hd ? View.VISIBLE : View.INVISIBLE);
        setHdr.setVisibility(hd && !prefs.panelTop() ? View.VISIBLE : View.GONE);
    }

    private void applyPrefs() {
        sig = sigOf();
        geom();
        computeGrid();
        dateTv.setVisibility(prefs.showDate() ? View.VISIBLE : View.GONE);
        show(weatherPill, prefs.showWeather());
        updateClock();
        layoutTop();
        applyLabels();
    }

    /** Режим фона: 0 градиент, 1 картинка, 2 видео. Без файла — фиксированный градиент. */
    private void applyBackground() {
        int mode = prefs.bgMode();
        if (mode == 1 && !Bg.has(this)) mode = 0;
        if (mode == 2 && !Bg.hasVideo(this)) mode = 0;
        bg.setMode(mode);
        bg.setAccent(prefs.themeColor());
        if (mode == 2) {
            video.setVisibility(View.VISIBLE);
            video.setSource(Bg.videoFile(this));
            if (resumed) video.play();
        } else {
            video.stop();
            video.setVisibility(View.GONE);
        }
    }

    private void loadApps() {
        Weather.IO.execute(() -> {
            final List<AppRepo.AppItem> l = AppRepo.loadAll(this);
            runOnUiThread(() -> {
                all = l;
                if (deletePkg != null) {
                    boolean gone = System.currentTimeMillis() - deleteAt < 120000;
                    for (AppRepo.AppItem x : l) if (x.pkg.equals(deletePkg)) gone = false;
                    if (gone) pendingFocus = deleteIdx;     // как при скрытии: на место удалённого встало правое соседнее, у крайнего правого — левое
                    deletePkg = null;
                }
                applyApps();
            });
        });
    }

    private void applyApps() {
        visible = AppRepo.visible(all, prefs);
        computeGrid();
        rebuildTiles();
        layoutContent();
        sig = sigOf();
        // нижние кнопки при смене числа рядов съезжают вниз/вверх, а подсветка — отдельный вид и остаётся на прежнем месте:
        // если фокус на одной из них (не на плитке приложения), ведём подсветку за ней
        View cf = getCurrentFocus();
        if (cf != null && !(cf instanceof AppTile) && cf instanceof GlassView && cf.getParent() == content) moveHighlight(cf);
        if (pendingFocus >= 0 && !tiles.isEmpty()) {
            int fi = Math.min(pendingFocus, tiles.size() - 1);
            pendingFocus = -1;
            tiles.get(fi).requestFocus();
        } else if (getCurrentFocus() == null || !getCurrentFocus().isFocused() || (!userMoved && !tiles.isEmpty())) {
            if (!tiles.isEmpty()) tiles.get(0).requestFocus(); else weatherPill.requestFocus();
        }
    }

    private void rebuildTiles() {
        boolean hadFocus = getCurrentFocus() instanceof AppTile;
        String focusedPkg = hadFocus ? ((AppTile) getCurrentFocus()).item.pkg : null;
        for (AppTile t : tiles) { content.removeView(t); slots.remove(t); }
        tiles.clear();
        AppTile toFocus = null;
        float rad = 22 * s;
        for (int i = 0; i < visible.size(); i++) {
            final AppRepo.AppItem it = visible.get(i);
            final AppTile t = new AppTile(this, it, prefs.themeColor());
            t.radius = rad;
            t.setOnFocusChangeListener(focusL);
            t.setOnClickListener(v -> launch(it));
            t.setOnLongClickListener(v -> { appMenu(t); return true; });
            placeC(t, slotX(i), slotY(i), tw, th);
            tiles.add(t);
            if (it.pkg.equals(focusedPkg)) toFocus = t;
        }
        hlGridR.bringToFront();       // кольцо должно лежать поверх только что добавленных плиток
        if (toFocus != null) toFocus.requestFocus();
    }

    private void launch(final AppRepo.AppItem it) {
        Dialog d = Parental.guard(this, prefs, it.pkg, it.label, () -> doLaunch(it));      // приложение под PIN-кодом — сначала спросим код
        if (d != null) show(d);
    }

    private void doLaunch(AppRepo.AppItem it) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN).setComponent(it.component())
                    .addCategory(Intent.CATEGORY_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
        } catch (Throwable t) {
            Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(it.pkg);
            if (i == null) i = getPackageManager().getLaunchIntentForPackage(it.pkg);
            if (i != null) UI.start(this, i); else UI.start(this, new Intent());
        }
    }

    private void appMenu(final AppTile t) {
        if (kidBlocked()) return;
        String[] items = {L.t("Открыть"), L.t("Переместить"), L.t("Скрыть"), L.t("Удалить"), L.t("О приложении")};
        show(GlassDialog.menu(this, t.item.label, items, i -> {
            switch (i) {
                case 0: launch(t.item); break;
                case 1: startMove(t); break;
                case 2: {
                    java.util.Set<String> hs = prefs.hidden();
                    hs.add(t.item.pkg);
                    prefs.setHidden(hs);
                    pendingFocus = Math.max(0, tiles.indexOf(t));      // выбор переходит на соседнее приложение (то, что встало на место скрытого)
                    applyApps();
                    break;
                }
                case 3:
                    deletePkg = t.item.pkg;
                    deleteIdx = Math.max(0, tiles.indexOf(t));
                    deleteAt = System.currentTimeMillis();
                    UI.start(this, new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + t.item.pkg)));
                    break;
                case 4: UI.start(this, new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + t.item.pkg))); break;
            }
        }));
    }

    // ------------------------------------------------------------ перемещение приложений
    private void startMove(AppTile t) {
        moving = true;
        movingTile = t;
        t.requestFocus();
        t.focusScale = 1.14f;
        t.applyFocusScale();
        hlGrid.tint = 0xFFFFD27A; hlGridR.tint = 0xFFFFD27A;
        moveHighlight(t);
        shelfTitle.setText(L.t("Перемещение: ") + t.item.label);
    }

    private void endMove() {
        moving = false;
        if (movingTile != null) {
            movingTile.focusScale = 1.08f;
            movingTile.applyFocusScale();
        }
        hlGrid.tint = 0xFFFFFFFF; hlGridR.tint = 0xFFFFFFFF;
        AppRepo.saveOrder(prefs, visible);
        sig = sigOf();
        movingTile = null;
        View f = getCurrentFocus();
        if (f != null) onFocused(f);
    }

    private boolean userMoved, longFired, swallowUp;
    private static boolean kcNotBack(KeyEvent e) { return e.getKeyCode() != KeyEvent.KEYCODE_BACK; }
    private Runnable longRun;

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) userMoved = true;
        trackUnlockKeys(e);
        // Свой разбор «короткое / долгое нажатие ОК» на плитках: не зависит от того, как пульт шлёт повтор клавиши
        View fv = getCurrentFocus();
        int kc = e.getKeyCode();
        // из верхнего ряда в сторону «в никуда» выбор не уходит: на список приложений — только кнопкой «Вниз»
        if (!moving && ((fv == btnGear && kc == KeyEvent.KEYCODE_DPAD_RIGHT) || (fv == weatherPill && kc == KeyEvent.KEYCODE_DPAD_LEFT))) return true;
        if (!moving && fv instanceof AppTile) {
            final AppTile ft = (AppTile) fv;
            if (kc == KeyEvent.KEYCODE_MENU) {
                if (e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0) appMenu(ft);
                return true;
            }
            if (kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) {
                    if (e.getRepeatCount() == 0) {
                        longFired = false;
                        if (longRun != null) h.removeCallbacks(longRun);
                        longRun = () -> { longFired = true; appMenu(ft); };
                        h.postDelayed(longRun, 450);
                    } else if (!longFired) {            // клавиша удерживается и шлёт повторы — это тоже долгое нажатие
                        if (longRun != null) h.removeCallbacks(longRun);
                        longFired = true;
                        appMenu(ft);
                    }
                } else if (e.getAction() == KeyEvent.ACTION_UP) {
                    if (longRun != null) h.removeCallbacks(longRun);
                    if (!longFired && !swallowUp) launch(ft.item);   // отпускание ОК после подтверждения перестановки — не запуск
                    longFired = false;
                    swallowUp = false;
                }
                return true;
            }
        }
        if (moving) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                int i = tiles.indexOf(movingTile), ni = i;
                switch (e.getKeyCode()) {
                    case KeyEvent.KEYCODE_DPAD_LEFT: ni = i - 1; break;
                    case KeyEvent.KEYCODE_DPAD_RIGHT: ni = i + 1; break;
                    case KeyEvent.KEYCODE_DPAD_UP: ni = i - cols; break;
                    case KeyEvent.KEYCODE_DPAD_DOWN: ni = i + cols; break;
                    case KeyEvent.KEYCODE_DPAD_CENTER:
                    case KeyEvent.KEYCODE_ENTER:
                    case KeyEvent.KEYCODE_BACK:
                        if (e.getRepeatCount() == 0) { endMove(); swallowUp = kcNotBack(e); }
                        return true;
                    default: return super.dispatchKeyEvent(e);
                }
                if (ni >= 0 && ni < tiles.size() && ni != i) {
                    visible.add(ni, visible.remove(i));
                    tiles.add(ni, tiles.remove(i));
                    for (int k = 0; k < tiles.size(); k++) moveTo(tiles.get(k), slotX(k), slotY(k));
                    moveHighlight(movingTile);
                    ensureVisible(movingTile);
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    // ------------------------------------------------------------ фокус и подсветка
    private void moveHighlight(final View v) {
        RectF r = slots.get(v);
        if (r == null) return;
        if (!v.isLaidOut()) {        // при запуске фокус ставится раньше, чем плитки размещены: подсветку покажем, когда будет готово
            for (HighlightView hv : hls()) hv.hide();
            v.post(() -> { if (v.hasFocus() && v.isLaidOut()) moveHighlight(v); });
            return;
        }
        // рамка строится по реальному (целому) положению плитки: слоты дробные, а сама плитка округляется отдельно по краям и размеру,
        // из-за чего рамка по высоте/ширине расходилась с плиткой на пиксель
        if (v.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) v.getLayoutParams();
            if (lp.width > 0 && lp.height > 0) r = new RectF(lp.leftMargin, lp.topMargin, lp.leftMargin + lp.width, lp.topMargin + lp.height);
        }
        boolean inGrid = v.getParent() == content;
        boolean pill = v instanceof GlassView && ((GlassView) v).solidFocus;
        // таблетка (кнопки, панель) лежит под самой кнопкой, а кольцо плитки — поверх неё, чтобы закрыть сглаженный край плитки
        HighlightView hl = pill ? (inGrid ? hlGrid : hlTop) : (inGrid ? hlGridR : hlTopR);
        for (HighlightView hv : hls()) if (hv != hl) hv.hide();
        hl.solid = pill;
        float sc = v instanceof GlassView ? ((GlassView) v).focusScale : 1.08f;
        // рамка точно по краю увеличенной плитки, в целых пикселях: раньше +0.5*s с округлением вверх делало низ/право на пиксель больше
        int dx = Math.round(r.width() * (sc - 1f) / 2f), dy = Math.round(r.height() * (sc - 1f) / 2f);
        RectF t = new RectF(r.left - dx, r.top - dy, r.right + dx, r.bottom + dy);
        float rad = v instanceof GlassView ? ((GlassView) v).radius : 20 * s;
        hl.moveTo(t, Math.min(rad * (t.height() / r.height()), t.height() / 2f));
    }

    /** Подкручивает прокрутку так, чтобы плитка с подсветкой целиком была видна. */
    private void ensureVisible(View v) {
        RectF r = slots.get(v);
        if (r == null || v.getParent() != content) return;
        float pad = BOTTOM_PAD * s;
        int vh = Math.round(H - vy), top = sv.getScrollY();
        if (root.getHeight() > 0) vh = Math.min(vh, Math.round(root.getHeight() - vy));   // реально видимая область
        if (sv.getHeight() > 0) vh = Math.min(vh, sv.getHeight());
        int ch = Math.max(contentH, vh);
        int target = top;
        if (r.top - pad < top) target = Math.round(r.top - pad);
        else if (r.bottom + pad > top + vh) target = Math.round(r.bottom + pad - vh);
        if (r.top < 160 * s) target = 0;
        target = Math.max(0, Math.min(target, Math.max(0, ch - vh)));
        if (target != top) sv.scrollTo(0, target);
    }

    private void onFocused(View v) {
        if (v instanceof GlassView) moveHighlight(v);
        ensureVisible(v);
        if (moving) return;
        if (v instanceof AppTile) {
            setShelf(((AppTile) v).item.label);
        } else if (v == btnNet) {
            setShelf(netTitle());
        } else if (v instanceof GlassView && ((GlassView) v).title != null) {
            setShelf(((GlassView) v).title);
        }
    }

    private void setShelf(String title) {
        if (!title.contentEquals(shelfTitle.getText())) {
            shelfTitle.setText(title);
        }
    }

    // ------------------------------------------------------------ часы и статус сети
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateClock();
            long now = System.currentTimeMillis();
            h.postDelayed(this, 60000 - now % 60000 + 30);
        }
    };

    private void updateClock() {
        Date d = new Date();
        if (prefs.clock12()) {
            // 12-часовой формат: «7:05», а «AM/PM» — мелким шрифтом справа (ширину часов см. в layoutTop)
            String t = new SimpleDateFormat("h:mm", Locale.getDefault()).format(d);
            String ap = new SimpleDateFormat("a", Locale.US).format(d).toUpperCase(Locale.US);
            android.text.SpannableString sp = new android.text.SpannableString(t + " " + ap);
            sp.setSpan(new android.text.style.RelativeSizeSpan(CLOCK_AP), t.length(), sp.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            clock.setText(sp);
            // ширина записи меняется только при переходе 9→10 и 12→1: тогда капсула один раз пересаживается к часам
            if (capsule != null && Math.abs(android.text.Layout.getDesiredWidth(sp, clock.getPaint()) - clockTextW) > 2) layoutTop();
        } else {
            clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(d));
        }
        String ds = new SimpleDateFormat(L.en() ? "EEEE, MMMM d" : "EEEE, d MMMM", L.locale()).format(d);
        dateTv.setText(ds.toUpperCase(L.locale()));
    }

    private int fastPolls;

    /** После возврата на рабочий стол первые ~40 с проверяем сеть каждые 1,5 с (VPN поднимается не сразу), потом реже. */
    private void startNetPoll() {
        fastPolls = 27;
        h.removeCallbacks(netPoll);
        h.postDelayed(netPoll, 800);
    }

    private final Runnable netPoll = new Runnable() {
        @Override public void run() {
            updateNet();
            h.postDelayed(this, fastPolls-- > 0 ? 1500 : 4000);
        }
    };

    /** Запасной признак VPN: поднятый туннельный интерфейс (tun/ppp/wg/tap). */
    private static boolean tunnelUp() {
        try {
            java.util.Enumeration<java.net.NetworkInterface> e = java.net.NetworkInterface.getNetworkInterfaces();
            while (e != null && e.hasMoreElements()) {
                java.net.NetworkInterface i = e.nextElement();
                String n = i.getName();
                if (i.isUp() && (n.startsWith("tun") || n.startsWith("ppp") || n.startsWith("wg") || n.startsWith("tap"))) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private void registerNet() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            netCb = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network n) { h.post(() -> updateNet()); }
                @Override public void onLost(Network n) { h.post(() -> updateNet()); }
                @Override public void onCapabilitiesChanged(Network n, NetworkCapabilities c) { h.post(() -> updateNet()); }
            };
            cm.registerNetworkCallback(new NetworkRequest.Builder()
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), netCb);
        } catch (Throwable ignored) {
            netCb = null;
        }
    }

    private void unregisterNet() {
        try {
            if (netCb != null) ((ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE)).unregisterNetworkCallback(netCb);
        } catch (Throwable ignored) {}
        netCb = null;
    }

    private String netTitle() {
        if (noInet) return vpn ? L.t("Нет доступа в интернет (проверьте VPN)") : L.t("Подключено, но нет доступа в интернет");
        return eth ? L.t("Проводное интернет-подключение") : wifi ? L.t("Беспроводное интернет-подключение") : L.t("Нет подключения");
    }

    private void updateNet() {
        boolean nv = false, ne = false, nw = false;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities c = cm.getNetworkCapabilities(n);
                if (c == null) continue;
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) nv = true;
                else if (c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    if (c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) ne = true;
                    else if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) nw = true;
                }
            }
        } catch (Throwable ignored) {}
        if (!nv && tunnelUp()) nv = true;
        // активная сеть (при включённом VPN — это сам VPN) не прошла проверку доступа в интернет
        boolean bad = false;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            Network an = cm.getActiveNetwork();
            NetworkCapabilities ac = an == null ? null : cm.getNetworkCapabilities(an);
            bad = (ne || nw || nv) && ac != null && !ac.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Throwable ignored) {}
        long now = android.os.SystemClock.elapsedRealtime();
        if (!bad) badSince = 0;
        else if (badSince == 0) badSince = now;
        // сразу после подключения система ещё проверяет сеть — ждём несколько секунд, чтобы значок не мигал
        boolean nn = bad && now - badSince >= 5000;
        if (bad && !nn) h.postDelayed(this::updateNet, 5200 - (now - badSince));
        boolean changed = nv != vpn || ne != eth || nw != wifi || nn != noInet;
        vpn = nv; eth = ne; wifi = nw; noInet = nn;
        if (!changed && btnNet.icon != 0 && slots.get(capsule).width() > 0 && btnNet.title != null) return;
        btnVpn.iconColor = vpn ? 0xFFFFFFFF : 0x66FFFFFF;
        btnVpn.title = vpn ? L.t("VPN включён") : L.t("VPN выключен");
        btnVpn.invalidate();
        btnVpn.postInvalidateOnAnimation();
        if (btnVpn.getParent() instanceof View) ((View) btnVpn.getParent()).invalidate();
        btnNet.icon = eth ? (noInet ? Icons.ETH_NOINET : Icons.ETH) : wifi ? (noInet ? Icons.WIFI_OFF : Icons.WIFI) : Icons.WIFI_OFF;
        btnNet.title = netTitle();
        btnNet.invalidate();
        layoutTop();
        View f = getCurrentFocus();
        if (f == btnNet || f == btnVpn) onFocused(f);      // подпись внизу обновляется сразу, не дожидаясь, пока уйдёшь и вернёшься
    }

    // ------------------------------------------------------------ поиск
    /** Поиск в выбранном приложении; выбор запоминается (повторно — долгое нажатие на лупу или настройки лаунчера). */
    private void onSearchClick(boolean forcePick) {
        final List<SearchHelper.Provider> ps = SearchHelper.find(this);
        if (ps.isEmpty()) {
            android.widget.Toast.makeText(this, L.t("Приложения для поиска не найдены. Установите приложение с поиском"), android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        SearchHelper.Provider ch = forcePick ? null : SearchHelper.chosen(prefs, ps);
        if (ch != null) { SearchHelper.launch(this, ch); return; }
        show(GlassDialog.menu(this, L.t("Приложение для поиска"), SearchHelper.names(ps), i -> {
            prefs.setSearchApp(ps.get(i).key());
            SearchHelper.launch(this, ps.get(i));
        }));
    }

    // ------------------------------------------------------------ VPN и источники сигнала
    private void onVpnClick() {
        String pkg = prefs.vpnPkg();
        boolean installed = false;
        if (!pkg.isEmpty()) {
            try { getPackageManager().getPackageInfo(pkg, 0); installed = true; } catch (Throwable ignored) {}
        }
        if (!installed) { pickVpnClient(); return; }
        VpnControl.toggle(this, pkg, !vpn);
        startNetPoll();
    }

    /** Выбор клиента VPN прямо с главного экрана (долгое нажатие на ключ) — то же, что в настройках лаунчера. */
    private void pickVpnClient() {
        final List<AppRepo.AppItem> cl = VpnControl.clients(this, all);
        if (cl.isEmpty()) {
            android.widget.Toast.makeText(this, L.t("VPN-клиенты не найдены. Установите приложение и выберите его в настройках лаунчера"), android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[cl.size()];
        for (int i = 0; i < names.length; i++) names[i] = cl.get(i).label;
        show(GlassDialog.menu(this, L.t("Клиент для VPN"), names, i -> {
            prefs.setVpnPkg(cl.get(i).pkg);
            VpnControl.toggle(this, cl.get(i).pkg, !vpn);
        }));
    }

    /** Кнопка «Источник» нужна только телевизору: у приставки нет входных портов (HDMI/AV/антенна). */
    private void refreshInputs() {
        inputs.clear();
        try {
            android.media.tv.TvInputManager tm = (android.media.tv.TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
            if (tm != null) {
                // HDMI-порт с подключённым CEC-устройством система показывает дважды: сам порт и отдельный вход самого устройства
                // (его родитель — этот порт). Оба ведут на одно и то же, поэтому порт, у которого есть такой дочерний вход, пропускаем.
                List<android.media.tv.TvInputInfo> all = tm.getTvInputList();
                java.util.Set<String> parents = new java.util.HashSet<>();
                for (android.media.tv.TvInputInfo i : all) {
                    if (i.isPassthroughInput() && i.getParentId() != null) parents.add(i.getParentId());
                }
                java.util.Map<String, String> labelById = new java.util.HashMap<>();
                for (android.media.tv.TvInputInfo i : all) labelById.put(i.getId(), rawLabel(i));
                inputLabel.clear();
                for (android.media.tv.TvInputInfo i : all) {
                    if (!i.isPassthroughInput() || parents.contains(i.getId())) continue;
                    String lb = rawLabel(i);
                    // у входа подключённого устройства название бывает пустым или «null» — тогда берём название порта (HDMI 1…)
                    if (lb.isEmpty() && i.getParentId() != null) lb = labelById.get(i.getParentId()) == null ? "" : labelById.get(i.getParentId());
                    if (lb.isEmpty()) continue;
                    int kind = sourceIcon(i, lb);
                    boolean hdmi = kind == Icons.SRC_HDMI;
                    boolean av = kind == Icons.SRC_AV || kind == Icons.SRC_SVIDEO || kind == Icons.SRC_COMPONENT
                            || kind == Icons.SRC_VGA || kind == Icons.SRC_DVI || kind == Icons.SRC_SCART;
                    if (!hdmi && !av) continue;            // только HDMI и AV: без антенны, USB и прочих служебных входов
                    try { if (i.isHidden(this)) continue; } catch (Throwable ignored) {}        // скрытые в настройках ТВ входы
                    if (av) {                                // разъёмы AV, которых у телевизора по факту нет, система тоже перечисляет
                        try { if (tm.getInputState(i.getId()) == android.media.tv.TvInputManager.INPUT_STATE_DISCONNECTED) continue; } catch (Throwable ignored) {}
                    }
                    inputs.add(i);
                    // номер порта: из названия порта (у входа устройства — из названия его родителя), иначе из идентификатора (…/HW5)
                    String base = i.getParentId() != null && labelById.get(i.getParentId()) != null && !labelById.get(i.getParentId()).isEmpty()
                            ? labelById.get(i.getParentId()) : lb;
                    int num = hdmi ? hdmiNumber(i, lb, labelById.get(i.getParentId()), base) : portNumber(base, i.getId());
                    if (hdmi && num != 1000) lb = "HDMI " + num;        // названия, заданные в самом ТВ («Xbox», «Приставка»…), не используем
                    inputLabel.put(i.getId(), lb);
                    inputOrder.put(i.getId(), num);
                }
                // HDMI по возрастанию номера порта, под ними AV (тоже по порядку)
                java.util.Collections.sort(inputs, (a, b) -> {
                    int ka = sourceIcon(a, inputLabel.get(a.getId())) == Icons.SRC_HDMI ? 0 : 1;
                    int kb = sourceIcon(b, inputLabel.get(b.getId())) == Icons.SRC_HDMI ? 0 : 1;
                    if (ka != kb) return ka - kb;
                    int na = inputOrder.get(a.getId()), nb = inputOrder.get(b.getId());
                    return na != nb ? Integer.compare(na, nb) : inputLabel.get(a.getId()).compareToIgnoreCase(inputLabel.get(b.getId()));
                });
            }
        } catch (Throwable ignored) {}
    }

    private final java.util.Map<String, String> inputLabel = new java.util.HashMap<>();
    private final java.util.Map<String, Integer> inputOrder = new java.util.HashMap<>();

    /** Номер в названии («HDMI 2» → 2); нет — число в конце идентификатора («…/HW5» → 5); иначе в конец списка. */
    private static int portNumber(String label, String id) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)\\s*$").matcher(label == null ? "" : label.trim());
        if (m.find()) { try { return Integer.parseInt(m.group(1)); } catch (Throwable ignored) {} }
        m = java.util.regex.Pattern.compile("(\\d+)\\s*$").matcher(id == null ? "" : id);
        if (m.find()) { try { return Integer.parseInt(m.group(1)); } catch (Throwable ignored) {} }
        return 1000;
    }

    /** Номер порта HDMI без оглядки на имена, которые пользователь дал входам в ТВ: «HDMI N» в названии → номер порта из системы → число в идентификаторе. */
    private static int hdmiNumber(android.media.tv.TvInputInfo in, String own, String parent, String base) {
        java.util.regex.Pattern pt = java.util.regex.Pattern.compile("^HDMI\\s*(\\d+)$", java.util.regex.Pattern.CASE_INSENSITIVE);
        for (String s : new String[]{own, parent}) {
            if (s == null) continue;
            java.util.regex.Matcher m = pt.matcher(s.trim());
            if (m.find()) { try { return Integer.parseInt(m.group(1)); } catch (Throwable ignored) {} }
        }
        try {
            Object info = android.media.tv.TvInputInfo.class.getMethod("getHdmiDeviceInfo").invoke(in);
            if (info != null) {
                int pid = (Integer) info.getClass().getMethod("getPortId").invoke(info);
                if (pid > 0) return pid;
            }
        } catch (Throwable ignored) {}
        return portNumber(null, in.getParentId() != null ? in.getParentId() : in.getId());
    }

    /** Название входа; пустая строка, если системе нечего показать (в том числе буквально «null»). */
    private String rawLabel(android.media.tv.TvInputInfo in) {
        CharSequence cs = null;
        // у HDMI имя, заданное пользователем в настройках ТВ, игнорируем — нужен системный «HDMI N»
        if (in.getType() != android.media.tv.TvInputInfo.TYPE_HDMI) { try { cs = in.loadCustomLabel(this); } catch (Throwable ignored) {} }
        if (cs == null || cs.length() == 0) { try { cs = in.loadLabel(this); } catch (Throwable ignored) {} }
        String s = cs == null ? "" : cs.toString().trim();
        return s.equalsIgnoreCase("null") ? "" : s;
    }

    private boolean showSource() {
        return prefs.showSourceBtn();
    }

    /** Значок по типу разъёма; если система тип не сообщает (OTHER) — по названию. */
    private static int sourceIcon(android.media.tv.TvInputInfo in, String label) {
        switch (in.getType()) {
            case android.media.tv.TvInputInfo.TYPE_HDMI: return Icons.SRC_HDMI;
            case android.media.tv.TvInputInfo.TYPE_DISPLAY_PORT: return Icons.SRC_DP;
            case android.media.tv.TvInputInfo.TYPE_COMPOSITE: return Icons.SRC_AV;
            case android.media.tv.TvInputInfo.TYPE_SVIDEO: return Icons.SRC_SVIDEO;
            case android.media.tv.TvInputInfo.TYPE_COMPONENT: return Icons.SRC_COMPONENT;
            case android.media.tv.TvInputInfo.TYPE_VGA: return Icons.SRC_VGA;
            case android.media.tv.TvInputInfo.TYPE_DVI: return Icons.SRC_DVI;
            case android.media.tv.TvInputInfo.TYPE_SCART: return Icons.SRC_SCART;
            case android.media.tv.TvInputInfo.TYPE_TUNER: return Icons.SRC_TUNER;
            default: break;
        }
        String l = label.toLowerCase(java.util.Locale.ROOT);
        if (l.contains("hdmi")) return Icons.SRC_HDMI;
        if (l.contains("displayport") || l.matches(".*\\bdp\\b.*")) return Icons.SRC_DP;
        if (l.contains("component") || l.contains("ypbpr")) return Icons.SRC_COMPONENT;
        if (l.contains("composite") || l.contains("cvbs") || l.matches(".*\\bav\\s*\\d?\\b.*")) return Icons.SRC_AV;
        if (l.contains("vga") || l.matches(".*\\bpc\\b.*")) return Icons.SRC_VGA;
        if (l.contains("dvi")) return Icons.SRC_DVI;
        if (l.contains("scart")) return Icons.SRC_SCART;
        if (l.contains("atv") || l.contains("dtv") || l.contains("tuner") || l.contains("антенн") || l.contains("кабел") || l.contains("эфир")) return Icons.SRC_TUNER;
        return Icons.INPUT;
    }

    /**
     * Системные панели выбора источника разных прошивок (компонент или действие). Перебираем по очереди и берём первую,
     * которая запустилась: у одного телевизора есть панель MediaTek, у другого Xiaomi, у третьего стандартная Google.
     */
    private static final String[][] SYSTEM_SOURCE_PANELS = {
            // действие + компонент (Xiaomi, панель из приложения Live TV: работает и при отключённом стоковом лаунчере)
            {"act+cmp", "com.android.tv.action.VIEW_INPUTS", "com.mitv.livetv", "com.mitv.livetv.input.SelectInputActivity"},
            {"act", "com.android.tv.action.VIEW_INPUTS"},
            {"com.mediatek.wwtv.tvcenter", "com.mediatek.wwtv.tvcenter.nav.inputpanel.InputsPanelActivity"},
            {"act", "com.xiaomi.mitv.settings.INPUTSOURCE_POPUP"},
            {"act", "com.mitv.tvhome.atv.app.tv.INPUTSOURCE_POPUP"},
            {"com.formovie.fmmediaselect", "com.formovie.fmmediaselect.MainActivity"},
            {"com.google.android.tvlauncher", "com.google.android.tvlauncher.inputs.InputsPanelActivity"},
    };

    private boolean openSystemSourcePanel() {
        for (String[] p : SYSTEM_SOURCE_PANELS) {
            try {
                Intent i;
                if ("act+cmp".equals(p[0])) i = new Intent(p[1]).setClassName(p[2], p[3]);
                else if ("act".equals(p[0])) i = new Intent(p[1]);
                else i = new Intent().setClassName(p[0], p[1]);
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (Throwable ignored) {
                // такой панели в прошивке нет или она закрыта для сторонних приложений — пробуем следующую
            }
        }
        return false;
    }

    private void updateInputsRow() {
        if (!resumed) return;
        boolean hadInputTiles = !inputTiles.isEmpty();
        View focused = getCurrentFocus();
        Object focusedInput = inputTiles.contains(focused) ? focused.getTag() : null;
        for (GlassView tile : inputTiles) {
            content.removeView(tile);
            slots.remove(tile);
        }
        inputTiles.clear();
        GlassView restoreFocus = null;
        if (prefs.showInputsRow()) {
            refreshInputs();
            for (android.media.tv.TvInputInfo input : inputs) {
                final String id = input.getId();
                String label = inputLabel.get(id);
                GlassView tile = glass(22 * s, sourceIcon(input, label), label);
                tile.title = label;
                tile.setContentDescription(label);
                tile.setTag(id);
                tile.frosted = true;
                tile.setOnClickListener(v -> openInput(id));
                placeC(tile, 0, 0, PANEL_TILE_WIDTH * s, PANEL_TILE_HEIGHT * s);
                inputTiles.add(tile);
                if (id.equals(focusedInput)) restoreFocus = tile;
            }
        }
        layoutContent();
        if (restoreFocus != null) restoreFocus.requestFocus();
        else if (focusedInput != null && !inputTiles.isEmpty()) inputTiles.get(0).requestFocus();
        else if (focusedInput != null && !tiles.isEmpty()) tiles.get(0).requestFocus();
        View current = getCurrentFocus();
        if (hadInputTiles && inputTiles.isEmpty()) {
            hlGrid.hide();
            hlGridR.hide();
            if (!(current instanceof GlassView)) setShelf("");
        }
        if (current instanceof GlassView) onFocused(current);
    }

    private void openInput(String id) {
        try {
            Uri uri = android.media.tv.TvContract.buildChannelUriForPassthroughInput(id);
            startActivity(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, android.media.tv.TvContract.Channels.CONTENT_ITEM_TYPE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (android.content.ActivityNotFoundException | SecurityException e) {
            android.widget.Toast.makeText(this, L.t("Не удалось переключить источник"), android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private void pickSource() {
        if (openSystemSourcePanel()) return;
        refreshInputs();
        if (inputs.isEmpty()) {
            android.widget.Toast.makeText(this, L.t("Источники сигнала не найдены"), android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        String[] names = new String[inputs.size()];
        int[] kinds = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            android.media.tv.TvInputInfo in = inputs.get(i);
            names[i] = inputLabel.get(in.getId());
            kinds[i] = sourceIcon(in, names[i]);
        }
        List<android.media.tv.TvInputInfo> choices = new ArrayList<>(inputs);
        show(GlassDialog.menuIcons(this, L.t("Источник сигнала"), names, kinds, i -> openInput(choices.get(i).getId())));
    }

    // ------------------------------------------------------------ погода
    private void maybeRefreshWeather() {
        if (!prefs.hasLocation()) return;
        if (wd == null || System.currentTimeMillis() - wd.fetched > 30 * 60 * 1000L) refreshWeather(false);
    }

    private void refreshWeather(final boolean toast) {
        Weather.refresh(this, new Weather.Callback<Weather.Data>() {
            @Override public void ok(Weather.Data d) {
                wd = d;
                weatherPill.invalidate();
                if (toast) android.widget.Toast.makeText(MainActivity.this, L.t("Погода обновлена"), android.widget.Toast.LENGTH_SHORT).show();
            }
            @Override public void fail(String m) {
                if (toast) android.widget.Toast.makeText(MainActivity.this, m, android.widget.Toast.LENGTH_LONG).show();
            }
        });
    }

    private void paintWeatherPillDark(Canvas c, GlassView v, float hh, float w) {
        final int main = 0xFF1B1B22, sub = 0xFF55555F;
        if (!prefs.hasLocation()) {
            Icons.weather(c, "d_c1", hh * 0.58f, hh / 2f, hh * 0.3f, v.tp);
            v.text(c, L.t("Погода"), hh * 1.1f, hh * 0.46f, hh * 0.37f, main, 2, Paint.Align.LEFT, w - hh * 1.3f);
            v.text(c, L.t("Выберите город в настройках"),
                    hh * 1.1f, hh * 0.77f, hh * 0.26f, sub, 1, Paint.Align.LEFT, w - hh * 1.3f);
            return;
        }
        if (wd == null || wd.cur == null) {
            v.text(c, L.t("Погода…"), hh * 0.5f, hh * 0.64f, hh * 0.34f, main, 1, Paint.Align.LEFT, w);
            return;
        }
        Weather.Pt p = wd.cur;
        Icons.weather(c, p.icon, hh * 0.58f, hh / 2f, hh * 0.3f, v.tp);
        String t = Weather.temp(p.t);
        float x = hh * 1.1f;
        v.text(c, t, x, hh * 0.66f, hh * 0.53f, main, 2, Paint.Align.LEFT, 0);
        float x2 = x + v.tp.measureText(t) + hh * 0.25f, mw = w - x2 - hh * 0.35f;
        v.text(c, p.desc == null ? "" : p.desc, x2, hh * 0.45f, hh * 0.295f, main, 1, Paint.Align.LEFT, mw);
        String sb = wd.city == null ? "" : wd.city;
        if (p.feel != null) sb += (sb.isEmpty() ? "" : " · ") + L.t("ощущается как ") + Weather.temp(p.feel);
        v.text(c, sb, x2, hh * 0.77f, hh * 0.25f, sub, 1, Paint.Align.LEFT, mw);
    }

    private void paintWeatherPill(Canvas c, GlassView v) {
        float hh = v.getHeight(), w = v.getWidth();
        if (v.isFocused() && v.solidFocus) {            // белая таблетка в фокусе — тёмный текст
            paintWeatherPillDark(c, v, hh, w);
            return;
        }
        if (!prefs.hasLocation()) {
            Icons.weather(c, "d_c1", hh * 0.58f, hh / 2f, hh * 0.3f, v.tp);
            v.text(c, L.t("Погода"), hh * 1.1f, hh * 0.46f, hh * 0.37f, 0xFFFFFFFF, 2, Paint.Align.LEFT, w - hh * 1.3f);
            v.text(c, L.t("Выберите город в настройках"),
                    hh * 1.1f, hh * 0.77f, hh * 0.26f, 0xAAFFFFFF, 1, Paint.Align.LEFT, w - hh * 1.3f);
            return;
        }
        if (wd == null || wd.cur == null) {
            v.text(c, L.t("Погода…"), hh * 0.5f, hh * 0.64f, hh * 0.34f, 0xCCFFFFFF, 1, Paint.Align.LEFT, w);
            return;
        }
        Weather.Pt p = wd.cur;
        Icons.weather(c, p.icon, hh * 0.58f, hh / 2f, hh * 0.3f, v.tp);
        String t = Weather.temp(p.t);
        float x = hh * 1.1f;
        v.text(c, t, x, hh * 0.66f, hh * 0.53f, 0xFFFFFFFF, 2, Paint.Align.LEFT, 0);
        float tw2 = v.tp.measureText(t);
        float x2 = x + tw2 + hh * 0.25f, mw = w - x2 - hh * 0.35f;
        v.text(c, p.desc == null ? "" : p.desc, x2, hh * 0.45f, hh * 0.295f, 0xF2FFFFFF, 1, Paint.Align.LEFT, mw);
        String sub = wd.city == null ? "" : wd.city;
        if (p.feel != null) sub += (sub.isEmpty() ? "" : " · ") + L.t("ощущается как ") + Weather.temp(p.feel);
        v.text(c, sub, x2, hh * 0.77f, hh * 0.25f, 0xA6FFFFFF, 1, Paint.Align.LEFT, mw);
    }
}
