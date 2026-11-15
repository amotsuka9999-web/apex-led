package apexled.app

import android.app.Application

class ApexApp : Application() {
    lateinit var graph: AppGraph
        private set
    lateinit var wear: apexled.app.wear.WearBridge
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.start()
        wear = apexled.app.wear.WearBridge(this, graph)
    }
}
