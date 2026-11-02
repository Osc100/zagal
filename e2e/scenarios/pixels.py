"""Checks on e2e screenshots: pixels.py <check> <png>...

Each check prints what it measured and exits non-zero when the screenshot
isn't what the scenario expects. Only the standard library: PNGs are decoded
here.
"""

import struct
import sys
import zlib


def load(path):
    """A PNG (8-bit RGB or RGBA, as screencap writes) as (width, height, rows of (r, g, b))."""
    with open(path, "rb") as f:
        data = f.read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", f"{path}: not a PNG"
    at, idat = 8, b""
    while at < len(data):
        length, kind = struct.unpack(">I4s", data[at : at + 8])
        body = data[at + 8 : at + 8 + length]
        if kind == b"IHDR":
            width, height, depth, color = struct.unpack(">IIBB", body[:10])
            assert depth == 8 and color in (2, 6), f"{path}: unsupported PNG"
            channels = 4 if color == 6 else 3
        elif kind == b"IDAT":
            idat += body
        at += 12 + length
    raw = zlib.decompress(idat)
    stride = width * channels
    rows, prev = [], bytearray(stride)
    for y in range(height):
        start = y * (stride + 1)
        kind, line = raw[start], bytearray(raw[start + 1 : start + 1 + stride])
        for i in range(stride):
            left = line[i - channels] if i >= channels else 0
            up = prev[i]
            corner = prev[i - channels] if i >= channels else 0
            if kind == 1:
                line[i] = (line[i] + left) & 0xFF
            elif kind == 2:
                line[i] = (line[i] + up) & 0xFF
            elif kind == 3:
                line[i] = (line[i] + (left + up) // 2) & 0xFF
            elif kind == 4:
                p = left + up - corner
                pa, pb, pc = abs(p - left), abs(p - up), abs(p - corner)
                pred = left if pa <= pb and pa <= pc else up if pb <= pc else corner
                line[i] = (line[i] + pred) & 0xFF
        rows.append([tuple(line[x * channels : x * channels + 3]) for x in range(width)])
        prev = line
    return width, height, rows


# ANSI 41-46 in the theme's colors; a band pixel is any colored one (the
# terminal background and the chrome around it are near grey).
def colored(pixel):
    r, g, b = pixel
    return max(r, g, b) - min(r, g, b) > 25


def band_rows(path, across=None):
    """The screen rows (y) that are bands across the grid, and the grid's x
    range: `across`, else the columns colored in most rows."""
    width, _, rows = load(path)
    if across:
        left, right = across
    else:
        columns = [sum(colored(row[x]) for row in rows[::8]) for x in range(width)]
        most = max(columns)
        xs = [x for x, n in enumerate(columns) if n > most * 0.5]
        left, right = xs[0], xs[-1]
    band = [y for y, row in enumerate(rows) if sum(colored(p) for p in row[left:right:4]) > (right - left) / 4 * 0.95]
    return band, (left, right)


def bands_whole(front, back):
    """Every row of bands in front is back, as far as the screen shows the grid
    (the keyboard may cover some): from the first band row to the last, no row
    went blank. Front is taken with nothing over the grid."""
    before, across = band_rows(front)
    after, _ = band_rows(back, across)
    if not after:
        print("back: no bands at all")
        return False
    gaps = [y for y in range(after[0], after[-1] + 1) if y not in set(after)]
    print(f"band rows: front {len(before)}, back {len(after)}; blank lines among them: {len(gaps)}")
    # A row lost is a whole cell tall (tens of pixels); the bands themselves meet exactly.
    return len(after) > 100 and len(gaps) < 4


CHECKS = {"bands-whole": bands_whole}

if __name__ == "__main__":
    check, *paths = sys.argv[1:]
    sys.exit(0 if CHECKS[check](*paths) else 1)
