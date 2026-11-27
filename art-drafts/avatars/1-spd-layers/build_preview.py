# SPDX-License-Identifier: GPL-3.0-or-later
"""Contact sheet for judging approach 1. Run after (or instead of) avatar_layers.py."""

from PIL import Image, ImageDraw, ImageFont

import avatar_layers as A

S = 6  # nearest-neighbour scale
ENV = A.REPO / "core/src/main/assets/environment/tiles_sewers.png"
RAT = A.SPRITES / "rat.png"
FONT = ImageFont.truetype("/usr/share/fonts/opentype/unifont/unifont.otf", 16)
BG = (22, 20, 28, 255)
INK = (214, 206, 186, 255)
DIM = (130, 124, 112, 255)
BAD = (226, 92, 76, 255)
AMBER = (226, 178, 72, 255)
CELL = 16 * S  # one SPD tile per cell
GAP = 10
LABEL_W = 150

# 1x verdicts, decided by looking at the native 1x copies (and a pixel-diff count, see NOTES).
# FAIL: cannot be told apart from a neighbouring option (or from nothing) at 1x.
# WEAK: readable only on some classes / colours, or only as "something is there".
FAIL = {"style:crop", "style:swept", "style:spiky", "style:braid", "skin:pale", "hair:auburn",
        "face:scar", "face:moustache", "face:glasses"}
WEAK = {"style:ponytail", "style:bald", "skin:tan", "head:cap", "head:headband", "back:quiver",
        "back:backpack", "pattern:sash", "pattern:trim", "outfit:snow"}

with Image.open(ENV) as e:
    TILES = e.convert("RGBA")
with Image.open(RAT) as r:
    RATS = r.convert("RGBA")


def tile(tx, ty):
    return TILES.crop((tx * 16, ty * 16, tx * 16 + 16, ty * 16 + 16))


FLOOR = tile(0, 0)


def up(img, s=S):
    return img.resize((img.width * s, img.height * s), Image.NEAREST)


def on_floor(sprite):
    t = FLOOR.copy()
    t.alpha_composite(sprite, (2, 1))
    return t


def text(draw, xy, s, fill=INK):
    draw.text(xy, s, font=FONT, fill=fill)


