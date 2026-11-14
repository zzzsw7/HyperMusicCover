package com.os4.musiccover.lyrics;

import android.os.SystemClock;

/** One animation callback, including when drawing synchronously re-enters the host. */
final class LyricFrameLoop implements Runnable {
    interface Host {
        void enqueue(Runnable frame);
        void cancel(Runnable frame);
        boolean frame();
        void idle();
    }

    /**
     * Frame budget for the lyric animation, in ms (0 = no cap, follow the display).
     *
     * Read from `debug.hyperextreme.lyric_fps` so it can be changed on device without a rebuild:
     *
     *   adb shell setprop debug.hyperextreme.lyric_fps 120   # follow the 120Hz panel
     *   adb shell setprop debug.hyperextreme.lyric_fps 60    # default: half the frames
     *   adb shell setprop debug.hyperextreme.lyric_fps 0     # no cap at all
     *
     * Why it exists: the lyric animation repaints the lyric view (and its glow/blur masks) on
     * every display frame, which on a 120Hz panel costs about 97% of one SystemUI core with the
     * lock screen showing lyrics. 60fps halves that; the scroll still reads smoothly because the
     * lyric motion is a slow translation, not a fast animation.
     */
    private static final String PROP_FPS = "debug.hyperextreme.lyric_fps";
    private static final long PROP_TTL_MS = 2_000L;

    private static long propReadAt;
    private static int propFps = 0;

    private long lastFrameAt;

    private final Host host;
    private boolean queued, running, requested;
    private int generation;

    LyricFrameLoop(Host host) {
        this.host = host;
    }

    boolean active() {
        return queued || running;
    }

    void request() {
        if (queued) return;
        if (running) {
            requested = true;
            return;
        }
        queued = true;
        host.enqueue(this);
    }

    void stop() {
        generation++;
        queued = requested = false;
        host.cancel(this);
        if (!running) host.idle();
    }

    @Override public void run() {
        if (!queued || running) return;
        queued = false;
        // Too soon for the next lyric frame: hand the callback straight back. Re-posting is
        // cheap (one Runnable, no traversal); the expensive half is host.frame().
        long gap = frameGapMs();
        if (gap > 0) {
            long now = SystemClock.uptimeMillis();
            if (now - lastFrameAt < gap) {
                request();
                return;
            }
            lastFrameAt = now;
        }
        running = true;
        requested = false;
        int entered = generation;
        boolean more;
        try {
            more = host.frame();
        } finally {
            running = false;
        }
        // A detach/park during a synchronous traversal must not start the loop again.
        if (entered == generation && (more || requested)) request();
        else if (!queued) host.idle();
    }

    /** -1 = 属性没设过，用 LyricPace（App 设置项）的值；显式 0 = 不限帧。 */
    private static final int PROP_UNSET = Integer.MIN_VALUE;

    /**
     * Milliseconds between lyric frames; 0 disables the cap.
     *
     * The app's setting ({@link LyricPace}, 30/60/90/120) is the source; `debug.hyperextreme.lyric_fps`
     * stays as an adb override for experiments and wins when it is set.
     */
    private static long frameGapMs() {
        int fps = currentFps();
        if (fps == PROP_UNSET) fps = LyricPace.fps;
        return fps <= 0 ? 0L : Math.max(1L, 1000L / fps);
    }

    private static int currentFps() {
        long now = SystemClock.uptimeMillis();
        if (propFps != 0 && now - propReadAt < PROP_TTL_MS) return propFps;
        propReadAt = now;
        propFps = readProp();
        return propFps;
    }

    private static int readProp() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            Object raw = sp.getMethod("get", String.class, String.class)
                    .invoke(null, PROP_FPS, "");
            if (raw instanceof String) {
                String text = ((String) raw).trim();
                if (!text.isEmpty()) return Integer.parseInt(text);
            }
        } catch (Throwable ignored) {
            // Hidden API blocked or unparsable: fall through to the app's setting.
        }
        return PROP_UNSET;
    }
}