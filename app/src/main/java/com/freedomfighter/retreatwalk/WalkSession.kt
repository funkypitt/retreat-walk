package com.freedomfighter.retreatwalk

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The one running session, in the two places it has to be known.
 *
 * [WalkSession] is the durable copy in SharedPreferences: the alarm that rings
 * the next bell arrives in a broadcast receiver, which may find the process was
 * killed in between, and it has to be able to work out on its own which bell is
 * due and when the one after it is. [Live] is the in-memory mirror the screen
 * observes, so the countdown redraws without reading preferences every second.
 */
object WalkSession {
    private const val PREFS = "retreat_walk"
    private const val KEY_RUNNING = "running"
    private const val KEY_INTERVAL = "interval_min"
    private const val KEY_STARTED_AT = "started_at"
    private const val KEY_RANG = "rang"
    private const val KEY_LAST_INTERVAL = "last_interval_min"

    /** Interval the input starts on, and the one this app exists for. */
    const val DEFAULT_INTERVAL_MIN = 10

    /** Bounds on the input. One minute is the shortest that leaves the bell room
     *  to ring out; three hours is past any use this app is for. */
    const val MIN_INTERVAL = 1
    const val MAX_INTERVAL = 180

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isRunning(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_RUNNING, false)

    fun intervalMin(ctx: Context): Int =
        prefs(ctx).getInt(KEY_INTERVAL, DEFAULT_INTERVAL_MIN)

    fun startedAt(ctx: Context): Long = prefs(ctx).getLong(KEY_STARTED_AT, 0L)

    /** How many bells have already rung this session. */
    fun rang(ctx: Context): Int = prefs(ctx).getInt(KEY_RANG, 0)

    /** The interval last used, so the input opens where the teacher left it. */
    fun lastInterval(ctx: Context): Int =
        prefs(ctx).getInt(KEY_LAST_INTERVAL, DEFAULT_INTERVAL_MIN)

    fun begin(ctx: Context, intervalMin: Int, startedAt: Long) {
        prefs(ctx).edit()
            .putBoolean(KEY_RUNNING, true)
            .putInt(KEY_INTERVAL, intervalMin)
            .putInt(KEY_LAST_INTERVAL, intervalMin)
            .putLong(KEY_STARTED_AT, startedAt)
            .putInt(KEY_RANG, 0)
            .apply()
    }

    fun recordRing(ctx: Context, rang: Int) {
        prefs(ctx).edit().putInt(KEY_RANG, rang).apply()
    }

    fun end(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_RUNNING, false).apply()
    }

    /**
     * When bell number [n] falls, counted from the start of the session rather
     * than from the previous bell. Measuring from [startedAt] is what keeps the
     * 10th bell at exactly 100 minutes: adding an interval to "now" each time
     * would fold every wake-up delay into the next gap, and by mid-afternoon the
     * bells would have drifted minutes away from the clock.
     */
    fun bellAt(startedAt: Long, intervalMin: Int, n: Int): Long =
        startedAt + n.toLong() * intervalMin * 60_000L

    /** In-memory mirror of the session, observed by the screen. */
    object Live {
        var running by mutableStateOf(false)
        var intervalMin by mutableStateOf(DEFAULT_INTERVAL_MIN)

        /** Wall-clock instant of the next bell; 0 when nothing is running. */
        var nextBellAt by mutableStateOf(0L)
        var rang by mutableStateOf(0)

        /**
         * Refresh from storage. A session whose alarm no longer exists — the
         * device rebooted, which drops every PendingIntent — is ended here rather
         * than left on screen as a countdown that will never reach zero.
         */
        fun sync(ctx: Context) {
            if (isRunning(ctx) && !WalkService.hasArmedBell(ctx)) end(ctx)
            running = isRunning(ctx)
            intervalMin = intervalMin(ctx)
            rang = rang(ctx)
            nextBellAt = if (running) {
                bellAt(startedAt(ctx), intervalMin, rang + 1)
            } else {
                0L
            }
        }

        fun clear() {
            running = false
            nextBellAt = 0L
            rang = 0
        }
    }
}
