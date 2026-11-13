package com.xtreamplayer.tv;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** List/grid cells with tvOS-style focus, plus the adapters that use them. */
public final class Adapters {
    private Adapters() {}

    /** A cell that knows how to draw itself focused ("hot") or not. */
    public interface Hot {
        void setHot(boolean hot, boolean animate);
    }

    /** Re-applies focus styling to the visible children of a list/grid. Call on selection/focus changes. */
    private static final java.util.WeakHashMap<AbsListView, Glider> GLIDERS = new java.util.WeakHashMap<>();

    static void setGlider(AbsListView list, Glider g) { GLIDERS.put(list, g); }

    static boolean hasGlider(AbsListView list) { return list != null && GLIDERS.containsKey(list); }

    public static void refresh(AbsListView list) {
        Glider g = GLIDERS.get(list);
        if (g != null) g.update();
        int first = list.getFirstVisiblePosition();
        int sel = list.getSelectedItemPosition();
        boolean focus = list.hasFocus();
        for (int i = 0; i < list.getChildCount(); i++) {
            View ch = list.getChildAt(i);
            if (ch instanceof Hot) ((Hot) ch).setHot(focus && first + i == sel, true);
        }
    }

    /** Standard setup for a TV list: no default selector, keep styling in sync while scrolling/focusing. */
    public static void prepare(final AbsListView list) {
        prepare(list, null);
    }

