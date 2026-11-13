package com.xtreamplayer.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Hold OK / ☰ on a film, series or episode: Favourite, Add to Queue, Mark as watched (and for an
 * episode, the whole season), Remove from Continue Watching.
 */
final class ItemMenu {
    private ItemMenu() {}

    /**
     * @param season the episode's season (for "Mark season as watched"), or null
     * @param changed run after anything changed, to refresh the screen
     */
    static void show(final Activity a, final Prefs prefs, final Item it, final List<Item> season, final Runnable changed) {
        final boolean episode = Item.EPISODE.equals(it.type), series = Item.SERIES.equals(it.type);
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();

        if (!episode) {
            final boolean fav = prefs.isFav(it);
            labels.add(fav ? "♥  Remove from Favourites" : "♡  Add to Favourites");
            actions.add(new Runnable() { public void run() { Open.toggleFav(a, prefs, it); } });
        }
        if (!series) {
            final boolean queued = prefs.inQueue(it);
            labels.add(queued ? "−  Remove from Queue" : "＋  Add to Queue");
            actions.add(new Runnable() {
                public void run() {
                    if (queued) prefs.queueRemove(it); else prefs.queueAdd(it);
                    Toast.makeText(a, (queued ? "Removed from your Queue — " : "Added to your Queue — ") + it.name, Toast.LENGTH_SHORT).show();
                }
            });
            final boolean watched = prefs.isWatched(it);
            labels.add(watched ? "○  Mark as Unwatched" : "✓  Mark as Watched");
            actions.add(new Runnable() { public void run() { prefs.setWatched(it, !watched); } });
        }
        if (episode && season != null && !season.isEmpty()) {
            final boolean all = prefs.watchedCount(season) == season.size();
            labels.add(all ? "○  Mark Season as Unwatched" : "✓  Mark Season as Watched");
            actions.add(new Runnable() { public void run() { prefs.setWatched(season, !all); } });
        }
        if (!series && (prefs.position(it) > 0 || prefs.isUpNext(it))) {
            labels.add("✕  Remove from Continue Watching");
            actions.add(new Runnable() { public void run() { prefs.setPosition(it, 0); } });
        }
        String title = episode && it.parentName != null ? it.parentName + "  ·  " + it.name : it.name;
        new AlertDialog.Builder(a, Ui.DARK ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert)
                .setTitle(title)
                .setItems(labels.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface d, int which) {
                        actions.get(which).run();
                        if (changed != null) changed.run();
                    }
                })
                .show();
    }
}
