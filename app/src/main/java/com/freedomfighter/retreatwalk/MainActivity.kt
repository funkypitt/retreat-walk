package com.freedomfighter.retreatwalk

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.compose.foundation.text.KeyboardOptions
import kotlinx.coroutines.delay

private val Paper = Color(0xFFF7F3EA)
private val Ink = Color(0xFF2B2620)
private val Accent = Color(0xFF8C5A2B)
private val Stop = Color(0xFF9A3B24)

class MainActivity : ComponentActivity() {

    private val requestNotif =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* nothing to do */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Accent, background = Paper)) {
                WalkApp()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // The session may have been stopped from the notification while the screen
        // was away; pick that up rather than showing a stale countdown.
        WalkSession.Live.sync(this)
    }
}

@Composable
private fun WalkApp() {
    val ctx = LocalContext.current
    val live = WalkSession.Live

    var minutes by remember { mutableStateOf(WalkSession.lastInterval(ctx).toString()) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* the session runs either way; without it the Stop action is just hidden */ }

    LaunchedEffect(Unit) {
        WalkSession.Live.sync(ctx)
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }

    val parsed = minutes.toIntOrNull()
    val valid = parsed != null && parsed >= WalkSession.MIN_INTERVAL && parsed <= WalkSession.MAX_INTERVAL

    Surface(Modifier.fillMaxSize(), color = Paper) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
        ) {
            Spacer(Modifier.height(56.dp))
            Text(
                "Retreat Walk",
                fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold,
                fontSize = 30.sp, color = Ink,
            )
            Text(
                "A bell every so many minutes, until you stop it.",
                fontFamily = FontFamily.Serif, fontSize = 14.sp,
                color = Ink.copy(alpha = 0.7f), textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp),
            )

            Spacer(Modifier.height(44.dp))

            OutlinedTextField(
                value = minutes,
                onValueChange = { new -> minutes = new.filter(Char::isDigit).take(3) },
                enabled = !live.running,
                singleLine = true,
                label = { Text("Minutes between bells") },
                suffix = { Text("min") },
                isError = minutes.isNotEmpty() && !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = LocalTextStyle.current.copy(
                    fontSize = 30.sp,
                    fontFamily = FontFamily.Serif,
                    textAlign = TextAlign.Center,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                if (minutes.isNotEmpty() && !valid) {
                    "Choose between ${WalkSession.MIN_INTERVAL} and ${WalkSession.MAX_INTERVAL} minutes."
                } else {
                    "The bell rings at that many minutes, then twice that, then three times — on and on."
                },
                fontSize = 12.sp,
                color = if (minutes.isNotEmpty() && !valid) Stop else Ink.copy(alpha = 0.55f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )

            Spacer(Modifier.height(36.dp))

            Button(
                onClick = {
                    if (live.running) {
                        WalkService.stop(ctx)
                        WalkSession.Live.clear()
                    } else {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                            != PackageManager.PERMISSION_GRANTED
                        ) {
                            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        // No sync() here: the service starts asynchronously, and
                        // reading back before it has armed the first bell would see
                        // no alarm and cancel the session we just asked for. The
                        // service syncs once it is up, which is what flips this
                        // button to Stop.
                        WalkService.start(ctx, parsed ?: WalkSession.DEFAULT_INTERVAL_MIN)
                    }
                },
                enabled = live.running || valid,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (live.running) Stop else Accent,
                ),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().height(76.dp),
            ) {
                Text(
                    if (live.running) "Stop" else "Start",
                    fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(36.dp))

            if (live.running) {
                val left = (live.nextBellAt - now).coerceAtLeast(0L)
                Text(
                    "Next bell in",
                    fontSize = 13.sp, color = Ink.copy(alpha = 0.6f),
                )
                Text(
                    countdown(left),
                    fontFamily = FontFamily.Monospace, fontSize = 46.sp,
                    fontWeight = FontWeight.Bold, color = Accent,
                )
                Text(
                    when (live.rang) {
                        0 -> "No bell yet — every ${live.intervalMin} min from the start"
                        1 -> "1 bell so far"
                        else -> "${live.rang} bells so far"
                    },
                    fontSize = 13.sp, color = Ink.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 6.dp),
                )
                Text(
                    "You can lock the screen. Stop is in the notification too.",
                    fontSize = 12.sp, color = Ink.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 20.dp),
                )
            } else {
                Text(
                    "For switching partners in a paired exercise, or a regular " +
                        "reminder to stay awake while sitting.",
                    fontSize = 13.sp, color = Ink.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** "07:42", or "1:02:30" once an interval runs past the hour. */
private fun countdown(ms: Long): String {
    val total = (ms + 999) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