    public static void prepare(final AbsListView list, final View.OnFocusChangeListener extra) {
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setClipToPadding(false);
        list.setClipChildren(false);
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            public void onScrollStateChanged(AbsListView v, int s) {}
            public void onScroll(AbsListView v, int a, int b, int c) { refresh(list); }
        });
        list.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            public void onFocusChange(View v, boolean has) {
                refresh(list);
                if (extra != null) extra.onFocusChange(v, has);
            }
        });
    }

    // ------------------------------------------------------------ cells

    /** A row in a list: optional number and logo, title, subtitle, trailing text. */
    public static class RowCell extends LinearLayout implements Hot {
        final TextView num, title, sub, trail;
        final ImageView logo;
        final FrameLayout logoBox;
        private boolean hot, active;
        /** The list draws a gliding highlight behind the rows, so the row itself stays clear. */
        boolean glide;

        public RowCell(Context c, boolean withNumber, boolean withLogo) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            int p = Ui.dp(c, 14);
            setPadding(p + Ui.dp(c, 4), p, p + Ui.dp(c, 4), p);
            num = Ui.medium(c, "", 16, Ui.TEXT2);
            num.setMinWidth(Ui.dp(c, 44));
            if (withNumber) addView(num);
            logoBox = new FrameLayout(c);
            logoBox.setBackground(Ui.rounded(c, Ui.SURFACE, 0, 8));
            logo = new ImageView(c);
            logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int lp = Ui.dp(c, 5);
            logo.setPadding(lp, lp, lp, lp);
            logoBox.addView(logo, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (withLogo) {
                LayoutParams llp = new LayoutParams(Ui.dp(c, 72), Ui.dp(c, 44));
                llp.rightMargin = Ui.dp(c, 16);
                addView(logoBox, llp);
            }
            LinearLayout col = new LinearLayout(c);
            col.setOrientation(VERTICAL);
            title = Ui.oneLine(Ui.medium(c, "", 19, Ui.TEXT));
            sub = Ui.oneLine(Ui.text(c, "", 14, Ui.TEXT2));
            sub.setPadding(0, Ui.dp(c, 4), 0, 0);
            col.addView(title);
            col.addView(sub);
            addView(col, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            trail = Ui.medium(c, "", 16, Ui.TEXT2);
            trail.setPadding(Ui.dp(c, 10), 0, 0, 0);
            addView(trail);
            setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            paint();
        }

        public void bind(String n, String icon, String t, String s, String tr) {
            num.setText(n == null ? "" : n);
            title.setText(t == null ? "" : t);
            sub.setText(s == null ? "" : s);
            sub.setVisibility(s == null || s.isEmpty() ? GONE : VISIBLE);
            trail.setText(tr == null ? "" : tr);
            trail.setVisibility(tr == null || tr.isEmpty() ? GONE : VISIBLE);
            if (logoBox.getParent() != null) ImageLoader.load(logo, icon, 144, 88);
        }

        public void setActive(boolean a) {
            if (a == active) return;
            active = a;
            paint();
        }

        public void setHot(boolean h, boolean animate) {
            if (h == hot) return;
            hot = h;
            paint();
            if (h && animate && !glide) { // highlight fades on rather than snapping
                final android.graphics.drawable.Drawable bg = getBackground();
                android.animation.ValueAnimator va = android.animation.ValueAnimator.ofInt(60, 255);
                va.setDuration(160);
                va.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener() {
                    public void onAnimationUpdate(android.animation.ValueAnimator v) {
                        if (getBackground() == bg) bg.setAlpha((Integer) v.getAnimatedValue());
                    }
                });
                va.start();
            }
        }

        private void paint() {
            Context c = getContext();
            if (hot && !glide) setBackground(Ui.rounded(c, Ui.FOCUS_BG, 0, 12));
            else if (hot) setBackground(null);
            else if (active) setBackground(Ui.rounded(c, Ui.SURFACE_HI, 0, 12));
            else setBackground(null);
            title.setTextColor(hot ? Ui.ON_FOCUS : Ui.TEXT);
            title.setTypeface(hot || active ? Ui.semibold() : Ui.medium());
            int second = hot ? Ui.ON_FOCUS2 : Ui.TEXT2;
            sub.setTextColor(second);
            num.setTextColor(second);
            trail.setTextColor(hot ? Ui.ON_FOCUS : Ui.TEXT2);
        }
    }

    /** Poster for movies/series grids: image on top, title underneath, lifts when focused. */
    public static class PosterCell extends LinearLayout implements Hot {
        final ImageView img;
        final FrameLayout frame;
        final TextView title;
        TextView badge;

        void setWatched(boolean w) { badge.setVisibility(w ? VISIBLE : GONE); }
        private boolean hot;

        public PosterCell(Context c, int w, int h) {
            super(c);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            int p = Ui.dp(c, 12);
            setPadding(p, p, p, p);
            setClipChildren(false);
            img = new ImageView(c);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            img.setBackground(Ui.rounded(c, Ui.SURFACE, 0, 12));
            img.setClipToOutline(true);
            frame = new FrameLayout(c);
            frame.setElevation(Ui.dp(c, 2));
            frame.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            badge = Cards.watchedBadge(c);
            badge.setVisibility(GONE);
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(Ui.dp(c, 26), Ui.dp(c, 26), Gravity.TOP | Gravity.END);
            blp.topMargin = blp.rightMargin = Ui.dp(c, 8);
            frame.addView(badge, blp);
            title = Ui.text(c, "", 15, Ui.TEXT2);
            Ui.lines(title, 2);
            title.setGravity(Gravity.CENTER_HORIZONTAL);
            title.setPadding(0, Ui.dp(c, 10), 0, 0);
            addView(frame, new LayoutParams(w, h));
            addView(title, new LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT));
            setLayoutParams(new AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        public void setHot(boolean h, boolean animate) {
            if (h == hot) return;
            hot = h;
            frame.setForeground(h ? Ui.outline(getContext(), 12) : null);
            title.setTextColor(h ? Ui.TEXT : Ui.TEXT2);
            title.setTypeface(h ? Ui.semibold() : Ui.regular());
            float s = h ? 1.09f : 1f;
            if (animate) {
                Ui.animateFocus(this, h, 1.09f);
            } else {
                setScaleX(s);
                setScaleY(s);
                setTranslationZ(h ? Ui.dp(getContext(), 14) : 0);
            }
        }
    }

    // ------------------------------------------------------------ adapters

    public abstract static class ItemAdapter extends BaseAdapter {
        protected final Context ctx;
        protected List<Item> items = new ArrayList<>();
        protected AbsListView list;

        ItemAdapter(Context c) { ctx = c; }

        public void attach(AbsListView l) {
            list = l;
            l.setAdapter(this);
        }

        public void set(List<Item> l) {
            items = l == null ? new ArrayList<Item>() : l;
            notifyDataSetChanged();
        }

        protected boolean isHot(int pos) {
            return list != null && list.hasFocus() && list.getSelectedItemPosition() == pos;
        }

        public List<Item> items() { return items; }
        @Override public int getCount() { return items.size(); }
        @Override public Item getItem(int p) { return items.get(p); }
        @Override public long getItemId(int p) { return p; }
    }

    /** Plain text rows (categories, settings-like lists). */
    public static class TextRows extends ItemAdapter {
        public int active = -1;
        /** While the top bar has focus, no category is marked (it comes back when you move down). */
        public boolean hideActive;

        public TextRows(Context c) { super(c); }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            RowCell cell = v instanceof RowCell ? (RowCell) v : new RowCell(ctx, false, false);
            cell.glide = hasGlider(list);
            Item it = getItem(pos);
            cell.bind(null, null, it.name, it.info, it.num);
            cell.setActive(!hideActive && pos == active);
            cell.setHot(isHot(pos), false);
            return cell;
        }
    }

    /** Live channels: number · logo · name · ♥ */
    public static class Channels extends ItemAdapter {
        private final Prefs prefs;
        public String playingId;

        public Channels(Context c, Prefs p) { super(c); prefs = p; }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            RowCell cell = v instanceof RowCell ? (RowCell) v : new RowCell(ctx, true, true);
            cell.glide = hasGlider(list);
            Item it = getItem(pos);
            String trail = prefs.isFav(it) ? "♥" : null;
            if (playingId != null && playingId.equals(it.id)) trail = "▶";
            cell.bind(it.num == null ? String.valueOf(pos + 1) : it.num, it.icon, it.name, null, trail);
            cell.setActive(playingId != null && playingId.equals(it.id));
            cell.setHot(isHot(pos), false);
            return cell;
        }
    }

    /** Poster grid for movies and series. */
    public static class Posters extends ItemAdapter {
        private final Prefs prefs;
        private final int w, h;

        public Posters(Context c, Prefs p) {
            super(c);
            prefs = p;
            w = Ui.dp(c, 150);
            h = Ui.dp(c, 225);
        }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            PosterCell cell = v instanceof PosterCell ? (PosterCell) v : new PosterCell(ctx, w, h);
            Item it = getItem(pos);
            cell.title.setText(prefs.isFav(it) ? "♥ " + it.name : it.name);
            cell.setWatched(prefs.isWatched(it));
            ImageLoader.load(cell.img, it.icon, 300, 450);
            cell.setHot(isHot(pos), false);
            return cell;
        }
    }
}
