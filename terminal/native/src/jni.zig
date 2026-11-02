//! JNI bridge between dev.herdr.terminal.GhosttyVt and ghostty's `ghostty-vt`
//! Zig module.
//!
//! One native handle owns a terminal, its VT stream, and a render state. All
//! calls come from the Android main thread, so nothing is locked.
//!
//! snapshot flattens the dirty viewport rows into a caller-provided
//! direct ByteBuffer (little-endian) so Kotlin can repaint without any
//! per-cell JNI calls. See GhosttyTerminalView.kt for the reader side.
//!
//! Buffer layout:
//!   header: 13 x i32
//!     [0] version (2)          [1] dirty kind (0 none / 1 partial / 2 full)
//!     [2] cols                 [3] rows
//!     [4] default bg (ARGB)    [5] default fg (ARGB)
//!     [6] cursor x or -1       [7] cursor y or -1
//!     [8] cursor style         [9] cursor visible (0/1)
//!     [10] row record count    [11] cursor blinking (0/1)
//!     [12] cursor color (ARGB) or 0 when unconfigured
//!   row record:
//!     i32 rowIndex, i32 selStartX or -1, i32 selEndX or -1, i32 textUnits
//!     cells[cols] x 16 bytes:
//!       i32 fg (ARGB), i32 bg (ARGB),
//!       u16 textOffset (UTF-16 units into row blob), u16 textLen (units),
//!       u16 flags, u16 pad
//!     u16 text[textUnits], zero-padded to a 4-byte boundary

const std = @import("std");
const c = @import("c");
const vt = @import("ghostty-vt");

const alloc = std.heap.c_allocator;
const log_tag = "herdr-terminal";

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

const header_bytes = 13 * 4;
const row_header_bytes = 4 * 4;
const cell_record_bytes = 16;
// Cap per-cell grapheme text; anything longer is truncated (degenerate input).
const max_cell_text_units = 32;

// Default boundaries for word selection, as ghostty's own
// (terminal/selection_codepoints.zig, which the module doesn't export).
const word_boundaries = [_]u21{ 0, ' ', '\t', '\'', '"', '│', '`', '|', ':', ';', ',', '(', ')', '[', ']', '{', '}', '<', '>', '$' };

const Session = struct {
    io: vt.TinyIo = .init,
    term: vt.Terminal,
    stream: vt.TerminalStream,
    render: vt.RenderState = .empty,
    // Query responses emitted by the terminal while writing; handed back to
    // the caller after each write, for the PTY.
    pty_out: std.ArrayList(u8) = .empty,

    fn fromHandler(handler: *vt.TerminalStream.Handler) *Session {
        const stream: *vt.TerminalStream = @fieldParentPtr("handler", handler);
        return @alignCast(@fieldParentPtr("stream", stream));
    }

    fn writePty(handler: *vt.TerminalStream.Handler, data: []const u8) void {
        const self = fromHandler(handler);
        self.pty_out.appendSlice(alloc, data) catch {};
    }

    // Answered like the C API does: with the default attributes.
    fn deviceAttributes(_: *vt.TerminalStream.Handler) vt.device_attributes.Attributes {
        return .{};
    }

    // Selection changes don't reliably mark rows dirty for our snapshot; force a
    // full re-emit so the per-row selection ranges refresh everywhere.
    fn dirtyAll(self: *Session) void {
        self.render.dirty = .full;
    }

    fn select(self: *Session, sel: vt.Selection) bool {
        self.term.screens.active.select(sel) catch return false;
        self.dirtyAll();
        return true;
    }

    fn viewportPin(self: *Session, col: c.jint, row: c.jint) ?vt.Pin {
        if (col < 0 or row < 0) return null;
        return self.term.screens.active.pages.pin(.{ .viewport = .{
            .x = @intCast(col),
            .y = @intCast(row),
        } });
    }

    fn modeOn(self: *Session, comptime mode: vt.Mode) bool {
        return self.term.modes.get(mode);
    }
};

fn fromHandle(handle: c.jlong) ?*Session {
    if (handle == 0) return null;
    return @ptrFromInt(@as(usize, @intCast(handle)));
}

fn logWarn(comptime fmt: []const u8, args: anytype) void {
    var buf: [256]u8 = undefined;
    const msg = std.fmt.bufPrint(buf[0 .. buf.len - 1], fmt, args) catch return;
    buf[msg.len] = 0;
    _ = c.__android_log_write(c.ANDROID_LOG_WARN, log_tag, &buf);
}

