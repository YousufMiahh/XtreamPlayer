package com.xtreamplayer.tv;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * The TV guide grid (TiviMate / Sky style): channels down the left, programmes across a
 * timeline, a line for "now". ▲▼ change channel, ◀▶ move through programmes, OK plays.
 * Drawn directly on a canvas so scrolling through thousands of channels stays smooth.
 */
public class EpgGrid extends View {
    public interface Listener {
        /** Highlight moved: the channel and the programme under it (programme may be null). */
        void onHighlight(int row, Item channel, XtreamApi.Programme p);
        void onPlay(int row);
        /** ◀ on the programme that's on now: leave the guide. */
        void onExit();
        /** ☰ or hold OK: the highlighted programme's full details. */
        void onDetails(int row, XtreamApi.Programme p);
        /** These channels are about to be on screen and need listings. */
        void onNeedListings(List<Item> channels);
    }

    private static final long HALF_HOUR = 1800;
    private static final DecelerateInterpolator EASE = new DecelerateInterpolator(2f);

    private List<Item> channels = new ArrayList<>();
    private Listener listener;
    private int sel, top;
    private float topOffset; // animated scroll position in rows
    private long windowStart, cursor; // unix seconds
    private int rowH, headerH, chanW;
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG), small = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Rect src = new Rect();
    private final SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.UK), day = new SimpleDateFormat("EEE d MMM", Locale.UK);
    private ValueAnimator scrollAnim;
    private final Runnable tick = new Runnable() { public void run() { invalidate(); } };

    public EpgGrid(Context c) {
        super(c);
        setFocusable(true);
        setFocusableInTouchMode(false);
        text.setTypeface(Ui.medium());
        text.setTextSize(Ui.sp(c, 17));
        small.setTypeface(Ui.medium());
        small.setTextSize(Ui.sp(c, 14));
        line.setStrokeWidth(Ui.dp(c, 2));
        headerH = Ui.dp(c, 44);
        chanW = Ui.dp(c, 330);
        rowH = Ui.dp(c, 72);
    }

    public void setListener(Listener l) { listener = l; }

    /** @param rowHeight match the channel list's rows so the guide lines up with it. */
    public void setChannels(List<Item> list, int selected, int firstVisible, int rowHeight) {
        channels = list == null ? new ArrayList<Item>() : list;
        if (rowHeight > 0) rowH = rowHeight;
        sel = Math.max(0, Math.min(selected, channels.size() - 1));
        top = Math.max(0, Math.min(firstVisible, sel));
        topOffset = top;
        long now = now();
        windowStart = now - now % HALF_HOUR;
        cursor = now;
        requestListings();
        highlight();
        invalidate();
    }

    public int selected() { return sel; }

    public int firstVisible() { return top; }

    public int headerHeight() { return headerH; }

    private static long now() { return System.currentTimeMillis() / 1000L; }

    private int visibleRows() { return Math.max(1, (getHeight() - headerH) / Math.max(1, rowH)); }

    private long windowLength() { return 2 * 3600; } // two hours across the screen

    // ------------------------------------------------------------ keys

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (channels.isEmpty()) return super.onKeyDown(code, e);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (sel >= channels.size() - 1) return true;
                moveRow(sel + 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                if (sel == 0) return super.onKeyDown(code, e); // up into the top bar
                moveRow(sel - 1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                XtreamApi.Programme p = current();
                long next = p != null && p.stop > cursor ? p.stop : nextListedStart(cursor);
                long limit = now() + 24 * 3600;
                if (next > limit) return true;
                cursor = next + 1;
                long end = windowStart + windowLength();
                while (cursor >= end - 300) { // keep the highlighted programme in view
                    windowStart += HALF_HOUR;
                    end += HALF_HOUR;
                }
                highlight();
                invalidate();
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_LEFT: {
                long now = now();
                XtreamApi.Programme p = current();
                boolean onNow = p != null ? p.start <= now : cursor - HALF_HOUR < now;
                if (onNow) { // already on what's on now: step back out of the guide
                    if (listener != null) listener.onExit();
                    return true;
                }
                cursor = Math.max(now, p != null ? p.start - 1 : cursor - HALF_HOUR);
                while (cursor < windowStart) windowStart -= HALF_HOUR;
                long nowFloor = now - now % HALF_HOUR;
                if (windowStart < nowFloor) windowStart = nowFloor;
                highlight();
                invalidate();
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                // Quick press = watch (on release); hold = full programme details
                if (e.getRepeatCount() == 0) {
                    okDown = true;
                    okHeld = false;
                } else if (okDown && !okHeld && e.getEventTime() - e.getDownTime() >= 450) {
                    okHeld = true;
                    if (listener != null) listener.onDetails(sel, current());
                }
                return true;
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_INFO:
                if (e.getRepeatCount() == 0 && listener != null) listener.onDetails(sel, current());
                return true;
        }
        return super.onKeyDown(code, e);
    }

    private boolean okDown, okHeld;

    @Override
    public boolean onKeyUp(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (okDown && !okHeld && listener != null && !channels.isEmpty()) listener.onPlay(sel);
            okDown = okHeld = false;
            return true;
        }
        return super.onKeyUp(code, e);
    }

    private void moveRow(int to) {
        sel = to;
        int vis = visibleRows();
        int newTop = top;
        if (sel < top + 1) newTop = Math.max(0, sel - 1);
        else if (sel > top + vis - 2) newTop = Math.min(Math.max(0, channels.size() - vis), sel - vis + 2);
        if (newTop != top) {
            top = newTop;
            animateScroll();
            requestListings();
        }
        highlight();
        invalidate();
    }

    private void animateScroll() {
        if (scrollAnim != null) scrollAnim.cancel();
        scrollAnim = ValueAnimator.ofFloat(topOffset, top);
        scrollAnim.setDuration(200);
        scrollAnim.setInterpolator(EASE);
        scrollAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator v) {
                topOffset = (Float) v.getAnimatedValue();
                invalidate();
            }
        });
        scrollAnim.start();
    }

    private void requestListings() {
        if (listener == null || channels.isEmpty()) return;
        int vis = getHeight() > 0 ? visibleRows() : 10;
        int from = Math.max(0, top - 2), to = Math.min(channels.size(), top + vis + 4);
        listener.onNeedListings(new ArrayList<>(channels.subList(from, to)));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        requestListings();
    }

    /** The programme under the highlight in the selected row. */
    private XtreamApi.Programme current() {
        if (sel < 0 || sel >= channels.size()) return null;
        List<XtreamApi.Programme> l = Guide.get(channels.get(sel).id);
        if (l == null) return null;
        for (XtreamApi.Programme p : l) if (p.start <= cursor && cursor < p.stop) return p;
        return null;
    }

    /** In a gap with nothing listed: jump straight to the next programme (not half an hour of nothing at a time). */
    private long nextListedStart(long from) {
        if (sel >= 0 && sel < channels.size()) {
            List<XtreamApi.Programme> l = Guide.get(channels.get(sel).id);
            if (l != null) for (XtreamApi.Programme p : l) if (p.start > from) return p.start;
        }
        return from + HALF_HOUR;
    }

    private void highlight() {
        if (listener != null && sel >= 0 && sel < channels.size()) listener.onHighlight(sel, channels.get(sel), current());
    }

    /** New listings arrived: redraw, and refresh the details if the highlighted channel's came in. */
    public void listingsChanged() {
        highlight();
        invalidate();
    }

    // ------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float gridX = chanW + Ui.dp(getContext(), 8), gridW = w - gridX;
        float perSec = gridW / windowLength();
        long now = now();
        boolean focused = hasFocus();

        // Header: date/time on the left, half-hour marks across
        small.setColor(Ui.TEXT2);
        c.drawText(day.format(new Date(now * 1000L)) + "   " + hm.format(new Date(now * 1000L)),
                Ui.dp(getContext(), 12), headerH * 0.62f, small);
        for (long t = windowStart; t < windowStart + windowLength(); t += HALF_HOUR) {
            float x = gridX + (t - windowStart) * perSec;
            c.drawText(hm.format(new Date(t * 1000L)), x + Ui.dp(getContext(), 8), headerH * 0.62f, small);
            line.setColor(Ui.SURFACE_HI);
            c.drawLine(x, headerH * 0.78f, x, headerH, line);
        }

        // Rows
        c.save();
        c.clipRect(0, headerH, w, h);
        float gap = Ui.dp(getContext(), 3), rad = Ui.dp(getContext(), 10);
        int first = (int) Math.floor(topOffset), last = Math.min(channels.size() - 1, first + visibleRows() + 1);
        for (int i = Math.max(0, first); i <= last; i++) {
            float y = headerH + (i - topOffset) * rowH;
            Item ch = channels.get(i);
            boolean selRow = i == sel;
            // channel cell
            r.set(0, y + gap / 2, chanW, y + rowH - gap / 2);
            fill.setColor(selRow ? Ui.SURFACE_HI : Ui.SURFACE_LO);
            c.drawRoundRect(r, rad, rad, fill);
            float tx = Ui.dp(getContext(), 14);
            small.setColor(Ui.TEXT2);
            String num = ch.num == null ? String.valueOf(i + 1) : ch.num;
            c.drawText(num, tx, y + rowH / 2f + small.getTextSize() / 3, small);
            tx += Ui.dp(getContext(), 50);
            Bitmap logo = ImageLoader.cached(ch.icon);
            float lw = Ui.dp(getContext(), 64), lh = Ui.dp(getContext(), 40);
            if (logo != null) {
                float sc = Math.min(lw / logo.getWidth(), lh / logo.getHeight());
                float dw = logo.getWidth() * sc, dh = logo.getHeight() * sc;
                r.set(tx + (lw - dw) / 2, y + (rowH - dh) / 2, tx + (lw + dw) / 2, y + (rowH + dh) / 2);
                src.set(0, 0, logo.getWidth(), logo.getHeight());
                c.drawBitmap(logo, src, r, null);
            } else if (ch.icon != null) {
                ImageLoader.prefetch(ch.icon, 144, 88, new Runnable() { public void run() { invalidate(); } });
            }
            tx += lw + Ui.dp(getContext(), 12);
            text.setColor(Ui.TEXT);
            text.setTypeface(selRow ? Ui.semibold() : Ui.medium());
            CharSequence name = TextUtils.ellipsize(ch.name, text, chanW - tx - Ui.dp(getContext(), 10), TextUtils.TruncateAt.END);
            c.drawText(name, 0, name.length(), tx, y + rowH / 2f + text.getTextSize() / 3, text);

            // programmes
            List<XtreamApi.Programme> l = Guide.get(ch.id);
            if (l == null || l.isEmpty()) {
                r.set(gridX, y + gap / 2, w, y + rowH - gap / 2);
                boolean hl = selRow && focused;
                fill.setColor(hl ? Ui.FOCUS_BG : Ui.SURFACE);
                c.drawRoundRect(r, rad, rad, fill);
                small.setColor(hl ? Ui.ON_FOCUS2 : Ui.TEXT3);
                c.drawText(l == null ? "Loading…" : "No information", gridX + Ui.dp(getContext(), 14), y + rowH / 2f + small.getTextSize() / 3, small);
                continue;
            }
            for (XtreamApi.Programme p : l) {
                long s = Math.max(p.start, windowStart), e = Math.min(p.stop, windowStart + windowLength());
                if (e <= s) continue;
                float x1 = gridX + (s - windowStart) * perSec, x2 = gridX + (e - windowStart) * perSec;
                r.set(x1 + gap / 2, y + gap / 2, x2 - gap / 2, y + rowH - gap / 2);
                boolean hl = selRow && focused && p.start <= cursor && cursor < p.stop;
                boolean onNow = p.start <= now && now < p.stop;
                fill.setColor(hl ? Ui.FOCUS_BG : onNow ? Ui.SURFACE_HI : Ui.SURFACE);
                c.drawRoundRect(r, rad, rad, fill);
                float avail = r.width() - Ui.dp(getContext(), 24);
                if (avail < Ui.dp(getContext(), 20)) continue;
                text.setColor(hl ? Ui.ON_FOCUS : Ui.TEXT);
                text.setTypeface(hl ? Ui.semibold() : Ui.medium());
                CharSequence t = TextUtils.ellipsize(p.title == null ? "" : p.title, text, avail, TextUtils.TruncateAt.END);
                c.drawText(t, 0, t.length(), r.left + Ui.dp(getContext(), 12), y + rowH / 2f + text.getTextSize() / 3, text);
            }
        }
        c.restore();

        // "Now" line
        if (now >= windowStart && now < windowStart + windowLength()) {
            float x = gridX + (now - windowStart) * perSec;
            line.setColor(Ui.ACCENT);
            c.drawLine(x, headerH * 0.7f, x, h, line);
            c.drawCircle(x, headerH * 0.7f, Ui.dp(getContext(), 4), line);
        }
        removeCallbacks(tick);
        postDelayed(tick, 30_000); // keep the "now" line moving
    }

    @Override
    protected void onFocusChanged(boolean gain, int dir, Rect prev) {
        super.onFocusChanged(gain, dir, prev);
        invalidate();
    }
}
