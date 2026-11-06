package com.xtreamplayer.tv;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Run work off the UI thread and deliver the result back on it. */
public final class Async {
    private Async() {}

    public interface Work<T> { T run() throws Exception; }
    public interface Done<T> { void done(T result, Exception error); }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(6);
    public static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static <T> void run(final Work<T> work, final Done<T> done) {
        runOn(POOL, work, done);
    }

    /** Same as run, but on a specific executor (e.g. search gets its own so it can't block screens). */
    public static <T> void runOn(java.util.concurrent.Executor ex, final Work<T> work, final Done<T> done) {
        ex.execute(new Runnable() {
            public void run() {
                T r = null;
                Exception ex = null;
                try {
                    r = work.run();
                } catch (Exception e) {
                    ex = e;
                } catch (OutOfMemoryError oom) {
                    ex = new Exception("Not enough memory for this list");
                }
                final T fr = r;
                final Exception fe = ex;
                MAIN.post(new Runnable() {
                    public void run() { done.done(fr, fe); }
                });
            }
        });
    }

    public static String message(Exception e) {
        if (e == null) return "";
        if (e instanceof java.net.UnknownHostException) return "Can't find that server — check the URL";
        if (e instanceof java.net.SocketTimeoutException) return "The server took too long to answer";
        if (e instanceof java.net.ConnectException) return "Couldn't connect to the server";
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }
}
