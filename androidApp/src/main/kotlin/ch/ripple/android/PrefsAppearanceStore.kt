package ch.ripple.android

import android.content.SharedPreferences
import ch.ripple.app.Appearance
import ch.ripple.app.AppearanceStore
import ch.ripple.app.appearanceOf

/** The phone's appearance choice, in the app's private preferences. */
class PrefsAppearanceStore(private val prefs: SharedPreferences) : AppearanceStore {
    override fun load(): Appearance = appearanceOf(prefs.getString("appearance", null))
    override fun save(appearance: Appearance) { prefs.edit().putString("appearance", appearance.name).apply() }
}
