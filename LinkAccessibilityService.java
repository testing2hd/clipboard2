package com.linkrouter.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONObject;

/**
 * Floating download button over YouTube / TikTok / Instagram / Facebook.
 * Tap it and the service presses the app's own Share button, then "Copy link",
 * reads the copied link and hands it to the yt-dlp downloader. It only looks at the
 * screen after you tap the button, and nothing leaves the phone except the download itself.
 */
public class LinkAccessibilityService extends AccessibilityService {
    /** ClipWatchService checks this so the copy we trigger does not also open the clipboard popup. */
    static volatile long suppressUntil;

    private static final String[] DEFAULT_APPS = {
        "com.google.android.youtube", "app.revanced.android.youtube", "com.vanced.android.youtube",
        "com.zhiliaoapp.musically", "com.ss.android.ugc.trill", "com.zhiliaoapp.musically.go",
        "com.instagram.android", "com.facebook.katana", "com.facebook.lite"
    };
    private static final String[] SHARE = {
        "share", "send post", "send reel", "send video", "शेयर", "साझा", "compartilhar", "compartir", "partager",
        "teilen", "condividi", "paylaş", "bagikan", "поделиться", "分享", "共有", "공유", "مشاركة", "chia sẻ"
    };
    private static final String[] COPY_STRONG = {
        "copy link", "copy url", "copy video link", "link copy", "लिङ्क कपी", "लिंक कॉपी", "कपी लिङ्क", "copiar link",
        "copiar enlace", "copier le lien", "link kopieren", "copia link", "bağlantıyı kopyala", "salin link",
        "salin tautan", "копировать ссылку", "复制链接", "リンクをコピー", "링크 복사", "نسخ الرابط", "sao chép liên kết"
    };
    private static final String[] COPY_WEAK = {
        "copy", "कपी", "कॉपी", "copiar", "copier", "kopieren", "copia", "kopyala", "salin", "копировать", "复制", "コピー", "복사", "نسخ"
    };

    private final Handler h = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private TextView bubble;
    private WindowManager.LayoutParams lp;
    private FocusView focusView;
    private boolean busy;
    private int session;
    private int scrolls;

    static boolean isEnabled(Context c) {
        try {
            String s = Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.contains(c.getPackageName() + "/") && s.contains("LinkAccessibilityService");
        } catch (Throwable t) { return false; }
    }

    // ---------- lifecycle ----------

