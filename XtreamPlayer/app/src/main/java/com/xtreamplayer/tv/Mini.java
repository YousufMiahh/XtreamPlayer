package com.xtreamplayer.tv;

import java.util.List;

/**
 * The Live TV mini player: when you press Back from a live channel you came to from Live TV,
 * the channel carries on (same VLC player, no reconnecting) in the preview box while you look
 * for something else. OK on it again goes back to full screen; Back on Live TV stops it.
 * Only one stream plays at a time, so it never uses a second connection.
 */
final class Mini {
    private Mini() {}

    private static VlcEngine engine;
    private static List<Item> queue;
    private static int index;

    /** Set by Live TV just before it opens the full-screen player. */
    static boolean fromLiveTv;

    static final Engine.Listener QUIET = new Engine.Listener() {
        public void onReady() {}
        public void onEnded() {}
        public void onError(String what, boolean behind) {}
        public void onPlayingChanged(boolean playing) {}
        public void onBuffering(boolean buffering) {}
        public void onTracksChanged() {}
    };

    static boolean active() { return engine != null; }

    static VlcEngine engine() { return engine; }

    static List<Item> queue() { return queue; }

    static Item item() {
        return engine == null || queue == null || index < 0 || index >= queue.size() ? null : queue.get(index);
    }

    /** The full-screen player hands its playing channel over. */
    static void take(VlcEngine e, List<Item> q, int i) {
        stop();
        e.setListener(QUIET); // the player screen has gone; Live TV listens once it's back
        engine = e;
        queue = q;
        index = i;
    }

    /** The full-screen player takes it back (same channel picked again). */
    static VlcEngine adopt() {
        VlcEngine e = engine;
        engine = null;
        queue = null;
        return e;
    }

    static void stop() {
        if (engine != null) engine.release();
        engine = null;
        queue = null;
    }
}
