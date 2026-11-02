//! A terminal session: ghostty's terminal, its VT stream and a render state,
//! with what the view reads of it (the snapshot) and asks of it (selection,
//! links). No JNI here: jni.zig wraps it for Kotlin, and tests and benchmarks
//! use it directly on this machine.
//!
//! snapshot flattens the dirty viewport rows into a caller-provided
//! direct ByteBuffer (little-endian) so Kotlin can repaint without any
//! per-cell JNI calls. See GhosttyTerminalView.kt for the reader side.
//!
//! Buffer layout:
//!   header: 15 x i32
//!     [0] version (4)          [1] dirty kind (0 none / 1 partial / 2 full)
//!     [2] cols                 [3] rows
//!     [4] default bg (ARGB)    [5] default fg (ARGB)
//!     [6] cursor x or -1 (a wide character's first cell when on either)
//!     [7] cursor y or -1
//!     [8] cursor style         [9] cursor visible (0/1)
//!     [10] row record count    [11] cursor blinking (0/1)
//!     [12] cursor color (ARGB) or 0 when unconfigured
//!     [13] held (0/1): synchronized output; the rows are the frame captured
//!          when it began, and the caller should look again shortly
//!     [14] cursor wide (0/1): on a wide character, two cells wide
//!   row record:
//!     i32 rowIndex, i32 selStartX or -1, i32 selEndX or -1, i32 textUnits
//!     cells[cols] x 20 bytes:
//!       i32 fg (ARGB), i32 bg (ARGB),
//!       u16 textOffset (UTF-16 units into row blob), u16 textLen (units),
//!       u16 flags, u16 underline style (0 none, 1 single, 2 double,
//!       3 curly, 4 dotted, 5 dashed), i32 underline color (ARGB) or 0
//!     u16 text[textUnits], zero-padded to a 4-byte boundary

const std = @import("std");
const vt = @import("ghostty-vt");

const alloc = std.heap.c_allocator;

// Cell flag bits shared with GhosttyTerminalView.kt.
const flag_bold: u16 = 1 << 0;
const flag_italic: u16 = 1 << 1;
const flag_faint: u16 = 1 << 2;
const flag_underline: u16 = 1 << 3;
const flag_strikethrough: u16 = 1 << 4;
const flag_inverse: u16 = 1 << 5;
const flag_invisible: u16 = 1 << 6;
const flag_wide: u16 = 1 << 7;
const flag_spacer: u16 = 1 << 8;
const flag_fg_default: u16 = 1 << 9;
const flag_bg_none: u16 = 1 << 10;

pub const header_bytes = 15 * 4;
const row_header_bytes = 4 * 4;
const cell_record_bytes = 20;
// How long a synchronized-output hold may last before drawing resumes anyway.
const max_hold_ms = 1000;
// The most a program may copy to the clipboard at once (OSC 52).
const max_clipboard_bytes = 1 << 20;
// Cap per-cell grapheme text; anything longer is truncated (degenerate input).
const max_cell_text_units = 32;

// Default boundaries for word selection, as ghostty's own
// (terminal/selection_codepoints.zig, which the module doesn't export).
pub const word_boundaries = [_]u21{ 0, ' ', '\t', '\'', '"', '│', '`', '|', ':', ';', ',', '(', ')', '[', ']', '{', '}', '<', '>', '$' };

