// New and close, as the desktop does them (herdr.core's Layout.kt writes the
// desktop's layout document), on the host the space or tab is on. No UI here:
// the screens ask before closing (with [closePrompt]) and show what failed.

package dev.herdr.mobile.session

import herdr.core.Choice
import herdr.core.Client
import herdr.core.Created
import herdr.core.confirmClose

/** What closing asks before it happens. */
data class Prompt(val title: String, val detail: String, val confirm: String)

class Actions(private val live: Live) {
    private fun client(host: String): Client = live.clientNow(host) ?: throw IllegalStateException("Not connected to ${host.substringAfter('@').substringBefore(':')}")

    private fun liveRecords(host: String) = live.core(host)?.snapshot?.value?.terminals.orEmpty().map { it.record }

    // What the core made, in app-wide ids.
    private fun Created.on(host: String) = Created(ref(host, address), ref(host, space), ref(host, tab))

    /**
     * What closing ends: the space as the desktop words it, or the tab and the
     * programs in it (a tab close kills them, so it asks too).
     */
    fun closePrompt(space: String, tab: String?): Prompt {
        val host = hostOf(space)
        val model = live.core(host)?.snapshot?.value?.spaces?.find { it.id == localOf(space) }
        val whole = model?.let { confirmClose(it, tab?.let(::localOf)) }
        if (whole != null || model == null || tab == null) {
            return Prompt(whole?.title ?: "Close space?", whole?.detail ?: "", "Close space")
        }
        val i = model.tabs.indexOfFirst { it.id == localOf(tab) }
        val names = live.session.value.terminals.associate { it.address to it.name }
        val shut = model.tabs.getOrNull(i)
        val programs = shut?.terminals.orEmpty().mapNotNull { names[ref(host, it)] }
        val name = shut?.name?.ifEmpty { null } ?: "Tab ${i + 1}"
        val ends = if (programs.isEmpty()) "" else " — ends ${programs.joinToString(", ")}"
        return Prompt("Close tab?", "$name in ${model.name}$ends", "Close tab")
    }

    suspend fun choices(host: String, machine: String?): List<Choice> = herdr.core.templates(client(host), machine)

    suspend fun newTab(machine: String?, space: String, after: String?, c: Choice): Created {
        val host = hostOf(space)
        return herdr.core.newTab(client(host), machine, localOf(space), name = c.tab, after = after?.let(::localOf), run = c.run).on(host)
    }

    suspend fun newSpace(host: String, machine: String?, c: Choice): Created =
        herdr.core.newSpace(client(host), machine, tab = c.tab, run = c.run).on(host)

    suspend fun closeTab(machine: String?, tab: String) {
        val host = hostOf(tab)
        herdr.core.closeTab(client(host), machine, localOf(tab), liveRecords(host))
    }

    suspend fun closeSpace(machine: String?, space: String) {
        val host = hostOf(space)
        herdr.core.closeSpace(client(host), machine, localOf(space), liveRecords(host))
    }
}
