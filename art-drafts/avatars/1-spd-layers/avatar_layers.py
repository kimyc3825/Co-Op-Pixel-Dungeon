# SPDX-License-Identifier: GPL-3.0-or-later
"""Design draft, approach 1: widen the layered paper-doll on the upstream 12x15 SPD heroes.

Run `python3 avatar_layers.py` to write sheet.png + sheet.json next to this file.
Upstream hero art (c) Oleg Dolya and Evan Debenham, GPL-3.0-or-later.
Every added pixel is placed by the hand-written maps below (digits index a palette ramp).
Derived from web-client/scripts/generate-avatars.py (same technique, copied, not imported).
"""

import json
from pathlib import Path

from PIL import Image, ImageOps

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
SPRITES = REPO / "core/src/main/assets/sprites"

FW, FH, FRAMES = 12, 15, 18
TIER = 1  # cloth-armour row: a real tunic to recolour (tier 0 is only a loincloth)
DEATH = range(8, 13)  # upstream death frames are a skull: no layers survive there
JOBS = ["warrior", "mage", "rogue", "huntress", "duelist", "cleric"]


def rgba(h):
    return (*bytes.fromhex(h), 255)


def ramp(s):
    return [rgba(h) for h in s.split()]


# ---------------------------------------------------------------- source ramps
# tone 0 = deepest shadow ... 4 = highlight. Extra colours map onto an existing tone.
SKIN_SRC = {
    "default": ("855938 be7f59 b89778 dcb497 ffdabf", {}),
    "duelist": ("2c1b13 351c15 5f3a34 6c4540 734e4d", {}),
}
HAIR_SRC = {
    "warrior": ("752b0e 97310d be390c c4703a dc7937", {"ad693c": 3}),
    "mage": ("6d7477 a6abad ced1d2 e8e8e9 f3f3f4", {"a7acae": 1}),
    "rogue": ("623709 85490c ab5e10 b88837 d19b3f", {}),
    "huntress": ("9e791c c8ba23 e2da5f f9ed43 fcf683", {}),
    "duelist": ("0f070b 190e14 1f131a 271a23 382833", {}),
    "cleric": ("411e0f 582a0f 63400f 774b0f 8a580f", {"753b0f": 2}),
}
MAIN_SRC = ("6b635b 9e9286 a59f98 c9c1b9 fbf2e7", {"f7e6d1": 4})
ACC_SRC = ("00134d 0054a6 3d689a 7da3c6 afc2d3", {"00468c": 1, "648aaf": 3})


def src_ramp(channel, job):
    if channel == "skin":
        return SKIN_SRC["duelist" if job == "duelist" else "default"]
    if channel == "hair":
        return HAIR_SRC[job]
    return MAIN_SRC if channel == "main" else ACC_SRC


def tone_table(channel, job):
    base, extra = src_ramp(channel, job)
    table = {c: i for i, c in enumerate(ramp(base))}
    table.update((rgba(h), t) for h, t in extra.items())
    return table


