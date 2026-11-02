package dev.herdr.terminal

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RenderNode
import android.graphics.Typeface
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.ActionMode
import android.view.Choreographer
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.Magnifier
import android.widget.OverScroller
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Canvas-backed ghostty terminal grid.
 *
 * Owns a libghostty-vt session via [GhosttyVt]. PTY output is fed in with
 * [write]; user input (IME text, key escape sequences, VT query responses)
 * comes back through [onInputBytes]. Rendering pulls flattened dirty-row
 * snapshots from JNI once per Choreographer frame and records each dirty row
 * into its own RenderNode; [onDraw] replays the rows (drawn by the GPU, clean
 * ones without re-recording) and overlays the cursor.
 */
internal class GhosttyTerminalView(context: Context) : View(context) {
  var onInputBytes: ((ByteArray) -> Unit)? = null
  var onGridResize: ((cols: Int, rows: Int) -> Unit)? = null

  /** A link Ctrl+tapped (OSC 8 or a written http(s) URL), for the host to open. */
  var onLink: ((String) -> Unit)? = null

  /** Sticky-modifier source for accessory-bar keys and IME text (optional). */
  var accessoryBar: TerminalAccessoryBar? = null

  private var handle: Long = GhosttyVt.create(INITIAL_COLS, INITIAL_ROWS, MAX_SCROLLBACK)
  private var finished = false