pub const Session = struct {
    io: vt.TinyIo = .init,
    term: vt.Terminal,
    stream: vt.TerminalStream,
    render: vt.RenderState = .empty,
    // Query responses emitted by the terminal while writing; handed back to
    // the caller after each write, for the PTY.
    pty_out: std.ArrayList(u8) = .empty,
    // When a synchronized-output hold began (monotonic ms), while it lasts.
    held_since: ?i64 = null,
    // The default colors the last snapshot gave, which the view drew its rows in.
    shown_colors: ?[2]vt.color.RGB = null,
    // Text a program copied (OSC 52), until takeClipboard collects it.
    clipboard: std.ArrayList(u8) = .empty,
    clipboard_pending: bool = false,
    clipboard_allowed: bool = true,
    // The cell last reported for motion, so a pointer moving within a cell sends nothing.
    mouse_cell: ?vt.Coordinate = null,

    fn fromHandler(handler: *vt.TerminalStream.Handler) *Session {
        const stream: *vt.TerminalStream = @fieldParentPtr("handler", handler);
        return @alignCast(@fieldParentPtr("stream", stream));
    }

    fn writePty(handler: *vt.TerminalStream.Handler, data: []const u8) void {
        const self = fromHandler(handler);
        self.pty_out.appendSlice(alloc, data) catch {};
    }

    // Synchronized output (mode 2026): capture the finished frame the program
    // wants on screen, then draw nothing newer until the hold ends.
    fn renderHold(handler: *vt.TerminalStream.Handler, held: bool) void {
        const self = fromHandler(handler);
        if (held) {
            self.render.update(alloc, &self.term) catch {};
            self.held_since = nowMs();
        } else {
            self.held_since = null;
        }
    }

    // A program copying text (OSC 52, kitty's OSC 5522), kept for
    // takeClipboard. Android has one clipboard: the standard one.
    fn clipboardWrite(handler: *vt.TerminalStream.Handler, request: vt.clipboard.Write) void {
        const self = fromHandler(handler);
        if (!self.clipboard_allowed) return request.reply(.denied);
        if (request.location != .standard) return request.reply(.unsupported);
        for (request.contents) |content| {
            if (!vt.clipboard.isTextMime(content.mime)) continue;
            if (content.data.len > max_clipboard_bytes) return request.reply(.invalid_data);
            self.clipboard.clearRetainingCapacity();
            self.clipboard.appendSlice(alloc, content.data) catch return request.reply(.io_error);
            self.clipboard_pending = true;
            return request.reply(.{ .success = .{} });
        }
        request.reply(.unsupported);
    }

    // Answered like the C API does: with the default attributes.
    fn deviceAttributes(_: *vt.TerminalStream.Handler) vt.device_attributes.Attributes {
        return .{};
    }

    // Selection changes don't reliably mark rows dirty for our snapshot; force a
    // full re-emit so the per-row selection ranges refresh everywhere.
    pub fn dirtyAll(self: *Session) void {
        self.render.dirty = .full;
    }

    pub fn select(self: *Session, sel: vt.Selection) bool {
        self.term.screens.active.select(sel) catch return false;
        self.dirtyAll();
        return true;
    }

    pub fn viewportPin(self: *Session, col: i32, row: i32) ?vt.Pin {
        if (col < 0 or row < 0) return null;
        return self.term.screens.active.pages.pin(.{ .viewport = .{
            .x = @intCast(col),
            .y = @intCast(row),
        } });
    }

    pub fn modeOn(self: *Session, comptime mode: vt.Mode) bool {
        return self.term.modes.get(mode);
    }
};

/// A terminal of `cols` × `rows` keeping `max_scrollback` lines. Free with `destroy`.
pub fn create(cols: i32, rows: i32, max_scrollback: i64) !*Session {
    if (cols <= 0 or rows <= 0) return error.InvalidSize;
    const session = try alloc.create(Session);
    errdefer alloc.destroy(session);
    session.* = .{ .term = undefined, .stream = undefined };
    session.term = try .init(session.io.io(), alloc, .{
        .cols = @intCast(cols),
        .rows = @intCast(rows),
    });
    const t = &session.term;
    // We don't install ghostty's shell integration, so don't assume prompts
    // can be redrawn on resize (shells can still opt in with OSC 133).
    t.flags.shell_redraws_prompt = .false;
    t.setScrollbackMaxLines(@intCast(max_scrollback));

    var handler = t.vtHandler();
    handler.effects.write_pty = &Session.writePty;
    handler.effects.device_attributes = &Session.deviceAttributes;
    handler.effects.render_hold = &Session.renderHold;
    handler.effects.clipboard_write = &Session.clipboardWrite;
    session.stream = .init(.{ .allocator = alloc, .handler = handler });

    t.colors.background.default = .{ .r = 0x00, .g = 0x00, .b = 0x00 };
    t.colors.foreground.default = .{ .r = 0xFF, .g = 0xFF, .b = 0xFF };
    t.flags.dirty.palette = true;
    // libghostty's default is a steady cursor; blink by default like the
    // terminal apps users come from. DECSCUSR still overrides.
    t.setDefaultCursorBlink(true);
    return session;
}

/// Frees a session from `create`.
pub fn destroy(session: *Session) void {
    session.render.deinit(alloc);
    session.stream.deinit();
    session.term.deinit(alloc);
    session.pty_out.deinit(alloc);
    session.clipboard.deinit(alloc);
    alloc.destroy(session);
}

pub fn argb(rgb: vt.color.RGB) i32 {
    return @bitCast(0xFF000000 | (@as(u32, rgb.r) << 16) | (@as(u32, rgb.g) << 8) | rgb.b);
}