# ---------------------------------------------------------------- option palettes
# (id, label, ramp). "natural" = keep the upstream colours of each class.
SKIN = [
    ("light", "밝은", "855938 be7f59 b89778 dcb497 ffdabf"),
    ("pale", "창백", "8a5a50 c48c7c bfa29a eccab8 fff0e6"),
    ("tan", "구릿빛", "6a3c22 a4643a a07e5e c69868 e8bc88"),
    ("brown", "갈색", "40220f 6e3e22 74523e 946446 b88660"),
    ("deep", "짙은", "2c1b13 351c15 5f3a34 6c4540 734e4d"),
    ("orc", "녹색*", "2a3a1a 46602a 586c44 789a48 a2c46a"),
]
HAIR = [
    ("black", "흑", "161620 25232e 383543 4a4655 66606b"),
    ("chestnut", "밤", "36221b 523122 70452c 9a623c c48c54"),
    ("auburn", "적갈", "3a1a1d 61272a 903b34 b75841 dd835e"),
    ("ginger", "주황", "752b0e 97310d be390c c4703a dc7937"),
    ("blonde", "금", "7e5c1c a8862e cca848 e6ca6a f6e69c"),
    ("silver", "은", "515765 717985 9ca4ad c3c9ce e6e8e8"),
    ("teal", "청록*", "0e3a3e 145a5a 1e8078 38a894 70d0b4"),
    ("violet", "보라*", "2c1648 46246e 663a9a 8a5cc0 b48ae0"),
    ("rose", "분홍*", "5a1a3a 8a2a5a b8467e dc70a0 f4a4c4"),
    ("lime", "연두*", "1a3a12 2a5a1a 3e8024 62a838 98d060"),
]
# outfit = main cloth ramp + accent ramp (the upstream tunic's blue trim)
OUTFITS = [
    ("slate", "석판", "2c313c 454d5c 616b80 8790a4 b4bccb", "5a3a10 8a5a14 b88a22 dcb444 f4dc7a"),
    ("forest", "숲", "1f3324 2f4d33 466d43 66905a 92b37a", "3a2416 5c3a22 7e5430 a2764a c49a6a"),
    ("wine", "포도주", "3a1c2c 5c2840 853a55 aa566e cc7e8c", "5a3a10 8a5a14 b88a22 dcb444 f4dc7a"),
    ("ocean", "바다", "1a2c40 26445e 34627e 4c86a0 78aec0", "6e6450 9c9076 c4b896 e2d8b8 f8f2dc"),
    ("crimson", "진홍", "3e1014 661a1c 922824 bc4030 dc6c48", "101014 1c1c24 2c2c38 40404e 5a5a6a"),
    ("violet", "보라", "2a1a40 422a64 5e3e8a 7e5aae a684cc", "4a4e58 6e7480 9aa0aa c4c8ce e8eaec"),
    ("ochre", "황토", "4a3010 74501a 9c7426 c09c3c dcc068", "1c3420 2c5030 3e6e3e 58904e 80b064"),
    ("charcoal", "숯", "18181e 26262e 383842 4e4e5a 6a6a78", "4a0e10 781a18 a82a20 d04428 ec7040"),
    ("snow", "설백", "7a8290 a4acb8 c8ced6 e2e6ea f8fafc", "1a3a6a 2a5a9a 3a7cc4 64a2e0 a0ccf4"),
]

GOLD = "5a3a10 8a5a14 b88a22 dcb444 f4dc7a"
RED = "3e0c12 6a1620 9a2430 c43c3c e2685a"

# ---------------------------------------------------------------- pixel maps
# Maps are anchored like the existing generator: left = first eye x - 4, top = eye y - 4.
# In anchor space the two eyes sit at (4,4) and (7,4); the face spans x 3..9, chin y 7,
# the tunic starts at y 8. Digits index the item's palette; "." leaves the pixel alone.

# Bald skull: where an upstream hair pixel sat, put this skin tone ("." = drop the pixel).
SKULL = (
    "..........",
    "...12221..",
    "..1234431.",
    "..1234444.",
    "..12333333",
    "..12333333",
    "..12333332",
    "...222222.",
)

