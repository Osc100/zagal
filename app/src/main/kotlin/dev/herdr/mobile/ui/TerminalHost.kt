// The one live terminal, kept for as long as the activity: its ghostty view
// and its attach stream outlive the Terminal screen, so switching tabs and
// back shows the same grid with nothing to replay. The core's attach stream
// feeds it; bytes are credited back only once drawn, so a flood can't swamp
// the phone; a dropped connection reattaches (`resumed`) as soon as the
// client is back.

package dev.herdr.mobile.ui

import android.content.Context
import android.graphics.Typeface
import android.view.ViewGroup
import androidx.core.content.res.ResourcesCompat
import dev.herdr.mobile.R
import dev.herdr.mobile.session.Prefs
import dev.herdr.mobile.session.drawLigatures
import dev.herdr.mobile.session.hostOf
import dev.herdr.mobile.session.localOf
import dev.herdr.mobile.session.reason
import dev.herdr.terminal.KeyColors
import dev.herdr.terminal.TerminalTheme
import dev.herdr.terminal.TerminalView
import herdr.core.AttachOptions
import herdr.core.Attached
import herdr.core.Client
import herdr.core.End
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

sealed interface PaneState {
    data object Waiting : PaneState
    data object Attaching : PaneState
    data object Live : PaneState
    data class Ended(val why: String) : PaneState
    data class Failed(val why: String) : PaneState
}

private fun TerminalView.detach() {
    (parent as? ViewGroup)?.removeView(this)
}

/** Everything here runs on the main thread (the scope is the activity's composition). */
class TerminalHost(
    private val context: Context,
    private val scope: CoroutineScope,
    /** A host's client while it's connected. */
    private val client: (host: String) -> Flow<Client?>,
) {
    private val _state = MutableStateFlow<PaneState>(PaneState.Waiting)
    val state: StateFlow<PaneState> = _state

    private var address: String? = null
    private var view: TerminalView? = null
    private var job: Job? = null
    private var stream: Attached? = null

    /**
     * The view for `address` (host-qualified): the one already showing it, or
     * a fresh one (ghostty's grid belongs to one stream).
     */
    fun show(address: String): TerminalView {
        view?.takeIf { this.address == address }?.let {
            it.detach()
            return it
        }
        release()
        this.address = address
        val size = MutableStateFlow<Pair<Int, Int>?>(null)
        val made = TerminalView(context).apply {
            onInput = { bytes -> stream?.write(bytes) }
            onResize = { cols, rows ->
                size.value = cols to rows
                stream?.resize(cols, rows)
            }
        }
        view = made
        job = scope.launch {
            var seen = false
            // Attach once there's a client and a size; a later resize (the keyboard,
            // rotation) goes to the live stream, never a fresh attach.
            combine(client(hostOf(address)), size) { c, s -> c to (s != null) }.distinctUntilChanged().collectLatest { (c, sized) ->
                val s = size.value
                if (c == null || !sized || s == null) {
                    _state.value = PaneState.Waiting
                    return@collectLatest
                }
                _state.value = PaneState.Attaching
                val a = try {
                    c.attach(localOf(address), AttachOptions(s.first, s.second, history = 2000, resumed = seen))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.value = PaneState.Failed(e.reason)
                    return@collectLatest
                }
                seen = true
                stream = a
                a.onData { bytes ->
                    // Stream listeners run on the client's thread; the view is the main thread's.
                    made.post {
                        made.write(bytes)
                        // Parsed: credit the stream for them.
                        a.consumed(bytes.size)
                    }
                }
                a.onEnd { end ->
                    made.post {
                        if (stream === a) stream = null
                        when (end) {
                            is End.Lost -> _state.value = PaneState.Waiting
                            is End.Closed -> {
                                made.finish()
                                _state.value = PaneState.Ended(end.summary)
                            }
                        }
                    }
                }
                a.focus(true)
                _state.value = PaneState.Live
                try {
                    awaitCancellation()
                } finally {
                    a.close()
                    if (stream === a) stream = null
                }
            }
        }
        return made
    }

    /** Draws with the user's font and the Herdr palette in use. */
    fun style(prefs: Prefs, colors: HerdrColors) {
        val v = view ?: return
        v.setTypeface(typeface(prefs.font))
        v.setFontSize(prefs.fontSize.toFloat())
        v.setLigatures(prefs.drawLigatures)
        v.setTheme(
            TerminalTheme(
                background = colors.background.hex(),
                foreground = colors.terminalForeground.hex(),
                cursorColor = colors.cursor.hex(),
                selectionBackground = colors.selection.hex(),
                palette = colors.ansi.map { it.hex() },
            ),
        )
        v.setKeyColors(
            KeyColors(
                background = colors.chrome.hex(),
                key = colors.raised.hex(),
                foreground = colors.foreground.hex(),
                activeKey = colors.solid.hex(),
                activeForeground = colors.solidForeground.hex(),
            ),
        )
    }

    private val typefaces = mutableMapOf<String?, Typeface?>()

    private fun typeface(font: String?): Typeface? = typefaces.getOrPut(font) {
        when (font) {
            "JetBrains Mono" -> ResourcesCompat.getFont(context, R.font.jetbrains_mono)
            "Geist Mono" -> ResourcesCompat.getFont(context, R.font.geist_mono)
            else -> null
        }
    }

    /** Detaches and frees the terminal (none to show, or the activity is gone). */
    fun release() {
        job?.cancel()
        job = null
        stream = null
        view?.let {
            it.detach()
            it.destroy()
        }
        view = null
        address = null
        _state.value = PaneState.Waiting
    }
}
