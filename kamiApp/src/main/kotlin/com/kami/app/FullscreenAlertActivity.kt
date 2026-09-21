package com.kami.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Configurable full-screen alert ("alarm style"): big clock, title, body and
 * a dismiss button. Shows over the lockscreen and turns the screen on — the
 * target of important reminders' full-screen intent and the fullscreen_alert
 * agent tool. Extras: title / text / vibrate (looping waveform while shown).
 */
class FullscreenAlertActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())

    private var nowMs by mutableStateOf(System.currentTimeMillis())

    private val tick = object : Runnable {
        override fun run() {
            nowMs = System.currentTimeMillis()
            // Minute-aligned: the clock shows HH:mm, so wake once a minute.
            handler.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000 + 50)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 锁屏之上可见 + 亮屏（manifest 属性 27+ 生效，flags 兜底）
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
        )
        val title = intent.getStringExtra("title") ?: "提醒"
        val text = intent.getStringExtra("text").orEmpty()
        if (intent.getBooleanExtra("vibrate", true)) {
            Vibe.pattern(this, longArrayOf(0, 500, 700), 0)
        }
        handler.postDelayed(tick, 60_000 - System.currentTimeMillis() % 60_000 + 50)
        setContent {
            val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(nowMs))
            MaterialTheme(colorScheme = MaterialTheme.colorScheme) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFF141414))
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(clock, fontSize = 64.sp, color = Color.White, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(24.dp))
                    Text(
                        title,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                    )
                    if (text.isNotBlank()) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text,
                            fontSize = 18.sp,
                            color = Color(0xFFCCCCCC),
                            textAlign = TextAlign.Center,
                        )
                    }
                    Spacer(Modifier.height(48.dp))
                    Button(
                        onClick = {
                            Vibe.cancel(this@FullscreenAlertActivity)
                            finish()
                        },
                        shape = RoundedCornerShape(28.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E6DE6)),
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) {
                        Text("知道了", fontSize = 18.sp, color = Color.White)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(tick)
        Vibe.cancel(this)
    }
}
