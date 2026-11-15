package apexled.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.store by preferencesDataStore(name = "apex_led")

/** Guarda o [AppSettings] inteiro como um JSON no DataStore. */
class SettingsRepository(private val context: Context) {
    private val key = stringPreferencesKey("settings_json")

    suspend fun load(): AppSettings = SettingsCodec.decode(context.store.data.map { it[key] }.first())

    suspend fun save(settings: AppSettings) {
        context.store.edit { it[key] = SettingsCodec.encode(settings) }
    }
}