HAIR_STYLES = {
    "original": None,
    "bald": (),
    "crop": (
        "..........",
        "...1222...",
        "..1233321.",
        "..12......",
        "..1.......",
    ),
    "swept": (
        "..........",
        "...12332..",
        "..1234443.",
        "..1233332.",
        "..1.....2.",
    ),
    "long": (
        "..........",
        "...12221..",
        "..123333..",
        ".1233.....",
        ".122......",
        ".122......",
        ".121......",
        ".12.......",
        ".01.......",
        ".0........",
    ),
    "bob": (
        "..........",
        "...12331..",
        "..1234443.",
        ".12333332.",
        ".1222.....",
        ".1222.....",
        ".1221.....",
        "..11......",
    ),
    "ponytail": (
        "..........",
        "...12221..",
        "..123332..",
        ".312......",
        "121.......",
        "12........",
        "01........",
        ".0........",
    ),
    "braid": (
        "..........",
        "...12221..",
        "..123332..",
        "..12......",
        ".12.......",
        ".31.......",
        ".12.......",
        ".31.......",
        ".12.......",
        ".0........",
    ),
    "mohawk": (
        "....343...",
        "....2431..",
        "....121...",
    ),
    "curly": (
        "...3.3....",
        "..3232323.",
        ".323232323",
        ".2323.....",
        ".232......",
        ".121......",
    ),
    "spiky": (
        "..3.3.3...",
        "..232323..",
        "..1233332.",
        "..12...2..",
        "..1.......",
    ),
}

# Headgear: (palette or channel, hair-clip rows, map). A channel name instead of a palette
# means the item recolours with that customization channel (hood = outfit cloth).
HEADGEAR = {
    "hood": ("main", 8, (
        "....1221..",
        "..123333..",
        ".123333321",
        ".1222.....",
        ".122......",
        ".122......",
        ".121......",
        "..11......",
        "..01......",
    )),
    "wizard": ("2a1440 482466 6c3a94 9460c0 dcb444", 4, (
        "0122......",
        "..12332...",
        "...14441..",
        ".012222210",
    )),
    "helm": ("2e3238 50565e 7c848c aab2b8 dce2e4", 4, (
        "..........",
        "...12332..",
        "..1234443.",
        "..0111111.",
        "..01......",
        "..0.......",
    )),
    "bandana": (RED + " f0e0c8", 4, (
        "..........",
        "...12332..",
        "..123545..",
        ".2112222..",
        "21........",
        ".1........",
    )),
    "crown": (GOLD + " c43c3c", 1, (
        "...4.4.4..",
        "...23232..",
        "...15251..",
    )),
    "horns": ("5a4a3a 8c7a62 c0ae90 e8dcc2", 0, (
        "2.......3.",
        "21.....32.",
        ".1.....2..",
    )),
    "cap": ("283137 394b4d 59706a 7d8872", 3, (
        "...12221..",
        "..1232221.",
        ".011110000",
    )),
    "headband": ("1a3a6a 2a5a9a 3a7cc4 64a2e0", 0, (
        "..........",
        "..........",
        "..........",
        "..0123222.",
        "..0.......",
    )),
}

FACE = {
    "glasses": ("2a2420 4a4038 8a7a62", (
        "..........",
        "..........",
        "..........",
        "....2..2..",
        "...1.01.1.",
        "....1..1..",
    )),
    "eyepatch": ("101014 2c2c34 4a4a56", (
        "..........",
        "..........",
        "..........",
        "..000000..",
        ".......12.",
        ".......1..",
    )),
    "beard": ("hair", (
        "..........",
        "..........",
        "..........",
        "..........",
        "..1.......",
        "..12.....2",
        "..1222222.",
        "...122221.",
        "....111...",
    )),
    "moustache": ("hair", (
        "..........",
        "..........",
        "..........",
        "..........",
        "..........",
        "..........",
        ".....1221.",
    )),
    "mask": ("1c1c24 2c2c38 40404e 5a5a6a", (
        "..........",
        "..........",
        "..........",
        "..........",
        "..........",
        "..0122223.",
        "..1122222.",
        "...11111..",
    )),
    "scar": ("6a1620 9a2430", (
        "..........",
        "..........",
        "..........",
        ".......1..",
        "..........",
        "........0.",
    )),
}

