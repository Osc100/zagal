package dev.herdr.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Draws the grid: the cell metrics of the terminal's font, one snapshot row
 * record at a time ([drawRow]), and the cursor ([drawCursor]). It reads the
 * records jni.zig writes and knows nothing of views, so tests draw with it
 * onto a bitmap.
 */
internal class GridPainter(private val context: Context) {
  private val resources get() = context.resources

  // ── Cell metrics ──
  var fontSizeDp = FONT_SIZE_DP
    private set
  private var typeface: Typeface? = null
  var ligatures = false
    private set
  private val runText = StringBuilder()
  val textPaint = newTextPaint(Typeface.MONOSPACE)
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
  var cellWidth = 0f
    private set
  var cellHeight = 0
    private set
  private var baseline = 0f
  val underlineThickness = max(1f, resources.displayMetrics.density)
  // Double, curly, dotted and dashed underlines, and colored ones.
  private val decorationPaint = Paint(Paint.ANTI_ALIAS_FLAG)
  private val curlyPath = Path()

  // ── One row's cells, as last read ──
  private var rowChars = CharArray(0)
  private var cellFg = IntArray(0)
  private var cellBg = IntArray(0)
  private var cellTextOff = IntArray(0)
  private var cellTextLen = IntArray(0)
  private var cellFlags = IntArray(0)
  private var cellUnderline = IntArray(0)
  private var cellUnderlineColor = IntArray(0)

  // Each row's selected columns, as its last record had them (-1: none).
  var rowSelStart = IntArray(0)
    private set
  var rowSelEnd = IntArray(0)
    private set
  var rowSelEndWide = BooleanArray(0)
    private set

  var defaultBg = 0xFF000000.toInt()
  var defaultFg = 0xFFFFFFFF.toInt()
  // Themed selection colors; when unset, selected cells keep the classic
  // fg/bg swap.
  var selectionBg: Int? = null
  var selectionFg: Int? = null

