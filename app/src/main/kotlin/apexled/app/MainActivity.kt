package apexled.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import apexled.app.ui.MainScreen
import apexled.app.ui.theme.ApexTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = (application as ApexApp).graph
        splash.setKeepOnScreenCondition { !graph.loaded.value }
        setContent {
            val settings by graph.settings.collectAsState()
            ApexTheme(settings.theme) { MainScreen(graph) }
        }
    }
}
