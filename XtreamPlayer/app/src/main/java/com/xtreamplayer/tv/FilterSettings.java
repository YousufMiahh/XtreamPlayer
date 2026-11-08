package com.xtreamplayer.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Settings screens for Countries & Languages and Hidden Categories. */
final class FilterSettings {
    private FilterSettings() {}

    private static int dialogTheme() {
        return Ui.DARK ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert;
    }

    static String countriesLabel(Prefs prefs) {
        int n = Filters.hiddenTags(prefs).size();
        return n == 0 ? "Showing everything" : n + (n == 1 ? " hidden" : " hidden");
    }

    static String hiddenLabel(Prefs prefs) {
        int n = 0;
        for (String t : new String[]{Item.LIVE, Item.MOVIE, Item.SERIES}) n += Filters.hiddenCategories(prefs, t).size();
        return n == 0 ? "None" : n + (n == 1 ? " category" : " categories");
    }

    /** Tick the countries and languages to keep; unticked ones disappear everywhere. */
    static void countries(final Activity a, final Prefs prefs, final TextView value) {
        final XtreamApi api = new XtreamApi(prefs);
        Toast.makeText(a, "Looking through your categories…", Toast.LENGTH_SHORT).show();
        Async.run(new Async.Work<LinkedHashMap<String, Integer>>() {
            public LinkedHashMap<String, Integer> run() { return Filters.detectTags(api); }
        }, new Async.Done<LinkedHashMap<String, Integer>>() {
            public void done(LinkedHashMap<String, Integer> tags, Exception e) {
                if (a.isFinishing()) return;
                if (tags == null || tags.isEmpty()) {
                    Toast.makeText(a, "Your provider's categories aren't labelled by country or language", Toast.LENGTH_LONG).show();
                    return;
                }
                final List<String> keys = new ArrayList<>(tags.keySet());
                Set<String> hidden = Filters.hiddenTags(prefs);
                final String[] labels = new String[keys.size()];
                final boolean[] shown = new boolean[keys.size()];
                for (int i = 0; i < keys.size(); i++) {
                    String k = keys.get(i);
                    String name = Filters.tagName(k);
                    int n = tags.get(k);
                    labels[i] = (name.equals(k) ? k : k + "  ·  " + name) + "   (" + n + (n == 1 ? " category)" : " categories)");
                    shown[i] = !hidden.contains(k);
                }
                new AlertDialog.Builder(a, dialogTheme())
                        .setTitle("Countries & languages to show")
                        .setMultiChoiceItems(labels, shown, new DialogInterface.OnMultiChoiceClickListener() {
                            public void onClick(DialogInterface d, int which, boolean on) { shown[which] = on; }
                        })
                        .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                Set<String> keep = new HashSet<>(Filters.hiddenTags(prefs));
                                for (int i = 0; i < keys.size(); i++) {
                                    if (shown[i]) keep.remove(keys.get(i));
                                    else keep.add(keys.get(i));
                                }
                                Filters.setHiddenTags(prefs, keep);
                                if (value != null) value.setText(countriesLabel(prefs));
                            }
                        })
                        .setNeutralButton("Show all", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                Filters.setHiddenTags(prefs, new HashSet<String>());
                                if (value != null) value.setText(countriesLabel(prefs));
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
    }

    /** Categories hidden by hand: untick to bring them back. */
    static void hidden(final Activity a, final Prefs prefs, final TextView value) {
        final XtreamApi api = new XtreamApi(prefs);
        Async.run(new Async.Work<List<String[]>>() {
            public List<String[]> run() {
                List<String[]> out = new ArrayList<>(); // {type, id, label}
                String[] types = {Item.LIVE, Item.MOVIE, Item.SERIES};
                String[] names = {"Live TV", "Movies", "Series"};
                for (int i = 0; i < 3; i++) {
                    Set<String> ids = Filters.hiddenCategories(prefs, types[i]);
                    if (ids.isEmpty()) continue;
                    Map<String, String> byId = new java.util.HashMap<>();
                    try {
                        for (Item c : Repo.categories(api, types[i])) byId.put(c.id, c.name);
                    } catch (Exception ignored) {
                    }
                    for (String id : ids) {
                        String n = byId.get(id);
                        out.add(new String[]{types[i], id, names[i] + "  ·  " + (n == null ? "Category " + id : n)});
                    }
                }
                return out;
            }
        }, new Async.Done<List<String[]>>() {
            public void done(final List<String[]> rows, Exception e) {
                if (a.isFinishing() || rows == null) return;
                if (rows.isEmpty()) {
                    Toast.makeText(a, "Nothing hidden. Hold OK (or press ☰) on a category to hide it.", Toast.LENGTH_LONG).show();
                    return;
                }
                final String[] labels = new String[rows.size()];
                final boolean[] keepHidden = new boolean[rows.size()];
                for (int i = 0; i < rows.size(); i++) {
                    labels[i] = rows.get(i)[2];
                    keepHidden[i] = true;
                }
                new AlertDialog.Builder(a, dialogTheme())
                        .setTitle("Hidden categories — untick to show again")
                        .setMultiChoiceItems(labels, keepHidden, new DialogInterface.OnMultiChoiceClickListener() {
                            public void onClick(DialogInterface d, int which, boolean on) { keepHidden[which] = on; }
                        })
                        .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                for (int i = 0; i < rows.size(); i++) {
                                    if (!keepHidden[i]) Filters.setCategoryHidden(prefs, rows.get(i)[0], rows.get(i)[1], false);
                                }
                                if (value != null) value.setText(hiddenLabel(prefs));
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        });
    }
}
