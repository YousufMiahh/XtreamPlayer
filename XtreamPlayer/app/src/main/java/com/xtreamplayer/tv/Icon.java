package com.xtreamplayer.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Simple line icons drawn in code (no image files), so they stay sharp and follow the theme colour. */
public class Icon extends View {
    public enum Kind {
        HOME, LIVE, MOVIES, SERIES, SEARCH, SETTINGS, BACK, DOWN, HEART, HEART_FILLED, PLAY, PAUSE,
        BACK10, FWD10, SUBS, LIST, CLOSE, PLUS, CHECK, INFO, NEXT, PREV, FIT, TRAILER, RESTART, CAST, CAST_ON, DOWNLOAD, GUIDE
    }

    private Kind kind;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
            text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF();

    public Icon(Context c, Kind k, int color) {
        super(c);
        kind = k;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Ui.bold());
        setColor(color);
    }

    public void setKind(Kind k) {
        if (k == kind) return;
        kind = k;
        invalidate();
    }

    public Kind kind() { return kind; }

    /**
     * A see-through colour (e.g. the grey of an unselected tab) is drawn solid and the whole icon
     * faded instead: drawing each stroke see-through doubles up where strokes overlap and leaves
     * soft, blotchy edges, so dim icons looked fuzzy.
     */
    public void setColor(int color) {
        int a = color >>> 24;
        int solid = color | 0xFF000000;
        stroke.setColor(solid);
        fill.setColor(solid);
        text.setColor(solid);
        setAlpha(a / 255f);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float size = Math.min(getWidth() - getPaddingLeft() - getPaddingRight(), getHeight() - getPaddingTop() - getPaddingBottom());
        if (size <= 0) return;
        float s = size / 24f;
        c.save();
        c.translate(getPaddingLeft() + (getWidth() - getPaddingLeft() - getPaddingRight() - size) / 2f,
                getPaddingTop() + (getHeight() - getPaddingTop() - getPaddingBottom() - size) / 2f);
        c.scale(s, s);
        stroke.setStrokeWidth(2.2f); // bold, rounded strokes and solid shapes (the clean TV-app look)
        path.reset();
        path.setFillType(Path.FillType.WINDING);
        switch (kind) {
            case HOME: { // solid house with a door cut out
                path.setFillType(Path.FillType.EVEN_ODD);
                path.moveTo(12, 3.2f); path.lineTo(21, 10.8f); path.lineTo(19, 10.8f); path.lineTo(19, 20.5f);
                path.lineTo(5, 20.5f); path.lineTo(5, 10.8f); path.lineTo(3, 10.8f); path.close();
                path.moveTo(10, 20.5f); path.lineTo(10, 15); path.lineTo(14, 15); path.lineTo(14, 20.5f); path.close();
                Paint.Style st = fill.getStyle();
                fill.setStyle(Paint.Style.FILL);
                c.drawPath(path, fill);
                stroke.setStrokeWidth(1.2f);
                Path outline = new Path();
                outline.moveTo(12, 3.2f); outline.lineTo(21, 10.8f); outline.lineTo(3, 10.8f); outline.close();
                c.drawPath(outline, stroke); // soften the roof corners
                fill.setStyle(st);
                break;
            }
            case LIVE: // broadcast waves  ((•))
                c.drawCircle(12, 12, 2, fill);
                r.set(12 - 5.5f, 12 - 5.5f, 12 + 5.5f, 12 + 5.5f);
                c.drawArc(r, 140, 80, false, stroke);
                c.drawArc(r, -40, 80, false, stroke);
                r.set(12 - 9.5f, 12 - 9.5f, 12 + 9.5f, 12 + 9.5f);
                c.drawArc(r, 135, 90, false, stroke);
                c.drawArc(r, -45, 90, false, stroke);
                break;
            case MOVIES: // film strip
                r.set(4, 3, 20, 21); c.drawRoundRect(r, 2, 2, stroke);
                path.moveTo(8, 3); path.lineTo(8, 21); path.moveTo(16, 3); path.lineTo(16, 21);
                path.moveTo(4, 8); path.lineTo(8, 8); path.moveTo(4, 12); path.lineTo(8, 12); path.moveTo(4, 16); path.lineTo(8, 16);
                path.moveTo(16, 8); path.lineTo(20, 8); path.moveTo(16, 12); path.lineTo(20, 12); path.moveTo(16, 16); path.lineTo(20, 16);
                c.drawPath(path, stroke);
                break;
            case SERIES: // a TV on its stand
                r.set(2.5f, 4, 21.5f, 17); c.drawRoundRect(r, 2.5f, 2.5f, stroke);
                c.drawLine(12, 17, 12, 20.3f, stroke);
                c.drawLine(8, 20.5f, 16, 20.5f, stroke);
                break;
            case SEARCH:
                stroke.setStrokeWidth(2.8f);
                c.drawCircle(10.3f, 10.3f, 6.3f, stroke);
                c.drawLine(15, 15, 20.5f, 20.5f, stroke);
                break;
            case SETTINGS: { // solid gear with a hole
                path.setFillType(Path.FillType.EVEN_ODD);
                int teeth = 8;
                for (int i = 0; i < teeth * 2; i++) {
                    double a0 = Math.PI * 2 * i / (teeth * 2) - Math.PI / (teeth * 2);
                    double a1 = a0 + Math.PI * 2 / (teeth * 2);
                    float rad = i % 2 == 0 ? 10.2f : 7.6f;
                    float x0 = 12 + (float) Math.cos(a0) * rad, y0 = 12 + (float) Math.sin(a0) * rad;
                    float x1 = 12 + (float) Math.cos(a1) * rad, y1 = 12 + (float) Math.sin(a1) * rad;
                    if (i == 0) path.moveTo(x0, y0); else path.lineTo(x0, y0);
                    path.lineTo(x1, y1);
                }
                path.close();
                path.addCircle(12, 12, 3.2f, Path.Direction.CW);
                c.drawPath(path, fill);
                break;
            }
            case BACK:
                path.moveTo(15, 4.5f); path.lineTo(7.5f, 12); path.lineTo(15, 19.5f);
                c.drawPath(path, stroke);
                break;
            case NEXT:
                path.moveTo(9, 4.5f); path.lineTo(16.5f, 12); path.lineTo(9, 19.5f);
                c.drawPath(path, stroke);
                break;
            case PREV:
                path.moveTo(15, 4.5f); path.lineTo(7.5f, 12); path.lineTo(15, 19.5f);
                c.drawPath(path, stroke);
                break;
            case DOWN:
                path.moveTo(6, 9); path.lineTo(12, 15); path.lineTo(18, 9);
                c.drawPath(path, stroke);
                break;
            case HEART:
            case HEART_FILLED:
                heart();
                c.drawPath(path, kind == Kind.HEART_FILLED ? fill : stroke);
                break;
            case PLAY:
                path.moveTo(7, 4); path.lineTo(20, 12); path.lineTo(7, 20); path.close();
                stroke.setStrokeWidth(1.5f);
                c.drawPath(path, fill);
                c.drawPath(path, stroke);
                break;
            case PAUSE:
                r.set(6, 4, 10, 20); c.drawRoundRect(r, 1.2f, 1.2f, fill);
                r.set(14, 4, 18, 20); c.drawRoundRect(r, 1.2f, 1.2f, fill);
                break;
            case BACK10:
            case FWD10: {
                boolean fwd = kind == Kind.FWD10;
                r.set(3, 3, 21, 21);
                c.save();
                if (fwd) c.scale(-1, 1, 12, 12);
                c.drawArc(r, -80, 300, false, stroke);
                path.moveTo(12.5f, 0.5f); path.lineTo(9.5f, 3); path.lineTo(12.5f, 5.5f);
                c.drawPath(path, stroke);
                c.restore();
                text.setTextSize(7.5f);
                c.drawText(fwd ? "10" : "5", 12, 14.7f, text);
                break;
            }
            case SUBS:
                r.set(2.5f, 4.5f, 21.5f, 18); c.drawRoundRect(r, 2.5f, 2.5f, stroke);
                path.moveTo(6.5f, 10.5f); path.lineTo(10, 10.5f); path.moveTo(12.5f, 10.5f); path.lineTo(17.5f, 10.5f);
                path.moveTo(6.5f, 14); path.lineTo(13, 14); path.moveTo(15.5f, 14); path.lineTo(17.5f, 14);
                c.drawPath(path, stroke);
                break;
            case LIST:
                for (int i = 0; i < 3; i++) {
                    float y = 6 + i * 6;
                    c.drawCircle(4.5f, y, 1.3f, fill);
                    c.drawLine(9, y, 20.5f, y, stroke);
                }
                break;
            case CLOSE:
                c.drawLine(6, 6, 18, 18, stroke);
                c.drawLine(18, 6, 6, 18, stroke);
                break;
            case PLUS:
                c.drawLine(12, 4.5f, 12, 19.5f, stroke);
                c.drawLine(4.5f, 12, 19.5f, 12, stroke);
                break;
            case CHECK:
                path.moveTo(4.5f, 12.5f); path.lineTo(9.5f, 17.5f); path.lineTo(19.5f, 6.5f);
                c.drawPath(path, stroke);
                break;
            case INFO:
                c.drawCircle(12, 12, 9, stroke);
                c.drawLine(12, 11, 12, 16.5f, stroke);
                c.drawCircle(12, 7.6f, 1.2f, fill);
                break;
            case FIT:
                path.moveTo(3, 8); path.lineTo(3, 4); path.lineTo(7, 4);
                path.moveTo(17, 4); path.lineTo(21, 4); path.lineTo(21, 8);
                path.moveTo(21, 16); path.lineTo(21, 20); path.lineTo(17, 20);
                path.moveTo(7, 20); path.lineTo(3, 20); path.lineTo(3, 16);
                c.drawPath(path, stroke);
                break;
            case TRAILER:
                r.set(2.5f, 5, 21.5f, 19); c.drawRoundRect(r, 4, 4, stroke);
                path.moveTo(10, 9); path.lineTo(15, 12); path.lineTo(10, 15); path.close();
                c.drawPath(path, fill);
                break;
            case CAST:
            case CAST_ON:
                // Screen outline, open at the bottom-left where the waves are
                path.moveTo(2.5f, 8);
                path.lineTo(2.5f, 6.5f);
                path.quadTo(2.5f, 4.5f, 4.5f, 4.5f);
                path.lineTo(19.5f, 4.5f);
                path.quadTo(21.5f, 4.5f, 21.5f, 6.5f);
                path.lineTo(21.5f, 17.5f);
                path.quadTo(21.5f, 19.5f, 19.5f, 19.5f);
                path.lineTo(14, 19.5f);
                c.drawPath(path, stroke);
                if (kind == Kind.CAST_ON) {
                    r.set(6.5f, 8, 18, 16);
                    c.drawRoundRect(r, 1, 1, fill);
                }
                c.drawCircle(3.2f, 18.8f, 1.3f, fill);
                r.set(2.5f - 5.5f, 19.5f - 5.5f, 2.5f + 5.5f, 19.5f + 5.5f);
                c.drawArc(r, -90, 90, false, stroke);
                r.set(2.5f - 9.5f, 19.5f - 9.5f, 2.5f + 9.5f, 19.5f + 9.5f);
                c.drawArc(r, -90, 90, false, stroke);
                break;
            case GUIDE: // a TV guide grid
                r.set(2.5f, 4, 21.5f, 20); c.drawRoundRect(r, 2.5f, 2.5f, stroke);
                path.moveTo(2.5f, 9.3f); path.lineTo(21.5f, 9.3f);
                path.moveTo(2.5f, 14.7f); path.lineTo(21.5f, 14.7f);
                path.moveTo(8, 4); path.lineTo(8, 20);
                path.moveTo(15, 9.3f); path.lineTo(15, 14.7f);
                path.moveTo(13, 14.7f); path.lineTo(13, 20);
                c.drawPath(path, stroke);
                break;
            case DOWNLOAD:
                c.drawLine(12, 3.5f, 12, 15, stroke);
                path.moveTo(7, 10.5f); path.lineTo(12, 15.5f); path.lineTo(17, 10.5f);
                path.moveTo(4.5f, 20); path.lineTo(19.5f, 20);
                c.drawPath(path, stroke);
                break;
            case RESTART:
                r.set(4, 4, 20, 20);
                c.drawArc(r, -60, 300, false, stroke);
                path.moveTo(5, 2.5f); path.lineTo(5.3f, 8); path.lineTo(10.5f, 7);
                c.drawPath(path, stroke);
                break;
        }
        c.restore();
    }

    private void heart() {
        path.moveTo(12, 20.5f);
        path.cubicTo(4.5f, 15, 2.5f, 11.5f, 2.5f, 8.5f);
        path.cubicTo(2.5f, 5.5f, 4.8f, 3.5f, 7.3f, 3.5f);
        path.cubicTo(9.2f, 3.5f, 11, 4.6f, 12, 6.3f);
        path.cubicTo(13, 4.6f, 14.8f, 3.5f, 16.7f, 3.5f);
        path.cubicTo(19.2f, 3.5f, 21.5f, 5.5f, 21.5f, 8.5f);
        path.cubicTo(21.5f, 11.5f, 19.5f, 15, 12, 20.5f);
        path.close();
    }
}
