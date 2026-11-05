package com.xtreamplayer.tv;

import java.util.ArrayList;
import java.util.List;

/** Hands the current list to the player without squeezing it through an Intent. */
public final class Queue {
    private Queue() {}

    public static List<Item> items = new ArrayList<>();

    public static void set(List<Item> list) {
        items = new ArrayList<>(list);
    }
}