# Back/neck items: (palette or channel, behind-map, front-map). Behind is drawn under the
# body (only what sticks out shows); front goes over the tunic.
E8 = ("..........",) * 8  # rows above the shoulders
BACK = {
    "cape": ("acc", E8 + (
        "..........",
        "0.........",
        "01........",
        "01........",
        "011.......",
        ".0........",
    ), E8 + (
        "..234.....",
        ".1232.....",
        ".1232.....",
        ".1221.....",
        ".121......",
    )),
    "scarf": ("6a4a10 9a6a14 c8962a ecc450 9a2430", E8[:7] + (
        "..........",
        "23........",
        ".2........",
    ), E8[:7] + (
        "..........",
        "..234323..",
        "..43......",
        "..3.......",
    )),
    "quiver": ("3a2416 5c3a22 7e5430 a2764a e8e0d0 c43c3c", (
        "..........", "..........", "..........", "..........",
        "45........",
        "454.......",
        ".22.......",
    ), E8[:6] + (
        "..........",
        ".12.......",
        "..23....0.",
        "..123..0..",
        "...12.0...",
        "....1.....",
    )),
    "backpack": ("3a2416 5c3a22 7e5430 a2764a dcb444", E8[:7] + (
        "1.........",
        "1.........",
        "1.........",
    ), E8[:7] + (
        ".122......",
        ".1233.....",
        ".1243.....",
        ".1233.....",
        "..111.....",
    )),
}

PATTERNS = ["trim", "sash", "twotone"]

# ---------------------------------------------------------------- frame analysis


def eyes(frame):
    for y in range(3, 8):
        dark = [x for x in range(FW) if frame.getpixel((x, y)) == (0, 0, 0, 255)]
        if len(dark) == 2 and dark[1] - dark[0] == 3:
            return dark[0], y
    raise ValueError("Unexpected upstream hero eye placement")


def load_frames(job):
    with Image.open(SPRITES / f"{job}.png") as png:
        sheet = png.convert("RGBA")
    top = TIER * FH
    return [sheet.crop((i * FW, top, i * FW + FW, top + FH)) for i in range(FRAMES)]


class Px:
    """One layer pixel: a channel tone (recolourable) with its natural colour."""
    __slots__ = ("channel", "tone", "color")

    def __init__(self, channel, tone, color):
        self.channel, self.tone, self.color = channel, tone, color


def stamp(layer, eye, shape, palette, job, top_shift=0):
    """Place a hand-drawn map. palette: hex ramp string or a channel name."""
    left, top = eye[0] - 4, eye[1] - 4 + top_shift
    channel = palette if palette in ("skin", "hair", "main", "acc") else None
    natural = ramp(src_ramp(channel, job)[0]) if channel else ramp(palette)
    for dy, row in enumerate(shape):
        for dx, ch in enumerate(row):
            x, y = left + dx, top + dy
            if ch != "." and 0 <= x < FW and 0 <= y < FH:
                t = int(ch)
                layer[(x, y)] = Px(channel, t, natural[t])