class Page:
    def __init__(self, w, h):
        self.im = Image.new("RGBA", (w, h), BG)
        self.d = ImageDraw.Draw(self.im)
        self.d.fontmode = "1"
        self.y = 16

    def title(self, s):
        self.y += 8
        text(self.d, (16, self.y), s)
        self.d.line((16, self.y + 20, self.im.width - 16, self.y + 20), fill=DIM)
        self.y += 30

    def row(self, label, sprites, captions, bad=(), weak=()):
        """sprites at xS on floor tiles, each with its native 1x copy underneath."""
        text(self.d, (16, self.y + CELL // 2 - 8), label, DIM)
        x = 16 + LABEL_W
        for i, (sp, cap) in enumerate(zip(sprites, captions)):
            self.im.alpha_composite(up(on_floor(sp)), (x, self.y))
            self.im.alpha_composite(on_floor(sp), (x + CELL // 2 - 8, self.y + CELL + 4))
            self.im.alpha_composite(up(on_floor(sp), 2), (x + CELL // 2 + 12, self.y + CELL + 4 - 8))
            color = BAD if cap in bad else AMBER if cap in weak else INK
            mark = " X" if cap in bad else " ~" if cap in weak else ""
            text(self.d, (x, self.y + CELL + 38), cap[:10] + mark, color)
            x += CELL + GAP
        self.y += CELL + 64


def main():
    data = A.build_all()
    base_job = "rogue"
    f0 = data[base_job][0]
    page = Page(1580, 6400)
    page.title("1. 축별 단독 (rogue, idle 0). 칸 아래 = 실제 1x / 2x.  빨강 X = 1x에서 구분 불가, 노랑 ~ = 약함")

    def axis(label, key, ids, prefix, extra=None):
        looks = [dict(extra or {}, **{key: i}) for i in ids]
        bad = {i for i in ids if f"{prefix}:{i}" in FAIL}
        weak = {i for i in ids if f"{prefix}:{i}" in WEAK}
        page.row(label, [A.composite(f0, lk) for lk in looks], ids, bad, weak)

    axis("skin 피부", "skin", ["natural"] + [s[0] for s in A.SKIN], "skin", {"style": "crop"})
    axis("hair colour", "hair", ["natural"] + [h[0] for h in A.HAIR], "hair")
    styles = list(A.HAIR_STYLES)
    axis("hair style", "style", styles, "style", {"hair": "chestnut"})
    axis("outfit 옷색", "outfit", ["natural"] + [o[0] for o in A.OUTFITS], "outfit")
    axis("pattern 무늬", "pattern", ["none"] + A.PATTERNS, "pattern", {"outfit": "slate"})
    axis("headgear 모자", "head", ["none"] + list(A.HEADGEAR), "head")
    axis("face 얼굴", "face", ["none"] + list(A.FACE), "face")
    axis("back/neck 등", "back", ["none"] + list(A.BACK), "back")

    page.title("2. 완성 캐릭터 조합 (직업당 2명)")
    looks = [
        ("warrior", dict(hair="black", style="crop", outfit="crimson", pattern="trim", head="helm", face="beard", back="cape")),
        ("warrior", dict(skin="brown", hair="silver", style="mohawk", outfit="charcoal", face="eyepatch", back="scarf")),
        ("mage", dict(hair="silver", style="long", outfit="violet", head="wizard", face="beard", back="cape")),
        ("mage", dict(skin="tan", hair="teal", style="bob", outfit="ocean", pattern="sash", face="glasses")),
        ("rogue", dict(hair="black", outfit="charcoal", head="hood", face="mask", back="backpack")),
        ("rogue", dict(skin="pale", hair="rose", style="ponytail", outfit="wine", pattern="twotone", head="bandana")),
        ("huntress", dict(hair="auburn", style="braid", outfit="forest", head="none", back="quiver")),
        ("huntress", dict(skin="deep", hair="lime", style="curly", outfit="ochre", pattern="sash", back="quiver")),
        ("duelist", dict(style="ponytail", outfit="crimson", pattern="trim", face="scar", back="scarf")),
        ("duelist", dict(skin="light", hair="blonde", style="swept", outfit="snow", head="crown", back="cape")),
        ("cleric", dict(hair="ginger", style="bald", outfit="snow", pattern="trim", face="beard", back="cape")),
        ("cleric", dict(skin="orc", hair="black", style="spiky", outfit="slate", head="horns", face="eyepatch")),
    ]
    for half in (looks[:6], looks[6:]):
        page.row("", [A.composite(data[j][0], lk) for j, lk in half], [j for j, _ in half])

    page.title("3. 애니메이션: idle 0-1 / run 2-7 / death 8-12 (원본=해골) / attack 13-15 / 16-17")
    for j, lk in (looks[0], looks[6]):
        frames = [A.composite(data[j][i], lk) for i in range(A.FRAMES)]
        x = 16
        for i, fr in enumerate(frames):
            page.im.alpha_composite(up(on_floor(fr), 5), (x, page.y))
            page.im.alpha_composite(on_floor(fr), (x + 32, page.y + 84))
            text(page.d, (x + 2, page.y + 2), str(i), DIM)
            x += 16 * 5 + 6
        page.y += 16 * 5 + 30

    page.title("4. 스케일 참고: SPD 하수도 타일(16x16) + 쥐. 왼쪽 x4, 오른쪽 실제 1x")
    cols, rws = 10, 5
    scene = Image.new("RGBA", (cols * 16, rws * 16))
    for x in range(cols):
        scene.alpha_composite(tile(x % 2, 3), (x * 16, 0))
        for y in range(1, rws):
            scene.alpha_composite(tile((x * 7 + y * 3) % 3 if (x + y) % 4 == 0 else 0, 0), (x * 16, y * 16))
    party = [(1, 2, looks[0]), (3, 2, looks[2]), (2, 3, looks[6]), (4, 3, looks[4]), (6, 2, looks[9])]
    for tx, ty, (j, lk) in party:
        scene.alpha_composite(A.composite(data[j][0], lk), (tx * 16 + 2, ty * 16 + 1))
    rat = RATS.crop((0, 0, 16, 15))
    scene.alpha_composite(ImageOpsMirror(rat), (8 * 16, 3 * 16 + 1))
    scene.alpha_composite(rat, (7 * 16, 2 * 16 + 1))
    page.im.alpha_composite(up(scene, 4), (16, page.y))
    page.im.alpha_composite(scene, (16 + cols * 64 + 24, page.y))
    page.im.alpha_composite(up(scene, 2), (16 + cols * 64 + 24, page.y + rws * 16 + 16))
    page.y += rws * 64 + 20

    page.im.crop((0, 0, page.im.width, page.y + 8)).save(A.HERE / "preview.png", optimize=True)
    print("preview", page.im.width, page.y)


def ImageOpsMirror(img):
    from PIL import ImageOps
    return ImageOps.mirror(img)


if __name__ == "__main__":
    main()