/// Appends one codepoint as UTF-16; returns the units written (0 if full).
fn appendUtf16(cp: u21, out: []u16, used: usize) usize {
    if (cp < 0x10000) {
        if (used + 1 > out.len) return 0;
        out[used] = @intCast(cp);
        return 1;
    }
    if (used + 2 > out.len) return 0;
    const v = cp - 0x10000;
    out[used] = @intCast(0xD800 + (v >> 10));
    out[used + 1] = @intCast(0xDC00 + (v & 0x3FF));
    return 2;
}

fn putI32(buf: []u8, at: usize, v: i32) void {
    std.mem.writeInt(i32, buf[at..][0..4], v, .little);
}

fn putU16(buf: []u8, at: usize, v: u16) void {
    std.mem.writeInt(u16, buf[at..][0..2], v, .little);
}

pub fn nowMs() i64 {
    var ts: std.c.timespec = undefined;
    _ = std.c.clock_gettime(.MONOTONIC, &ts);
    return @as(i64, ts.sec) * 1000 + @divTrunc(@as(i64, ts.nsec), 1_000_000);
}

/// Writes the snapshot (see the layout above) into `buf`; returns the row records written.
pub fn fillSnapshot(session: *Session, buf: []u8) !i32 {
    const state = &session.render;
    // During a hold the render state keeps the frame captured when it began.
    // A program that never ends its hold doesn't freeze the screen.
    if (session.held_since) |since| {
        if (nowMs() - since > max_hold_ms) {
            session.term.modes.set(.synchronized_output, false);
            session.held_since = null;
        }
    }
    const held = session.held_since != null;
    if (!held) try state.update(alloc, &session.term);

    // New default colors (OSC 10/11, reverse video) mark no row, but every
    // row was drawn in the old ones: all of them again.
    const colors = [2]vt.color.RGB{ state.colors.background, state.colors.foreground };
    const recolored = if (session.shown_colors) |shown| !std.meta.eql(shown, colors) else false;
    session.shown_colors = colors;
    const dirty: @TypeOf(state.dirty) = if (recolored) .full else state.dirty;
    const cols: usize = state.cols;
    const cursor = state.cursor;
    const cursor_color: i32 = if (session.term.colors.cursor.get()) |rgb| argb(rgb) else 0;
    const visual: i32 = switch (cursor.visual_style) {
        .bar => 0,
        .block => 1,
        .underline => 2,
        .block_hollow => 3,
    };

    // On a wide character's right half, the cursor is on the character: its first cell, two wide.
    const wide = switch (cursor.cell.wide) {
        .wide, .spacer_tail => true,
        .narrow, .spacer_head => false,
    };
    const cursor_x: i32 = if (cursor.viewport) |vp| @as(i32, vp.x) - @intFromBool(vp.wide_tail) else -1;

    const header = [_]i32{
        4,
        @backingInt(dirty),
        @intCast(cols),
        @intCast(state.rows),
        argb(state.colors.background),
        argb(state.colors.foreground),
        cursor_x,
        if (cursor.viewport) |vp| @intCast(vp.y) else -1,
        if (cursor.viewport != null) visual else 1,
        @intFromBool(cursor.visible),
        0, // row record count, set below
        @intFromBool(cursor.blinking),
        cursor_color,
        @intFromBool(held),
        @intFromBool(cursor.viewport != null and wide),
    };
    comptime std.debug.assert(header.len * 4 == header_bytes);
    for (header, 0..) |v, i| putI32(buf, i * 4, v);

    if (dirty == .false) return 0;

    const range = state.rowDataRange();
    const rows = state.row_data.slice();
    const cells_of = rows.items(.cells)[range.start..range.end];
    const selections = rows.items(.selection)[range.start..range.end];
    const row_dirty = rows.items(.dirty)[range.start..range.end];
    const palette = &state.colors.palette;

    var offset: usize = header_bytes;
    var row_count: i32 = 0;

    for (cells_of, 0..) |row_cells, y| {
        const was_dirty = row_dirty[y];
        row_dirty[y] = false;
        if (dirty != .full and !was_dirty) continue;

        const cells_offset = offset + row_header_bytes;
        const text_offset = cells_offset + cols * cell_record_bytes;
        if (text_offset > buf.len) return error.NoSpace;

        putI32(buf, offset, @intCast(y));
        const sel = selections[y];
        putI32(buf, offset + 4, if (sel) |s| @intCast(s[0]) else -1);
        putI32(buf, offset + 8, if (sel) |s| @intCast(s[1]) else -1);

        const text_cap = (buf.len - text_offset) / 2;
        const text: []u16 = @as([*]u16, @ptrCast(@alignCast(buf.ptr + text_offset)))[0..text_cap];
        var text_units: usize = 0;

        const data = row_cells.slice();
        const raws = data.items(.raw);
        const graphemes = data.items(.grapheme);
        const styles = data.items(.style);

        var col: usize = 0;
        while (col < cols and col < raws.len) : (col += 1) {
            const cell = raws[col];
            const record = cells_offset + col * cell_record_bytes;
            var flags: u16 = 0;

            switch (cell.wide) {
                .wide => flags |= flag_wide,
                .spacer_tail, .spacer_head => flags |= flag_spacer,
                else => {},
            }

            // Style data is undefined for default-styled cells.
            const style: vt.Style = if (cell.hasStyling()) styles[col] else .{};
            if (style.flags.bold) flags |= flag_bold;
            if (style.flags.italic) flags |= flag_italic;
            if (style.flags.faint) flags |= flag_faint;
            if (style.flags.underline != .none) flags |= flag_underline;
            if (style.flags.strikethrough) flags |= flag_strikethrough;
            if (style.flags.inverse) flags |= flag_inverse;
            if (style.flags.invisible) flags |= flag_invisible;

            const fg: ?vt.color.RGB = switch (style.fg_color) {
                .none => null,
                .palette => |idx| palette[idx],
                .rgb => |rgb| rgb,
            };
            // A bg-only cell carries its color in the content.
            const bg = style.bg(&cell, palette);
            if (fg == null) flags |= flag_fg_default;
            if (bg == null) flags |= flag_bg_none;

            const start = text_units;
            var units: usize = 0;
            if (flags & flag_spacer == 0 and cell.hasText()) {
                const first = appendUtf16(cell.codepoint(), text, text_units);
                units += first;
                if (first > 0 and cell.hasGrapheme()) {
                    for (graphemes[col]) |cp| {
                        if (units >= max_cell_text_units) break;
                        const n = appendUtf16(cp, text, text_units + units);
                        if (n == 0) break;
                        units += n;
                    }
                }
            }
            text_units += units;

            putI32(buf, record, if (fg) |rgb| argb(rgb) else 0);
            putI32(buf, record + 4, if (bg) |rgb| argb(rgb) else 0);
            putU16(buf, record + 8, @intCast(start));
            putU16(buf, record + 10, @intCast(units));
            putU16(buf, record + 12, flags);
            putU16(buf, record + 14, @intFromEnum(style.flags.underline));
            const underline_color: ?vt.color.RGB = switch (style.underline_color) {
                .none => null,
                .palette => |idx| palette[idx],
                .rgb => |rgb| rgb,
            };
            putI32(buf, record + 16, if (underline_color) |rgb| argb(rgb) else 0);
        }
        // Zero-fill any remaining cell records (defensive; cols should match).
        while (col < cols) : (col += 1) {
            const record = cells_offset + col * cell_record_bytes;
            @memset(buf[record..][0..cell_record_bytes], 0);
            putU16(buf, record + 12, flag_fg_default | flag_bg_none);
        }

        putI32(buf, offset + 12, @intCast(text_units));
        offset = text_offset + text_units * 2;
        if (offset % 4 != 0) {
            if (offset + 2 > buf.len) return error.NoSpace;
            putU16(buf, offset, 0);
            offset += 2;
        }
        row_count += 1;
    }

    state.dirty = .false;
    putI32(buf, 40, row_count);
    return row_count;
}

