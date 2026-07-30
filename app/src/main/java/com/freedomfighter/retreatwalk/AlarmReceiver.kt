package com.freedomfighter.retreatwalk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fired by AlarmManager at each bell. Its only job is to hand straight over to
 * [WalkService], which rings and arms the following one.
 *
 * Starting a foreground service from an exact-alarm broadcast is explicitly
 * allowed by the OS even while the device is idle, so this is reliable.
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        WalkService.ringNow(context)
    }
}
