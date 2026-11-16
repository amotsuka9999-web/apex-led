package apexled.app.extras

import android.content.Context
import apexled.core.Telemetry
import apexled.safety.SafetyOutput
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Grava o passeio num CSV (5 linhas por segundo) em Arquivos do app/passeios. As colunas seguem o
 * espírito do CSV do WheelLog (data, hora, velocidade, tensão, corrente, PWM, bateria, temperatura).
 */
class TripRecorder(context: Context) {
    val file: File
    private val out: BufferedWriter
    private var lastMs = 0L
    private var lines = 0

    init {
        val dir = dir(context).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        file = File(dir, "passeio_$stamp.csv")
        out = file.bufferedWriter()
        out.write("date,time,speed,voltage,current,pwm,battery_level,temperature,safety_level\n")
    }

    fun onTick(now: Long, t: Telemetry?, safety: SafetyOutput?) {
        if (t == null || now - lastMs < 200) return
        lastMs = now
        val d = Date()
        val line = String.format(
            Locale.US, "%s,%s,%.2f,%.2f,%s,%s,%s,%s,%s\n",
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d), SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(d),
            t.speedKmh, t.voltage, t.currentA?.let { "%.2f".format(Locale.US, it) } ?: "",
            t.pwm?.let { "%.1f".format(Locale.US, it * 100) } ?: "", t.batteryPercent?.let { "%.0f".format(Locale.US, it) } ?: "",
            t.temperatureC?.let { "%.1f".format(Locale.US, it) } ?: "", safety?.level?.name ?: "",
        )
        runCatching { out.write(line); if (++lines % 25 == 0) out.flush() }
    }

    fun close() { runCatching { out.flush(); out.close() } }

    companion object {
        fun dir(context: Context) = File(context.getExternalFilesDir(null) ?: context.filesDir, "passeios")
        fun list(context: Context): List<File> = dir(context).listFiles { f -> f.extension == "csv" }?.sortedByDescending { it.name }.orEmpty()
    }
}