pub fn hyperlinkUri(pin: vt.Pin) ?[]const u8 {
    const page = pin.node.page();
    const cell = page.getRowAndCell(pin.x, pin.y).cell;
    if (!cell.hyperlink) return null;
    const id = page.lookupHyperlink(cell) orelse return null;
    return page.hyperlink_set.get(page.memory, id).uri.slice(page.memory);
}

/// The http(s) URL in `text` that covers byte `at`, less the punctuation
/// that usually follows one in prose (a period, an unmatched parenthesis).
pub fn urlAround(text: []const u8, at: usize) ?[]const u8 {
    var start = at + 1;
    while (true) {
        if (start == 0) return null;
        start -= 1;
        const rest = text[start..];
        if (std.mem.startsWith(u8, rest, "https://") or std.mem.startsWith(u8, rest, "http://")) break;
        if (endsUrl(text[start])) return null;
    }
    var end = start;
    while (end < text.len and !endsUrl(text[end])) end += 1;
    while (end > start) {
        const url = text[start..end];
        const last = url[url.len - 1];
        const unmatched = (last == ')' and std.mem.count(u8, url, "(") < std.mem.count(u8, url, ")")) or
            (last == ']' and std.mem.count(u8, url, "[") < std.mem.count(u8, url, "]"));
        if (!unmatched and std.mem.indexOfScalar(u8, ".,;:!?", last) == null) break;
        end -= 1;
    }
    const url = text[start..end];
    if (at >= end or std.mem.endsWith(u8, url, "://")) return null;
    return url;
}

