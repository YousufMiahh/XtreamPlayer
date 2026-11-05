package com.xtreamplayer.tv;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public final class Adapters {
    private Adapters() {}

    /** Base adapter over a list of Items. */
    public abstract static class ItemAdapter extends BaseAdapter {
        protected final Context ctx;
        protected List<Item> items = new ArrayList<>();

        ItemAdapter(Context c) { ctx = c; }

        public void set(List<Item> list) {
            items = list == null ? new ArrayList<Item>() : list;
            notifyDataSetChanged();
        }

        public List<Item> items() { return items; }
        @Override public int getCount() { return items.size(); }
        @Override public Item getItem(int p) { return items.get(p); }
        @Override public long getItemId(int p) { return p; }
    }

    /** Text rows (categories, seasons, episodes). The "active" row is drawn in the accent colour. */
    public static class TextRows extends ItemAdapter {
        public int active = -1;

        public TextRows(Context c) { super(c); }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            LinearLayout row;
            TextView a, b;
            if (v == null) {
                row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.VERTICAL);
                int p = Ui.dp(ctx, 12);
                row.setPadding(p, p, p, p);
                a = Ui.oneLine(Ui.text(ctx, "", 18, Ui.TEXT));
                b = Ui.oneLine(Ui.text(ctx, "", 13, Ui.MUTED));
                row.addView(a);
                row.addView(b);
                row.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                row = (LinearLayout) v;
                a = (TextView) row.getChildAt(0);
                b = (TextView) row.getChildAt(1);
            }
            Item it = getItem(pos);
            a.setText(it.name);
            a.setTextColor(pos == active ? Ui.ACCENT : Ui.TEXT);
            a.setTypeface(pos == active ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            b.setText(it.info == null ? "" : it.info);
            b.setVisibility(it.info == null ? View.GONE : View.VISIBLE);
            return row;
        }
    }

    /** Live channel rows: number · logo · name · ★ */
    public static class Channels extends ItemAdapter {
        private final Prefs prefs;

        public Channels(Context c, Prefs p) { super(c); prefs = p; }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            LinearLayout row;
            TextView num, name, star;
            ImageView logo;
            if (v == null) {
                row = new LinearLayout(ctx);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int p = Ui.dp(ctx, 10);
                row.setPadding(p, p, p, p);
                num = Ui.text(ctx, "", 15, Ui.MUTED);
                num.setMinWidth(Ui.dp(ctx, 46));
                logo = new ImageView(ctx);
                logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
                LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(Ui.dp(ctx, 64), Ui.dp(ctx, 40));
                llp.rightMargin = Ui.dp(ctx, 12);
                name = Ui.oneLine(Ui.text(ctx, "", 18, Ui.TEXT));
                star = Ui.text(ctx, "★", 18, Ui.ACCENT);
                row.addView(num);
                row.addView(logo, llp);
                row.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                row.addView(star);
                row.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                row = (LinearLayout) v;
                num = (TextView) row.getChildAt(0);
                logo = (ImageView) row.getChildAt(1);
                name = (TextView) row.getChildAt(2);
                star = (TextView) row.getChildAt(3);
            }
            Item it = getItem(pos);
            num.setText(it.num == null ? String.valueOf(pos + 1) : it.num);
            name.setText(it.name);
            star.setVisibility(prefs.isFav(it) ? View.VISIBLE : View.GONE);
            ImageLoader.load(logo, it.icon, 128, 80);
            return row;
        }
    }

    /** Poster grid cells for movies and series. */
    public static class Posters extends ItemAdapter {
        private final Prefs prefs;
        private final int w, h;

        public Posters(Context c, Prefs p) {
            super(c);
            prefs = p;
            w = Ui.dp(c, 134);
            h = Ui.dp(c, 200);
        }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            LinearLayout cell;
            ImageView img;
            TextView name;
            if (v == null) {
                cell = new LinearLayout(ctx);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                int p = Ui.dp(ctx, 8);
                cell.setPadding(p, p, p, p);
                img = new ImageView(ctx);
                img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                img.setBackground(Ui.rounded(ctx, Ui.CARD, 0, 8));
                img.setClipToOutline(true);
                name = Ui.text(ctx, "", 14, Ui.TEXT);
                name.setMaxLines(2);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                name.setGravity(Gravity.CENTER_HORIZONTAL);
                name.setPadding(0, Ui.dp(ctx, 6), 0, 0);
                cell.addView(img, new LinearLayout.LayoutParams(w, h));
                cell.addView(name, new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT));
                cell.setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else {
                cell = (LinearLayout) v;
                img = (ImageView) cell.getChildAt(0);
                name = (TextView) cell.getChildAt(1);
            }
            Item it = getItem(pos);
            name.setText(prefs.isFav(it) ? "★ " + it.name : it.name);
            ImageLoader.load(img, it.icon, 270, 400);
            return cell;
        }
    }
}
