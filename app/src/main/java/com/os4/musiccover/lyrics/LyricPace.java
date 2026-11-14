package com.os4.musiccover.lyrics;

/**
 * The lyric animation's frame budget, in fps (2026-11-14).
 *
 * Written once by the 歌词外观 page's setting (Settings.LYRIC_FPS) and read by
 * {@link LyricFrameLoop} on every frame, so it is a plain volatile int: no Context, no binder,
 * and it is already in the module process where the loop runs.
 *
 * 0 or less means "no cap" (follow the display); the setting only offers 30/60/90/120.
 */
public final class LyricPace {
    private LyricPace() {}

    public static volatile int fps = 60;

    /** Milliseconds between lyric frames; 0 when uncapped. */
    public static long frameGapMs() {
        int value = fps;
        return value <= 0 ? 0L : Math.max(1L, 1000L / value);
    }
}