    @Override
    protected void onServiceConnected() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        try {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null && r.getPackageName() != null) handlePackage(r.getPackageName().toString());
        } catch (Throwable ignored) { }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || e.getPackageName() == null) return;
        handlePackage(e.getPackageName().toString());
    }

    @Override public void onInterrupt() { }

    @Override
    public boolean onUnbind(android.content.Intent i) {
        session++;
        removeFocus();
        hideBubble();
        return super.onUnbind(i);
    }

    // ---------- which app is in front ----------

    private Set<String> targets() {
        Set<String> s = new HashSet<>(Arrays.asList(DEFAULT_APPS));
        for (String x : csv("bubbleApps")) s.add(x);
        return s;
    }

    private List<String> csv(String key) {
        List<String> out = new ArrayList<>();
        try {
            for (String x : Config.dl(this).optString(key, "").split("[,\\n]")) {
                x = x.trim().toLowerCase(Locale.ROOT);
                if (!x.isEmpty()) out.add(x);
            }
        } catch (Throwable ignored) { }
        return out;
    }

    private boolean ignorable(String p) {
        return p.equals(getPackageName()) || p.equals("android") || p.equals("com.android.systemui")
                || p.contains("intentresolver") || p.contains("inputmethod") || p.contains("keyboard");
    }

    private void handlePackage(String p) {
        boolean enabled = true;
        try { enabled = Config.dl(this).optBoolean("bubble", true); } catch (Throwable ignored) { }
        if (targets().contains(p)) { if (enabled) showBubble(); else hideBubble(); }
        else if (!ignorable(p) && !busy) hideBubble();
    }

    // ---------- the bubble ----------

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }

    private int overlayType() {
        return Settings.canDrawOverlays(this) ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
    }

    private void showBubble() {
        if (bubble != null || wm == null) return;
        final int size = dp(46);
        final TextView b = new TextView(this);
        b.setText("\u2193");
        b.setTextColor(Color.WHITE);
        b.setTextSize(24);
        b.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0xE60E8F84);
        g.setStroke(dp(2), 0xCCFFFFFF);
        b.setBackground(g);
        b.setElevation(dp(6));

        SharedPreferences sp = getSharedPreferences("bubble", 0);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        lp = new WindowManager.LayoutParams(size, size, overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = sp.getInt("x", dm.widthPixels - size);
        lp.y = sp.getInt("y", dm.heightPixels * 2 / 5);

        b.setOnTouchListener(new View.OnTouchListener() {
            float dx, dy;
            int sx, sy;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = lp.x; sy = lp.y; dx = e.getRawX(); dy = e.getRawY(); moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float mx = e.getRawX() - dx, my = e.getRawY() - dy;
                        if (!moved && Math.hypot(mx, my) > dp(8)) moved = true;
                        if (moved) {
                            lp.x = (int) (sx + mx);
                            lp.y = (int) (sy + my);
                            try { wm.updateViewLayout(b, lp); } catch (Throwable ignored) { }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (moved) snap(b, size); else grab();
                        return true;
                    default:
                        return true;
                }
            }
        });
        try {
            wm.addView(b, lp);
            bubble = b;
        } catch (Throwable t) { bubble = null; }
    }

    private void snap(TextView b, int size) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        lp.x = (lp.x + size / 2 < dm.widthPixels / 2) ? 0 : dm.widthPixels - size;
        lp.y = Math.max(0, Math.min(lp.y, dm.heightPixels - size));
        try { wm.updateViewLayout(b, lp); } catch (Throwable ignored) { }
        getSharedPreferences("bubble", 0).edit().putInt("x", lp.x).putInt("y", lp.y).apply();
    }

    private void hideBubble() {
        if (bubble == null) return;
        try { wm.removeView(bubble); } catch (Throwable ignored) { }
        bubble = null;
    }

    /** While working the bubble shows "...", turns click-through (so gesture taps reach the app) and ignores touches. */
    private void setBusy(boolean on, String label) {
        if (bubble == null) return;
        bubble.setText(label);
        bubble.setAlpha(on ? 0.6f : 1f);
        if (on) lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try { wm.updateViewLayout(bubble, lp); } catch (Throwable ignored) { }
    }

    private boolean ours(AccessibilityNodeInfo r) {
        CharSequence p = r.getPackageName();
        return p != null && getPackageName().contentEquals(p);
    }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }

    private void done(String label) {
        busy = false;
        session++;
        setBusy(false, label);
        h.postDelayed(() -> { if (!busy && bubble != null) bubble.setText("\u2193"); }, 1500);
    }

    // ---------- the automation ----------

    private void grab() {
        if (busy) return;
        busy = true;
        scrolls = 0;
        final int sid = ++session;
        final long t0 = System.currentTimeMillis();
        suppressUntil = SystemClock.elapsedRealtime() + 9000;
        setBusy(true, "\u2026");
        findShare(sid, t0, 0);
    }

    private void fail(int sid, String msg) {
        if (sid != session) return;
        toast(msg);
        done("!");
    }

    private static class Cand {
        AccessibilityNodeInfo node;
        String label;
        int dist;
    }

    private static String label(AccessibilityNodeInfo n) {
        CharSequence t = n.getText(), d = n.getContentDescription();
        String a = t == null ? "" : t.toString();
        String b = d == null ? "" : d.toString();
        return (a.equals(b) ? a : (a + " " + b)).trim().toLowerCase(Locale.ROOT);
    }

    private void collect(AccessibilityNodeInfo n, List<Cand> out, int[] budget, int centerY) {
        if (n == null || budget[0]-- <= 0) return;
        if (n.isVisibleToUser()) {
            String l = label(n);
            if (!l.isEmpty() && l.length() <= 48) {
                Cand c = new Cand();
                c.node = n;
                c.label = l;
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                c.dist = Math.abs(r.centerY() - centerY);
                out.add(c);
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), out, budget, centerY);
    }

    private static int score(String l, List<String> words) {
        int best = 0;
        for (int i = 0; i < words.size(); i++) {
            String w = words.get(i);
            int s = l.equals(w) ? 3000 : l.startsWith(w) ? 2000 : l.contains(w) ? 1000 : 0;
            if (s > 0) best = Math.max(best, s - i);
        }
        return best;
    }

    private List<String> words(String[] base, String customKey) {
        List<String> w = new ArrayList<>(csv(customKey));
        w.addAll(Arrays.asList(base));
        return w;
    }

    /** Best match: highest score, and among equals the one nearest the middle of the screen. */
    private Cand pick(List<Cand> cands, List<String> words) {
        Cand best = null;
        int bestScore = 0;
        for (Cand c : cands) {
            int s = score(c.label, words);
            if (s == 0) continue;
            if (best == null || s > bestScore || (s == bestScore && c.dist < best.dist)) { best = c; bestScore = s; }
        }
        return best;
    }

    private void findShare(final int sid, final long t0, final int attempt) {
        if (sid != session) return;
        List<Cand> cands = new ArrayList<>();
        int centerY = getResources().getDisplayMetrics().heightPixels / 2;
        try { collect(getRootInActiveWindow(), cands, new int[]{2500}, centerY); } catch (Throwable ignored) { }
        Cand best = pick(cands, words(SHARE, "bubbleShare"));
        if (best == null) {
            if (attempt < 3) { h.postDelayed(() -> findShare(sid, t0, attempt + 1), 250); return; }
            fail(sid, "Couldn't find a Share button here. Open a video first.");
            return;
        }
        if (!click(best.node)) { fail(sid, "Couldn't press Share."); return; }
        h.postDelayed(() -> pollCopy(sid, t0, 0), 300);
    }

    private void pollCopy(final int sid, final long t0, final int attempt) {
        if (sid != session) return;
        List<Cand> cands = new ArrayList<>();
        int centerY = getResources().getDisplayMetrics().heightPixels / 2;
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) {
                for (AccessibilityWindowInfo w : ws) {
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r == null || ours(r)) continue;
                    collect(r, cands, new int[]{2500}, centerY);
                }
            }
        } catch (Throwable ignored) { }
        Cand best = pick(cands, words(COPY_STRONG, "bubbleCopy"));
        if (best == null && attempt >= 10) best = pick(cands, Arrays.asList(COPY_WEAK));
        if (best != null) {
            if (!click(best.node)) { fail(sid, "Couldn't press Copy link."); return; }
            h.postDelayed(() -> readClip(sid, t0, 0), 350);
            return;
        }
        if (attempt >= 6 && attempt % 4 == 2 && scrolls < 3) { scrolls++; scrollRow(cands); }
        if (attempt >= 27) { fail(sid, "Couldn't find \"Copy link\" in this app's share menu."); return; }
        h.postDelayed(() -> pollCopy(sid, t0, attempt + 1), 150);
    }

    /** The share row can hide "Copy link" off to the side. Only short rows in the lower screen are scrolled, never the video pager. */
    private void scrollRow(List<Cand> unused) {
        try {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws == null) return;
            for (AccessibilityWindowInfo w : ws) {
                AccessibilityNodeInfo r = w.getRoot();
                if (r == null || ours(r)) continue;
                if (scrollIn(r, dm, new int[]{2000})) return;
            }
        } catch (Throwable ignored) { }
    }

    private boolean scrollIn(AccessibilityNodeInfo n, DisplayMetrics dm, int[] budget) {
        if (n == null || budget[0]-- <= 0) return false;
        if (n.isScrollable() && n.isVisibleToUser()) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.height() < dm.heightPixels * 0.4 && r.top > dm.heightPixels * 0.35
                    && n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true;
        }
        for (int i = 0; i < n.getChildCount(); i++) if (scrollIn(n.getChild(i), dm, budget)) return true;
        return false;
    }

    private boolean click(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo c = n;
        for (int i = 0; i < 6 && c != null; i++) {
            if (c.isClickable() && c.isEnabled()) {
                if (c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                break;
            }
            c = c.getParent();
        }
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        Path p = new Path();
        p.moveTo(r.centerX(), r.centerY());
        return dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null);
    }

    // ---------- reading the copied link ----------

    private String freshText(long t0) {
        try {
            ClipData d = ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).getPrimaryClip();
            if (d == null || d.getItemCount() == 0) return null;
            if (d.getDescription().getTimestamp() < t0 - 1500) return "";   // an older clip: the copy has not happened (yet)
            CharSequence cs = d.getItemAt(0).coerceToText(this);
            return cs == null ? null : cs.toString();
        } catch (Throwable t) { return null; }
    }

    /** null = clipboard not readable from here, "" = only an old clip, otherwise the fresh text. */
    private void readClip(final int sid, final long t0, final int attempt) {
        if (sid != session) return;
        String t = freshText(t0);
        if (t == null) { focusRead(sid, t0); return; }              // background read is blocked: take focus briefly
        if (t.isEmpty()) {
            if (attempt < 4) { h.postDelayed(() -> readClip(sid, t0, attempt + 1), 250); return; }
            fail(sid, "The link wasn't copied. Try again.");
            return;
        }
        finish(sid, t);
    }

    private static class FocusView extends FrameLayout {
        Runnable onFocus;
        boolean fired;
        FocusView(Context c) { super(c); }

        @Override
        public void onWindowFocusChanged(boolean has) {
            super.onWindowFocusChanged(has);
            if (has && !fired && onFocus != null) { fired = true; onFocus.run(); }
        }
    }

    /** A 1px focusable window: while it has focus Android lets us read the clipboard. */
    private void focusRead(final int sid, final long t0) {
        removeFocus();
        final FocusView v = new FocusView(this);
        v.onFocus = () -> h.postDelayed(() -> readFocused(sid, t0, v, 0), 150);
        try {
            WindowManager.LayoutParams p = new WindowManager.LayoutParams(1, 1, overlayType(),
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            p.gravity = Gravity.TOP | Gravity.START;
            wm.addView(v, p);
            focusView = v;
        } catch (Throwable t) { fail(sid, "Couldn't read the copied link."); return; }
        h.postDelayed(() -> {
            if (focusView == v && !v.fired) { removeFocus(); fail(sid, "Couldn't read the copied link."); }
        }, 2500);
    }

    private void readFocused(int sid, long t0, FocusView v, int attempt) {
        if (sid != session || focusView != v) return;
        String t = freshText(t0);
        if (t == null || t.isEmpty()) {
            if (attempt < 4) { h.postDelayed(() -> readFocused(sid, t0, v, attempt + 1), 250); return; }
            removeFocus();
            fail(sid, "The link wasn't copied. Try again.");
            return;
        }
        finish(sid, t);   // starts the download while the overlay is still up, then removes it
    }

    private void removeFocus() {
        if (focusView == null) return;
        try { wm.removeView(focusView); } catch (Throwable ignored) { }
        focusView = null;
    }

    private void finish(int sid, String text) {
        if (sid != session) return;
        String url = Config.extractUrl(text);
        if (url == null || !Config.matches(url)) {
            removeFocus();
            fail(sid, "That isn't a supported video link.");
            return;
        }
        suppressUntil = SystemClock.elapsedRealtime() + 4000;
        try {
            DownloadService.start(this, url);
            toast("Downloading\u2026");
            removeFocus();
            done("\u2713");
        } catch (Throwable t) {
            removeFocus();
            fail(sid, "Android blocked the download from the background. Open Link Router and allow \"Display over other apps\".");
        }
    }
}
