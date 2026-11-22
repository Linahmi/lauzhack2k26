package ch.ripple.app

import java.util.prefs.Preferences

/** The laptop's appearance choice, in the user's Java preferences (the Windows registry, per user). */
internal class PrefsAppearanceStore(
    private val prefs: Preferences = Preferences.userRoot().node(System.getenv("ROUTES_PREFS_NODE") ?: "ch/ripple/routes"),
) : AppearanceStore {
    override fun load(): Appearance = appearanceOf(prefs.get("appearance", null))
    override fun save(appearance: Appearance) { prefs.put("appearance", appearance.name); prefs.flush() }
}
