package apexled.app.extras

import android.content.Context
import android.os.BatteryManager
import android.speech.tts.TextToSpeech
import apexled.app.data.VoiceConfig
import apexled.core.SourceStatus
import apexled.core.Telemetry
import java.util.Locale

/**
 * Alertas falados pelo celular (saem no fone Bluetooth, se estiver conectado):
 * PWM ao cruzar limites, bateria da roda, sinal da roda perdido e bateria do celular baixa.
 * Cada aviso fala uma vez e só "rearma" depois de voltar com folga, para não ficar repetindo.
 */
class VoiceAlerts(private val context: Context, private val onNotice: (String) -> Unit) {
    private var tts: TextToSpeech? = null
    private var ready = false

    private var pwmSpoken = -1          // índice do maior limite de PWM já falado
    private val batterySpoken = HashSet<Int>()
    private var wasConnected = false
    private var lostSpoken = false
    private var lastPhoneCheckMs = 0L
    private var phoneSpoken = false

    private fun ensure() {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) tts?.language = Locale("pt", "BR")
        }
    }

    fun say(text: String) {
        ensure()
        if (ready) tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "apexled-${System.nanoTime()}")
    }

    fun onTick(now: Long, t: Telemetry?, status: SourceStatus, cfg: VoiceConfig) {
        if (!cfg.enabled) { reset(); return }
        ensure()
        val connected = status is SourceStatus.Connected

        // PWM
        val pwm = t?.pwm
        if (pwm != null && connected) {
            val steps = cfg.pwmSteps.sorted()
            val idx = steps.indexOfLast { pwm >= it }
            if (idx > pwmSpoken) { pwmSpoken = idx; say("PWM ${(steps[idx] * 100).toInt()}") }
            else if (pwmSpoken >= 0 && pwm < steps[pwmSpoken] - 0.05f) pwmSpoken = steps.indexOfLast { pwm >= it + 0.05f }
        }

        // bateria da roda
        t?.batteryPercent?.let { b ->
            if (!connected) return@let
            cfg.batterySteps.sortedDescending().firstOrNull { b <= it && it !in batterySpoken }?.let { step ->
                batterySpoken += cfg.batterySteps.filter { it >= step }
                say("Bateria da roda $step por cento")
            }
        }

        // sinal perdido
        if (connected) { wasConnected = true; lostSpoken = false }
        else if (wasConnected && cfg.signalLost && !lostSpoken && status !is SourceStatus.Idle) {
            lostSpoken = true
            say("Sinal da roda perdido")
        }
        if (status is SourceStatus.Idle) wasConnected = false

        // bateria do celular (a cada minuto)
        if (cfg.phoneBatteryBelow > 0 && now - lastPhoneCheckMs > 60_000) {
            lastPhoneCheckMs = now
            val bm = context.getSystemService(BatteryManager::class.java)
            val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
            val charging = bm?.isCharging == true
            if (pct in 1..cfg.phoneBatteryBelow && !charging && !phoneSpoken) {
                phoneSpoken = true
                say("Bateria do celular em $pct por cento")
                onNotice("Bateria do celular em $pct%: se ele desligar, as luzes de segurança param.")
            } else if (pct > cfg.phoneBatteryBelow + 5 || charging) phoneSpoken = false
        }
    }

    private fun reset() { pwmSpoken = -1; batterySpoken.clear(); lostSpoken = false; wasConnected = false }

    fun shutdown() { tts?.shutdown(); tts = null; ready = false }
}