  init {
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

  /** The font size in dp, clamped to 4–64; false when it didn't change. */
  fun setFontSize(dp: Float): Boolean {
    val clamped = dp.coerceIn(MIN_FONT_SIZE_DP, MAX_FONT_SIZE_DP)
    if (clamped == fontSizeDp) return false
    fontSizeDp = clamped
    val px = clamped * resources.displayMetrics.density
    for (paint in stylePaints) paint.textSize = px
    symbolsPaint?.textSize = px
    fallbackPaint?.textSize = px
    measureCell()
    return true
  }

  private fun measureCell() {
    val metrics = textPaint.fontMetrics
    cellWidth = textPaint.measureText("M")
    cellHeight = ceil(metrics.descent - metrics.ascent).toInt()
    baseline = -metrics.ascent
  }

  /**
   * The terminal font; null is the system monospace. Bold uses the font's
   * `wght` axis when it is a variable font, else Android's synthetic styles.
   * False when it didn't change.
   */
  fun setTypeface(face: Typeface?): Boolean {
    if (face == typeface) return false
    typeface = face
    drawable.clear()
    val base = face ?: Typeface.MONOSPACE
    styled(textPaint, base, bold = false, italic = false)
    styled(boldPaint, base, bold = true, italic = false)
    styled(italicPaint, base, bold = false, italic = true)
    styled(boldItalicPaint, base, bold = true, italic = true)
    measureCell()
    return true
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
   * advances keep every glyph on its own cell. Off disables liga/calt. False
   * when it didn't change.
   */
  fun setLigatures(enabled: Boolean): Boolean {
    if (enabled == ligatures) return false
    ligatures = enabled
    val features = if (enabled) "'liga' 1, 'calt' 1" else "'liga' 0, 'calt' 0"
    for (paint in stylePaints) paint.fontFeatureSettings = features
    return true
  }

  /** Room for a grid of [cols] × [rows]: the per-cell arrays and row selections. */
  fun allocate(cols: Int, rows: Int) {
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

  private fun joinable(col: Int): Boolean {
    val flags = cellFlags[col]
    if (cellTextLen[col] != 1 || flags and (FLAG_SPACER or FLAG_INVISIBLE or FLAG_WIDE) != 0) {
      return false
    }
    val c = rowChars[cellTextOff[col]].code
    return c in 0x21..0x7E
  }

  /**
   * Reads one row record at [buf]'s position (leaving it at the next) and
   * draws it on [canvas]; null only reads it (a row outside the grid).
   */
  fun drawRow(buf: ByteBuffer, canvas: Canvas?, snapCols: Int) {
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
      val paint = paintFor(codepoint, flags)
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
        if (!drawBlock(canvas, codepoint, col, top, bottom, paint)) {
          canvas.drawText(rowChars, cellTextOff[col], len, x, top + baseline, paint)
        }
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

  /** The paint a cell's character is drawn with: its font, by the character and the cell's style. */
  private fun paintFor(codepoint: Int, flags: Int): Paint = when {
    symbolsPaint != null &&
      (codepoint in PUA_BMP_FIRST..PUA_BMP_LAST || codepoint >= PUA_SUPPLEMENTARY_FIRST) ->
      symbolsPaint
    fallbackPaint != null && codepoint >= 0x80 && !canDraw(codepoint) -> fallbackPaint
    flags and FLAG_BOLD != 0 && flags and FLAG_ITALIC != 0 -> boldItalicPaint
    flags and FLAG_BOLD != 0 -> boldPaint
    flags and FLAG_ITALIC != 0 -> italicPaint
    else -> textPaint
  }

  /**
   * A block element (U+2580–259F: ▀ █ ▐ ▛ ░ …) as rectangles on the cell's own
   * pixel edges, as ghostty draws them: a font's glyph leaves gaps between
   * cells, which split pictures made of them (Claude Code's logo). False for
   * other characters.
   */
  private fun drawBlock(canvas: Canvas, codepoint: Int, col: Int, top: Float, bottom: Float, paint: Paint): Boolean {
    if (codepoint !in 0x2580..0x259F) return false
    val left = (col * cellWidth).roundToInt().toFloat()
    val right = ((col + 1) * cellWidth).roundToInt().toFloat()
    // A part of the cell, in eighths across and down.
    fun part(x0: Int, y0: Int, x1: Int, y1: Int) = canvas.drawRect(
      left + ((right - left) * x0 / 8).roundToInt(), top + ((bottom - top) * y0 / 8).roundToInt(),
      left + ((right - left) * x1 / 8).roundToInt(), top + ((bottom - top) * y1 / 8).roundToInt(),
      paint,
    )
    when (codepoint) {
      0x2580 -> part(0, 0, 8, 4)
      in 0x2581..0x2588 -> part(0, 8 - (codepoint - 0x2580), 8, 8)
      in 0x2589..0x258F -> part(0, 0, 8 - (codepoint - 0x2588), 8)
      0x2590 -> part(4, 0, 8, 8)
      in 0x2591..0x2593 -> {
        // Shades: the whole cell, a quarter, half or three quarters as strong.
        val alpha = paint.alpha
        paint.alpha = alpha * (codepoint - 0x2590) / 4
        part(0, 0, 8, 8)
        paint.alpha = alpha
      }
      0x2594 -> part(0, 0, 8, 1)
      0x2595 -> part(7, 0, 8, 8)
      else -> {
        val quadrants = QUADRANTS[codepoint - 0x2596]
        if (quadrants and 1 != 0) part(0, 0, 4, 4)
        if (quadrants and 2 != 0) part(4, 0, 8, 4)
        if (quadrants and 4 != 0) part(0, 4, 4, 8)
        if (quadrants and 8 != 0) part(4, 4, 8, 8)
      }
    }
    return true
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

  /**
   * The cursor at cell ([x], [y]) in [style], in [color] (0: the default
   * foreground); [wide] on a wide character, over both its cells. A block is
   * solid, the cell's character ([glyph], [glyphLength] units, its cell's
   * [glyphFlags]) drawn again over it in the background color, as ghostty
   * draws it.
   */
  fun drawCursor(
    canvas: Canvas,
    x: Int,
    y: Int,
    style: Int,
    color: Int,
    wide: Boolean = false,
    glyph: CharArray? = null,
    glyphLength: Int = 0,
    glyphFlags: Int = 0,
  ) {
    val left = x * cellWidth
    val span = if (wide) cellWidth * 2 else cellWidth
    val top = (y * cellHeight).toFloat()
    val bottom = top + cellHeight
    cursorPaint.color = if (color != 0) color else defaultFg
    when (style) {
      CURSOR_STYLE_BAR -> {
        cursorPaint.style = Paint.Style.FILL
        canvas.drawRect(left, top, left + underlineThickness * 2, bottom, cursorPaint)
      }
      CURSOR_STYLE_UNDERLINE -> {
        cursorPaint.style = Paint.Style.FILL
        canvas.drawRect(left, bottom - underlineThickness * 2, left + span, bottom, cursorPaint)
      }
      CURSOR_STYLE_BLOCK_HOLLOW -> {
        cursorPaint.style = Paint.Style.STROKE
        cursorPaint.strokeWidth = underlineThickness
        canvas.drawRect(left, top, left + span, bottom, cursorPaint)
      }
      else -> {
        cursorPaint.style = Paint.Style.FILL
        canvas.drawRect(left, top, left + span, bottom, cursorPaint)
        if (glyph == null || glyphLength == 0) return
        val codepoint = Character.codePointAt(glyph, 0)
        val paint = paintFor(codepoint, glyphFlags)
        paint.color = defaultBg
        if (!drawBlock(canvas, codepoint, x, top, bottom, paint)) {
          canvas.drawText(glyph, 0, glyphLength, left, top + baseline, paint)
        }
      }
    }
  }

  companion object {
    // ▖ ▗ ▘ ▙ ▚ ▛ ▜ ▝ ▞ ▟ (U+2596–259F): which quarters each fills
    // (1 upper left, 2 upper right, 4 lower left, 8 lower right).
    private val QUADRANTS = intArrayOf(4, 8, 1, 1 or 4 or 8, 1 or 8, 1 or 2 or 4, 1 or 2 or 8, 2, 2 or 4, 2 or 4 or 8)

    const val FONT_SIZE_DP = 14f
    // Same bounds and step as the iOS pinch-zoom (UITerminalView).
    const val MIN_FONT_SIZE_DP = 4f
    const val MAX_FONT_SIZE_DP = 64f
    const val SYMBOLS_FONT_ASSET = "SymbolsNerdFontMono-Regular.ttf"
    const val FALLBACK_FONT_ASSET = "NotoSansSymbols2-Regular.ttf"

    // Nerd Font glyph ranges: BMP private-use area plus the supplementary
    // private-use planes (material icons live at U+F0000+ since v3).
    const val PUA_BMP_FIRST = 0xE000
    const val PUA_BMP_LAST = 0xF8FF
    const val PUA_SUPPLEMENTARY_FIRST = 0xF0000

    // The snapshot's layout (jni.zig).
    const val HEADER_BYTES = 76
    const val ROW_HEADER_BYTES = 16
    const val CELL_RECORD_BYTES = 20
    const val MAX_CELL_TEXT_UNITS = 32
    const val UNDERLINE_SINGLE = 1
    const val UNDERLINE_DOUBLE = 2
    const val UNDERLINE_CURLY = 3
    const val UNDERLINE_DOTTED = 4
    const val UNDERLINE_DASHED = 5

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
