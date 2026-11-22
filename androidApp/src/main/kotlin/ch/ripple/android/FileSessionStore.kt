package ch.ripple.android

import ch.ripple.app.phone.SessionStore
import java.io.File

/** The phone's session file in app-private storage (excluded from backup in the manifest). */
class FileSessionStore(private val dir: File) : SessionStore {
    private val file = File(dir, "routes-session.json")
    override fun load(): String? = file.takeIf { it.exists() }?.readText()
    override fun save(text: String) {
        val tmp = File(dir, "routes-session.json.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.delete(); check(tmp.renameTo(file)) { "could not replace the session file" } }
    }
    override fun setAside() { if (file.exists()) file.renameTo(File(dir, "routes-session.unreadable-${System.currentTimeMillis()}.json")) }
    override fun clear() { file.delete() }
}
