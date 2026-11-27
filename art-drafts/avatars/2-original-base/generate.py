# SPDX-License-Identifier: GPL-3.0-or-later
"""Build sheet.png / sheet.json / preview.png for avatar draft 2 (original base).

    python3 generate.py

Only parts.py pixels are used for the character.  SPD's sewer tiles, rat and
warrior are read solely to paint the scale-reference strip of preview.png.
"""

import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

import parts as P

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
SPD = ROOT / "core/src/main/assets"
FW, FH = 16, 20

# ------------------------------------------------------------ palettes -----
def ramp(s):
    return [tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) for h in s.split()]

SKIN = {
    "light": ramp("6b3a2e a9654b d4936c eeb98f fbdcb4"),
    "pale": ramp("70423c ad7766 d9a58e f1cbb4 fde8d8"),
    "tan": ramp("5c3121 925234 bd7a4b d99e68 efc38e"),
    "olive": ramp("4d3020 7c5232 a5784a c49d65 dcbf87"),
    "brown": ramp("3d2117 683a27 925a3a b47a50 cf9a68"),
    "deep": ramp("28140f 46271b 66402b 86583b a5724c"),
    "ash": ramp("35364a 5a5f78 8389a3 a9b0c6 cdd3e2"),
    "moss": ramp("2b3a22 4c6338 708a4c 94ab66 b7c986"),
}
HAIRC = {
    "chestnut": ramp("3b2117 5e3522 834e2f a86d40 c78f58"),
    "black": ramp("15141c 26242f 393644 4e4a5a 6a6577"),
    "blonde": ramp("6b4a1a a07a2a c9a73e e3cd62 f5e89a"),
    "ginger": ramp("4a1a0e 7f2c12 b2481b d6692a ef9446"),
    "silver": ramp("4a4d5a 737886 a0a6b2 c8ccd4 eceef1"),
    "blue": ramp("1c2448 2c3c78 3f5cab 5d84cf 8fb2e8"),
    "pink": ramp("5a2440 8e3a63 bf5a8a de82ac f2b2cd"),
    "green": ramp("1e3a24 2f5c36 45824a 66a95f 98cf84"),
}
CLOTH = {
    "crimson": ramp("3e1218 6d1f27 9c2f33 c44a43 e07a63"),
    "navy": ramp("141a33 233059 344a85 4b68ad 7290cc"),
    "forest": ramp("16291c 25442b 39643a 52864b 7aa868"),
    "violet": ramp("25163a 412665 5e3a8e 7d58b3 a283d1"),
    "ochre": ramp("3d2a0e 6a4a17 977024 bf9637 dcbd5e"),
    "slate": ramp("22252c 3a3f4a 565d6b 767f8e 9aa3b1"),
    "teal": ramp("0f2b2e 1a4a4c 276d6b 3d938b 69b9a8"),
    "cream": ramp("5a4d3e 8f7f69 bdae93 dcd0b6 f2ead8"),
}
FIXED = {
    "leather": ramp("3a2216 63402a 8d5f3c b4834f"),
    "metal": ramp("2c2e38 525867 838a98 b4bac4 e6e9ee"),
    "gold": ramp("7a4b12 c48d22 f0cf55"),
    "wood": ramp("3a2516 6b4a2a 9a7040"),
    "darkcloth": ramp("221f2c 37334a 4f4a63 6a647f 8a849f"),
    "whitecloth": ramp("4e4446 8f8682 c4bcb0 e4ded2 f8f6ef"),
    "leafcloth": ramp("1f2e1c 34492b 4c6838 6a8a4c 8fab68"),
    "steelcloth": ramp("2c2e38 525867 838a98 b4bac4 e6e9ee"),
}
INK = ramp("1e1a22")[0]
WHITE = ramp("f4f1ea")[0]

MATERIAL = {}
for i, c in enumerate("abcde"): MATERIAL[c] = ("skin", i)
for i, c in enumerate("hijkl"): MATERIAL[c] = ("hair", i)
for i, c in enumerate("pqrst"): MATERIAL[c] = ("primary", i)
for i, c in enumerate("uvwxy"): MATERIAL[c] = ("secondary", i)
for i, c in enumerate("1234"): MATERIAL[c] = ("leather", i)
for i, c in enumerate("56789"): MATERIAL[c] = ("metal", i)
for i, c in enumerate("XYZ"): MATERIAL[c] = ("gold", i)
for i, c in enumerate("mno"): MATERIAL[c] = ("wood", i)
MATERIAL["K"] = ("ink", 0)
MATERIAL["W"] = ("white", 0)
ZONE = {}
for zone, chars in (("torso", "ABCD"), ("sleeve", "EFGH"), ("thigh", "LMNO"),
                    ("shin", "QRST"), ("foot", "UVIJ")):
    for i, c in enumerate(chars):
        ZONE[c] = (zone, i)


