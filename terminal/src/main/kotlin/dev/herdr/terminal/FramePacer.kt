package dev.herdr.terminal

/**
 * When the view builds a frame (a snapshot, the rows that changed): on the
 * next vsync once something asks ([post] schedules it, [fired] runs it), and
 * never while it's off screen. A kept terminal not on screen still parses its
 * output, but builds no frames until it's shown again ([attach] says whether
 * it missed any), as herdr-app draws only the terminals shown.
 */
internal class FramePacer(private val post: () -> Unit) {
  private var posted = false
  private var attached = false
  private var missed = false

  /** Something changed: a frame on the next vsync, or once on screen again. */
  fun request() {
    if (!attached) {
      missed = true
      return
    }
    if (posted) return
    posted = true
    post()
  }

  /** The posted frame's turn: whether to build it. */
  fun fired(): Boolean {
    posted = false
    return attached
  }

  /** On screen: true when frames were asked for meanwhile (build one now, every row). */
  fun attach(): Boolean {
    attached = true
    return missed.also { missed = false }
  }

  fun detach() {
    attached = false
  }

  /** Whether a frame is posted (to cancel it). */
  val pending get() = posted
}
