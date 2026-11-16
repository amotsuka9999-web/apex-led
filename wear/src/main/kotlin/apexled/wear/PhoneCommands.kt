package apexled.wear

import android.content.Context
import com.google.android.gms.wearable.Wearable

/** Envia um comando para o app do celular (todos os aparelhos conectados) e guarda o último estado conhecido. */
object PhoneCommands {
    fun send(context: Context, path: String, data: ByteArray) {
        val app = context.applicationContext
        val messages = Wearable.getMessageClient(app)
        Wearable.getNodeClient(app).connectedNodes.addOnSuccessListener { nodes ->
            nodes.forEach { n -> messages.sendMessage(n.id, path, data) }
        }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("apexled", Context.MODE_PRIVATE)

    /** Último estado recebido do celular, para o bloco (tile) mostrar. */
    fun saveState(context: Context, safety: Boolean, turn: String?) {
        prefs(context).edit().putBoolean("safety", safety).putString("turn", turn).apply()
    }

    fun safety(context: Context) = prefs(context).getBoolean("safety", false)
    fun turn(context: Context): String? = prefs(context).getString("turn", null)
}