class Look:
    def __init__(self, skin="light", hair="crop", hair_color="chestnut", job="warrior",
                 cloth="crimson", hat="none", face="none", back="none"):
        self.skin, self.hair, self.hair_color, self.job = skin, hair, hair_color, job
        self.cloth, self.hat, self.face, self.back = cloth, hat, face, back

    def ramps(self):
        r = {"skin": SKIN[self.skin], "hair": HAIRC[self.hair_color],
             "primary": CLOTH[self.cloth], "ink": [INK], "white": [WHITE]}
        r.update(FIXED)
        r["secondary"] = FIXED[P.OUTFITS[self.job]["secondary"]] if self.job else FIXED["darkcloth"]
        return r


# --------------------------------------------------------------- poses -----
BASE = {"head": (4, 4), "torso": (5, 11)}

def pose(legs="stand", near="side", far="side", head=(0, 0), torso=(0, 0), eyes="open", bob=0):
    return dict(legs=legs, near=near, far=far, head=head, torso=torso, eyes=eyes, bob=bob)

# 21 frames, same slots as SPD HeroSprite: idle 0-1, run 2-7, die 8-12,
# attack 13-15, operate 16-17, fly 18, read 19-20.  "rot" frames are the idle pose laid down.
FRAMES = [
    pose(),                                                   # 0 idle
    pose(head=(0, 1), torso=(0, 1)),                          # 1 idle breathe
    pose("strideA", "back", "fwd"),                           # 2 run contact A
    pose("passA", "side", "side", bob=-1),                    # 3 run pass A (up)
    pose("passA", "fwd", "back"),                             # 4 run down A
    pose("strideB", "fwd", "back"),                           # 5 run contact B
    pose("passB", "side", "side", bob=-1),                    # 6 run pass B (up)
    pose("passB", "back", "fwd"),                             # 7 run down B
    pose("stand", "back", "fwd", head=(-1, 0), eyes="closed"),   # 8 die: flinch
    pose("kneel", "side", "side", head=(0, 2), torso=(0, 2), eyes="closed"),   # 9 kneel
    pose("kneel", "reach", "side", head=(1, 3), torso=(0, 2), eyes="closed"),  # 10 slump
    "rot0",                                                   # 11 lying
    "rot1",                                                   # 12 lying (settle)
    pose("stand", "windup", "guard", head=(-1, 0), torso=(-1, 0)),          # 13 wind-up
    pose("lunge", "punch", "back", head=(1, 0), torso=(1, 0)),              # 14 strike
    pose("stand", "half", "side", head=(1, 0)),                             # 15 recover
    pose("kneel", "reach", "reach", head=(1, 2), torso=(0, 2)),             # 16 operate
    pose("kneel", "half", "reach", head=(1, 2), torso=(0, 2)),              # 17 operate
    pose("passB", "fwd", "fwd", bob=-1),                                    # 18 fly / jump
    pose("stand", "half", "guard", head=(0, 1)),                            # 19 read
    pose("stand", "half", "guard", head=(0, 1), eyes="closed"),             # 20 read (blink)
]
ANIMS = {"idle": [0, 0, 0, 1, 0, 0, 1, 1], "run": [2, 3, 4, 5, 6, 7],
         "die": [8, 9, 10, 11, 12, 11], "attack": [13, 14, 15, 0], "operate": [16, 17, 16, 17],
         "fly": [18], "read": [19, 20, 20, 20, 20, 20, 20, 20, 20, 19]}


def anchors(f):
    hx, hy = BASE["head"]; tx, ty = BASE["torso"]
    hx += f["head"][0]; hy += f["head"][1] + f["bob"]
    tx += f["torso"][0]; ty += f["torso"][1] + f["bob"]
    return (hx, hy), (tx, ty), (BASE["torso"][0] - 2, BASE["torso"][1] + 4)


# ------------------------------------------------------------- painting ----
def stamp(cells, rows, ox, oy, tag, clip_row=None):
    for y, row in enumerate(rows):
        if clip_row is not None and y < clip_row:
            continue
        for x, ch in enumerate(row):
            if ch == "." or ch == " ":
                continue
            X, Y = ox + x, oy + y
            if 0 <= X < FW and 0 <= Y < FH:
                cells[Y][X] = (ch, tag)


