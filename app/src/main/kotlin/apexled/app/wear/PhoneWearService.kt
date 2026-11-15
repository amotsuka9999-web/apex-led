package apexled.app.wear

import apexled.app.ApexApp
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService

/** Recebe os comandos do relógio, mesmo com o app do celular fechado (o sistema abre o processo). */
class PhoneWearService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        (application as ApexApp).wear.onMessage(event.path, event.data, event.sourceNodeId)
    }
}