class FrameLayers:
    def __init__(self, job, index, frame):
        self.job, self.index = job, index
        self.mirrored = index == 1  # upstream draws the second idle cell facing left
        self.death = index in DEATH
        self.layers = {}
        if self.death:
            self.eye = None
            self.layers["base"] = {(x, y): Px(None, 0, frame.getpixel((x, y)))
                                   for y in range(FH) for x in range(FW) if frame.getpixel((x, y))[3]}
            return
        f = ImageOps.mirror(frame) if self.mirrored else frame
        self.eye = eye = eyes(f)
        ex, ey = eye
        hair_t, skin_t = tone_table("hair", job), tone_table("skin", job)
        main_t, acc_t = tone_table("main", job), tone_table("acc", job)
        skin_nat = ramp(src_ramp("skin", job)[0])
        base, hair, cloth = {}, {}, {}
        for y in range(FH):
            for x in range(FW):
                p = f.getpixel((x, y))
                if not p[3]:
                    continue
                if y <= ey + 3 and p in hair_t:
                    hair[(x, y)] = Px("hair", hair_t[p], p)
                    dx, dy = x - (ex - 4), y - (ey - 4)
                    ch = SKULL[dy][dx] if 0 <= dy < len(SKULL) and 0 <= dx < 10 else "."
                    if ch != ".":
                        base[(x, y)] = Px("skin", int(ch), skin_nat[int(ch)])
                elif y >= ey + 4 and p in main_t:
                    cloth[(x, y)] = Px("main", main_t[p], p)
                elif y >= ey + 4 and p in acc_t:
                    cloth[(x, y)] = Px("acc", acc_t[p], p)
                elif p in skin_t:
                    base[(x, y)] = Px("skin", skin_t[p], p)
                else:
                    base[(x, y)] = Px(None, 0, p)
        # upstream skin pixels behind the skull (e.g. the Warrior's wide jaw line) belong to
        # the original hairstyle silhouette; every other style would leave them orphaned.
        for (x, y) in [k for k in base if k[1] <= ey + 3 and k[0] < ex - 2]:
            hair[(x, y)] = base.pop((x, y))
        L = self.layers
        L["base"], L["cloth"] = base, cloth
        # --- patterns, derived from the cloth mask so they follow every pose
        gold, red = ramp(GOLD), ramp(RED)
        trim, sash, two = {}, {}, {}
        for (x, y), px in cloth.items():
            if y in (ey + 4, ey + 7):
                trim[(x, y)] = Px(None, px.tone, gold[max(1, px.tone)])
            if ey + 4 <= y <= ey + 7 and (x - ex) - (y - ey) in (-5, -4):
                sash[(x, y)] = Px(None, px.tone, red[min(4, px.tone + (0 if (x - ex) - (y - ey) == -5 else 1))])
            if y >= ey + 8 and px.channel == "main":
                two[(x, y)] = Px("acc", px.tone, ramp(ACC_SRC[0])[px.tone])
        L["pattern:trim"], L["pattern:sash"], L["pattern:twotone"] = trim, sash, two
        # --- hair
        for style, shape in HAIR_STYLES.items():
            layer = {}
            if shape is None:
                layer = dict(hair)
            else:
                stamp(layer, eye, shape, "hair", job)
            L["hair:" + style] = layer
        for name, (pal, _clip, shape) in HEADGEAR.items():
            layer = {}
            stamp(layer, eye, shape, pal, job)
            L["head:" + name] = layer
        for name, (pal, shape) in FACE.items():
            layer = {}
            stamp(layer, eye, shape, pal, job)
            L["face:" + name] = layer
        for name, (pal, behind, front) in BACK.items():
            b, fr = {}, {}
            stamp(b, eye, behind, pal, job)
            stamp(fr, eye, front, pal, job)
            L["back:" + name], L["neck:" + name] = b, fr

    def image(self, key, sel=None):
        img = Image.new("RGBA", (FW, FH))
        for (x, y), px in self.layers.get(key, {}).items():
            img.putpixel((x, y), recolor(px, sel))
        return ImageOps.mirror(img) if self.mirrored else img


def palette_of(channel, option):
    table = {"skin": SKIN, "hair": HAIR}
    if channel in table:
        for oid, _l, r in table[channel]:
            if oid == option:
                return ramp(r)
    for oid, _l, main, acc in OUTFITS:
        if oid == option:
            return ramp(main if channel == "main" else acc)
    return None


def recolor(px, sel):
    if sel and px.channel:
        key = "outfit" if px.channel in ("main", "acc") else px.channel
        pal = palette_of(px.channel, sel.get(key, "natural"))
        if pal:
            return pal[px.tone]
    return px.color


# ---------------------------------------------------------------- compositor
ROWS = (["base", "cloth"] + ["pattern:" + p for p in PATTERNS]
        + ["hair:" + s for s in HAIR_STYLES] + ["head:" + h for h in HEADGEAR]
        + ["face:" + f for f in FACE] + ["back:" + b for b in BACK] + ["neck:" + b for b in BACK])


