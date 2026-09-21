#!/usr/bin/env python3
"""Deterministic fixture corpus generator — CAMSCAN-003 (Worker 2).

Regenerates the ENTIRE lab/fixtures/ corpus from code + fixed seed:

    documents/    7 static phone-camera-like frames (synthetic pages)
    sequences/    3 scripted camera-motion frame sets
    manifest.json content-addressed manifest (validates against
                  lab/fixtures/fixture.schema.json)

Determinism contract
--------------------
* Every random draw comes from random.Random seeded with
  MASTER_SEED ^ crc32(fixture_id) — no wall-clock, no hash(), no threading.
* generated_utc is the fixed CORPUS_EPOCH (NOT wall-clock) so regeneration is
  byte-identical, including manifest.json itself.
* PNG/JPEG bytes carry no timestamps (Pillow writes neither by default).
* --check regenerates the whole corpus into a temp dir and verifies every
  manifest entry (path, bytes, sha256) plus byte-equality of manifest.json.

Conventions
-----------
* Frames are 1080x1920 portrait (>=1080p class) — phone-camera-like.
* page_quad_px point order: [TL, TR, BR, BL]; x right, y down, frame pixels.
* Documents are PNG (lossless — they are the OCR oracles); sequences are
  JPEG quality 87 (camera-like motion frames).
* ground_truth.text_content is the EXACT text rendered on the page.
* documents/multi-page/ is an ordered set: page 1..3 share one camera
  position (fixed mount, repeatable placement) so one quad is truth for all.
* Sequences record per-frame index + a machine-parseable transform string:
  pan(dx,dy) | shake(dx,dy,theta,scale) | rotate(theta,scale).
  document-pan intentionally lets the page partially exit the frame at the
  extremes (detection tracking behavior).

Rendering environment: Pillow >= 9 (developed on 11.3.0) + FreeType via
ImageFont, DejaVu fonts (system dir, matplotlib bundle as fallback). The
same Pillow/FreeType versions reproduce byte-identical output; the manifest
sha256s remain the cross-environment integrity truth.

Usage:
    python3 lab/fixtures/generate.py           # regenerate corpus in place
    python3 lab/fixtures/generate.py --check   # hash-verify against manifest
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import random
import sys
import tempfile
import zlib
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parents[2]
FIXTURES = ROOT / "lab" / "fixtures"

MASTER_SEED = 20260921
CORPUS_EPOCH = "2026-09-21T00:00:00Z"  # fixed corpus epoch (determinism)
GENERATOR_COMMAND = "python3 lab/fixtures/generate.py"

FRAME_W, FRAME_H = 1080, 1920  # portrait, phone-camera-like, >=1080p class
BLEED = 12  # px of paper-color bleed around each page canvas

DOC_FORMAT = "png"
SEQ_FORMAT = "jpg"
JPEG_QUALITY = 87

INK = (28, 28, 34)
PAPER = (250, 249, 246)
RECEIPT_PAPER = (242, 240, 234)
CARD_PAPER = (252, 252, 250)
NOTEBOOK_PAPER = (250, 248, 242)
NOTEBOOK_INK = (55, 58, 105)

_FONT_DIR_SYSTEM = Path("/usr/share/fonts/truetype/dejavu")


def _mpl_font_dir() -> Path:
    try:
        import matplotlib

        return Path(matplotlib.get_data_path()) / "fonts" / "ttf"
    except ImportError:  # matplotlib is an optional font fallback, not a dependency
        return Path("/nonexistent/matplotlib-fonts")


FONT_CANDIDATES: dict[str, list[Path]] = {
    "serif": [_FONT_DIR_SYSTEM / "DejaVuSerif.ttf",
              _mpl_font_dir() / "DejaVuSerif.ttf"],
    "serif-bold": [_FONT_DIR_SYSTEM / "DejaVuSerif-Bold.ttf",
                   _mpl_font_dir() / "DejaVuSerif-Bold.ttf"],
    "sans": [_FONT_DIR_SYSTEM / "DejaVuSans.ttf",
             _mpl_font_dir() / "DejaVuSans.ttf"],
    "sans-bold": [_FONT_DIR_SYSTEM / "DejaVuSans-Bold.ttf",
                  _mpl_font_dir() / "DejaVuSans-Bold.ttf"],
    "mono": [_FONT_DIR_SYSTEM / "DejaVuSansMono.ttf",
             _mpl_font_dir() / "DejaVuSansMono.ttf"],
    "hand": [_FONT_DIR_SYSTEM / "DejaVuSans-Oblique.ttf",
             _mpl_font_dir() / "DejaVuSans-Oblique.ttf"],
}

_font_cache: dict[tuple[str, int], ImageFont.FreeTypeFont] = {}


def load_font(style: str, size: int) -> ImageFont.FreeTypeFont:
    key = (style, size)
    if key not in _font_cache:
        for cand in FONT_CANDIDATES[style]:
            if cand.exists():
                _font_cache[key] = ImageFont.truetype(str(cand), size)
                break
        else:
            tried = ", ".join(str(p) for p in FONT_CANDIDATES[style])
            raise SystemExit(f"font style {style!r} not found (tried: {tried})")
    return _font_cache[key]


# --------------------------------------------------------------------------
# Deterministic linear algebra: 8x8 solve + homography (dst -> src)
# --------------------------------------------------------------------------

def _solve(A: list[list[float]], b: list[float]) -> list[float]:
    n = len(A)
    M = [row[:] + [b[i]] for i, row in enumerate(A)]
    for col in range(n):
        piv = max(range(col, n), key=lambda r: abs(M[r][col]))
        if abs(M[piv][col]) < 1e-12:
            raise ValueError("singular matrix")
        M[col], M[piv] = M[piv], M[col]
        inv = 1.0 / M[col][col]
        for r in range(col + 1, n):
            f = M[r][col] * inv
            if f != 0.0:
                for c in range(col, n + 1):
                    M[r][c] -= f * M[col][c]
    x = [0.0] * n
    for i in range(n - 1, -1, -1):
        s = M[i][n] - sum(M[i][j] * x[j] for j in range(i + 1, n))
        x[i] = s / M[i][i]
    return x


def _homography(dst_quad, src_quad) -> tuple[float, ...]:
    """Coefficients (a..h) mapping dst (x,y) -> src (u,v):
    u = (a x + b y + c) / (g x + h y + 1),  v = (d x + e y + f) / (g x + h y + 1)
    — exactly the tuple Pillow's Image.PERSPECTIVE transform expects.
    Point order for both quads: TL, TR, BR, BL.
    """
    A: list[list[float]] = []
    b: list[float] = []
    for (x, y), (u, v) in zip(dst_quad, src_quad):
        A.append([x, y, 1, 0, 0, 0, -u * x, -u * y])
        b.append(u)
        A.append([0, 0, 0, x, y, 1, -v * x, -v * y])
        b.append(v)
    return tuple(_solve(A, b))


# --------------------------------------------------------------------------
# Geometry helpers
# --------------------------------------------------------------------------

def rotated_quad(cx: float, cy: float, w: float, h: float, deg: float):
    """Axis-aligned rect (w x h) centered at (cx, cy), rotated in-plane by deg.
    Returns [TL, TR, BR, BL], each rounded to 2 decimals (manifest == warp truth).
    """
    t = math.radians(deg)
    c, s = math.cos(t), math.sin(t)
    pts = [(-w / 2, -h / 2), (w / 2, -h / 2), (w / 2, h / 2), (-w / 2, h / 2)]
    return [(round(cx + dx * c - dy * s, 2), round(cy + dx * s + dy * c, 2))
            for dx, dy in pts]


def _dist(p, q) -> float:
    return math.hypot(p[0] - q[0], p[1] - q[1])


# --------------------------------------------------------------------------
# Page canvases (paper + content), returned WITHOUT bleed; caller pads.
# --------------------------------------------------------------------------

def page_canvas(w: int, h: int, paper_rgb, rng: random.Random, noise_amp: int = 2):
    """Paper-colored canvas (w+2*BLEED, h+2*BLEED) with faint paper grain.
    Returns (RGB image, source_quad of the paper proper)."""
    pw, ph = w + 2 * BLEED, h + 2 * BLEED
    img = Image.new("RGB", (pw, ph), paper_rgb)
    if noise_amp > 0:
        n = Image.frombytes("L", (pw, ph), rng.randbytes(pw * ph))
        n = n.point(lambda v: 128 + (v - 128) * noise_amp // 128)
        img = ImageChops.add(img, n.convert("RGB"), 1, -128)
    src_quad = [(BLEED, BLEED), (w + BLEED, BLEED),
                (w + BLEED, h + BLEED), (BLEED, h + BLEED)]
    return img, src_quad


def _check_fits(draw: ImageDraw.ImageDraw, line: str, font, max_w: int, ctx: str) -> None:
    if draw.textlength(line, font=font) > max_w:
        raise ValueError(f"{ctx}: line too wide for page ({draw.textlength(line, font=font):.0f}px "
                         f"> {max_w}px): {line!r}")


def render_text_page(w: int, h: int, rng: random.Random, *, title: str, subtitle: str,
                     body_lines: list[str], footer: str | None = None) -> Image.Image:
    """A4-style text page: bold serif title, rule, subtitle, serif body."""
    img, _ = page_canvas(w, h, PAPER, rng)
    d = ImageDraw.Draw(img)
    ml, mr = 110, 110
    content_w = w - ml - mr
    ox, oy = BLEED, BLEED

    tf = load_font("serif-bold", 58)
    _check_fits(d, title, tf, content_w, title)
    d.text((ox + ml, oy + 130), title, font=tf, fill=INK)
    ty = oy + 130 + 78
    d.line([(ox + ml, ty), (ox + w - mr, ty)], fill=(160, 158, 152), width=3)
    sf = load_font("sans", 30)
    _check_fits(d, subtitle, sf, content_w, subtitle)
    d.text((ox + ml, ty + 22), subtitle, font=sf, fill=(90, 90, 96))

    bf = load_font("serif", 36)
    y = ty + 96
    for line in body_lines:
        _check_fits(d, line, bf, content_w, "body")
        if line:
            d.text((ox + ml, y), line, font=bf, fill=INK)
        y += 54

    if footer:
        ff = load_font("sans", 30)
        _check_fits(d, footer, ff, content_w, "footer")
        fx = ox + (w - d.textlength(footer, font=ff)) / 2
        d.text((fx, oy + h - 110), footer, font=ff, fill=(90, 90, 96))
    return img


def render_receipt(w: int, h: int, rng: random.Random, lines: list[str],
                   center_first: int) -> Image.Image:
    """Narrow thermal receipt: mono font; first `center_first` lines centered."""
    img, _ = page_canvas(w, h, RECEIPT_PAPER, rng, noise_amp=3)
    d = ImageDraw.Draw(img)
    f = load_font("mono", 32)
    ml = 40
    content_w = w - 2 * ml
    y = BLEED + 56
    for i, line in enumerate(lines):
        _check_fits(d, line, f, content_w, "receipt")
        if i < center_first:
            x = BLEED + (w - d.textlength(line, font=f)) / 2
        else:
            x = BLEED + ml
        d.text((x, y), line, font=f, fill=INK)
        y += 46
    return img


def render_business_card(w: int, h: int, rng: random.Random, *, name: str, role: str,
                          company: str, contact: list[str]) -> Image.Image:
    """Small-format card: bold name, role, company, mono contact lines."""
    img, _ = page_canvas(w, h, CARD_PAPER, rng, noise_amp=2)
    d = ImageDraw.Draw(img)
    ml = 70
    content_w = w - 2 * ml
    x, y = BLEED + ml, BLEED + 80

    nf = load_font("sans-bold", 52)
    _check_fits(d, name, nf, content_w, "card name")
    d.text((x, y), name, font=nf, fill=INK)
    y += 76
    d.line([(x, y), (x + 230, y)], fill=(120, 110, 100), width=4)
    y += 26

    rf = load_font("sans", 30)
    _check_fits(d, role, rf, content_w, "card role")
    d.text((x, y), role, font=rf, fill=(95, 95, 100))
    y += 58
    cf = load_font("sans-bold", 34)
    _check_fits(d, company, cf, content_w, "card company")
    d.text((x, y), company, font=cf, fill=INK)
    y += 66

    mf = load_font("mono", 26)
    for line in contact:
        _check_fits(d, line, mf, content_w, "card contact")
        d.text((x, y), line, font=mf, fill=(70, 70, 78))
        y += 40
    return img


def render_handwritten(w: int, h: int, rng: random.Random,
                        lines: list[str]) -> Image.Image:
    """Ruled notebook page with jittered per-glyph 'handwriting' (OCR hardness)."""
    img, _ = page_canvas(w, h, NOTEBOOK_PAPER, rng, noise_amp=2)
    d = ImageDraw.Draw(img)

    # ruled lines + red margin
    rule_top, rule_step = 200, 88
    for i in range(16):
        ry = BLEED + rule_top + i * rule_step
        d.line([(BLEED + 60, ry), (BLEED + w - 60, ry)], fill=(170, 195, 225), width=2)
    mx = BLEED + 150
    d.line([(mx, BLEED + 140), (mx, BLEED + h - 120)], fill=(215, 130, 130), width=3)

    f = load_font("hand", 46)
    space_w = f.getlength(" ") * 1.6
    x0 = 190
    for li, line in enumerate(lines):
        x = float(BLEED + x0 + rng.uniform(-4, 4))
        base = BLEED + rule_top + li * rule_step
        for ch in line:
            if ch == " ":
                x += space_w
                continue
            cw = f.getlength(ch)
            bb = f.getbbox(ch)
            ch_h = bb[3] - bb[1]
            tile = Image.new("L", (int(cw) + 10, ch_h + 12), 0)
            td = ImageDraw.Draw(tile)
            td.text((5 - bb[0], 6 - bb[1]), ch, font=f, fill=255)
            tile = tile.rotate(rng.uniform(-4.0, 4.0), resample=Image.Resampling.BICUBIC)
            px = int(x + rng.uniform(-2, 2))
            py = int(base - ch_h - 6 + rng.uniform(-5, 3))
            img.paste(NOTEBOOK_INK, (px, py), tile)
            x += cw + rng.uniform(-1.5, 2.5)
    return img


# --------------------------------------------------------------------------
# Frame composition: background desk + cast shadow + perspective warp + light
# --------------------------------------------------------------------------

LIGHTING = {
    "even-daylight": dict(top=1.00, bottom=0.93, tint=(1.00, 0.995, 0.970),
                          vignette=22, noise=4),
    "neutral-indoor": dict(top=0.98, bottom=0.89, tint=(1.00, 0.990, 0.955),
                           vignette=28, noise=6),
    "dim-tungsten": dict(top=0.36, bottom=0.30, tint=(1.07, 0.980, 0.860),
                         vignette=46, noise=20),
}


def make_background(rng: random.Random) -> Image.Image:
    """Desk surface: warm base + low-frequency mottle + vignette."""
    base = (126 + rng.randint(-6, 6), 108 + rng.randint(-6, 6), 92 + rng.randint(-5, 5))
    bg = Image.new("RGB", (FRAME_W, FRAME_H), base)
    mw, mh = 54, 96
    mot = Image.frombytes("L", (mw, mh), rng.randbytes(mw * mh))
    mot = mot.resize((FRAME_W, FRAME_H), Image.Resampling.BILINEAR)
    bg = Image.blend(bg, mot.convert("RGB"), 0.12)
    return bg


def _vignette_mask(strength: int) -> Image.Image:
    vig = Image.radial_gradient("L").resize((FRAME_W, FRAME_H), Image.Resampling.BILINEAR)
    return vig.point(lambda v: v * strength // 255)


def _vertical_gradient_rgb(top: float, bottom: float, tint: tuple[float, float, float]):
    chans = []
    for c in range(3):
        t, b = top * tint[c], bottom * tint[c]
        rows = []
        for y in range(FRAME_H):
            g = t + (b - t) * y / (FRAME_H - 1)
            v = max(0, min(255, int(255 * g)))
            rows.append(bytes([v]) * FRAME_W)
        chans.append(Image.frombytes("L", (FRAME_W, FRAME_H), b"".join(rows)))
    return Image.merge("RGB", chans)


def apply_lighting(frame: Image.Image, rng: random.Random, preset: dict) -> Image.Image:
    out = ImageChops.multiply(frame, _vertical_gradient_rgb(
        preset["top"], preset["bottom"], preset["tint"]))
    out.paste((0, 0, 0), mask=_vignette_mask(preset["vignette"]))
    amp = preset["noise"]
    if amp > 0:
        planes = []
        for _ in range(3):
            n = Image.frombytes("L", (FRAME_W, FRAME_H), rng.randbytes(FRAME_W * FRAME_H))
            planes.append(n.point(lambda v: 128 + (v - 128) * amp // 128))
        out = ImageChops.add(out, Image.merge("RGB", planes), 1, -128)
    return out


def add_shadow(bg: Image.Image, quad) -> None:
    sh = Image.new("L", (FRAME_W, FRAME_H), 0)
    d = ImageDraw.Draw(sh)
    d.polygon([(x + 16, y + 20) for x, y in quad], fill=84)
    sh = sh.filter(ImageFilter.GaussianBlur(18))
    bg.paste((24, 19, 14), mask=sh)


def _preblur(page: Image.Image, page_size, quad) -> Image.Image:
    """Anti-alias pre-filter when the warp downscales the page significantly."""
    w, h = page_size
    lens = [_dist(quad[i], quad[(i + 1) % 4]) for i in range(4)]
    s = (lens[0] / w + lens[1] / h + lens[2] / w + lens[3] / h) / 4
    if s < 0.85:
        r = min(2.5, (1.0 / s - 1.0) * 1.5)
        if r >= 0.4:
            page = page.filter(ImageFilter.GaussianBlur(r))
    return page


def render_frame(page: Image.Image, page_size, quad, rng: random.Random,
                 lighting: str) -> Image.Image:
    """Full camera-like frame: desk + shadow + perspective-warped page + light."""
    w, h = page_size
    src_quad = [(BLEED, BLEED), (w + BLEED, BLEED),
                (w + BLEED, h + BLEED), (BLEED, h + BLEED)]
    bg = make_background(rng)
    add_shadow(bg, quad)
    frame = bg.convert("RGBA")
    page = _preblur(page.convert("RGBA"), page_size, quad)
    coeffs = _homography(quad, src_quad)
    warped = page.transform((FRAME_W, FRAME_H), Image.PERSPECTIVE, coeffs,
                            resample=Image.Resampling.BICUBIC)
    frame = Image.alpha_composite(frame, warped).convert("RGB")
    return apply_lighting(frame, rng, LIGHTING[lighting])


# --------------------------------------------------------------------------
# Fixture content (synthetic only — no personal data, no third-party content)
# --------------------------------------------------------------------------

A4 = (1240, 1754)

CLEAN_A4_BODY = [
    "This page is a synthetic fixture generated",
    "for camera capture testing. It contains no",
    "personal data and no third party content.",
    "",
    "Paragraph two covers detection. The page",
    "edges are clean and the lighting is even,",
    "so automatic document detection should",
    "locate the full boundary on the first",
    "attempt.",
    "",
    "The text on this page is intentionally",
    "simple so that optical character",
    "recognition can be compared between the",
    "reference application and the implementation.",
]

SKEWED_BODY = [
    "This synthetic page is photographed at an",
    "angle. The perspective is intentionally",
    "strong so that edge detection must correct",
    "the keystone before the scan is stored.",
    "",
    "A second block of text gives the",
    "recognition engine more material to",
    "compare after perspective correction has",
    "been applied by both applications.",
]

LOW_LIGHT_BODY = [
    "This synthetic page is underexposed on",
    "purpose. Enhancement modes should brighten",
    "the page before optical character",
    "recognition runs.",
    "",
    "The exposure is roughly one third of the",
    "normal level so the enhancement path is",
    "exercised end to end.",
]

RECEIPT_LINES = [
    "CAMSCAN STORE",
    "SYNTHETIC RECEIPT 0007",
    "2026-09-28 14:22",
    "--------------------",
    "NOTEBOOK       2.50",
    "INK PEN        1.20",
    "PAPER CLIP     0.30",
    "RULER          1.05",
    "--------------------",
    "TOTAL          5.05",
    "CASH",
    "THANK YOU",
    "FIXTURE ONLY - NO SALE",
]

HANDWRITTEN_LINES = [
    "Notes on parity",
    "scan the page twice",
    "compare edges first",
    "then compare the text",
    "lighting matters most",
    "keep the phone steady",
]

MULTI_PAGE_BODIES = [
    [
        "First page of the ordered multi page",
        "fixture. Each page carries its own",
        "ordinal footer so that page order can",
        "be verified after the scan.",
    ],
    [
        "Second page of the ordered multi page",
        "fixture. The camera frames are captured",
        "in sequence and the application must",
        "preserve the capture order.",
    ],
    [
        "Third page of the ordered multi page",
        "fixture. The final page closes the set",
        "and the application should report three",
        "pages in total.",
    ],
]

PAN_BODY = [
    "This synthetic page is used by the",
    "document pan sequence. The camera drifts",
    "slowly across the page while the",
    "detection overlay is expected to track",
    "the document boundary in every frame.",
]

MOTION_BODY = [
    "This synthetic page is used by the",
    "camera motion sequence. Handheld shake",
    "is applied frame by frame so detection",
    "stability and refocus behavior can be",
    "compared between the two applications.",
]

ROTATION_BODY = [
    "This synthetic page is used by the",
    "rotation sequence. The device rotates",
    "during capture and the page appears to",
    "turn inside the frame while orientation",
    "behavior is observed.",
]


def _doc_text(title: str, subtitle: str, body: list[str], footer: str | None = None) -> str:
    lines = [title, subtitle, *body]
    if footer:
        lines.append(footer)
    return "\n".join(lines)


# -- static documents ---------------------------------------------------------

DOCUMENT_SPECS = [
    dict(
        id="clean-a4",
        page=lambda rng: render_text_page(*A4, rng, title="CAMSCAN PARITY LAB",
                                          subtitle="Fixture Document A-001",
                                          body_lines=CLEAN_A4_BODY),
        page_size=A4,
        quad=rotated_quad(540, 925, 990, 1400, 1.2),
        lighting="even-daylight",
        rotation_deg=1.2,
        text=_doc_text("CAMSCAN PARITY LAB", "Fixture Document A-001", CLEAN_A4_BODY),
    ),
    dict(
        id="skewed-document",
        page=lambda rng: render_text_page(*A4, rng, title="SKEWED PAGE FIXTURE",
                                          subtitle="Fixture Document B-002",
                                          body_lines=SKEWED_BODY),
        page_size=A4,
        # strong keystone: viewed from the lower-left (left edge near + tall)
        quad=[(115.0, 300.0), (820.0, 405.0), (1010.0, 1445.0), (180.0, 1690.0)],
        lighting="even-daylight",
        rotation_deg=None,  # perspective skew is encoded by the quad itself
        text=_doc_text("SKEWED PAGE FIXTURE", "Fixture Document B-002", SKEWED_BODY),
    ),
    dict(
        id="receipt",
        page=lambda rng: render_receipt(620, 1750, rng, RECEIPT_LINES, center_first=3),
        page_size=(620, 1750),
        quad=rotated_quad(540, 950, 460, 1600, 2.5),
        lighting="neutral-indoor",
        rotation_deg=2.5,
        text="\n".join(RECEIPT_LINES),
    ),
    dict(
        id="business-card",
        page=lambda rng: render_business_card(
            1060, 668, rng,
            name="ALEX SAMPLE", role="Document Engineer",
            company="CAMSCAN FIXTURE WORKS",
            contact=["+1 555 010 7299", "alex.sample@example.org"]),
        page_size=(1060, 668),
        quad=rotated_quad(555, 1000, 640, 404, 0.8),
        lighting="even-daylight",
        rotation_deg=0.8,
        text="\n".join(["ALEX SAMPLE", "Document Engineer", "CAMSCAN FIXTURE WORKS",
                        "+1 555 010 7299", "alex.sample@example.org"]),
    ),
    dict(
        id="handwritten",
        page=lambda rng: render_handwritten(*A4, rng, HANDWRITTEN_LINES),
        page_size=A4,
        quad=rotated_quad(540, 930, 950, 1343, -1.5),
        lighting="neutral-indoor",
        rotation_deg=-1.5,
        text="\n".join(HANDWRITTEN_LINES),
    ),
    dict(
        id="low-light",
        page=lambda rng: render_text_page(*A4, rng, title="LOW LIGHT PAGE",
                                          subtitle="Fixture Document C-003",
                                          body_lines=LOW_LIGHT_BODY),
        page_size=A4,
        quad=rotated_quad(540, 930, 970, 1370, 1.0),
        lighting="dim-tungsten",
        rotation_deg=1.0,
        text=_doc_text("LOW LIGHT PAGE", "Fixture Document C-003", LOW_LIGHT_BODY),
    ),
    dict(
        id="multi-page",
        pages=[
            dict(render=lambda rng, i=i: render_text_page(
                *A4, rng, title="MULTI PAGE SET M-004",
                subtitle="Fixture Document M-004",
                body_lines=MULTI_PAGE_BODIES[i], footer=f"PAGE {i + 1} OF 3"),
                text=_doc_text("MULTI PAGE SET M-004", "Fixture Document M-004",
                               MULTI_PAGE_BODIES[i], f"PAGE {i + 1} OF 3"))
            for i in range(3)
        ],
        page_size=A4,
        quad=rotated_quad(540, 930, 980, 1386, 0.8),
        lighting="even-daylight",
        rotation_deg=0.8,
    ),
]


# -- scripted sequences --------------------------------------------------------

SEQUENCE_SPECS = [
    dict(
        id="document-pan",
        frame_count=16,
        page=dict(
            render=lambda rng: render_text_page(*A4, rng, title="PAN SEQUENCE PAGE",
                                                subtitle="Fixture Document P-101",
                                                body_lines=PAN_BODY),
            text=_doc_text("PAN SEQUENCE PAGE", "Fixture Document P-101", PAN_BODY),
        ),
        base=dict(w=940, h=1330, cx=540, cy=940),
        lighting="neutral-indoor",
        motion_description=(
            "Slow lateral pan across the document: 16 frames, camera drifts "
            "left to right by 400 px total (26.67 px/frame) with a slight "
            "vertical drift (+20 to -20 px). The page partially exits the "
            "frame at both extremes; ground truth quad per frame = base quad "
            "translated by (dx, dy)."),
    ),
    dict(
        id="camera-motion",
        frame_count=20,
        page=dict(
            render=lambda rng: render_text_page(*A4, rng, title="MOTION SEQUENCE PAGE",
                                                subtitle="Fixture Document M-102",
                                                body_lines=MOTION_BODY),
            text=_doc_text("MOTION SEQUENCE PAGE", "Fixture Document M-102", MOTION_BODY),
        ),
        base=dict(w=900, h=1273, cx=540, cy=950),
        lighting="neutral-indoor",
        motion_description=(
            "Handheld shake: 20 frames of seeded small-amplitude jitter around "
            "a centered framing (dx within +/-12 px, dy within +/-10 px, "
            "in-plane rotation within +/-1.5 deg, scale 0.99-1.01). Ground "
            "truth quad per frame = rotated/scaled base quad at (dx, dy)."),
    ),
    dict(
        id="rotation",
        frame_count=24,
        page=dict(
            render=lambda rng: render_text_page(*A4, rng, title="ROTATION SEQUENCE PAGE",
                                                subtitle="Fixture Document R-103",
                                                body_lines=ROTATION_BODY),
            text=_doc_text("ROTATION SEQUENCE PAGE", "Fixture Document R-103", ROTATION_BODY),
        ),
        base=dict(w=760, h=1073, cx=540, cy=960),
        lighting="neutral-indoor",
        motion_description=(
            "Device rotation during capture: 24 frames, in-plane page rotation "
            "0 deg to 90 deg at ~3.91 deg/frame; the page is progressively "
            "scaled down (up to ~0.79x) to remain inside the frame, ending "
            "landscape. Ground truth quad per frame = base quad rotated by "
            "theta and scaled by s."),
    ),
]


# --------------------------------------------------------------------------
# Corpus generation
# --------------------------------------------------------------------------

def _fixture_rng(fixture_id: str) -> random.Random:
    return random.Random(MASTER_SEED ^ zlib.crc32(fixture_id.encode("ascii")))


def _save(img: Image.Image, path: Path, fmt: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if fmt == "png":
        img.save(path, format="PNG", optimize=False)
    elif fmt == "jpg":
        img.save(path, format="JPEG", quality=JPEG_QUALITY,
                 optimize=False, progressive=False)
    else:
        raise ValueError(f"unknown format {fmt!r}")


def _file_entry(out_dir: Path, rel: str) -> dict:
    data = (out_dir / rel).read_bytes()
    return {"path": rel, "sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)}


def generate_documents(out_dir: Path) -> list[dict]:
    entries = []
    for spec in DOCUMENT_SPECS:
        rng = _fixture_rng(spec["id"])
        files = []
        if "page" in spec:  # single-page fixture
            page = spec["page"](rng)
            frame = render_frame(page, spec["page_size"], spec["quad"], rng, spec["lighting"])
            rel = f"documents/{spec['id']}.png"
            _save(frame, out_dir / rel, DOC_FORMAT)
            e = _file_entry(out_dir, rel)
            e["page"] = None
            files.append(e)
            texts = spec["text"]
            page_count = 1
        else:  # multi-page ordered set, one shared camera position
            for i, p in enumerate(spec["pages"]):
                page = p["render"](rng)
                frame = render_frame(page, spec["page_size"], spec["quad"],
                                     rng, spec["lighting"])
                rel = f"documents/{spec['id']}/page-{i + 1:02d}.png"
                _save(frame, out_dir / rel, DOC_FORMAT)
                e = _file_entry(out_dir, rel)
                e["page"] = i + 1
                files.append(e)
            texts = [p["text"] for p in spec["pages"]]
            page_count = len(spec["pages"])

        gt = {
            "page_count": page_count,
            "text_content": texts,
            "page_quad_px": [[float(x), float(y)] for x, y in spec["quad"]],
            "lighting": spec["lighting"],
            "rotation_deg": spec["rotation_deg"],
        }
        entries.append({
            "id": spec["id"],
            "files": files,
            "format": DOC_FORMAT,
            "dimensions": {"width_px": FRAME_W, "height_px": FRAME_H},
            "ground_truth": gt,
        })
    return entries


def _pan_frame(i: int, n: int, base: dict):
    t = i / (n - 1)
    dx = -200.0 + 400.0 * t
    dy = 20.0 - 40.0 * t
    base_quad = rotated_quad(base["cx"], base["cy"], base["w"], base["h"], 0.0)
    quad = [(round(x + dx, 2), round(y + dy, 2)) for x, y in base_quad]
    return quad, f"pan(dx={dx:+.2f},dy={dy:+.2f})"


def _shake_frame(rng: random.Random, base: dict):
    dx = rng.uniform(-12.0, 12.0)
    dy = rng.uniform(-10.0, 10.0)
    theta = rng.uniform(-1.5, 1.5)
    sc = rng.uniform(0.99, 1.01)
    quad = rotated_quad(base["cx"] + dx, base["cy"] + dy,
                        base["w"] * sc, base["h"] * sc, theta)
    return quad, f"shake(dx={dx:+.2f},dy={dy:+.2f},theta={theta:+.2f}deg,scale={sc:.4f})"


def _rotate_frame(i: int, n: int, base: dict):
    theta = 90.0 * i / (n - 1)
    t = math.radians(theta)
    c, s = abs(math.cos(t)), abs(math.sin(t))
    w, h = base["w"], base["h"]
    sc = min(1.0, 1020.0 / (w * c + h * s), 1860.0 / (w * s + h * c))
    quad = rotated_quad(base["cx"], base["cy"], w * sc, h * sc, theta)
    return quad, f"rotate(theta={theta:.2f}deg,scale={sc:.4f})"


def generate_sequences(out_dir: Path) -> list[dict]:
    entries = []
    for spec in SEQUENCE_SPECS:
        rng = _fixture_rng(spec["id"])
        page = spec["page"]["render"](rng)  # one deterministic page for the whole set
        frames = []
        for i in range(spec["frame_count"]):
            if spec["id"] == "document-pan":
                quad, transform = _pan_frame(i, spec["frame_count"], spec["base"])
            elif spec["id"] == "camera-motion":
                quad, transform = _shake_frame(rng, spec["base"])
            else:
                quad, transform = _rotate_frame(i, spec["frame_count"], spec["base"])
            frame = render_frame(page, A4, quad, rng, spec["lighting"])
            rel = f"sequences/{spec['id']}/frame-{i:02d}.jpg"
            _save(frame, out_dir / rel, SEQ_FORMAT)
            e = _file_entry(out_dir, rel)
            e["index"] = i
            e["transform"] = transform
            frames.append(e)
        entries.append({
            "id": spec["id"],
            "frames": frames,
            "format": SEQ_FORMAT,
            "dimensions": {"width_px": FRAME_W, "height_px": FRAME_H},
            "ground_truth": {
                "frame_count": spec["frame_count"],
                "motion_description": spec["motion_description"],
            },
        })
    return entries


def generate_corpus(out_dir: Path) -> dict:
    """Generate all files into out_dir and return the manifest dict
    (also written to out_dir/manifest.json)."""
    documents = generate_documents(out_dir)
    sequences = generate_sequences(out_dir)
    manifest = {
        "schema_version": "0.1",
        "generator": {
            "script": "lab/fixtures/generate.py",
            "seed": MASTER_SEED,
            "command": GENERATOR_COMMAND,
        },
        "generated_utc": CORPUS_EPOCH,
        "documents": documents,
        "sequences": sequences,
    }
    (out_dir / "manifest.json").write_text(
        json.dumps(manifest, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")
    return manifest


# --------------------------------------------------------------------------
# CLI: generate / --check
# --------------------------------------------------------------------------

def _iter_files(manifest: dict):
    for entry in manifest["documents"]:
        for f in entry["files"]:
            yield "documents", entry["id"], f
    for entry in manifest["sequences"]:
        for f in entry["frames"]:
            yield "sequences", entry["id"], f


def cmd_generate() -> int:
    manifest = generate_corpus(FIXTURES)
    total_files = sum(1 for _ in _iter_files(manifest))
    total_bytes = sum(f["bytes"] for _, _, f in _iter_files(manifest))
    print(f"generated {total_files} files ({total_bytes / 1e6:.1f} MB) + manifest.json")
    for group, fid, f in _iter_files(manifest):
        print(f"  {f['path']:<44} {f['bytes']:>10,} B  sha256:{f['sha256'][:16]}...")
    print(f"manifest: {FIXTURES / 'manifest.json'}")
    return 0


def cmd_check() -> int:
    manifest_path = FIXTURES / "manifest.json"
    if not manifest_path.exists():
        print("CHECK FAILED: lab/fixtures/manifest.json not found — run generate.py first")
        return 1
    disk = json.loads(manifest_path.read_text())
    problems: list[str] = []

    # 1. every manifest entry exists on disk with matching bytes + sha256
    for group, fid, f in _iter_files(disk):
        p = FIXTURES / f["path"]
        if not p.exists():
            problems.append(f"missing file: {f['path']}")
            continue
        data = p.read_bytes()
        if len(data) != f["bytes"]:
            problems.append(f"byte size mismatch: {f['path']} "
                            f"(disk {len(data)}, manifest {f['bytes']})")
        digest = hashlib.sha256(data).hexdigest()
        if digest != f["sha256"]:
            problems.append(f"sha256 mismatch: {f['path']} (disk {digest})")

    # 2. full regeneration into a temp dir must be byte-identical
    with tempfile.TemporaryDirectory(prefix="camscan-fixtures-") as td:
        gen = generate_corpus(Path(td))
        for (g1, fid1, f1), (g2, fid2, f2) in zip(_iter_files(disk), _iter_files(gen)):
            if f1 != f2:
                problems.append(f"regenerated entry differs: {f1['path']}")
        if sum(1 for _ in _iter_files(disk)) != sum(1 for _ in _iter_files(gen)):
            problems.append("regenerated corpus has a different file count")
        gen_manifest = Path(td) / "manifest.json"
        if gen_manifest.read_bytes() != manifest_path.read_bytes():
            problems.append("regenerated manifest.json is not byte-identical")

    if problems:
        print("CHECK FAILED:")
        for m in problems:
            print("  -", m)
        return 1
    n_files = sum(1 for _ in _iter_files(disk))
    n_docs = len(disk["documents"])
    n_seqs = len(disk["sequences"])
    print(f"CHECK PASSED: {n_files} files across {n_docs} documents + {n_seqs} sequences")
    print("  - all manifest entries verified on disk (bytes + sha256)")
    print("  - full regeneration byte-identical (files and manifest.json)")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--check", action="store_true",
                    help="regenerate to a temp dir and hash-verify every manifest entry")
    args = ap.parse_args()
    return cmd_check() if args.check else cmd_generate()


if __name__ == "__main__":
    sys.exit(main())
