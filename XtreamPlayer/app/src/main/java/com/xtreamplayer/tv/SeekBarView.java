package com.xtreamplayer.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * Rounded progress bar: track, buffered, played. The bar can be thinner than the view (it's
 * drawn centred), which leaves room for the round scrub knob: while scrubbing, the bar thickens,
 * the stretch between where you are and where the knob is shows lighter, and the knob sits on top.
 */
public class SeekBarView extends View {
    private float progress, buffered, knob = -1;
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint buf = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ahead = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private float barHeight = -1;

    public SeekBarView(Context c) {
        super(c);
        track.setColor(0x40FFFFFF);
        buf.setColor(0x66FFFFFF);
        fill.setColor(0xFFFFFFFF);
        ahead.setColor(0x99FFFFFF);
        knobPaint.setColor(0xFFFFFFFF);
        knobPaint.setShadowLayer(c.getResources().getDisplayMetrics().density * 6, 0, 0, 0x80000000);
        setLayerType(LAYER_TYPE_SOFTWARE, null); // for the knob's shadow
    }

    /** Bar thickness in px (default: the view's full height). */
    public void setBarHeight(float px) {
        barHeight = px;
        invalidate();
    }

    /** Scrub knob position 0–1, or -1 for no knob. */
    public void setKnob(float f) {
        knob = f < 0 ? -1 : clamp(f);
        invalidate();
    }

    public void set(float progress, float buffered) {
        this.progress = clamp(progress);
        this.buffered = clamp(buffered);
        invalidate();
    }

    private static float clamp(float f) {
        return Float.isNaN(f) ? 0 : Math.max(0, Math.min(1, f));
    }

    /** Where the knob centre is, in px from the left (for lining the preview up above it). */
    public float knobX() {
        float pad = barHeight > 0 ? getHeight() / 2f : 0;
        return pad + (getWidth() - pad * 2) * Math.max(0, knob);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), full = getHeight();
        boolean scrub = knob >= 0;
        float h = barHeight > 0 ? Math.min(barHeight * (scrub ? 1.5f : 1f), full) : full;
        float pad = barHeight > 0 ? full / 2f : 0; // room for the knob at either end
        float top = (full - h) / 2f, rad = h / 2f, left = pad, width = w - pad * 2;
        r.set(left, top, left + width, top + h);
        c.drawRoundRect(r, rad, rad, track);
        if (buffered > 0) {
            r.set(left, top, left + Math.max(h, width * buffered), top + h);
            c.drawRoundRect(r, rad, rad, buf);
        }
        if (scrub && knob > progress) { // the stretch you're about to jump forward over
            r.set(left, top, left + width * knob, top + h);
            c.drawRoundRect(r, rad, rad, ahead);
        }
        float played = scrub ? Math.min(progress, knob) : progress;
        if (played > 0) {
            r.set(left, top, left + Math.max(h, width * played), top + h);
            c.drawRoundRect(r, rad, rad, fill);
        }
        if (scrub) c.drawCircle(left + width * knob, full / 2f, full / 2.6f, knobPaint);
    }
}
