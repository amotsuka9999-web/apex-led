package apexled.app.data

import apexled.core.LedState
import apexled.core.SourceKind
import apexled.core.WheelProfile
import apexled.safety.SafetyConfig

enum class ThemeMode(val label: String) { DARK("Escuro"), LIGHT("Claro"), SYSTEM("Sistema") }

/** Tudo que é persistido entre execuções. */
data class AppSettings(
    val profiles: List<WheelProfile> = WheelProfile.all(),
    val activeProfileId: String = "begode-a2",
    /** Detectar o perfil pelo modelo que a roda informa (Veteran informa; Begode/Gotway não). */
    val autoDetectProfile: Boolean = true,
    val safety: SafetyConfig = SafetyConfig(),
    val source: SourceKind = SourceKind.SIMULATOR,
    val wheelAddress: String? = null,
    val wheelName: String? = null,
    val ledAddress: String? = null,
    val ledName: String? = null,
    /** true = usa o controlador simulado (sem Bluetooth); false = SP621E real. */
    val ledSimulated: Boolean = true,
    val theme: ThemeMode = ThemeMode.DARK,
    val disclaimerAccepted: Boolean = false,
    val led: LedState = LedState(),
) {
    val activeProfile: WheelProfile
        get() = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.first()
}