def blank():
    return [[None] * FW for _ in range(FH)]


def to_image(cells, colour):
    img = Image.new("RGBA", (FW, FH))
    for y in range(FH):
        for x in range(FW):
            c = cells[y][x]
            if c is None:
                continue
            rgb = colour(c)
            if rgb is not None:
                img.putpixel((x, y), rgb + (255,))
    return img


def body_cells(f, job):
    """Char-level composite of body parts (+ class overlays) in depth order."""
    (hx, hy), (tx, ty), (lx, ly) = anchors(f)
    cells = blank()
    out = P.OUTFITS.get(job) if job else None
    g, (ax, ay) = P.FAR_ARM[f["far"]]
    stamp(cells, g, tx + 6 - ax, ty - ay, "body")
    stamp(cells, P.LEGS[f["legs"]], lx, ly, "body")
    if out and out["skirt"]:
        fam = "stride" if f["legs"] in ("strideA", "strideB", "lunge") else "still"
        stamp(cells, out["skirt"][fam], lx, ly, "over")
    stamp(cells, P.TORSO, tx, ty, "body")
    if out and out["torso"]:
        (dx, dy), g2 = out["torso"]
        stamp(cells, g2, tx + dx, ty + dy, "over")
    stamp(cells, P.HEAD[f["eyes"]], hx, hy, "body")
    g, (ax, ay) = P.NEAR_ARM[f["near"]]
    stamp(cells, g, tx - 1 - ax, ty - ay, "body")
    if out and out.get("shoulder"):
        (dx, dy), g3 = out["shoulder"]
        stamp(cells, g3, tx + dx, ty + dy, "over")
    return cells


def colourer(look, want):
    """want: 'body' -> skin pixels only (zones the class leaves bare, or all
    zones when look.job is None); 'outfit' -> clothed zones + overlays."""
    R = look.ramps()
    zmap = P.OUTFITS[look.job]["zones"] if look.job else {}

    def col(cell):
        ch, tag = cell
        if ch in ZONE:
            zone, i = ZONE[ch]
            rname = zmap.get(zone, "skin")
            layer = "body" if rname == "skin" else "outfit"
            if layer != want:
                return None
            r = R[rname]
            return r[min(i, len(r) - 1)]
        layer = "outfit" if tag == "over" else ("body" if MATERIAL[ch][0] in ("skin", "ink") else tag)
        if tag == "body":
            layer = "body"
        if layer != want and not (want == "any"):
            return None
        name, i = MATERIAL[ch]
        return R[name][min(i, len(R[name]) - 1)]
    return col


def simple_layer(rows, ox, oy, look, clip=None):
    cells = blank()
    if rows:
        stamp(cells, rows, ox, oy, "x", clip)
    R = look.ramps()
    return to_image(cells, lambda c: R[MATERIAL[c[0]][0]][MATERIAL[c[0]][1]])


LAYER_ORDER = ["back", "hair_back", "outfit_back", "body", "outfit", "hair_front", "face", "hat"]


def layers_for(frame_index, look, apply_clip=True):
    f = FRAMES[frame_index] if isinstance(frame_index, int) else frame_index
    if isinstance(f, str):
        return rotated(look, settle=f == "rot1", apply_clip=apply_clip)
    (hx, hy), (tx, ty), _ = anchors(f)
    out = {}
    out["back"] = simple_layer(P.BACK[look.back], tx - 5, ty - 5, look)
    hair = P.HAIR[look.hair]
    out["hair_back"] = simple_layer(hair["back"], hx - 4, hy - 2, look)
    ob = P.OUTFITS[look.job]["back"] if look.job else None
    if ob:
        out["outfit_back"] = simple_layer(ob[1], tx + ob[0][0], ty + ob[0][1], look)
    else:
        out["outfit_back"] = Image.new("RGBA", (FW, FH))
    cells = body_cells(f, look.job)
    out["body"] = to_image(cells, colourer(look, "body"))
    out["outfit"] = to_image(cells, colourer(look, "outfit")) if look.job else Image.new("RGBA", (FW, FH))
    hat = P.HEADGEAR[look.hat]
    clip = hat["clip"] if (apply_clip and hat["rows"]) else None
    lift = hair.get("lift", 0)
    out["hair_front"] = simple_layer(hair["front"], hx - 2, hy - 2 - lift, look,
                                     None if clip is None else clip + lift)
    out["face"] = simple_layer(P.FACE[look.face], hx, hy, look)
    out["hat"] = simple_layer(hat["rows"], hx - 2, hy - 2 - hat["lift"], look)
    return out


