package com.freedomfighter.retreatwalk

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Calendar

/**
 * The whole app, really: a foreground service that rings the bell at every
 * multiple of the chosen interval until it is stopped.
 *
 * Each bell is armed with [AlarmManager.setAlarmClock] — the one alarm type
 * Android guarantees fires at the exact time even in Doze, with the screen
 * locked for an hour. Running in the foreground (type mediaPlayback) keeps the
 * service alive between bells and puts the Stop button in the shade.
 */
class WalkService : Service() {

    private var player: MediaPlayer? = null
    private var ringLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSession()
            ACTION_RING -> ring()
            else -> {
                // A null intent is Android restarting us after a kill (START_STICKY),
                // and carries no interval — resuming the stored session is right;
                // starting a fresh one from a missing extra would silently invent a
                // one-minute bell.
                val minutes = intent?.getIntExtra(EXTRA_INTERVAL_MIN, 0) ?: 0
                if (minutes > 0) startSession(minutes) else resumeSession()
            }
        }
        return START_STICKY
    }

    /** Re-establish the stored session — after the OS restarted the service, or
     *  when the screen asks us to make sure the next bell really is armed. */
    private fun resumeSession() {
        // Foreground first, always: this instance may have been created from a
        // background start, and a service started that way which does not call
        // startForeground promptly is killed with an exception — including on the
        // path where it turns out there is nothing to resume.
        goForeground()
        if (!WalkSession.isRunning(this)) {
            stopSession()
            return
        }
        armNext()
        WalkSession.Live.sync(this)
    }

    private fun startSession(requestedMinutes: Int) {
        val interval = requestedMinutes
            .coerceIn(WalkSession.MIN_INTERVAL, WalkSession.MAX_INTERVAL)
        val startedAt = System.currentTimeMillis()

        WalkSession.begin(this, interval, startedAt)
        goForeground()
        armNext()
        WalkSession.Live.sync(this)
    }

    /** Ring bell number n, then arm n+1. Reads the session from storage rather
     *  than from a field, so a bell still lands correctly if the process was
     *  killed between two of them and this is a fresh instance. */
    private fun ring() {
        goForeground()
        // Stop pressed in the same instant the alarm fired: the session is gone,
        // so ring nothing and shut down.
        if (!WalkSession.isRunning(this)) {
            stopSession()
            return
        }
        WalkSession.recordRing(this, WalkSession.rang(this) + 1)
        playBell()
        armNext()
        WalkSession.Live.sync(this)
        pushNotification()
    }

    private fun playBell() {
        // Held briefly so the CPU cannot drop back to sleep between the alarm
        // waking us and the bowl actually sounding.
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        ringLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RetreatWalk:ring").apply {
            setReferenceCounted(false)
            acquire(60_000L)
        }

        runCatching { player?.release() }
        player = null
        runCatching {
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                setWakeMode(this@WalkService, PowerManager.PARTIAL_WAKE_LOCK)
                val afd = resources.openRawResourceFd(R.raw.bell)
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                setOnCompletionListener { releasePlayer() }
                setOnErrorListener { _, _, _ -> releasePlayer(); true }
                prepare()
                start()
            }
        }.onFailure { releasePlayer() }
    }

    private fun releasePlayer() {
        runCatching { player?.release() }
        player = null
        runCatching { if (ringLock?.isHeld == true) ringLock?.release() }
        ringLock = null
    }

    /** Arm the next multiple of the interval. Any bell whose moment has already
     *  passed — the device was off, or a bell was missed — is skipped rather than
     *  fired late, so the session stays on the grid it started on. */
    private fun armNext() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val startedAt = WalkSession.startedAt(this)
        val interval = WalkSession.intervalMin(this)
        var n = WalkSession.rang(this) + 1
        val now = System.currentTimeMillis()
        while (WalkSession.bellAt(startedAt, interval, n) <= now) n++
        if (n != WalkSession.rang(this) + 1) WalkSession.recordRing(this, n - 1)

        val triggerAt = WalkSession.bellAt(startedAt, interval, n)
        val show = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), firePendingIntent(this))
    }

    private fun stopSession() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching { am.cancel(firePendingIntent(this)) }
        WalkSession.end(this)
        WalkSession.Live.clear()
        releasePlayer()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releasePlayer()
        super.onDestroy()
    }

    // ---- notification ----

    private fun goForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun pushNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): android.app.Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // IMPORTANCE_LOW, silent, no vibration: the bowl is the only sound this
            // app should ever make. A notification ding on top of it is exactly the
            // noise a silent room must not have.
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_session),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.channel_session_desc)
                    setSound(null, null)
                    enableVibration(false)
                },
            )
        }

        val interval = WalkSession.intervalMin(this)
        val rang = WalkSession.rang(this)
        val next = WalkSession.bellAt(WalkSession.startedAt(this), interval, rang + 1)

        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, WalkService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title, interval))
            .setContentText(
                if (rang == 0) {
                    getString(R.string.notif_first, clock(next))
                } else {
                    getString(R.string.notif_next, rang, clock(next))
                },
            )
            .setSmallIcon(R.drawable.ic_bell)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.stop), stop)
            .build()
    }

    private fun clock(millis: Long): String {
        val c = Calendar.getInstance().apply { timeInMillis = millis }
        return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    companion object {
        const val ACTION_STOP = "com.freedomfighter.retreatwalk.STOP"
        const val ACTION_RING = "com.freedomfighter.retreatwalk.RING"
        const val EXTRA_INTERVAL_MIN = "interval_min"

        private const val CHANNEL_ID = "retreat_walk_session"
        private const val NOTIF_ID = 1
        private const val RING_ACTION = "com.freedomfighter.retreatwalk.ALARM"

        /** The alarm that wakes us for the next bell. Extras are deliberately
         *  absent: PendingIntent equality ignores them, and the service reads the
         *  session from storage anyway, so one intent serves every bell. */
        private fun firePendingIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
            ctx, 0,
            Intent(ctx, AlarmReceiver::class.java).apply {
                action = RING_ACTION
                data = Uri.parse("retreatwalk://bell")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        /**
         * True while a bell is actually armed. Every PendingIntent is dropped on
         * reboot, so this is how the screen tells a live session from one that the
         * stored flag still claims but that died with the last power cycle.
         */
        fun hasArmedBell(ctx: Context): Boolean = PendingIntent.getBroadcast(
            ctx, 0,
            Intent(ctx, AlarmReceiver::class.java).apply {
                action = RING_ACTION
                data = Uri.parse("retreatwalk://bell")
            },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) != null

        fun start(ctx: Context, intervalMin: Int) = send(
            ctx,
            Intent(ctx, WalkService::class.java).putExtra(EXTRA_INTERVAL_MIN, intervalMin),
        )

        fun stop(ctx: Context) =
            send(ctx, Intent(ctx, WalkService::class.java).setAction(ACTION_STOP))

        fun ringNow(ctx: Context) =
            send(ctx, Intent(ctx, WalkService::class.java).setAction(ACTION_RING))

        private fun send(ctx: Context, intent: Intent) {
            ContextCompat.startForegroundService(ctx, intent)
        }
    }
}
