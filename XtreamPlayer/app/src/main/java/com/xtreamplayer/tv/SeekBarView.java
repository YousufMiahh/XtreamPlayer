package com.xtreamplayer.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Thin rounded progress bar: track, buffered, played. */
public class SeekBarView extends View {
    private float progress, buffered;
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint buf = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();

    public SeekBarView(Context c) {
        super(c);
        track.setColor(0x40FFFFFF);
        buf.setColor(0x66FFFFFF);
        fill.setColor(0xFFFFFFFF);
    }

    public void set(float progress, float buffered) {
        this.progress = clamp(progress);
        this.buffered = clamp(buffered);
        invalidate();
    }

    private static float clamp(float f) {
        return Float.isNaN(f) ? 0 : Math.max(0, Math.min(1, f));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), rad = h / 2f;
        r.set(0, 0, w, h);
        c.drawRoundRect(r, rad, rad, track);
        if (buffered > 0) {
            r.set(0, 0, Math.max(h, w * buffered), h);
            c.drawRoundRect(r, rad, rad, buf);
        }
        if (progress > 0) {
            r.set(0, 0, Math.max(h, w * progress), h);
            c.drawRoundRect(r, rad, rad, fill);
        }
    }
}