def rotated(look, settle=False, apply_clip=True):
    """Lying frames: the closed-eye idle pose turned 90 degrees CCW.
    PIL's ROTATE_90 is an exact pixel permutation (no resampling), so every
    layer turns identically and stays aligned."""
    src = layers_for(pose(eyes="closed"), look, apply_clip)
    # The offset comes from the bare mannequin so it is identical for every
    # look and every layer row of the sheet (layers stay aligned).
    man = layers_for(pose(eyes="closed"), Look(job=None, hair="none"))["body"]
    bb = man.transpose(Image.Transpose.ROTATE_90).getbbox()
    dx = (FW - (bb[2] - bb[0])) // 2 - bb[0]
    dy = (FH - (1 if settle else 0)) - bb[3]
    out = {}
    for k, im in src.items():
        fr = Image.new("RGBA", (FW, FH))
        r = im.transpose(Image.Transpose.ROTATE_90)
        fr.paste(r, (dx, dy))
        out[k] = fr
    return out


def flatten(layers):
    img = Image.new("RGBA", (FW, FH))
    for k in LAYER_ORDER:
        img.alpha_composite(layers[k])
    return img


def frame(look, i=0):
    return flatten(layers_for(i, look))


# --------------------------------------------------------------- sheet -----
def build_sheet():
    n = len(FRAMES)
    rows = []  # (label, layer, look)
    rows.append(("body", "body", Look(job=None, hair="none")))
    for job in P.OUTFITS:
        rows.append((f"outfit_back:{job}", "outfit_back", Look(job=job, hair="none")))
        rows.append((f"outfit:{job}", "outfit", Look(job=job, hair="none")))
    for h in P.HAIR:
        if h == "none":
            continue
        rows.append((f"hair_back:{h}", "hair_back", Look(hair=h)))
        rows.append((f"hair_front:{h}", "hair_front", Look(hair=h)))
    for k in P.HEADGEAR:
        if k != "none":
            rows.append((f"hat:{k}", "hat", Look(hat=k)))
    for k in P.FACE:
        if k != "none":
            rows.append((f"face:{k}", "face", Look(face=k)))
    for k in P.BACK:
        if k != "none":
            rows.append((f"back:{k}", "back", Look(back=k)))
    # drop rows that are empty in every frame (e.g. outfit_back for most jobs)
    sheet_rows = []
    for label, layer, look in rows:
        imgs = [layers_for(i, look, apply_clip=False)[layer] for i in range(n)]
        if any(im.getbbox() for im in imgs):
            sheet_rows.append((label, imgs))
    pal_h = 8
    sheet = Image.new("RGBA", (n * FW, len(sheet_rows) * FH + pal_h * 4))
    for r, (label, imgs) in enumerate(sheet_rows):
        for i, im in enumerate(imgs):
            sheet.alpha_composite(im, (i * FW, r * FH))
    # palette strip: key ramps (row 0: skin, 1: hair, 2: primary, 3: fixed)
    y0 = len(sheet_rows) * FH
    keys = [list(SKIN.values()), list(HAIRC.values()), list(CLOTH.values())]
    for r, group in enumerate(keys):
        x = 0
        for rp in group:
            for c in rp:
                for yy in range(pal_h // 2):
                    sheet.putpixel((x, y0 + r * pal_h + yy), c + (255,))
                x += 1
            x += 1
    x = 0
    for name in ("leather", "metal", "gold", "wood", "darkcloth", "whitecloth", "leafcloth"):
        for c in FIXED[name]:
            for yy in range(pal_h // 2):
                sheet.putpixel((x, y0 + 3 * pal_h + yy), c + (255,))
            x += 1
        x += 1
    sheet.save(HERE / "sheet.png")
    meta = {
        "frameWidth": FW, "frameHeight": FH, "frames": n,
        "anchor": {"x": 8, "y": 19, "note": "feet rest on row 19; centre column 7.5"},
        "animations": ANIMS,
        "layerOrder": LAYER_ORDER,
        "rows": [label for label, _ in sheet_rows],
        "paletteStripY": y0,
        "recolour": {
            "skin": {k: ["%02x%02x%02x" % c for c in v] for k, v in SKIN.items()},
            "hair": {k: ["%02x%02x%02x" % c for c in v] for k, v in HAIRC.items()},
            "outfit": {k: ["%02x%02x%02x" % c for c in v] for k, v in CLOTH.items()},
            "keys": {"skin": "light", "hair": "chestnut", "outfit": "crimson"},
        },
        "hatHidesHairAboveRow": {k: v["clip"] for k, v in P.HEADGEAR.items() if v["rows"]},
    }
    (HERE / "sheet.json").write_text(json.dumps(meta, indent=1))
    return sheet_rows


# ------------------------------------------------------------- preview -----
S = 6
BG = (27, 25, 32)
PANEL = (36, 34, 42)
TXT = (222, 210, 180)
SUB = (150, 140, 125)
FONT = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf", 15)
SMALL = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf", 11)


def up(img, s=S):
    return img.resize((img.width * s, img.height * s), Image.Resampling.NEAREST)


class Canvas:
    def __init__(self, w):
        self.w = w
        self.parts = []  # (y, image or text)
        self.y = 10

    def title(self, t, sub=None):
        self.y += 10
        self.parts.append(("t", self.y, t, FONT, TXT))
        self.y += 20
        if sub:
            self.parts.append(("t", self.y, sub, SMALL, SUB))
            self.y += 16

    def row(self, items, cell_w=FW + 4, label_h=14, floor=True, s=S, x0=12):
        """items: list of (native image, label)."""
        x = x0
        h = max(im.height for im, _ in items) * s
        for im, lab in items:
            self.parts.append(("i", x, self.y, im, floor, s))
            if lab:
                self.parts.append(("l", x, self.y + h + 2, lab))
            x += cell_w * s
        self.y += h + label_h + 8

    def render(self):
        img = Image.new("RGB", (self.w, self.y + 10), BG)
        d = ImageDraw.Draw(img)
        for p in self.parts:
            if p[0] == "t":
                d.text((12, p[1]), p[2], font=p[3], fill=p[4])
            elif p[0] in ("i1", "i2"):
                k = int(p[0][1])
                im = up(p[3], k)
                img.paste(im, (p[1], p[2]), im)
            elif p[0] == "l":
                d.text((p[1], p[2]), p[3], font=SMALL, fill=SUB)
            else:
                _, x, y, im, floor = p[:5]
                s = p[5] if len(p) > 5 else S
                if floor:
                    d.rectangle((x - 2, y - 2, x + im.width * s + 1, y + im.height * s + 1), fill=PANEL)
                img.paste(up(im, s), (x, y), up(im, s))
        return img


def spd_reference(looks):
    """Native-res strip: SPD sewer wall/floor, SPD rat, SPD warrior, our heroes.
    One sprite per tile, bottoms on the tile bottom like CharSprite.place()."""
    tiles = Image.open(SPD / "environment/tiles_sewers.png").convert("RGBA")
    floor = tiles.crop((0, 0, 16, 16))
    wall = tiles.crop((0, 80, 16, 96))
    rat = Image.open(SPD / "sprites/rat.png").convert("RGBA").crop((0, 0, 16, 16))
    war = Image.open(SPD / "sprites/warrior.png").convert("RGBA").crop((0, 15, 12, 30))
    mag = Image.open(SPD / "sprites/mage.png").convert("RGBA").crop((0, 15, 12, 30))
    sprites = [rat, war] + [frame(lk) for lk in looks] + [mag, rat]
    cols = len(sprites) + 2
    strip = Image.new("RGBA", (cols * 16, 16 + 32))
    for i in range(cols):
        strip.alpha_composite(wall, (i * 16, 0))
        strip.alpha_composite(floor, (i * 16, 16))
        strip.alpha_composite(floor, (i * 16, 32))
    for n, sp in enumerate(sprites):
        x = (n + 1) * 16 + (16 - sp.width) // 2
        strip.alpha_composite(sp, (x, 48 - sp.height))
    return strip


def build_preview():
    base = dict(skin="light", hair="crop", hair_color="chestnut", job="warrior", cloth="crimson")
    def L(**kw):
        d = dict(base); d.update(kw); return Look(**d)

    W = 1500
    c = Canvas(W)
    c.title("DRAFT 2 - ORIGINAL BASE  16x20 frame  x6 nearest", "all character pixels hand-placed for this draft")
    c.title("SCALE: SPD rat | SPD warrior 12x15 | ours 16x20 x4 | SPD mage 12x15 | SPD rat")
    ref = spd_reference([Look(), Look("tan", "ponytail", "black", "huntress", "forest", back="quiver"),
                         Look("pale", "long", "silver", "mage", "violet", hat="wizard"),
                         Look("brown", "spiky", "ginger", "rogue", "slate", face="mask")])
    c.parts.append(("i", 12, c.y, ref, False))
    c.parts.append(("i1", 12 + ref.width * S + 30, c.y, ref))
    c.parts.append(("i2", 12 + ref.width * S + 30, c.y + ref.height + 12, ref))
    c.parts.append(("l", 12 + ref.width * S + 30, c.y + ref.height * 3 + 18, "x1 / x2"))
    c.y += ref.height * S + 10
    c.title("SKIN TONE")
    c.row([(frame(L(skin=k, hair="none", job="huntress", cloth="cream")), k) for k in SKIN])
    c.title("HAIR STYLE")
    c.row([(frame(L(hair=k, job="duelist", cloth="navy")), k) for k in P.HAIR])
    c.title("HAIR COLOUR")
    c.row([(frame(L(hair="bob", hair_color=k, job="duelist", cloth="navy")), k) for k in HAIRC])
    c.title("OUTFIT COLOUR")
    c.row([(frame(L(cloth=k, job="mage")), k) for k in CLOTH])
    c.title("HEADGEAR")
    c.row([(frame(L(hat=k, job="rogue", cloth="forest")), k) for k in P.HEADGEAR])
    c.title("FACE ITEM")
    c.row([(frame(L(face=k, job="cleric", cloth="navy")), k) for k in P.FACE])
    c.title("BACK ITEM")
    c.row([(frame(L(back=k, job="huntress", cloth="forest")), k) for k in P.BACK])
    c.title("CLASS OUTFITS")
    c.row([(frame(L(job=k)), k) for k in P.OUTFITS])
    c.title("FINISHED MIXES")
    mixes = [
        Look("light", "crop", "chestnut", "warrior", "crimson", "helm", back="shield"),
        Look("pale", "long", "silver", "mage", "violet", "wizard"),
        Look("brown", "spiky", "black", "rogue", "slate", "hood", "mask"),
        Look("tan", "ponytail", "ginger", "huntress", "forest", "feathercap", back="quiver"),
        Look("olive", "swept", "black", "duelist", "crimson", face="monocle", back="cape"),
        Look("pale", "bun", "blonde", "cleric", "cream", "circlet"),
        Look("deep", "bob", "pink", "mage", "teal", face="glasses", back="backpack"),
        Look("ash", "spiky", "blue", "warrior", "navy", "bandana", "eyepatch", "greatsword"),
        Look("moss", "none", "black", "rogue", "ochre", "hood", back="backpack"),
        Look("light", "long", "green", "huntress", "teal", "circlet", "freckles"),
        Look("tan", "crop", "silver", "cleric", "crimson", face="glasses", back="cape"),
        Look("brown", "ponytail", "blue", "duelist", "violet", "bandana", back="greatsword"),
        Look("pale", "swept", "ginger", "warrior", "ochre", face="freckles", back="cape"),
        Look("deep", "bun", "silver", "mage", "forest", "hood", "monocle"),
    ]
    c.row([(frame(m), None) for m in mixes[:7]], label_h=0)
    c.row([(frame(m), None) for m in mixes[7:]], label_h=0)
    c.title("LAYERS FOLLOW THE POSE  (x4: rows = frames 0,3,5,9,14,16; cols = final + each layer)",
            "back | hair_back | outfit_back | body | outfit | hair_front | face | hat")
    lkL = Look("tan", "ponytail", "ginger", "huntress", "teal", "feathercap", "glasses", "quiver")
    for fi in (0, 3, 5, 9, 14, 16):
        ly = layers_for(fi, lkL)
        # the body row of the sheet is the full mannequin (what the game draws)
        ly["body"] = layers_for(fi, Look(skin=lkL.skin, job=None, hair="none"))["body"]
        c.row([(flatten(ly), None)] + [(ly[k], None) for k in LAYER_ORDER], label_h=0, s=4)
    c.title("ANIMATION  (slots = SPD hero: idle 0-1 | run 2-7 | die 8-12 | attack 13-15 | operate 16-17 | fly 18 | read 19-20)")
    lk = mixes[3]
    n = len(FRAMES)
    for l in (lk, mixes[1]):
        c.row([(frame(l, i), str(i)) for i in range(11)])
        c.row([(frame(l, i), str(i)) for i in range(11, n)])
    img = c.render()
    img.save(HERE / "preview.png")
    return img


if __name__ == "__main__":
    rows = build_sheet()
    build_preview()
    print("sheet rows:", len(rows))