fn argb(rgb: vt.color.RGB) i32 {
    return @bitCast(0xFF000000 | (@as(u32, rgb.r) << 16) | (@as(u32, rgb.g) << 8) | rgb.b);
}

// ---- JNI helpers -----------------------------------------------------------

const Env = [*c]c.JNIEnv;

fn jni(env: Env) *const c.struct_JNINativeInterface {
    return env.*;
}

/// The array's bytes, copied out (JNI may pin or copy either way). Free with alloc.
fn bytesOf(env: Env, array: c.jbyteArray) ?[]u8 {
    if (array == null) return null;
    const len: usize = @intCast(jni(env).GetArrayLength.?(env, array));
    const out = alloc.alloc(u8, len) catch return null;
    if (len > 0) jni(env).GetByteArrayRegion.?(env, array, 0, @intCast(len), @ptrCast(out.ptr));
    return out;
}

fn newBytes(env: Env, data: []const u8) c.jbyteArray {
    const out = jni(env).NewByteArray.?(env, @intCast(data.len));
    if (out == null) return null;
    if (data.len > 0) jni(env).SetByteArrayRegion.?(env, out, 0, @intCast(data.len), @ptrCast(data.ptr));
    return out;
}

/// Null for no bytes, which Kotlin reads as "nothing to send".
fn newBytesOrNull(env: Env, data: []const u8) c.jbyteArray {
    return if (data.len == 0) null else newBytes(env, data);
}

/// Parses a color in ghostty config syntax (hex with or without '#', X11
/// names, rgb:/rgbi: forms). Null on a null or invalid string.
fn colorOf(env: Env, str: c.jstring) ?vt.color.RGB {
    if (str == null) return null;
    const utf = jni(env).GetStringUTFChars.?(env, str, null);
    if (utf == null) return null;
    defer jni(env).ReleaseStringUTFChars.?(env, str, utf);
    const text = std.mem.span(utf);
    return vt.color.RGB.parse(text) catch {
        logWarn("ignoring invalid theme color: {s}", .{text});
        return null;
    };
}

// ---- Keys --------------------------------------------------------------------

fn keyOffset(base: vt.input.Key, offset: i32) vt.input.Key {
    return @fromBackingInt(@intCast(@backingInt(base) + offset));
}

fn mapKey(code: i32) vt.input.Key {
    if (code >= c.AKEYCODE_A and code <= c.AKEYCODE_Z) return keyOffset(.key_a, code - c.AKEYCODE_A);
    if (code >= c.AKEYCODE_0 and code <= c.AKEYCODE_9) return keyOffset(.digit_0, code - c.AKEYCODE_0);
    if (code >= c.AKEYCODE_NUMPAD_0 and code <= c.AKEYCODE_NUMPAD_9) return keyOffset(.numpad_0, code - c.AKEYCODE_NUMPAD_0);
    if (code >= c.AKEYCODE_F1 and code <= c.AKEYCODE_F12) return keyOffset(.f1, code - c.AKEYCODE_F1);
    return switch (code) {
        c.AKEYCODE_GRAVE => .backquote,
        c.AKEYCODE_BACKSLASH => .backslash,
        c.AKEYCODE_LEFT_BRACKET => .bracket_left,
        c.AKEYCODE_RIGHT_BRACKET => .bracket_right,
        c.AKEYCODE_COMMA => .comma,
        c.AKEYCODE_EQUALS => .equal,
        c.AKEYCODE_MINUS => .minus,
        c.AKEYCODE_PERIOD => .period,
        c.AKEYCODE_APOSTROPHE => .quote,
        c.AKEYCODE_SEMICOLON => .semicolon,
        c.AKEYCODE_SLASH => .slash,
        c.AKEYCODE_ALT_LEFT => .alt_left,
        c.AKEYCODE_ALT_RIGHT => .alt_right,
        c.AKEYCODE_DEL => .backspace,
        c.AKEYCODE_CAPS_LOCK => .caps_lock,
        c.AKEYCODE_CTRL_LEFT => .control_left,
        c.AKEYCODE_CTRL_RIGHT => .control_right,
        c.AKEYCODE_ENTER => .enter,
        c.AKEYCODE_META_LEFT => .meta_left,
        c.AKEYCODE_META_RIGHT => .meta_right,
        c.AKEYCODE_SHIFT_LEFT => .shift_left,
        c.AKEYCODE_SHIFT_RIGHT => .shift_right,
        c.AKEYCODE_SPACE => .space,
        c.AKEYCODE_TAB => .tab,
        c.AKEYCODE_FORWARD_DEL => .delete,
        c.AKEYCODE_MOVE_END => .end,
        c.AKEYCODE_MOVE_HOME => .home,
        c.AKEYCODE_INSERT => .insert,
        c.AKEYCODE_PAGE_DOWN => .page_down,
        c.AKEYCODE_PAGE_UP => .page_up,
        c.AKEYCODE_DPAD_DOWN => .arrow_down,
        c.AKEYCODE_DPAD_LEFT => .arrow_left,
        c.AKEYCODE_DPAD_RIGHT => .arrow_right,
        c.AKEYCODE_DPAD_UP => .arrow_up,
        c.AKEYCODE_NUM_LOCK => .num_lock,
        c.AKEYCODE_NUMPAD_ADD => .numpad_add,
        c.AKEYCODE_NUMPAD_COMMA => .numpad_comma,
        c.AKEYCODE_NUMPAD_DOT => .numpad_decimal,
        c.AKEYCODE_NUMPAD_DIVIDE => .numpad_divide,
        c.AKEYCODE_NUMPAD_ENTER => .numpad_enter,
        c.AKEYCODE_NUMPAD_EQUALS => .numpad_equal,
        c.AKEYCODE_NUMPAD_MULTIPLY => .numpad_multiply,
        c.AKEYCODE_NUMPAD_SUBTRACT => .numpad_subtract,
        c.AKEYCODE_ESCAPE => .escape,
        else => .unidentified,
    };
}

