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
        boolean filters = !Filters.hiddenTags(prefs).isEmpty();
        if (n == 0) return filters ? "See everything that's hidden" : "None";
        return n + " hidden by you" + (filters ? " · more by filters" : "");
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
                    labels[i] = (k.startsWith("#") || name.equals(k) ? name : k + "  ·  " + name) + "   (" + n + (n == 1 ? " category)" : " categories)");
                    shown[i] = !hidden.contains(k);
                }
                final AlertDialog dlg = new AlertDialog.Builder(a, dialogTheme())
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
                        .setNeutralButton("Unselect all", null) // wired below so it doesn't close the list
                        .setNegativeButton("Cancel", null)
                        .create();
                dlg.setOnShowListener(new DialogInterface.OnShowListener() {
                    public void onShow(DialogInterface d) {
                        final android.widget.Button all = dlg.getButton(DialogInterface.BUTTON_NEUTRAL);
                        final Runnable label = new Runnable() {
                            public void run() {
                                boolean any = false;
                                for (boolean b : shown) any |= b;
                                all.setText(any ? "Unselect all" : "Select all");
                            }
                        };
                        label.run();
                        all.setOnClickListener(new android.view.View.OnClickListener() {
                            public void onClick(android.view.View v) {
                                boolean any = false;
                                for (boolean b : shown) any |= b;
                                // Untick everything (then tick just the ones you want), or tick everything again
                                for (int i = 0; i < shown.length; i++) {
                                    shown[i] = !any;
                                    dlg.getListView().setItemChecked(i, !any);
                                }
                                label.run();
                            }
                        });
                        dlg.getListView().setOnItemClickListener(new android.widget.AdapterView.OnItemClickListener() {
                            public void onItemClick(android.widget.AdapterView<?> p, android.view.View v, int i, long id) {
                                shown[i] = dlg.getListView().isItemChecked(i);
                                label.run();
                            }
                        });
                    }
                });
                dlg.show();
            }
        });
    }

    /**
     * Everything that's hidden, whether you hid it or a country / writing filter did.
     * Untick to bring a single category back (it then shows even though its country is hidden).
     */
    static void hidden(final Activity a, final Prefs prefs, final TextView value) {
        final XtreamApi api = new XtreamApi(prefs);
        Async.run(new Async.Work<List<Object[]>>() {
            public List<Object[]> run() {
                // {type, id, label, hiddenByYou, hiddenByFilter}
                List<Object[]> mine = new ArrayList<>(), filtered = new ArrayList<>();
                String[] types = {Item.LIVE, Item.MOVIE, Item.SERIES};
                String[] names = {"Live TV", "Movies", "Series"};
                Set<String> tags = Filters.hiddenTags(prefs);
                for (int i = 0; i < 3; i++) {
                    Set<String> byYou = Filters.hiddenCategories(prefs, types[i]);
                    Set<String> shows = Filters.alwaysShown(prefs, types[i]);
                    List<Item> cats;
                    try {
                        cats = Repo.categories(api, types[i]);
                    } catch (Exception e) {
                        continue;
                    }
                    for (Item c : cats) {
                        boolean you = byYou.contains(c.id);
                        String t = Filters.tag(c.name);
                        boolean filter = t != null && tags.contains(t);
                        if (!you && !filter) continue;
                        String why = you ? "hidden by you" : "hidden by the " + Filters.tagName(t) + " filter";
                        boolean nowHidden = you || !shows.contains(c.id);
                        Object[] row = {types[i], c.id, names[i] + "  ·  " + c.name + "   (" + why + ")", you, filter, nowHidden};
                        (you ? mine : filtered).add(row);
                    }
                }
                mine.addAll(filtered); // yours first, then the ones the filters hid
                return mine;
            }
        }, new Async.Done<List<Object[]>>() {
            public void done(final List<Object[]> rows, Exception e) {
                if (a.isFinishing() || rows == null) return;
                if (rows.isEmpty()) {
                    Toast.makeText(a, "Nothing hidden. Hold OK (or press ☰) on a category to hide it.", Toast.LENGTH_LONG).show();
                    return;
                }
                final String[] labels = new String[rows.size()];
                final boolean[] hidden = new boolean[rows.size()];
                for (int i = 0; i < rows.size(); i++) {
                    labels[i] = (String) rows.get(i)[2];
                    hidden[i] = (Boolean) rows.get(i)[5];
                }
                new AlertDialog.Builder(a, dialogTheme())
                        .setTitle("Hidden categories — untick to show")
                        .setMultiChoiceItems(labels, hidden, new DialogInterface.OnMultiChoiceClickListener() {
                            public void onClick(DialogInterface d, int which, boolean on) { hidden[which] = on; }
                        })
                        .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                for (int i = 0; i < rows.size(); i++) {
                                    Object[] r = rows.get(i);
                                    String type = (String) r[0], id = (String) r[1];
                                    boolean you = (Boolean) r[3], filter = (Boolean) r[4];
                                    if (hidden[i] == (Boolean) r[5]) continue; // unchanged
                                    if (!hidden[i]) { // bring it back
                                        if (you) Filters.setCategoryHidden(prefs, type, id, false);
                                        if (filter) Filters.setAlwaysShown(prefs, type, id, true);
                                    } else { // hide it again
                                        if (filter) Filters.setAlwaysShown(prefs, type, id, false);
                                        else Filters.setCategoryHidden(prefs, type, id, true);
                                    }
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
