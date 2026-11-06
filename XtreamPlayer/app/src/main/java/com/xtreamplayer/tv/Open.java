package com.xtreamplayer.tv;

import android.app.Activity;
import android.content.Intent;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** What happens when you pick something anywhere in the app. */
public final class Open {
    private Open() {}

    /** @param siblings the list it came from (used to zap between live channels). */
    public static void item(Activity a, Item it, List<Item> siblings) {
        if (Item.MOVIE.equals(it.type)) {
            a.startActivity(new Intent(a, MovieActivity.class).putExtra("item", it.toJson().toString()));
        } else if (Item.SERIES.equals(it.type)) {
            a.startActivity(new Intent(a, SeriesActivity.class).putExtra("series", it.toJson().toString()));
        } else if (Item.LIVE.equals(it.type)) {
            List<Item> q = new ArrayList<>();
            int idx = 0;
            if (siblings != null) {
                for (Item s : siblings) if (Item.LIVE.equals(s.type)) q.add(s);
            }
            // Find the picked channel by its ID (the list may hold different copies of it)
            idx = -1;
            for (int i = 0; i < q.size(); i++) {
                if (q.get(i).key().equals(it.key())) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0) { // not in the list at all: play just this one
                q.clear();
                q.add(it);
                idx = 0;
            }
            play(a, q, idx);
        } else {
            List<Item> q = new ArrayList<>();
            q.add(it);
            play(a, q, 0);
        }
    }

    public static void play(Activity a, List<Item> queue, int index) {
        Queue.set(queue);
        a.startActivity(new Intent(a, PlayerActivity.class).putExtra("index", index));
    }

    public static void toggleFav(Activity a, Prefs prefs, Item it) {
        boolean now = prefs.toggleFav(it);
        Toast.makeText(a, (now ? "Added to Favourites — " : "Removed from Favourites — ") + it.name, Toast.LENGTH_SHORT).show();
    }
}