fn mapMods(meta: i32) vt.input.KeyMods {
    const on = struct {
        fn f(m: i32, bit: c_int) bool {
            return m & bit != 0;
        }
    }.f;
    var mods: vt.input.KeyMods = .{
        .shift = on(meta, c.AMETA_SHIFT_ON),
        .ctrl = on(meta, c.AMETA_CTRL_ON),
        .alt = on(meta, c.AMETA_ALT_ON),
        .super = on(meta, c.AMETA_META_ON),
        .caps_lock = on(meta, c.AMETA_CAPS_LOCK_ON),
        .num_lock = on(meta, c.AMETA_NUM_LOCK_ON),
    };
    if (on(meta, c.AMETA_SHIFT_RIGHT_ON)) mods.sides.shift = .right;
    if (on(meta, c.AMETA_CTRL_RIGHT_ON)) mods.sides.ctrl = .right;
    if (on(meta, c.AMETA_ALT_RIGHT_ON)) mods.sides.alt = .right;
    if (on(meta, c.AMETA_META_RIGHT_ON)) mods.sides.super = .right;
    return mods;
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

// ---- Natives (registered in JNI_OnLoad below) ---------------------------------

fn create(
    _: Env,
    _: c.jobject,
    cols: c.jint,
    rows: c.jint,
    max_scrollback: c.jlong,
) callconv(.c) c.jlong {
    const session = newSession(cols, rows, max_scrollback) catch |err| {
        logWarn("failed to create terminal session: {s}", .{@errorName(err)});
        return 0;
    };
    return @intCast(@intFromPtr(session));
}

fn newSession(cols: c.jint, rows: c.jint, max_scrollback: c.jlong) !*Session {
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
    session.stream = .init(.{ .allocator = alloc, .handler = handler });

    t.colors.background.default = .{ .r = 0x00, .g = 0x00, .b = 0x00 };
    t.colors.foreground.default = .{ .r = 0xFF, .g = 0xFF, .b = 0xFF };
    t.flags.dirty.palette = true;
    // libghostty's default is a steady cursor; blink by default like the
    // terminal apps users come from. DECSCUSR still overrides.
    t.setDefaultCursorBlink(true);
    return session;
}

fn free(_: Env, _: c.jobject, handle: c.jlong) callconv(.c) void {
    const session = fromHandle(handle) orelse return;
    session.render.deinit(alloc);
    session.stream.deinit();
    session.term.deinit(alloc);
    session.pty_out.deinit(alloc);
    alloc.destroy(session);
}

fn write(
    env: Env,
    _: c.jobject,
    handle: c.jlong,
    data: c.jbyteArray,
) callconv(.c) c.jbyteArray {
    const session = fromHandle(handle) orelse return null;
    if (data == null) return null;
    session.pty_out.clearRetainingCapacity();
    const len = jni(env).GetArrayLength.?(env, data);
    if (len > 0) {
        const bytes = jni(env).GetByteArrayElements.?(env, data, null);
        if (bytes == null) return null;
        const slice: [*]const u8 = @ptrCast(bytes);
        session.stream.nextSlice(slice[0..@intCast(len)]);
        jni(env).ReleaseByteArrayElements.?(env, data, bytes, c.JNI_ABORT);
    }
    return newBytesOrNull(env, session.pty_out.items);
}

fn resize(
    _: Env,
    _: c.jobject,
    handle: c.jlong,
    cols: c.jint,
    rows: c.jint,
    cell_width_px: c.jint,
    cell_height_px: c.jint,
) callconv(.c) void {
    const session = fromHandle(handle) orelse return;
    if (cols <= 0 or rows <= 0) return;
    session.stream.handler.resize(.{
        .cols = @intCast(cols),
        .rows = @intCast(rows),
        .cell_size_px = .{
            .width = @intCast(@max(cell_width_px, 0)),
            .height = @intCast(@max(cell_height_px, 0)),
        },
    }) catch return;
    // The renderer discards its bitmap on every resize; a same-grid resize
    // (font-size change landing on identical cols/rows) would otherwise leave
    // no dirty rows to repaint it with.
    session.dirtyAll();
}

// Sets the terminal's default colors (theme). Nulls clear back to the
// built-in defaults; invalid color strings are logged and skipped, as ghostty
// does for bad config values. The palette overrides entries by index on top
// of ghostty's default 256-color palette; OSC 4 overrides set by the running
// program are kept by the terminal.
fn setTheme(
    env: Env,
    _: c.jobject,
    handle: c.jlong,
    foreground: c.jstring,
    background: c.jstring,
    cursor: c.jstring,
    palette: c.jobjectArray,
) callconv(.c) void {
    const session = fromHandle(handle) orelse return;
    const t = &session.term;
    t.colors.foreground.default = colorOf(env, foreground);
    t.colors.background.default = colorOf(env, background);
    t.colors.cursor.default = colorOf(env, cursor);

    var table: vt.color.Palette = vt.color.default;
    if (palette != null) {
        const count: usize = @min(@as(usize, @intCast(jni(env).GetArrayLength.?(env, palette))), 256);
        for (0..count) |i| {
            const entry: c.jstring = @ptrCast(jni(env).GetObjectArrayElement.?(env, palette, @intCast(i)));
            if (colorOf(env, entry)) |rgb| table[i] = rgb;
            if (entry != null) jni(env).DeleteLocalRef.?(env, entry);
        }
    }
    t.colors.palette.changeDefault(t.gpa(), table) catch {};
    t.flags.dirty.palette = true;
    session.dirtyAll();
}

// Parses a color in ghostty config syntax to ARGB, or 0 when invalid (real
// colors always carry 0xFF alpha, so 0 is never a valid result).
fn parseColor(env: Env, _: c.jobject, color: c.jstring) callconv(.c) c.jint {
    return if (colorOf(env, color)) |rgb| argb(rgb) else 0;
}

fn scroll(_: Env, _: c.jobject, handle: c.jlong, delta_rows: c.jint) callconv(.c) void {
    const session = fromHandle(handle) orelse return;
    session.term.scrollViewport(.{ .delta = delta_rows });
}

fn scrollToBottom(_: Env, _: c.jobject, handle: c.jlong) callconv(.c) void {
    const session = fromHandle(handle) orelse return;
    session.term.scrollViewport(.bottom);
}

// The viewport scrollbar as [total, offset, len] rows.
fn scrollbar(env: Env, _: c.jobject, handle: c.jlong) callconv(.c) c.jlongArray {
    const session = fromHandle(handle) orelse return null;
    const bar = session.term.screens.active.pages.scrollbar();
    const out = jni(env).NewLongArray.?(env, 3);
    if (out == null) return null;
    const values = [3]c.jlong{ @intCast(bar.total), @intCast(bar.offset), @intCast(bar.len) };
    jni(env).SetLongArrayRegion.?(env, out, 0, 3, &values);
    return out;
}

fn snapshot(
    env: Env,
    _: c.jobject,
    handle: c.jlong,
    buffer: c.jobject,
) callconv(.c) c.jint {
    const session = fromHandle(handle) orelse return -1;
    if (buffer == null) return -1;
    const base_ptr: ?[*]u8 = @ptrCast(jni(env).GetDirectBufferAddress.?(env, buffer));
    const base = base_ptr orelse return -1;
    const cap: usize = @intCast(jni(env).GetDirectBufferCapacity.?(env, buffer));
    if (cap < header_bytes) return -1;
    return fillSnapshot(session, base[0..cap]) catch -1;
}

fn putI32(buf: []u8, at: usize, v: i32) void {
    std.mem.writeInt(i32, buf[at..][0..4], v, .little);
}

fn putU16(buf: []u8, at: usize, v: u16) void {
    std.mem.writeInt(u16, buf[at..][0..2], v, .little);
}

fn fillSnapshot(session: *Session, buf: []u8) !c.jint {
    const state = &session.render;
    try state.update(alloc, &session.term);

    const dirty = state.dirty;
    const cols: usize = state.cols;
    const cursor = state.cursor;
    const cursor_color: i32 = if (session.term.colors.cursor.get()) |rgb| argb(rgb) else 0;
    const visual: i32 = switch (cursor.visual_style) {
        .bar => 0,
        .block => 1,
        .underline => 2,
        .block_hollow => 3,
    };

    const header = [_]i32{
        2,
        @backingInt(dirty),
        @intCast(cols),
        @intCast(state.rows),
        argb(state.colors.background),
        argb(state.colors.foreground),
        if (cursor.viewport) |vp| @intCast(vp.x) else -1,
        if (cursor.viewport) |vp| @intCast(vp.y) else -1,
        if (cursor.viewport != null) visual else 1,
        @intFromBool(cursor.visible),
        0, // row record count, set below
        @intFromBool(cursor.blinking),
        cursor_color,
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
            putU16(buf, record + 14, 0);
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

fn selectWord(
    _: Env,
    _: c.jobject,
    handle: c.jlong,
    col: c.jint,
    row: c.jint,
) callconv(.c) c.jboolean {
    const session = fromHandle(handle) orelse return c.JNI_FALSE;
    const pin = session.viewportPin(col, row) orelse return c.JNI_FALSE;
    const sel = session.term.screens.active.selectWord(pin, &word_boundaries) orelse return c.JNI_FALSE;
    return @intFromBool(session.select(sel));
}

fn selectAll(_: Env, _: c.jobject, handle: c.jlong) callconv(.c) c.jboolean {
    const session = fromHandle(handle) orelse return c.JNI_FALSE;
    const sel = session.term.screens.active.selectAll() orelse return c.JNI_FALSE;
    return @intFromBool(session.select(sel));
}

fn setSelection(
    _: Env,
    _: c.jobject,
    handle: c.jlong,
    anchor_col: c.jint,
    anchor_row: c.jint,
    col: c.jint,
    row: c.jint,
) callconv(.c) c.jboolean {
    const session = fromHandle(handle) orelse return c.JNI_FALSE;
    const start = session.viewportPin(anchor_col, anchor_row) orelse return c.JNI_FALSE;
    const end = session.viewportPin(col, row) orelse return c.JNI_FALSE;
    return @intFromBool(session.select(.init(start, end, false)));
}

fn clearSelection(_: Env, _: c.jobject, handle: c.jlong) callconv(.c) void {
    const session = fromHandle(handle) orelse return;
    session.term.screens.active.clearSelection();
    session.dirtyAll();
}

// The selection as UTF-8 (plain, unwrapped, trimmed: ghostty's copy
// semantics), or null when there's none.
fn selectionText(env: Env, _: c.jobject, handle: c.jlong) callconv(.c) c.jbyteArray {
    const session = fromHandle(handle) orelse return null;
    const sel = session.term.screens.active.selection orelse return null;
    var formatter: vt.formatter.TerminalFormatter = .init(&session.term, .{
        .emit = .plain,
        .unwrap = true,
        .trim = true,
    });
    formatter.content = .{ .selection = sel };
    var out: std.Io.Writer.Allocating = .init(alloc);
    defer out.deinit();
    formatter.format(&out.writer) catch return null;
    return newBytes(env, out.written());
}

/// The link at a viewport cell: its OSC 8 hyperlink, or else the http(s) URL
/// written across the cell's line (soft wraps included); null when neither.
fn linkAt(
    env: Env,
    _: c.jobject,
    handle: c.jlong,
    col: c.jint,
    row: c.jint,
) callconv(.c) c.jbyteArray {
    const session = fromHandle(handle) orelse return null;
    var pin = session.viewportPin(col, row) orelse return null;
    // A wide character's right half belongs to its left.
    if (pin.rowAndCell().cell.wide == .spacer_tail and pin.x > 0) pin.x -= 1;
    if (hyperlinkUri(pin)) |uri| return newBytes(env, uri);

    const screen = session.term.screens.active;
    const sel = screen.selectLine(.{
        .pin = pin,
        .whitespace = null,
        .semantic_prompt_boundary = false,
    }) orelse return null;
    var line = screen.selectionStringMap(alloc, .{ .sel = sel, .trim = false }) catch return null;
    defer line.deinit(alloc);
    for (0..line.map.count()) |i| {
        const at = line.map.get(i) orelse continue;
        if (at.node != pin.node or at.x != pin.x or at.y != pin.y) continue;
        const url = urlAround(line.string, i) orelse return null;
        return newBytes(env, url);
    }
    return null;
}

fn hyperlinkUri(pin: vt.Pin) ?[]const u8 {
    const page = pin.node.page();
    const cell = page.getRowAndCell(pin.x, pin.y).cell;
    if (!cell.hyperlink) return null;
    const id = page.lookupHyperlink(cell) orelse return null;
    return page.hyperlink_set.get(page.memory, id).uri.slice(page.memory);
}

/// The http(s) URL in `text` that covers byte `at`, less the punctuation
/// that usually follows one in prose (a period, an unmatched parenthesis).
fn urlAround(text: []const u8, at: usize) ?[]const u8 {
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

fn isPasteSafe(env: Env, _: c.jobject, data: c.jbyteArray) callconv(.c) c.jboolean {
    const bytes = bytesOf(env, data) orelse return c.JNI_TRUE;
    defer alloc.free(bytes);
    return @intFromBool(vt.input.isSafePaste(bytes));
}

// Clipboard text for the PTY: unsafe control bytes stripped, and wrapped in
// bracketed-paste sequences when the program has mode 2004 set.
fn encodePaste(
    env: Env,
    _: c.jobject,
    handle: c.jlong,
    data: c.jbyteArray,
) callconv(.c) c.jbyteArray {
    const session = fromHandle(handle) orelse return null;
    const bytes = bytesOf(env, data) orelse return null;
    defer alloc.free(bytes);
    const parts = vt.input.encodePaste(bytes, .{ .bracketed = session.modeOn(.bracketed_paste) });
    const joined = std.mem.concat(alloc, u8, &parts) catch return null;
    defer alloc.free(joined);
    return newBytes(env, joined);
}

fn encodeKey(
    env: Env,
    _: c.jobject,
    handle: c.jlong,
    key_code: c.jint,
    action: c.jint,
    meta_state: c.jint,
    unshifted_codepoint: c.jint,
    utf8: c.jbyteArray,
) callconv(.c) c.jbyteArray {
    const session = fromHandle(handle) orelse return null;
    const text = bytesOf(env, utf8);
    defer if (text) |t| alloc.free(t);

    // Terminal modes (DECCKM, Kitty flags, ...) change as programs run; read
    // them before every encode so arrows and keypad follow the active mode.
    const opts: vt.input.KeyEncodeOptions = .fromTerminal(&session.term);
    const event: vt.input.KeyEvent = .{
        .action = switch (action) {
            0 => .release,
            2 => .repeat,
            else => .press,
        },
        .key = mapKey(key_code),
        .mods = mapMods(meta_state),
        .utf8 = text orelse "",
        .unshifted_codepoint = std.math.cast(u21, unshifted_codepoint) orelse 0,
    };
    var out: std.Io.Writer.Allocating = .init(alloc);
    defer out.deinit();
    vt.input.encodeKey(&out.writer, event, opts) catch return null;
    return newBytesOrNull(env, out.written());
}

// What a vertical swipe means to the program in front: 1 when it tracks the
// mouse (send wheel events), 2 on the alternate screen without tracking (send
// arrow keys, like xterm's alternate scroll and Termux), 0 otherwise (scroll
// this view's scrollback).
fn scrollTarget(_: Env, _: c.jobject, handle: c.jlong) callconv(.c) c.jint {
    const session = fromHandle(handle) orelse return 0;
    if (session.modeOn(.mouse_event_x10) or session.modeOn(.mouse_event_normal) or
        session.modeOn(.mouse_event_button) or session.modeOn(.mouse_event_any)) return 1;
    if (session.modeOn(.alt_screen_save_cursor_clear_enter) or session.modeOn(.alt_screen) or
        session.modeOn(.alt_screen_legacy)) return 2;
    return 0;
}

// `count` wheel notches (up or down) at a pixel position, encoded for the
// program's mouse mode and format; null when it encodes to nothing.
fn encodeWheel(
    env: Env,
    _: c.jobject,
    handle: c.jlong,
    up: c.jboolean,
    count: c.jint,
    x: c.jfloat,
    y: c.jfloat,
    screen_width: c.jint,
    screen_height: c.jint,
    cell_width: c.jint,
    cell_height: c.jint,
) callconv(.c) c.jbyteArray {
    const session = fromHandle(handle) orelse return null;
    if (count <= 0 or cell_width <= 0 or cell_height <= 0) return null;
    const t = &session.term;
    const opts: vt.input.MouseEncodeOptions = .{
        .event = t.flags.mouse_event,
        .format = t.flags.mouse_format,
        .size = .{
            .screen = .{ .width = @intCast(@max(screen_width, 0)), .height = @intCast(@max(screen_height, 0)) },
            .cell = .{ .width = @intCast(cell_width), .height = @intCast(cell_height) },
            .padding = .{},
        },
    };
    const event: vt.input.MouseEncodeEvent = .{
        .action = .press,
        .button = if (up != 0) .four else .five,
        .pos = .{ .x = x, .y = y },
    };
    var out: std.Io.Writer.Allocating = .init(alloc);
    defer out.deinit();
    for (0..@intCast(count)) |_| vt.input.encodeMouse(&out.writer, event, opts) catch break;
    return newBytesOrNull(env, out.written());
}

// ---- Registration ------------------------------------------------------------

/// GhosttyVt's external funs, by name and JNI signature.
const methods = [_]c.JNINativeMethod{
    method("create", "(IIJ)J", &create),
    method("free", "(J)V", &free),
    method("write", "(J[B)[B", &write),
    method("resize", "(JIIII)V", &resize),
    method("setTheme", "(JLjava/lang/String;Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;)V", &setTheme),
    method("parseColor", "(Ljava/lang/String;)I", &parseColor),
    method("scroll", "(JI)V", &scroll),
    method("scrollToBottom", "(J)V", &scrollToBottom),
    method("scrollTarget", "(J)I", &scrollTarget),
    method("encodeWheel", "(JZIFFIIII)[B", &encodeWheel),
    method("scrollbar", "(J)[J", &scrollbar),
    method("snapshot", "(JLjava/nio/ByteBuffer;)I", &snapshot),
    method("encodeKey", "(JIIII[B)[B", &encodeKey),
    method("selectWord", "(JII)Z", &selectWord),
    method("selectAll", "(J)Z", &selectAll),
    method("setSelection", "(JIIII)Z", &setSelection),
    method("clearSelection", "(J)V", &clearSelection),
    method("selectionText", "(J)[B", &selectionText),
    method("linkAt", "(JII)[B", &linkAt),
    method("isPasteSafe", "([B)Z", &isPasteSafe),
    method("encodePaste", "(J[B)[B", &encodePaste),
};

fn method(comptime name: [:0]const u8, comptime signature: [:0]const u8, func: *const anyopaque) c.JNINativeMethod {
    return .{ .name = @constCast(name.ptr), .signature = @constCast(signature.ptr), .fnPtr = @constCast(func) };
}

/// Hands Kotlin's GhosttyVt its natives when the library loads, so the
/// functions above keep short names instead of JNI's `Java_<package>_<class>_<method>`.
export fn JNI_OnLoad(vm: *c.JavaVM, _: ?*anyopaque) callconv(.c) c.jint {
    var env: Env = null;
    if (vm.*.*.GetEnv.?(vm, @ptrCast(&env), c.JNI_VERSION_1_6) != c.JNI_OK) return c.JNI_ERR;
    const class = jni(env).FindClass.?(env, "dev/herdr/terminal/GhosttyVt");
    if (class == null) return c.JNI_ERR;
    if (jni(env).RegisterNatives.?(env, class, &methods, methods.len) != c.JNI_OK) return c.JNI_ERR;
    return c.JNI_VERSION_1_6;
}