def composite(fl, look):
    """look: dict skin/hair/outfit/style/pattern/head/face/back -> option id."""
    sel = {k: look.get(k, "natural") for k in ("skin", "hair", "outfit")}
    out = Image.new("RGBA", (FW, FH))

    def put(key):
        if key in fl.layers:
            out.alpha_composite(fl.image(key, sel))

    if fl.death:
        put("base")
        return out
    back, head = look.get("back", "none"), look.get("head", "none")
    face, style = look.get("face", "none"), look.get("style", "original")
    put("back:" + back)
    put("base")
    put("cloth")
    put("pattern:" + look.get("pattern", "none"))
    put("neck:" + back)
    hair = fl.image("hair:" + style, sel)
    if head in HEADGEAR:  # compositor rule: headgear hides hair above its clip row
        clip = fl.eye[1] - 4 + HEADGEAR[head][1]
        for y in range(clip):
            for x in range(FW):
                hair.putpixel((x, y), (0, 0, 0, 0))
    if face in ("beard", "moustache"):
        put("face:" + face)
        out.alpha_composite(hair)
    else:
        out.alpha_composite(hair)
        put("face:" + face)
    put("head:" + head)
    return out


def build_all():
    return {job: [FrameLayers(job, i, f) for i, f in enumerate(load_frames(job))] for job in JOBS}


def main():
    data = build_all()
    sheet = Image.new("RGBA", (len(JOBS) * FRAMES * FW, len(ROWS) * FH))
    anchors = {}
    for j, job in enumerate(JOBS):
        anchors[job] = []
        for fl in data[job]:
            anchors[job].append(None if fl.death else
                                {"eye": list(fl.eye), "mirrored": fl.mirrored})
            for r, key in enumerate(ROWS):
                sheet.paste(fl.image(key), ((j * FRAMES + fl.index) * FW, r * FH))
    sheet.save(HERE / "sheet.png", optimize=True)

    def ramps(channel, job):
        base, extra = src_ramp(channel, job)
        return {"tones": ["#" + h for h in base.split()], "extra": {"#" + k: v for k, v in extra.items()}}

    catalog = {
        "frameWidth": FW, "frameHeight": FH, "frames": FRAMES,
        "layout": "one 216px column block per job, one 15px row per layer key; "
                  "natural colours; recolour = exact source-colour -> tone -> option ramp",
        "jobs": JOBS, "rows": ROWS,
        "recolourChannels": {"base": "skin", "cloth": "outfit", "pattern:twotone": "outfit",
                             "hair:*": "hair", "head:hood": "outfit", "face:beard": "hair",
                             "face:moustache": "hair", "back:cape": "outfit", "neck:cape": "outfit"},
        "drawOrder": ["back:*", "base", "cloth", "pattern:*", "neck:*", "face:beard|moustache",
                      "hair:* (clipped by head:*.hairClip)", "face:*", "head:*"],
        "headgearHairClip": {k: v[1] for k, v in HEADGEAR.items()},
        "sourceRamps": {job: {c: ramps(c, job) for c in ("skin", "hair", "main", "acc")} for job in JOBS},
        "skinTones": [{"id": i, "label": l, "ramp": ["#" + h for h in r.split()]} for i, l, r in SKIN],
        "hairColors": [{"id": i, "label": l, "ramp": ["#" + h for h in r.split()]} for i, l, r in HAIR],
        "outfits": [{"id": i, "label": l, "main": ["#" + h for h in m.split()],
                     "accent": ["#" + h for h in a.split()]} for i, l, m, a in OUTFITS],
        "hairStyles": list(HAIR_STYLES), "patterns": ["none"] + PATTERNS,
        "headgear": ["none"] + list(HEADGEAR), "face": ["none"] + list(FACE),
        "back": ["none"] + list(BACK),
        "anchors": anchors,
    }
    (HERE / "sheet.json").write_text(json.dumps(catalog, ensure_ascii=False, indent=1) + "\n",
                                     encoding="utf-8")
    print("sheet", sheet.size, "rows", len(ROWS))


if __name__ == "__main__":
    main()
