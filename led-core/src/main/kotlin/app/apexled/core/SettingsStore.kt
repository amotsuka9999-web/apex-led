package app.apexled.core

import java.io.File
import java.io.IOException

/** Where settings live between sessions. */
interface SettingsStore {
    /** The saved settings, or defaults when nothing usable is saved. Never throws. */
    fun load(): LedSettings

    /** Throws [IOException] when the settings could not be written. */
    fun save(settings: LedSettings)
}

class InMemorySettingsStore(private var settings: LedSettings = LedSettings()) : SettingsStore {
    @Synchronized override fun load(): LedSettings = settings

    @Synchronized override fun save(settings: LedSettings) {
        this.settings = settings
    }
}

/**
 * Settings as a JSON file. Uses only java.io, so it runs on every Android version WheelLog supports.
 * A write replaces the file in one step, so a crash mid-save leaves the previous settings intact.
 * A file that cannot be read is kept aside as `<name>.corrupt` and defaults are used.
 */
class FileSettingsStore(private val file: File) : SettingsStore {

    @Synchronized
    override fun load(): LedSettings {
        if (!file.isFile) return LedSettings()
        return try {
            SettingsJson.decode(file.readText(Charsets.UTF_8))
        } catch (e: IOException) {
            LedSettings()
        } catch (e: IllegalArgumentException) {
            // Covers malformed JSON and values outside their valid range.
            file.copyTo(File(file.path + ".corrupt"), overwrite = true)
            LedSettings()
        }
    }

    @Synchronized
    override fun save(settings: LedSettings) {
        file.absoluteFile.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeText(SettingsJson.encode(settings), Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            // Some filesystems refuse to rename over an existing file.
            if (!file.delete() || !temp.renameTo(file)) {
                temp.delete()
                throw IOException("could not replace ${file.path}")
            }
        }
    }
}
