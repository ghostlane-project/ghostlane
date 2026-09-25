#!/usr/bin/env python3
# Google Play store assets from the app's own tokens and bundled fonts -> docs/play/.
# Needs Pillow. Re-run after a copy or screenshot change; see docs/play-listing.md.
import os

from PIL import Image, ImageDraw, ImageFont, ImageFilter

FORK = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
F = FORK + '/sharedUI/src/commonMain/composeResources/font/'
OUT = FORK + '/docs/play'
os.makedirs(OUT + '/screenshots', exist_ok=True)

# PkPalette / Color.kt tokens (the app's own values)
BG = (7, 8, 13); SURF = (15, 17, 23); SURF2 = (22, 24, 34); BORDER = (30, 32, 48)
LIME = (181, 242, 61); TEXT = (232, 236, 242); DIM = (139, 147, 168); MUTED = (85, 90, 114)

# ---------- icon: flatten the RGBA Play icon over the app background ----------
ic = Image.open(FORK + '/androidApp/src/main/res/playstore_icon.png').convert('RGBA')
flat = Image.new('RGB', ic.size, BG)
flat.paste(ic, (0, 0), ic)
flat.save(OUT + '/icon-512.png', optimize=True)

# ---------- feature graphic 1024x500 ----------
W, H = 1024, 500
img = Image.new('RGB', (W, H), BG)
d = ImageDraw.Draw(img, 'RGBA')
for x in range(0, W, 32):
    d.line([(x, 0), (x, H)], fill=(255, 255, 255, 7))
for y in range(0, H, 32):
    d.line([(0, y), (W, y)], fill=(255, 255, 255, 7))
halo = Image.new('RGBA', (W, H), (0, 0, 0, 0))
ImageDraw.Draw(halo).ellipse([-120, 40, 560, 600], fill=LIME + (34,))
halo = halo.filter(ImageFilter.GaussianBlur(110))
img.paste(halo, (0, 0), halo)
d = ImageDraw.Draw(img, 'RGBA')

sg = ImageFont.truetype(F + 'space_grotesk_bold.ttf', 104)
ps = ImageFont.truetype(F + 'ibm_plex_sans_medium.ttf', 34)
ps_sb = ImageFont.truetype(F + 'ibm_plex_sans_semi_bold.ttf', 26)
pm = ImageFont.truetype(F + 'ibm_plex_mono_medium.ttf', 22)
pm_s = ImageFont.truetype(F + 'ibm_plex_mono_medium.ttf', 18)
pm_sb = ImageFont.truetype(F + 'ibm_plex_mono_semi_bold.ttf', 20)

x0 = 72
# eyebrow
d.text((x0, 100), '$', font=pm, fill=LIME)
d.text((x0 + 26, 100), 'ghostlane connect --olcrtc', font=pm, fill=DIM)
# wordmark + tagline
d.text((x0 - 4, 128), 'Ghostlane', font=sg, fill=TEXT)
d.text((x0, 262), 'A tunnel inside a video call', font=ps, fill=TEXT)
# status line, as on the connected card
cy = 340
d.ellipse([x0, cy - 8, x0 + 16, cy + 8], fill=LIME)
d.text((x0 + 30, cy - 13), 'IN A ROOM · OLCRTC', font=pm_sb, fill=LIME)
d.text((x0 + 30, cy + 16), '↓ 89 KB   ↑ 63 KB   00:10', font=pm_s, fill=DIM)
# sparkline
pts = [(x0, 432), (x0 + 60, 428), (x0 + 120, 420), (x0 + 180, 430), (x0 + 240, 424),
       (x0 + 300, 400), (x0 + 360, 428), (x0 + 420, 426), (x0 + 470, 420)]
d.polygon(pts + [(pts[-1][0], 446), (x0, 446)], fill=LIME + (28,))
d.line(pts, fill=LIME, width=3, joint='curve')

# rooms panel on the right: three cards, the first one is the user's seat. It ends
# at the 72 px margin and starts clear of the wordmark (which ends near x=577).
px, py, pw, ch, gap = 604, 88, 348, 96, 12
rooms = [('AU · VP8', 1, True), ('DE · VP8', 0, False), ('FR · VP8', 2, False)]
for i, (name, taken, mine) in enumerate(rooms):
    y = py + i * (ch + gap)
    border = LIME if mine else BORDER
    fill = (14, 18, 12) if mine else SURF
    d.rounded_rectangle([px, y, px + pw, y + ch], radius=16, fill=fill, outline=border, width=2)
    d.text((px + 22, y + 16), name, font=ps_sb, fill=LIME if mine else TEXT)
    free = 8 - taken
    label = f'{free} free'
    lw = d.textlength(label, font=pm_sb)
    d.text((px + pw - 22 - lw, y + 20), label, font=pm_sb, fill=LIME)
    if mine:
        seat = 'YOUR SEAT'
        pm_xs = ImageFont.truetype(F + 'ibm_plex_mono_medium.ttf', 15)
        sw = d.textlength(seat, font=pm_xs)
        sx = px + 22 + d.textlength(name, font=ps_sb) + 12
        d.rounded_rectangle([sx, y + 18, sx + sw + 14, y + 44], radius=8, fill=LIME + (40,))
        d.text((sx + 7, y + 23), seat, font=pm_xs, fill=LIME)
    # slots, then the count to their right; the slots stop short of the count
    cnt = f'{taken} / 8'
    cw = d.textlength(cnt, font=pm_s)
    cx = px + pw - 22 - cw
    sy = y + 62
    pitch = (cx - 14 - (px + 22)) // 8
    for s in range(8):
        sx = px + 22 + s * pitch
        d.rounded_rectangle([sx, sy, sx + pitch - 5, sy + 14], radius=4,
                            fill=LIME if s < taken else SURF2)
    d.text((cx, sy - 3), cnt, font=pm_s, fill=DIM)

img.save(OUT + '/feature-graphic.png', optimize=True)

# ---------- screenshots: 1320x2868 (App Store) -> 1320x2632, ratio 1.99 ----------
SS = FORK + '/docs/screenshots'
for name in sorted(os.listdir(SS)):
    if not name.lower().endswith('.png'):
        continue
    im = Image.open(os.path.join(SS, name)).convert('RGB')
    w, h = im.size
    out = im.crop((0, 168, w, h - 68))
    out.save(os.path.join(OUT, 'screenshots', name.lower()), optimize=True)
    print(name.lower(), out.size, 'ratio %.3f' % (out.size[1] / out.size[0]))

print('icon', flat.size, flat.mode)
print('feature', img.size, img.mode)