  // ── Cell metrics ──
  private var fontSizeDp = FONT_SIZE_DP
  private var typeface: Typeface? = null
  private var ligatures = false
  private val runText = StringBuilder()
  private val textPaint = newTextPaint(Typeface.MONOSPACE)
  private val boldPaint = newTextPaint(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD))
  private val italicPaint = newTextPaint(Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC))
  private val boldItalicPaint =
    newTextPaint(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD_ITALIC))
  private val stylePaints = listOf(textPaint, boldPaint, italicPaint, boldItalicPaint)

  // Nerd Font private-use glyphs (powerline, devicons, …) are absent from the
  // system fallback chain; route those cells to the bundled symbols-only font.
  private val symbolsPaint: Paint? = assetPaint(SYMBOLS_FONT_ASSET)

  // Symbols neither the terminal font nor Android's fallback chain draw (⏵,
  // many arrows and shapes): a subset of Noto Sans Symbols 2. Whether the
  // cell's own paint can, by code point, until the typeface changes.
  private val fallbackPaint: Paint? = assetPaint(FALLBACK_FONT_ASSET)
  private val drawable = HashMap<Int, Boolean>()
  private val fillPaint = Paint()
  private val cursorPaint = Paint()
  private var cellWidth = 0f
  private var cellHeight = 0
  private var baseline = 0f
  private val underlineThickness = max(1f, resources.displayMetrics.density)
  // Double, curly, dotted and dashed underlines, and colored ones.
  private val decorationPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val curlyPath = Path()

  // ── Grid state ──
  private var cols = INITIAL_COLS
  private var rows = INITIAL_ROWS
  // One display list per grid row, re-recorded only when the row changes; null
  // until the row is first drawn. Each spans the whole grid and clips to it, so a
  // glyph spilling past its row or the grid's edge draws as it did onto the
  // grid-sized bitmap these replaced.
  private var rowNodes = arrayOfNulls<RenderNode>(0)
  private var gridWidthPx = 0
  private var gridHeightPx = 0
  private var snapshotBuf: ByteBuffer? = null
  private var rowChars = CharArray(0)
  private var cellFg = IntArray(0)
  private var cellBg = IntArray(0)
  private var cellTextOff = IntArray(0)
  private var cellTextLen = IntArray(0)
  private var cellFlags = IntArray(0)
  private var cellUnderline = IntArray(0)
  private var cellUnderlineColor = IntArray(0)
  private var rowSelStart = IntArray(0)
  private var rowSelEnd = IntArray(0)
  private var rowSelEndWide = BooleanArray(0)

  // ── Selection state ──
  private var selectionMode = false
  private var actionMode: ActionMode? = null
  private var draggingHandle = HANDLE_NONE
  private var dragAnchorCol = 0
  private var dragAnchorRow = 0
  private var dragBiasRows = 0
  private var lastPressCol = 0
  private var lastPressRow = 0
  // The link under the long press, offered as "Open link" until the selection ends.
  private var pressedLink: String? = null
  private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val handleRadius = HANDLE_RADIUS_DP * resources.displayMetrics.density
  private val magnifier = Magnifier.Builder(this).build()

  private var defaultBg = 0xFF000000.toInt()
  private var defaultFg = 0xFFFFFFFF.toInt()
  // Themed selection colors; when unset, selected cells keep the classic
  // fg/bg swap. Cursor color rides the snapshot header (0 = unconfigured).
  private var selectionBg: Int? = null
  private var selectionFg: Int? = null
  private var cursorColor = 0
  private var cursorX = -1
  private var cursorY = -1
  private var cursorStyle = CURSOR_STYLE_BLOCK
  private var cursorVisible = false
  private var cursorBlinks = false

  // The cursor is an onDraw overlay, so blinking only replays the recorded
  // rows; the grid is untouched.
  private var blinkPhaseOn = true
  private var blinkScheduled = false
  private val blinkRunnable = object : Runnable {
    override fun run() {
      if (!blinkScheduled) return
      blinkPhaseOn = !blinkPhaseOn
      invalidate()
      postDelayed(this, BLINK_INTERVAL_MS)
    }
  }

  private var framePending = false
  private val frameCallback = Choreographer.FrameCallback {
    framePending = false
    renderFrame()
  }

  private var scrollAccum = 0f
  private var lastTouchX = 0f
  private var lastTouchY = 0f

  // ── Scrollback state ──
  private val scroller = OverScroller(context)
  private var flingLastY = 0
  private var scrollbarTotal = 0L
  private var scrollbarOffset = 0L
  private var scrollbarLen = 0L
  private var lastScrollActivityAt = 0L
  private val scrollbarPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val jumpChipPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val jumpChipArrow = Path()
  private val flingRunnable = object : Runnable {
    override fun run() {
      if (handle == 0L || !scroller.computeScrollOffset()) return
      val y = scroller.currY
      scrollBy((y - flingLastY).toFloat())
      flingLastY = y
      postOnAnimation(this)
    }
  }

  private val gestureDetector = GestureDetector(
    context,
    object : GestureDetector.SimpleOnGestureListener() {
      override fun onDown(e: MotionEvent) = true

      override fun onSingleTapUp(e: MotionEvent): Boolean {
        // Ctrl+tap (a keyboard's Ctrl, or the key bar's) opens the link under it;
        // a long press offers it as "Open link".
        val sticky = accessoryBar?.stickyMetaState() ?: 0
        if ((e.metaState or sticky) and KeyEvent.META_CTRL_ON != 0 && openLinkAt(e.x, e.y)) {
          if (sticky and KeyEvent.META_CTRL_ON != 0) accessoryBar?.consumeSticky()
          return true
        }
        if (scrolledBack) {
          val (cx, cy) = jumpChipCenter()
          val slop = JUMP_CHIP_RADIUS_DP * 1.5f * resources.displayMetrics.density
          if (hypot(e.x - cx, e.y - cy) <= slop) {
            jumpToBottom()
            return true
          }
        }
        if (selectionMode || actionMode != null) {
          clearSelection()
          return true
        }
        requestFocus()
        context.getSystemService(InputMethodManager::class.java)
          ?.showSoftInput(this@GhosttyTerminalView, 0)
        return true
      }

      override fun onLongPress(e: MotionEvent) {
        if (handle == 0L || finished || scaleDetector.isInProgress) return
        lastPressCol = (e.x / cellWidth).toInt().coerceIn(0, cols - 1)
        lastPressRow = (e.y / cellHeight).toInt().coerceIn(0, rows - 1)
        pressedLink = linkAt(lastPressCol, lastPressRow)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        selectionMode = GhosttyVt.selectWord(handle, lastPressCol, lastPressRow)
        scheduleFrame()
        if (selectionMode) {
          // Keep dragging without lifting: extend from the pressed cell.
          draggingHandle = HANDLE_END
          dragFrom(lastPressCol, lastPressRow, biasRows = 0)
        } else {
          // Even without a word under the finger, offer Paste / Select all.
          startTerminalActionMode()
        }
      }

      override fun onScroll(
        e1: MotionEvent?,
        e2: MotionEvent,
        distanceX: Float,
        distanceY: Float
      ): Boolean {
        if (handle == 0L || scaleDetector.isInProgress) return false
        lastTouchX = e2.x
        lastTouchY = e2.y
        scrollBy(distanceY)
        return true
      }

      override fun onFling(
        e1: MotionEvent?,
        e2: MotionEvent,
        velocityX: Float,
        velocityY: Float
      ): Boolean {
        if (handle == 0L || scaleDetector.isInProgress) return false
        // Finger up (negative velocity) scrolls content down: invert.
        flingLastY = 0
        scroller.fling(0, 0, 0, -velocityY.toInt(), 0, 0, Int.MIN_VALUE, Int.MAX_VALUE)
        postOnAnimation(flingRunnable)
        return true
      }
    }
  )

  // Mirrors the iOS pinch-zoom: every 0.1 of accumulated scale steps the
  // font size by ±1dp through the same path as the fontSize prop.
  private var pinchScale = 1f
  private var pinchLastScale = 1f
  private val scaleDetector = ScaleGestureDetector(
    context,
    object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
      override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
        pinchScale = 1f
        pinchLastScale = 1f
        return true
      }

      override fun onScale(detector: ScaleGestureDetector): Boolean {
        pinchScale *= detector.scaleFactor
        val steps = ((pinchScale - pinchLastScale) / PINCH_SCALE_STEP).toInt()
        if (steps != 0) {
          pinchLastScale += steps * PINCH_SCALE_STEP
          setFontSize(fontSizeDp + steps)
        }
        return true
      }
    }
  )

  init {
    isFocusable = true
    isFocusableInTouchMode = true
    measureCell()
  }

  private fun assetPaint(name: String): Paint? = try {
    newTextPaint(Typeface.createFromAsset(context.assets, name))
  } catch (e: RuntimeException) {
    null
  }

  private fun newTextPaint(typeface: Typeface) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.typeface = typeface
    textSize = fontSizeDp * resources.displayMetrics.density
  }

  /**
   * Base font size in dp (default 14, clamped to 4–64 like iOS); pinch-to-zoom
   * steps from the current value. Content reflows in place — libghostty-vt
   * owns the grid state, so no output is lost.
   */
  fun setFontSize(dp: Float) {
    val clamped = dp.coerceIn(MIN_FONT_SIZE_DP, MAX_FONT_SIZE_DP)
    if (clamped == fontSizeDp) return
    fontSizeDp = clamped
    val px = clamped * resources.displayMetrics.density
    for (paint in stylePaints) paint.textSize = px
    symbolsPaint?.textSize = px
    fallbackPaint?.textSize = px
    remeasure()
  }

  private fun measureCell() {
    val metrics = textPaint.fontMetrics
    cellWidth = textPaint.measureText("M")
    cellHeight = ceil(metrics.descent - metrics.ascent).toInt()
    baseline = -metrics.ascent
  }

  private fun remeasure() {
    measureCell()
    // Cell geometry moved under any active selection handles.
    if (selectionMode || actionMode != null) clearSelection()
    updateGridGeometry(force = true)
  }

  /**
   * The terminal font; null is the system monospace. Bold uses the font's
   * `wght` axis when it is a variable font, else Android's synthetic styles.
   */
  fun setTypeface(face: Typeface?) {
    if (face == typeface) return
    typeface = face
    drawable.clear()
    val base = face ?: Typeface.MONOSPACE
    styled(textPaint, base, bold = false, italic = false)
    styled(boldPaint, base, bold = true, italic = false)
    styled(italicPaint, base, bold = false, italic = true)
    styled(boldItalicPaint, base, bold = true, italic = true)
    remeasure()
  }

  private fun styled(paint: Paint, base: Typeface, bold: Boolean, italic: Boolean) {
    paint.typeface = base
    paint.textSkewX = 0f
    // Variable fonts (JetBrains Mono, Geist Mono) carry their weight as an axis.
    if (paint.setFontVariationSettings(if (bold) "'wght' 700" else "'wght' 400")) {
      if (italic) paint.textSkewX = -0.2f
      return
    }
    paint.setFontVariationSettings(null)
    val style = when {
      bold && italic -> Typeface.BOLD_ITALIC
      bold -> Typeface.BOLD
      italic -> Typeface.ITALIC
      else -> Typeface.NORMAL
    }
    paint.typeface = Typeface.create(base, style)
  }

  /**
   * Programming ligatures (`->`, `!=`, `===`): runs of plain ASCII cells in one
   * style are drawn as one string so the font can join them; monospace
   * advances keep every glyph on its own cell. Off disables liga/calt.
   */
  fun setLigatures(enabled: Boolean) {
    if (enabled == ligatures) return
    ligatures = enabled
    val features = if (enabled) "'liga' 1, 'calt' 1" else "'liga' 0, 'calt' 0"
    for (paint in stylePaints) paint.fontFeatureSettings = features
    updateGridGeometry(force = true)
  }

  private fun joinable(col: Int): Boolean {
    val flags = cellFlags[col]
    if (cellTextLen[col] != 1 || flags and (FLAG_SPACER or FLAG_INVISIBLE or FLAG_WIDE) != 0) {
      return false
    }
    val c = rowChars[cellTextOff[col]].code
    return c in 0x21..0x7E
  }

  /**
   * Apply theme colors. Foreground/background/cursor/palette go into the
   * terminal itself (so OSC 4/10/11/12 queries and resets stay truthful);
   * selection colors are a renderer concern and stay here. Null fields (or
   * a null theme) clear back to the defaults.
   */
  fun setTheme(theme: TerminalTheme?) {
    if (handle == 0L) return
    selectionBg = GhosttyVt.colorOrNull(theme?.selectionBackground)
    selectionFg = GhosttyVt.colorOrNull(theme?.selectionForeground)
    GhosttyVt.setTheme(
      handle, theme?.foreground, theme?.background, theme?.cursorColor, theme?.palette?.toTypedArray()
    )
    scheduleFrame()
  }

  // ── Session I/O ──

  /** Hands the link under ([x], [y]) to [onLink]; false when there's none. */
  private fun openLinkAt(x: Float, y: Float): Boolean {
    if (cellWidth <= 0f || cellHeight <= 0) return false
    val col = (x / cellWidth).toInt()
    val row = (y / cellHeight).toInt()
    if (col !in 0 until cols || row !in 0 until rows) return false
    val link = linkAt(col, row) ?: return false
    onLink?.invoke(link)
    return true
  }

  /** The link at a viewport cell, when the host takes links. */
  private fun linkAt(col: Int, row: Int): String? {
    if (onLink == null || handle == 0L) return null
    return GhosttyVt.linkAt(handle, col, row)?.let { String(it, Charsets.UTF_8) }
  }

  /** Feed PTY output into the grid. */
  fun write(data: ByteArray) {
    if (handle == 0L) return
    val response = GhosttyVt.write(handle, data)
    if (response != null && response.isNotEmpty() && !finished) {
      onInputBytes?.invoke(response)
    }
    GhosttyVt.takeClipboard(handle)?.let(::copyFromProgram)
    holdBlinkSolid()
    scheduleFrame()
  }

  /** Mark the underlying PTY as exited: stop forwarding input. */
  fun finish() {
    finished = true
  }

  fun destroy() {
    if (framePending) {
      Choreographer.getInstance().removeFrameCallback(frameCallback)
      framePending = false
    }
    blinkScheduled = false
    removeCallbacks(blinkRunnable)
    scroller.abortAnimation()
    removeCallbacks(flingRunnable)
    actionMode?.finish()
    for (node in rowNodes) node?.discardDisplayList()
    if (handle != 0L) {
      GhosttyVt.free(handle)
      handle = 0
    }
  }

  private fun sendBytes(bytes: ByteArray) {
    if (!finished && bytes.isNotEmpty()) {
      if (selectionMode) clearSelection()
      // Typing while scrolled into history jumps back to the live view.
      if (handle != 0L && scrolledBack) jumpToBottom()
      holdBlinkSolid()
      onInputBytes?.invoke(bytes)
    }
  }

  /** Whether the viewport is above the live screen, in scrollback. */
  private val scrolledBack: Boolean
    get() = scrollbarTotal > 0 && scrollbarOffset + scrollbarLen < scrollbarTotal

  private fun jumpToBottom() {
    GhosttyVt.scrollToBottom(handle)
    scheduleFrame()
  }

  /** Accumulates a drag of [distance] px (positive: up) and scrolls by whole rows. */
  private fun scrollBy(distance: Float) {
    scrollAccum += distance
    val deltaRows = (scrollAccum / cellHeight).toInt()
    if (deltaRows != 0) {
      scrollAccum -= deltaRows * cellHeight
      scrollRows(deltaRows)
    }
  }

  /**
   * A swipe of [deltaRows] (negative: up, toward older lines) goes where the
   * program in front expects it: mouse-wheel events when it tracks the mouse
   * (opencode, htop, vim with mouse=a), arrow keys on the alternate screen
   * (less, man), else this view's scrollback.
   */
  private fun scrollRows(deltaRows: Int) {
    val notches = abs(deltaRows)
    when (GhosttyVt.scrollTarget(handle)) {
      1 -> GhosttyVt.encodeWheel(
        handle, deltaRows < 0, notches, lastTouchX, lastTouchY,
        width, height, cellWidth.roundToInt(), cellHeight
      )?.let(::sendBytes)
      2 -> {
        val key = if (deltaRows < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
        repeat(notches) {
          GhosttyVt.encodeKey(handle, key, 1, 0, 0, null)?.let(::sendBytes)
        }
      }
      else -> {
        GhosttyVt.scroll(handle, deltaRows)
        markScrollActivity()
        scheduleFrame()
      }
    }
  }

  /** The regular text face, for labels that should match the grid. */
  fun labelTypeface(): Typeface = textPaint.typeface ?: Typeface.MONOSPACE

  private fun markScrollActivity() {
    lastScrollActivityAt = SystemClock.uptimeMillis()
  }

  /**
   * Typed text: IME commits and accessory-bar symbols. A lit sticky modifier
   * turns a single char into a key chord (Ctrl+C etc.) through ghostty's encoder.
   */
  fun sendText(text: CharSequence) {
    if (text.isEmpty()) return
    val meta = accessoryBar?.stickyMetaState() ?: 0
    if (meta != 0 && text.length == 1) {
      val keyCode = charToKeyCode(text[0])
      if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
        accessoryBar?.consumeSticky()
        GhosttyVt.encodeKey(handle, keyCode, 1, meta, text[0].lowercaseChar().code, null)
          ?.let(::sendBytes)
        return
      }
    }
    sendBytes(text.toString().toByteArray(Charsets.UTF_8))
  }

  /** Send an accessory-bar key press with the current sticky modifiers. */
  fun sendAccessoryKey(keyCode: Int) {
    if (handle == 0L || finished) return
    val meta = accessoryBar?.takeStickyMetaState() ?: 0
    GhosttyVt.encodeKey(handle, keyCode, 1, meta, 0, null)?.let(::sendBytes)
  }

  private fun charToKeyCode(char: Char): Int {
    val lower = char.lowercaseChar()
    return when (lower) {
      in 'a'..'z' -> KeyEvent.KEYCODE_A + (lower - 'a')
      in '0'..'9' -> KeyEvent.KEYCODE_0 + (lower - '0')
      ' ' -> KeyEvent.KEYCODE_SPACE
      '[' -> KeyEvent.KEYCODE_LEFT_BRACKET
      ']' -> KeyEvent.KEYCODE_RIGHT_BRACKET
      '-' -> KeyEvent.KEYCODE_MINUS
      '=' -> KeyEvent.KEYCODE_EQUALS
      '\\' -> KeyEvent.KEYCODE_BACKSLASH
      '/' -> KeyEvent.KEYCODE_SLASH
      '.' -> KeyEvent.KEYCODE_PERIOD
      ',' -> KeyEvent.KEYCODE_COMMA
      ';' -> KeyEvent.KEYCODE_SEMICOLON
      '\'' -> KeyEvent.KEYCODE_APOSTROPHE
      '`' -> KeyEvent.KEYCODE_GRAVE
      else -> KeyEvent.KEYCODE_UNKNOWN
    }
  }

  // ── Layout / resize ──

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    updateGridGeometry(force = false)
  }

  private fun updateGridGeometry(force: Boolean) {
    if (width <= 0 || height <= 0 || handle == 0L) return
    val newCols = max(2, floor(width / cellWidth).toInt())
    val newRows = max(2, height / cellHeight)
    val changed = newCols != cols || newRows != rows
    cols = newCols
    rows = newRows
    if (changed || force || rowNodes.size != rows) {
      GhosttyVt.resize(handle, cols, rows, cellWidth.roundToInt(), cellHeight)
      allocateGridBuffers()
      onGridResize?.invoke(cols, rows)
    }
    scheduleFrame()
  }

  private fun allocateGridBuffers() {
    gridWidthPx = max(1, ceil(cols * cellWidth).toInt())
    gridHeightPx = max(1, rows * cellHeight)
    for (node in rowNodes) node?.discardDisplayList()
    rowNodes = arrayOfNulls(rows)
    val capacity = HEADER_BYTES +
      rows * (ROW_HEADER_BYTES + cols * CELL_RECORD_BYTES + cols * MAX_CELL_TEXT_UNITS * 2 + 4)
    snapshotBuf = ByteBuffer.allocateDirect(capacity).order(ByteOrder.LITTLE_ENDIAN)
    rowChars = CharArray(cols * MAX_CELL_TEXT_UNITS)
    cellFg = IntArray(cols)
    cellBg = IntArray(cols)
    cellTextOff = IntArray(cols)
    cellTextLen = IntArray(cols)
    cellFlags = IntArray(cols)
    cellUnderline = IntArray(cols)
    cellUnderlineColor = IntArray(cols)
    rowSelStart = IntArray(rows) { -1 }
    rowSelEnd = IntArray(rows) { -1 }
    rowSelEndWide = BooleanArray(rows)
  }

  // ── Rendering ──

  private fun scheduleFrame() {
    if (framePending) return
    framePending = true
    Choreographer.getInstance().postFrameCallback(frameCallback)
  }

  private fun renderFrame() {
    val buf = snapshotBuf ?: return
    if (handle == 0L) return

    val rowCount = GhosttyVt.snapshot(handle, buf)
    if (rowCount < 0) return
    GhosttyVt.scrollbar(handle)?.let { scrollbar ->
      scrollbarTotal = scrollbar[0]
      scrollbarOffset = scrollbar[1]
      scrollbarLen = scrollbar[2]
    }

    buf.position(0)
    buf.int // version
    val dirtyKind = buf.int
    val snapCols = buf.int
    buf.int // rows
    defaultBg = buf.int
    defaultFg = buf.int
    cursorX = buf.int
    cursorY = buf.int
    cursorStyle = buf.int
    cursorVisible = buf.int != 0
    buf.int // row count (== rowCount)
    cursorBlinks = buf.int != 0
    cursorColor = buf.int
    // Synchronized output: the rows are the frame the program left on screen
    // when its update began. Look again shortly; the hold ends with the
    // update, or on its own after a second.
    if (buf.int != 0) postDelayed(::scheduleFrame, HOLD_POLL_MS)
    syncBlinkTimer()

    // Everything changed: rows the snapshot leaves out show the background.
    if (dirtyKind == DIRTY_FULL) for (node in rowNodes) node?.discardDisplayList()
    repeat(rowCount) { recordRow(buf, snapCols) }
    syncSelectionUi()
    invalidate()
  }

  // ── Selection ──

  /** Top-left / bottom-right cells of the rendered selection. */
  private data class SelectionBounds(val top: Int, val topCol: Int, val bottom: Int, val bottomCol: Int)

  private fun selectionBounds(): SelectionBounds? {
    var top = -1
    var bottom = -1
    for (row in rowSelStart.indices) {
      if (rowSelStart[row] >= 0) {
        if (top < 0) top = row
        bottom = row
      }
    }
    if (top < 0) return null
    return SelectionBounds(top, rowSelStart[top], bottom, rowSelEnd[bottom])
  }

  /** Where the start handle sits horizontally: the left edge of the first cell. */
  private val SelectionBounds.startX get() = topCol * cellWidth

  /** Where the end handle sits horizontally: the right edge of the last cell, wide or not. */
  private val SelectionBounds.endX: Float
    get() {
      val wide = bottom < rowSelEndWide.size && rowSelEndWide[bottom]
      return (bottomCol + 1 + if (wide) 1 else 0) * cellWidth
    }

  private fun syncSelectionUi() {
    if (!selectionMode) return
    if (selectionBounds() == null) {
      // Scrolled out of the viewport (or collapsed): drop the whole selection
      // so the UI and terminal state never disagree.
      clearSelection()
    } else {
      actionMode?.invalidateContentRect()
    }
  }

  private fun clearSelection() {
    if (handle != 0L) GhosttyVt.clearSelection(handle)
    selectionMode = false
    pressedLink = null
    draggingHandle = HANDLE_NONE
    actionMode?.finish()
    scheduleFrame()
  }

  private fun selectAllContent() {
    if (handle == 0L) return
    if (GhosttyVt.selectAll(handle)) {
      selectionMode = true
      scheduleFrame()
      actionMode?.invalidate()
    }
  }

  /** A cell's size in pixels, width rounded as the terminal is told it. */
  val cellPixels: Pair<Int, Int> get() = cellWidth.roundToInt() to cellHeight

  /** Whether programs may copy to the clipboard (OSC 52); on by default. */
  fun setClipboardWrite(allowed: Boolean) {
    if (handle != 0L) GhosttyVt.setClipboardWrite(handle, allowed)
  }

  /** Text the program copied (OSC 52), onto the phone's clipboard. */
  private fun copyFromProgram(bytes: ByteArray) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("terminal", String(bytes, Charsets.UTF_8)))
  }

  private fun copySelection() {
    if (handle == 0L) return
    val bytes = GhosttyVt.selectionText(handle) ?: return
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(
      ClipData.newPlainText("terminal", String(bytes, Charsets.UTF_8))
    )
    clearSelection()
  }

  /** Paste the clipboard, confirming first when it could run commands. */
  fun requestPaste() {
    if (handle == 0L || finished) return
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    val clip = clipboard.primaryClip?.takeIf { it.itemCount > 0 } ?: return
    val text = clip.getItemAt(0).coerceToText(context)?.toString()
    if (text.isNullOrEmpty()) return
    val bytes = text.toByteArray(Charsets.UTF_8)
    if (GhosttyVt.isPasteSafe(bytes)) {
      sendPaste(bytes)
      return
    }
    AlertDialog.Builder(context)
      .setTitle("Paste multi-line text?")
      .setMessage("The clipboard contains newlines or control sequences that the shell may run as commands.")
      .setPositiveButton("Paste") { _, _ -> sendPaste(bytes) }
      .setNegativeButton(android.R.string.cancel, null)
      .show()
  }

  private fun sendPaste(bytes: ByteArray) {
    val encoded = GhosttyVt.encodePaste(handle, bytes) ?: return
    if (selectionMode) clearSelection() else actionMode?.finish()
    sendBytes(encoded)
  }

  private fun startTerminalActionMode() {
    val current = actionMode
    if (current != null) {
      current.invalidate()
      current.invalidateContentRect()
      return
    }
    actionMode = startActionMode(
      object : ActionMode.Callback2() {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
          menu.add(Menu.NONE, MENU_OPEN_LINK, 0, "Open link")
          menu.add(Menu.NONE, MENU_COPY, 0, android.R.string.copy)
          menu.add(Menu.NONE, MENU_PASTE, 1, android.R.string.paste)
          menu.add(Menu.NONE, MENU_SELECT_ALL, 2, android.R.string.selectAll)
          return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
          menu.findItem(MENU_OPEN_LINK)?.isVisible = pressedLink != null
          menu.findItem(MENU_COPY)?.isVisible = selectionMode
          return true
        }

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
          when (item.itemId) {
            MENU_OPEN_LINK -> {
              val link = pressedLink
              clearSelection()
              if (link != null) onLink?.invoke(link)
            }
            MENU_COPY -> copySelection()
            MENU_PASTE -> requestPaste()
            MENU_SELECT_ALL -> selectAllContent()
            else -> return false
          }
          return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
          actionMode = null
        }

        override fun onGetContentRect(mode: ActionMode, view: View, outRect: Rect) {
          outRect.set(selectionContentRect())
        }
      },
      ActionMode.TYPE_FLOATING
    )
  }

  private fun selectionContentRect(): Rect {
    val bounds = selectionBounds()
    return if (bounds != null) {
      val singleRow = bounds.top == bounds.bottom
      Rect(
        if (singleRow) bounds.startX.toInt() else 0,
        bounds.top * cellHeight,
        if (singleRow) bounds.endX.toInt() else width,
        (bounds.bottom + 1) * cellHeight
      )
    } else {
      Rect(
        (lastPressCol * cellWidth).toInt(),
        lastPressRow * cellHeight,
        ((lastPressCol + 1) * cellWidth).toInt(),
        (lastPressRow + 1) * cellHeight
      )
    }
  }

  /** Returns which handle (start/end) is under the touch, arming the drag anchor. */
  private fun hitTestHandle(x: Float, y: Float): Int {
    val bounds = selectionBounds() ?: return HANDLE_NONE
    val slop = HANDLE_HIT_SLOP_DP * resources.displayMetrics.density
    val distStart = hypot(x - bounds.startX, y - handleCenterY(bounds.top))
    val distEnd = hypot(x - bounds.endX, y - handleCenterY(bounds.bottom))
    // Dragging one handle keeps the other end where it is.
    return when {
      distEnd <= slop && distEnd <= distStart -> {
        dragFrom(bounds.topCol, bounds.top)
        HANDLE_END
      }
      distStart <= slop -> {
        dragFrom(bounds.bottomCol, bounds.bottom)
        HANDLE_START
      }
      else -> HANDLE_NONE
    }
  }

  /**
   * Arms a selection drag anchored at a cell. [biasRows]: how far above the
   * finger the dragged cell is (handles hang below their row).
   */
  private fun dragFrom(col: Int, row: Int, biasRows: Int = 1) {
    dragAnchorCol = col
    dragAnchorRow = row
    dragBiasRows = biasRows
  }

  /** The handle knob's center below a selection row. */
  private fun handleCenterY(row: Int) = (row + 1) * cellHeight + handleRadius

  private fun dragSelection(x: Float, y: Float) {
    if (handle == 0L) return
    val col = (x / cellWidth).toInt().coerceIn(0, cols - 1)
    val row = ((y / cellHeight).toInt() - dragBiasRows).coerceIn(0, rows - 1)
    GhosttyVt.setSelection(handle, dragAnchorCol, dragAnchorRow, col, row)
    magnifier.show(x, y)
    scheduleFrame()
  }

  private fun drawSelectionHandles(canvas: Canvas) {
    if (!selectionMode) return
    val bounds = selectionBounds() ?: return
    handlePaint.color = HANDLE_COLOR
    drawHandle(canvas, bounds.startX, bounds.top)
    drawHandle(canvas, bounds.endX, bounds.bottom)
  }

  /** A stem from inside the cell down to a knob below [row], at [x]. */
  private fun drawHandle(canvas: Canvas, x: Float, row: Int) {
    val stem = max(2f, resources.displayMetrics.density * 2)
    val rowBottom = ((row + 1) * cellHeight).toFloat()
    val knobY = handleCenterY(row)
    canvas.drawRect(x - stem / 2, rowBottom - cellHeight / 3f, x + stem / 2, knobY, handlePaint)
    canvas.drawCircle(x, knobY, handleRadius, handlePaint)
  }

  // ── Cursor blink ──

  private fun wantsBlink(): Boolean {
    return cursorBlinks && cursorVisible && isAttachedToWindow && hasWindowFocus() &&
      Settings.Global.getFloat(
        context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
      ) != 0f
  }

  private fun syncBlinkTimer() {
    val wants = wantsBlink()
    if (wants && !blinkScheduled) {
      blinkScheduled = true
      postDelayed(blinkRunnable, BLINK_INTERVAL_MS)
    } else if (!wants && blinkScheduled) {
      blinkScheduled = false
      removeCallbacks(blinkRunnable)
      showCursor()
    }
  }

  private fun showCursor() {
    if (blinkPhaseOn) return
    blinkPhaseOn = true
    invalidate()
  }

  /** Show a solid cursor and restart the blink interval (any input/output). */
  private fun holdBlinkSolid() {
    showCursor()
    if (blinkScheduled) {
      removeCallbacks(blinkRunnable)
      postDelayed(blinkRunnable, BLINK_INTERVAL_MS)
    }
  }

  override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
    super.onWindowFocusChanged(hasWindowFocus)
    syncBlinkTimer()
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    syncBlinkTimer()
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()
    syncBlinkTimer()
  }

  /** Records one snapshot row record into its row's RenderNode. */
  private fun recordRow(buf: ByteBuffer, snapCols: Int) {
    val rowIndex = buf.peekInt()
    if (rowIndex !in rowNodes.indices) {
      drawRowRecord(buf, null, snapCols)
      return
    }
    val node = rowNodes[rowIndex] ?: RenderNode("terminal row").also {
      it.setPosition(0, 0, gridWidthPx, gridHeightPx)
      rowNodes[rowIndex] = it
    }
    val canvas = node.beginRecording(gridWidthPx, gridHeightPx)
    try {
      drawRowRecord(buf, canvas, snapCols)
    } finally {
      node.endRecording()
    }
  }

  private fun ByteBuffer.peekInt() = getInt(position())

  /** Reads one row record; draws it on [canvas] unless null (a row outside the grid). */
  private fun drawRowRecord(buf: ByteBuffer, canvas: Canvas?, snapCols: Int) {
    val rowIndex = buf.int
    val selStart = buf.int
    val selEnd = buf.int
    val textUnits = buf.int
    if (rowIndex < rowSelStart.size) {
      rowSelStart[rowIndex] = selStart
      rowSelEnd[rowIndex] = selEnd
      rowSelEndWide[rowIndex] = false
    }
    val cellsPos = buf.position()
    val textPos = cellsPos + snapCols * CELL_RECORD_BYTES

    buf.position(textPos)
    if (textUnits > rowChars.size) rowChars = CharArray(textUnits)
    for (i in 0 until textUnits) rowChars[i] = buf.char
    val nextRecord = textPos + textUnits * 2 + if (textUnits % 2 != 0) 2 else 0

    buf.position(cellsPos)
    val colCount = minOf(snapCols, cellFg.size)
    for (col in 0 until snapCols) {
      val fg = buf.int
      val bg = buf.int
      val textOff = buf.short.toInt() and 0xFFFF
      val textLen = buf.short.toInt() and 0xFFFF
      val flags = buf.short.toInt() and 0xFFFF
      val underline = buf.short.toInt() and 0xFFFF
      val underlineColor = buf.int
      if (col >= colCount) continue
      if (col == selEnd && flags and FLAG_WIDE != 0 && rowIndex < rowSelEndWide.size) {
        rowSelEndWide[rowIndex] = true
      }
      val selected = selStart >= 0 && col >= selStart && col <= selEnd
      var effFg = if (flags and FLAG_FG_DEFAULT != 0) defaultFg else fg
      var effBg = if (flags and FLAG_BG_NONE != 0) defaultBg else bg
      if (flags and FLAG_INVERSE != 0) effFg = effBg.also { effBg = effFg }
      if (selected) {
        val themedBg = selectionBg
        if (themedBg != null) {
          effBg = themedBg
          selectionFg?.let { effFg = it }
        } else {
          // No themed selection colors: classic fg/bg swap.
          effFg = effBg.also { effBg = effFg }
        }
      }
      cellFg[col] = effFg
      cellBg[col] = effBg
      cellTextOff[col] = textOff
      cellTextLen[col] = textLen
      cellFlags[col] = flags
      cellUnderline[col] = underline
      cellUnderlineColor[col] = underlineColor
    }
    buf.position(nextRecord)
    if (canvas == null) return

    val top = (rowIndex * cellHeight).toFloat()
    val bottom = top + cellHeight

    // Background pass: row clear, then per-cell backgrounds.
    fillPaint.color = defaultBg
    canvas.drawRect(0f, top, canvas.width.toFloat(), bottom, fillPaint)
    for (col in 0 until colCount) {
      val bg = cellBg[col]
      if (bg == defaultBg) continue
      val x = col * cellWidth
      fillPaint.color = bg
      canvas.drawRect(x, top, x + cellSpan(cellFlags[col]), bottom, fillPaint)
    }

    // Text pass.
    var col = 0
    while (col < colCount) {
      val flags = cellFlags[col]
      val len = cellTextLen[col]
      if (len == 0 || flags and FLAG_SPACER != 0 || flags and FLAG_INVISIBLE != 0) {
        col++
        continue
      }
      val codepoint = Character.codePointAt(rowChars, cellTextOff[col])
      val paint = when {
        symbolsPaint != null &&
          (codepoint in PUA_BMP_FIRST..PUA_BMP_LAST || codepoint >= PUA_SUPPLEMENTARY_FIRST) ->
          symbolsPaint
        fallbackPaint != null && codepoint >= 0x80 && !canDraw(codepoint) -> fallbackPaint
        flags and FLAG_BOLD != 0 && flags and FLAG_ITALIC != 0 -> boldItalicPaint
        flags and FLAG_BOLD != 0 -> boldPaint
        flags and FLAG_ITALIC != 0 -> italicPaint
        else -> textPaint
      }
      // Ligatures: extend over following ASCII cells with the same look.
      var end = col + 1
      if (ligatures && joinable(col)) {
        while (end < colCount && joinable(end) && cellFg[end] == cellFg[col] &&
          cellFlags[end] and STYLE_FLAGS == flags and STYLE_FLAGS
        ) end++
      }
      paint.color = cellFg[col]
      if (flags and FLAG_FAINT != 0) paint.alpha = 160
      val x = col * cellWidth
      if (end == col + 1) {
        canvas.drawText(rowChars, cellTextOff[col], len, x, top + baseline, paint)
      } else {
        runText.setLength(0)
        for (c in col until end) runText.append(rowChars[cellTextOff[c]])
        canvas.drawText(runText, 0, runText.length, x, top + baseline, paint)
      }
      for (c in col until end) {
        val cx = c * cellWidth
        val span = cellSpan(cellFlags[c])
        if (cellFlags[c] and FLAG_UNDERLINE != 0) {
          drawUnderline(canvas, cellUnderline[c], cellUnderlineColor[c], cx, span, bottom, paint)
        }
        if (cellFlags[c] and FLAG_STRIKETHROUGH != 0) {
          val mid = top + cellHeight / 2f
          canvas.drawRect(cx, mid - underlineThickness / 2, cx + span, mid + underlineThickness / 2, paint)
        }
      }
      paint.alpha = 255
      col = end
    }
  }

  /**
   * One cell's underline in its SGR 4 style, in its SGR 58 color or else the
   * text's ([paint], faint included). A plain one is the text-colored bar.
   */
  private fun drawUnderline(canvas: Canvas, style: Int, color: Int, x: Float, span: Float, bottom: Float, paint: Paint) {
    val t = underlineThickness
    if (style <= UNDERLINE_SINGLE && color == 0) {
      canvas.drawRect(x, bottom - t, x + span, bottom, paint)
      return
    }
    val p = decorationPaint
    p.color = if (color != 0) color else paint.color
    p.alpha = paint.alpha
    p.style = Paint.Style.FILL
    when (style) {
      UNDERLINE_DOUBLE -> {
        canvas.drawRect(x, bottom - t, x + span, bottom, p)
        canvas.drawRect(x, bottom - 3 * t, x + span, bottom - 2 * t, p)
      }
      UNDERLINE_CURLY -> {
        // One wave per cell, peak to trough within the bottom of the cell.
        val amplitude = t * 1.5f
        val mid = bottom - amplitude - t / 2
        curlyPath.reset()
        curlyPath.moveTo(x, mid)
        var at = x
        val half = cellWidth / 2
        var up = true
        while (at < x + span - 0.01f) {
          val end = minOf(at + half, x + span)
          curlyPath.quadTo((at + end) / 2, if (up) mid - amplitude * 2 else mid + amplitude * 2, end, mid)
          at = end
          up = !up
        }
        p.style = Paint.Style.STROKE
        p.strokeWidth = t
        canvas.drawPath(curlyPath, p)
      }
      UNDERLINE_DOTTED -> {
        var at = x + t / 2
        while (at < x + span) {
          canvas.drawCircle(at, bottom - t / 2, t / 2, p)
          at += t * 2
        }
      }
      UNDERLINE_DASHED -> {
        val dash = span / 3
        canvas.drawRect(x, bottom - t, x + dash, bottom, p)
        canvas.drawRect(x + dash * 2, bottom - t, x + span, bottom, p)
      }
      else -> canvas.drawRect(x, bottom - t, x + span, bottom, p)
    }
  }

  /** Whether the terminal font, or a font Android falls back to, has [codepoint]. */
  private fun canDraw(codepoint: Int) =
    drawable.getOrPut(codepoint) { textPaint.hasGlyph(String(Character.toChars(codepoint))) }

  /** A cell's width in px: two columns for a wide character. */
  private fun cellSpan(flags: Int) = if (flags and FLAG_WIDE != 0) cellWidth * 2 else cellWidth

  override fun onDraw(canvas: Canvas) {
    canvas.drawColor(defaultBg)
    // Rows replay only on a hardware canvas: a software one (a View.draw into
    // a bitmap) gets the background and overlays.
    if (canvas.isHardwareAccelerated) {
      for (node in rowNodes) if (node != null && node.hasDisplayList()) canvas.drawRenderNode(node)
    }
    drawCursor(canvas)
    drawSelectionHandles(canvas)
    drawScrollIndicator(canvas)
    drawJumpToBottomChip(canvas)
  }

  private fun drawScrollIndicator(canvas: Canvas) {
    if (scrollbarTotal <= scrollbarLen) return
    val elapsed = SystemClock.uptimeMillis() - lastScrollActivityAt
    if (elapsed > SCROLLBAR_FADE_END_MS) return
    val alpha = if (elapsed <= SCROLLBAR_FADE_START_MS) {
      255
    } else {
      (255 * (SCROLLBAR_FADE_END_MS - elapsed) / (SCROLLBAR_FADE_END_MS - SCROLLBAR_FADE_START_MS)).toInt()
    }
    val density = resources.displayMetrics.density
    val barWidth = 3 * density
    val right = width - 2 * density
    val trackHeight = height.toFloat()
    val top = trackHeight * scrollbarOffset / scrollbarTotal
    val thumbHeight = max(16 * density, trackHeight * scrollbarLen / scrollbarTotal)
    scrollbarPaint.color = SCROLLBAR_COLOR
    scrollbarPaint.alpha = alpha
    canvas.drawRoundRect(
      right - barWidth, top, right, (top + thumbHeight).coerceAtMost(trackHeight),
      barWidth / 2, barWidth / 2, scrollbarPaint
    )
    if (elapsed > SCROLLBAR_FADE_START_MS) invalidate()
  }

  private fun jumpChipCenter(): Pair<Float, Float> {
    val density = resources.displayMetrics.density
    return Pair(width - 32 * density, height - 32 * density)
  }

  private fun drawJumpToBottomChip(canvas: Canvas) {
    if (!scrolledBack) return
    val density = resources.displayMetrics.density
    val (cx, cy) = jumpChipCenter()
    val radius = JUMP_CHIP_RADIUS_DP * density
    jumpChipPaint.style = Paint.Style.FILL
    jumpChipPaint.color = JUMP_CHIP_BACKGROUND
    canvas.drawCircle(cx, cy, radius, jumpChipPaint)
    // Downward chevron.
    val span = radius * 0.45f
    jumpChipArrow.reset()
    jumpChipArrow.moveTo(cx - span, cy - span * 0.5f)
    jumpChipArrow.lineTo(cx, cy + span * 0.5f)
    jumpChipArrow.lineTo(cx + span, cy - span * 0.5f)
    jumpChipPaint.style = Paint.Style.STROKE
    jumpChipPaint.strokeWidth = 2 * density
    jumpChipPaint.color = JUMP_CHIP_ARROW_COLOR
    canvas.drawPath(jumpChipArrow, jumpChipPaint)
  }

  private fun drawCursor(canvas: Canvas) {
    if (!cursorVisible || cursorX < 0 || cursorY < 0) return
    if (cursorBlinks && blinkScheduled && !blinkPhaseOn) return
    val x = cursorX * cellWidth
    val top = (cursorY * cellHeight).toFloat()
    val bottom = top + cellHeight
    cursorPaint.color = if (cursorColor != 0) cursorColor else defaultFg
    when (cursorStyle) {
      CURSOR_STYLE_BAR -> {
        cursorPaint.style = Paint.Style.FILL
        canvas.drawRect(x, top, x + underlineThickness * 2, bottom, cursorPaint)
      }
      CURSOR_STYLE_UNDERLINE -> {
        cursorPaint.style = Paint.Style.FILL
        canvas.drawRect(x, bottom - underlineThickness * 2, x + cellWidth, bottom, cursorPaint)
      }
      CURSOR_STYLE_BLOCK_HOLLOW -> {
        cursorPaint.style = Paint.Style.STROKE
        cursorPaint.strokeWidth = underlineThickness
        canvas.drawRect(x, top, x + cellWidth, bottom, cursorPaint)
      }
      else -> {
        cursorPaint.style = Paint.Style.FILL
        cursorPaint.alpha = 170
        canvas.drawRect(x, top, x + cellWidth, bottom, cursorPaint)
        cursorPaint.alpha = 255
      }
    }
  }

  // ── Input ──

  override fun onTouchEvent(event: MotionEvent): Boolean {
    if (event.actionMasked == MotionEvent.ACTION_DOWN) scroller.abortAnimation()
    scaleDetector.onTouchEvent(event)
    if (scaleDetector.isInProgress) {
      if (draggingHandle != HANDLE_NONE) {
        draggingHandle = HANDLE_NONE
        magnifier.dismiss()
      }
      // Keep the tap/long-press detector's stream consistent; its callbacks
      // are guarded on isInProgress.
      gestureDetector.onTouchEvent(event)
      return true
    }
    if (selectionMode) {
      when (event.actionMasked) {
        MotionEvent.ACTION_DOWN -> {
          draggingHandle = hitTestHandle(event.x, event.y)
          if (draggingHandle != HANDLE_NONE) {
            // Hide the toolbar while dragging; it comes back on release.
            actionMode?.finish()
            return true
          }
        }
        MotionEvent.ACTION_MOVE -> if (draggingHandle != HANDLE_NONE) {
          dragSelection(event.x, event.y)
          return true
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
          if (draggingHandle != HANDLE_NONE) {
            draggingHandle = HANDLE_NONE
            magnifier.dismiss()
            startTerminalActionMode()
            return true
          }
      }
    }
    return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
  }

  override fun onCheckIsTextEditor(): Boolean = true

  override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
    // Visible-password + no-suggestions keeps IMEs in send-as-you-type mode
    // (no autocorrect batching) while still allowing CJK composition.
    outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
      InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
      InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
      EditorInfo.IME_FLAG_NO_FULLSCREEN or
      EditorInfo.IME_FLAG_NO_EXTRACT_UI
    return TerminalInputConnection()
  }

  override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
    return handleKeyEvent(event) || super.onKeyDown(keyCode, event)
  }

  override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
    return handleKeyEvent(event) || super.onKeyUp(keyCode, event)
  }

  /**
   * A hardware keyboard's key that arrived while focus was elsewhere: the
   * terminal takes focus and the key. False for keys it leaves alone.
   */
  fun takeHardwareKey(event: KeyEvent): Boolean {
    if (event.isSystem) return false
    if (!hasFocus()) requestFocus()
    return handleKeyEvent(event)
  }

  private fun handleKeyEvent(event: KeyEvent): Boolean {
    if (handle == 0L || finished) return false
    val action = when (event.action) {
      KeyEvent.ACTION_DOWN -> if (event.repeatCount > 0) 2 else 1
      KeyEvent.ACTION_UP -> 0
      else -> return false
    }
    // Sticky accessory-bar modifiers apply to hardware keys too.
    val stickyMeta = if (action == 1) accessoryBar?.takeStickyMetaState() ?: 0 else 0
    val metaState = event.metaState or stickyMeta
    val unicode = event.getUnicodeChar(
      metaState and (KeyEvent.META_CTRL_MASK or KeyEvent.META_META_MASK).inv()
    )
    val utf8 = if (unicode != 0 && unicode and KeyCharacterMap.COMBINING_ACCENT == 0) {
      String(Character.toChars(unicode)).toByteArray(Charsets.UTF_8)
    } else {
      null
    }
    val unshifted = event.getUnicodeChar(0)
    val bytes = GhosttyVt.encodeKey(
      handle, event.keyCode, action, metaState, unshifted, utf8
    ) ?: return false
    if (bytes.isEmpty()) return false
    sendBytes(bytes)
    return true
  }

  private inner class TerminalInputConnection :
    BaseInputConnection(this@GhosttyTerminalView, false) {
    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
      sendText(text)
      return super.commitText("", newCursorPosition)
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
      repeat(beforeLength) { tap(KeyEvent.KEYCODE_DEL) }
      return true
    }

    override fun performEditorAction(actionCode: Int): Boolean {
      tap(KeyEvent.KEYCODE_ENTER)
      return true
    }

    private fun tap(keyCode: Int) {
      sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
      sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean =
      handleKeyEvent(event) || super.sendKeyEvent(event)
  }

  private companion object {
    const val FONT_SIZE_DP = 14f
    // Same bounds and step as the iOS pinch-zoom (UITerminalView).
    const val MIN_FONT_SIZE_DP = 4f
    const val MAX_FONT_SIZE_DP = 64f
    const val PINCH_SCALE_STEP = 0.1f
    const val INITIAL_COLS = 80
    const val INITIAL_ROWS = 24
    const val MAX_SCROLLBACK = 10_000L
    const val BLINK_INTERVAL_MS = 600L
    const val SYMBOLS_FONT_ASSET = "SymbolsNerdFontMono-Regular.ttf"
    const val FALLBACK_FONT_ASSET = "NotoSansSymbols2-Regular.ttf"

    // Nerd Font glyph ranges: BMP private-use area plus the supplementary
    // private-use planes (material icons live at U+F0000+ since v3).
    const val PUA_BMP_FIRST = 0xE000
    const val PUA_BMP_LAST = 0xF8FF
    const val PUA_SUPPLEMENTARY_FIRST = 0xF0000

    const val HANDLE_NONE = 0
    const val HANDLE_START = 1
    const val HANDLE_END = 2
    const val HANDLE_RADIUS_DP = 8f
    const val HANDLE_HIT_SLOP_DP = 24f
    const val HANDLE_COLOR = 0xFF4C8DF5.toInt()

    const val MENU_OPEN_LINK = 4
    const val MENU_COPY = 1
    const val MENU_PASTE = 2
    const val MENU_SELECT_ALL = 3

    const val SCROLLBAR_FADE_START_MS = 800L
    const val SCROLLBAR_FADE_END_MS = 1400L
    const val SCROLLBAR_COLOR = 0xB3AAAAAA.toInt()
    const val JUMP_CHIP_RADIUS_DP = 18f
    const val JUMP_CHIP_BACKGROUND = 0xCC2A2A2E.toInt()
    const val JUMP_CHIP_ARROW_COLOR = 0xFFE0E0E0.toInt()

    const val HEADER_BYTES = 56
    const val ROW_HEADER_BYTES = 16
    const val CELL_RECORD_BYTES = 20
    const val UNDERLINE_SINGLE = 1
    const val UNDERLINE_DOUBLE = 2
    const val UNDERLINE_CURLY = 3
    const val UNDERLINE_DOTTED = 4
    const val UNDERLINE_DASHED = 5
    const val HOLD_POLL_MS = 100L
    const val MAX_CELL_TEXT_UNITS = 32

    const val DIRTY_FULL = 2

    const val CURSOR_STYLE_BAR = 0
    const val CURSOR_STYLE_BLOCK = 1
    const val CURSOR_STYLE_UNDERLINE = 2
    const val CURSOR_STYLE_BLOCK_HOLLOW = 3

    const val FLAG_BOLD = 1 shl 0
    const val FLAG_ITALIC = 1 shl 1
    const val FLAG_FAINT = 1 shl 2
    const val FLAG_UNDERLINE = 1 shl 3
    const val FLAG_STRIKETHROUGH = 1 shl 4
    const val FLAG_INVERSE = 1 shl 5
    const val FLAG_INVISIBLE = 1 shl 6
    const val FLAG_WIDE = 1 shl 7
    const val FLAG_SPACER = 1 shl 8
    const val FLAG_FG_DEFAULT = 1 shl 9
    const val FLAG_BG_NONE = 1 shl 10
    const val STYLE_FLAGS = FLAG_BOLD or FLAG_ITALIC or FLAG_FAINT
  }
}
