package apexled.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import apexled.app.AppGraph
import apexled.app.ApexApp
import apexled.app.MainActivity
import apexled.app.R
import apexled.core.LedLinkState
import apexled.core.SourceStatus
import apexled.data.ble.BlePermissions
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Serviço em primeiro plano (tipo connectedDevice): mantém o processo vivo para a roda e a fita continuarem
 * conectadas com a tela apagada. A lógica em si mora no [AppGraph]; aqui só há a notificação.
 */
class ConnectionService : Service() {
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val graph = (application as ApexApp).graph
        if (intent?.action == ACTION_STOP) {
            graph.stopAll()
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel()
        try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, buildNotification("Conectando…"),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
            )
        } catch (e: Exception) {
            // Sem permissão de Bluetooth concedida o Android 14+ recusa o tipo connectedDevice.
            stopSelf(); return START_NOT_STICKY
        }
        job?.cancel()
        job = graph.scope.launch {
            combine(graph.telemetry, graph.sourceStatus, graph.ledLink, graph.safety, graph.needsForeground) { t, st, link, safe, need ->
                if (!need) null else buildString {
                    append("Roda: ")
                    append(when (st) {
                        is SourceStatus.Connected -> if (t != null) String.format(Locale.US, "%.0f km/h", t.speedKmh) +
                            (t.pwm?.let { String.format(Locale.US, " · PWM %.0f%%", it * 100) } ?: "") else "conectada"
                        is SourceStatus.Connecting -> "conectando…"
                        is SourceStatus.Failed -> "falha"
                        SourceStatus.Idle -> "desligada"
                    })
                    append("  |  LED: ")
                    append(when (link) {
                        LedLinkState.CONNECTED -> "ok"
                        LedLinkState.DISCONNECTED -> "desconectado"
                        else -> "reconectando…"
                    })
                    if (safe != null) append("  |  ").append(safe.level.label)
                }
            }.collect { text ->
                if (text == null) {
                    // tolerância: o estado do grafo pode ainda não ter refletido a conexão recém-pedida
                    kotlinx.coroutines.delay(2500)
                    if (!graph.needsForeground.value) { stopForegroundCompat(); stopSelf() }
                } else {
                    getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun stopForegroundCompat() = ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)

    override fun onDestroy() { job?.cancel(); super.onDestroy() }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ConnectionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Apex LED")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Parar", stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "conexao"
        private const val NOTIF_ID = 42
        private const val ACTION_STOP = "apexled.STOP"

        /** Liga o serviço se há algo conectado que precise dele (o serviço se encerra sozinho quando não precisar). */
        fun sync(context: Context, graph: AppGraph) {
            if (!BlePermissions.hasAll(context)) return
            if (graph.needsForeground.value || shouldStartSoon(graph)) {
                runCatching { ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java)) }
            }
        }

        private fun shouldStartSoon(graph: AppGraph): Boolean {
            val s = graph.settings.value
            return (graph.source.value?.kind?.let { it != apexled.core.SourceKind.SIMULATOR } == true) || !s.ledSimulated
        }
    }
}
