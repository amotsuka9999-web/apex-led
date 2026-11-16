package apexled.wear

import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

/**
 * App do relógio: 3 páginas (deslize para o lado).
 *  1. Modo seguro: liga/desliga e mostra velocidade, PWM e bateria, com a cor da fita em volta.
 *  2. Setas: esquerda, direita e pisca-alerta.
 *  3. Cores fixas: toque numa cor e a fita fica nela (pausa a segurança).
 *  4. Modos: 6 efeitos prontos (pausa a segurança).
 * Vibra quando a margem de segurança se esgota.
 */
class WearActivity : ComponentActivity() {
    private lateinit var link: PhoneLink

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        link = PhoneLink(this)
        setContent { WearApp(link, ::vibrate) }
    }

    override fun onResume() { super.onResume(); link.start() }
    override fun onPause() { link.stop(); super.onPause() }

    private fun vibrate() {
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(Vibrator::class.java)
        v?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 120, 300, 120, 300), -1))
    }
}

private val Bg = Color(0xFF000000)
private val Card = Color(0xFF1C1F24)
private val Muted = Color(0xFF9AA3AD)
private val Good = Color(0xFF22C55E)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WearApp(link: PhoneLink, vibrate: () -> Unit) {
    val state by link.state.collectAsState()
    val pager = rememberPagerState(pageCount = { 4 })

    LaunchedEffect(state.critical) { if (state.critical) vibrate() }

    Box(Modifier.fillMaxSize().background(Bg)) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            when (page) {
                0 -> SafetyPage(state, link)
                1 -> TurnPage(state, link)
                2 -> ColorsPage(state, link)
                else -> PresetsPage(state, link)
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(4) { i ->
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (i == pager.currentPage) Color.White else Color(0xFF555555)))
            }
        }
    }
}

@Composable
private fun SafetyPage(s: WatchState, link: PhoneLink) {
    val ring = s.ledColor
    Box(
        Modifier.fillMaxSize().padding(4.dp).border(10.dp, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(statusLine(s), color = if (s.critical) Color(0xFFFF4444) else Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
            Text(s.speed?.let { String.format(Locale.US, "%.0f", it) } ?: "--", color = Color.White, fontSize = 46.sp, fontWeight = FontWeight.Bold)
            Text("km/h", color = Muted, fontSize = 11.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Metric("PWM", s.pwm?.let { String.format(Locale.US, "%.0f%%", it * 100) } ?: "--")
                Metric("BAT", s.battery?.let { String.format(Locale.US, "%.0f%%", it) } ?: "--")
            }
            Spacer(Modifier.height(4.dp))
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(if (s.safety) Good else Card)
                    .clickable { link.setSafety(!s.safety) }.padding(horizontal = 18.dp, vertical = 9.dp),
            ) {
                Text(if (s.safety) "Modo seguro: ON" else "Modo seguro: OFF", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun statusLine(s: WatchState): String = when {
    !s.connected -> "Sem celular"
    s.critical -> "MARGEM ESGOTADA"
    !s.wheel -> "Roda desconectada"
    !s.led -> "LED desconectado"
    s.driving -> "Segurança ativa"
    else -> "Pronto"
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(label, color = Muted, fontSize = 10.sp)
    }
}

private val Amber = Color(0xFFFF8C00)

@Composable
private fun TurnPage(s: WatchState, link: PhoneLink) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)) {
        Text("Setas", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            TurnButton("◀", s.turn == "LEFT", 64.dp) { link.setTurn("LEFT") }
            TurnButton("▶", s.turn == "RIGHT", 64.dp) { link.setTurn("RIGHT") }
        }
        TurnButton("⚠ Alerta", s.turn == "HAZARD", 110.dp, 44.dp) { link.setTurn("HAZARD") }
        Text("Toque de novo para desligar", color = Muted, fontSize = 10.sp)
    }
}

@Composable
private fun TurnButton(label: String, on: Boolean, w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp = 64.dp, onClick: () -> Unit) {
    Box(
        Modifier.width(w).height(h).clip(RoundedCornerShape(h / 2)).background(if (on) Amber else Card).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = if (h >= 60.dp) 26.sp else 15.sp, fontWeight = FontWeight.Bold) }
}

private val FIXED = listOf(
    Color(0xFFFF0000), Color(0xFFFF7A00), Color(0xFFFFD000),
    Color(0xFF00E04A), Color(0xFF00D8FF), Color(0xFF0040FF),
    Color(0xFFA000FF), Color(0xFFFFFFFF), Color(0xFF000000),
)

@Composable
private fun ColorsPage(s: WatchState, link: PhoneLink) {
    Column(Modifier.fillMaxSize().padding(horizontal = 30.dp, vertical = 26.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
        Text("Cores fixas", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        FIXED.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { c ->
                    val off = c == Color(0xFF000000)
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(if (off) Card else c)
                            .border(2.dp, if (s.ledColor == c) Color.White else Color.Transparent, CircleShape)
                            .clickable { link.setColor(c) },
                        contentAlignment = Alignment.Center,
                    ) { if (off) Text("OFF", color = Color.White, fontSize = 11.sp) }
                }
            }
        }
    }
}

@Composable
private fun PresetsPage(s: WatchState, link: PhoneLink) {
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically)) {
        Text("Modos", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        s.presets.take(6).withIndex().chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (i, name) ->
                    Box(
                        Modifier.width(78.dp).height(40.dp).clip(RoundedCornerShape(14.dp)).background(Card)
                            .clickable { link.setPreset(i) }.padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(name, color = Color.White, fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 2, lineHeight = 12.sp) }
                }
            }
        }
    }
}