/// Bytes a URL doesn't run through: whitespace and controls, quotes and angle
/// brackets, and anything non-ASCII (a TUI's box drawing around it).
fn endsUrl(byte: u8) bool {
    return byte <= ' ' or byte >= 0x7F or std.mem.indexOfScalar(u8, "<>\"'`", byte) != null;
}


/// A snapshot buffer for a `cols` × `rows` grid, as the view sizes it.
fn testBuffer(comptime cols: usize, comptime rows: usize) type {
    return [header_bytes + rows * (row_header_bytes + cols * cell_record_bytes + cols * max_cell_text_units * 2 + 4)]u8;
}

/// The header's dirty kind and row record count, after a snapshot.
fn headerOf(buf: []const u8) struct { dirty: i32, rows: i32, bg: i32, fg: i32, cursor_x: i32, cursor_wide: i32 } {
    const at = struct {
        fn f(b: []const u8, i: usize) i32 {
            return std.mem.readInt(i32, b[i * 4 ..][0..4], .little);
        }
    }.f;
    return .{
        .dirty = at(buf, 1),
        .rows = at(buf, 10),
        .bg = at(buf, 4),
        .fg = at(buf, 5),
        .cursor_x = at(buf, 6),
        .cursor_wide = at(buf, 14),
    };
}

test "a snapshot of written text" {
    const session = try create(10, 3, 100);
    defer destroy(session);
    session.stream.nextSlice("hi");
    var buf: testBuffer(10, 3) align(4) = undefined;
    try std.testing.expectEqual(3, try fillSnapshot(session, &buf));
    // Nothing new: nothing to draw.
    try std.testing.expectEqual(0, try fillSnapshot(session, &buf));
}

test "every row again when the default colors change" {
    // OSC 11 and reverse video change what every row's blank cells are drawn
    // in, without marking a row: the view drew them in the old colors.
    const session = try create(10, 3, 100);
    defer destroy(session);
    var buf: testBuffer(10, 3) align(4) = undefined;
    session.stream.nextSlice("a\r\nb\r\nc");
    _ = try fillSnapshot(session, &buf);

    session.stream.nextSlice("\x1b]11;#5f0000\x07");
    try std.testing.expectEqual(3, try fillSnapshot(session, &buf));
    try std.testing.expectEqual(2, headerOf(&buf).dirty);
    try std.testing.expectEqual(argb(.{ .r = 0x5f, .g = 0, .b = 0 }), headerOf(&buf).bg);
    try std.testing.expectEqual(0, try fillSnapshot(session, &buf));

    session.stream.nextSlice("\x1b[?5h");
    try std.testing.expectEqual(3, try fillSnapshot(session, &buf));
    try std.testing.expectEqual(argb(.{ .r = 0x5f, .g = 0, .b = 0 }), headerOf(&buf).fg);
}

test "the cursor on a wide character covers it, from its first cell" {
    const session = try create(10, 3, 100);
    defer destroy(session);
    var buf: testBuffer(10, 3) align(4) = undefined;
    // 中 takes columns 0 and 1, 文 2 and 3.
    session.stream.nextSlice("中文");
    for ([_]struct { col: u8, x: i32, wide: i32 }{
        .{ .col = 1, .x = 0, .wide = 1 }, // on 中
        .{ .col = 2, .x = 0, .wide = 1 }, // on 中's right half
        .{ .col = 5, .x = 4, .wide = 0 }, // after both
    }) |case| {
        var move: [16]u8 = undefined;
        session.stream.nextSlice(try std.fmt.bufPrint(&move, "\x1b[1;{d}H", .{case.col}));
        _ = try fillSnapshot(session, &buf);
        try std.testing.expectEqual(case.x, headerOf(&buf).cursor_x);
        try std.testing.expectEqual(case.wide, headerOf(&buf).cursor_wide);
    }
}
