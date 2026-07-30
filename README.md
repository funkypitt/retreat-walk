# Retreat Walk

An Android app that rings a singing bowl at a fixed interval — every 10 minutes,
say — over and over until you stop it. For switching partners in a paired
exercise, and for sitting with a regular reminder to stay awake.

One number, one button. That is the whole app.

## How it works

Set the minutes between bells and press **Start**. The bell rings at that many
minutes, then at twice, then three times, and so on. Press **Stop** — in the app
or on the notification — and it ends.

Each bell is timed from the **start of the session**, not from the bell before
it. Adding an interval to "now" at every ring would fold each wake-up delay into
the next gap, and an hour in, the bells would have drifted minutes off the clock.

## Why it rings on time

- Every bell is armed with `AlarmManager.setAlarmClock()` — the one alarm type
  Android guarantees will fire at the exact time even in Doze, with the screen
  locked. It is the same mechanism the stock Clock app uses.
- The session runs as a **foreground service** (type `mediaPlayback`) holding a
  **wake lock** while the bowl sounds, so nothing the system does can mute it or
  kill it partway through.
- The notification is silent and carries a **Stop** action, so the session can be
  ended from the lock screen without opening the app.
- A session deliberately does **not** survive a reboot: the app notices that the
  alarm is gone and shows itself as stopped, rather than a countdown that will
  never reach zero.

## The bell

`app/src/main/res/raw/bell.mp3` is one strike of the Satipanya bowl — the
single-strike cut from [Retreat Timer](https://github.com/funkypitt/retreat-timer),
loudness-matched to −20 LUFS. One strike, not three: this is a marker inside a
sitting, not the signal that opens or closes one.

## Build

```
./gradlew assembleDebug
```

No ads, no tracking, no accounts, no network permission at all. Permissions are
limited to exact alarms, wake lock, foreground-service playback, and posting the
notification that carries Stop.
