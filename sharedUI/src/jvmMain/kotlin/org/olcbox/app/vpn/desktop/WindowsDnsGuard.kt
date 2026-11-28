package org.olcbox.app.vpn.desktop

/** RED stub: the guard is written in the next commit. */
internal class WindowsDnsGuard(private val journal: WindowsTunJournal, private val addLog: (String) -> Unit = {}) {
    suspend fun apply(tunAdapter: String, resolver: String): Unit = throw NotImplementedError("WindowsDnsGuard")
    suspend fun remove(): Unit = throw NotImplementedError("WindowsDnsGuard")

    companion object {
        const val RULE_PREFIX = "Ghostlane-DNS"
        const val NRPT_NAME = "Ghostlane-DNS"
    }
}
