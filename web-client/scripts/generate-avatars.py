# SPDX-License-Identifier: GPL-3.0-or-later
"""Build layered inn and original-game portraits from the upstream hero sprites.

Run from any directory with Python 3 and Pillow installed.
Upstream hero art © Oleg Dolya and Evan Debenham, GPL-3.0-or-later.
"""

import json
from pathlib import Path

from PIL import Image, ImageOps


FRAME_WIDTH = 12
FRAME_HEIGHT = 15
FRAME_COUNT = 18
ADVENTURE_FRAME_COUNT = 21
LAYER_COLUMNS = 8
DEATH_FRAMES = range(8, 13)
JOBS = {
    "warrior": "전사", "mage": "마법사", "rogue": "도적", "huntress": "사냥꾼",
    "duelist": "결투자", "cleric": "성직자",
}
HAIR = {
    "chestnut": ("밤색", "36221b 523122 70452c 9a623c c48c54"),
    "black": ("흑색", "161620 25232e 383543 4a4655 66606b"),
    "silver": ("은빛", "515765 717985 9ca4ad c3c9ce e6e8e8"),
    "auburn": ("적갈색", "3a1a1d 61272a 903b34 b75841 dd835e"),
}
OUTFITS = {
    "slate": ("석판색", "33343b 494b56 636674 808596 a1a8b5"),
    "forest": ("숲색", "26372b 3a5237 507246 6d9257 90ab73"),
    "wine": ("포도주색", "412d39 63374b 864458 a65a6d c67984"),
    "ocean": ("바다색", "253c49 365b70 487a92 689db1 8fbcc5"),
}
HAIR_STYLES = {"original": "원래 머리", "swept": "옆가르마", "crop": "짧은 머리", "tie": "묶은 머리"}
ACCESSORIES = {"none": "없음", "round-glasses": "둥근 안경", "headband": "머리띠", "cap": "천 모자"}

# The native palette replaces only these colors in the face/crown mask. Extra
# colors keep small accents such as the Mage's drinking pose and Warrior's beard.
SOURCE_HAIR = {
    "warrior": ("752b0e 97310d be390c c4703a dc7937", {"ad693c": 3}),
    "mage": ("6d7477 a6abad ced1d2 e8e8e9 f3f3f4", {"a7acae": 1}),
    "rogue": ("623709 85490c ab5e10 b88837 d19b3f", {}),
    "huntress": ("9e791c c8ba23 e2da5f f9ed43 fcf683", {}),
    "duelist": ("0f070b 190e14 1f131a 271a23 382833", {}),
    "cleric": ("411e0f 582a0f 63400f 774b0f 8a580f", {"753b0f": 2}),
}
SOURCE_CLOTH = "7e7e7e a3a3a3 b3b3b3 cccccc ffffff"
DUELIST_CLOTH = "000000 121212 191919 262626 3d3d3d"
CLOTH_ARMOR = "6b635b 9e9286 a59f98 c9c1b9 fbf2e7"

# Hand-drawn pixels anchored four columns left and four rows above the first eye.
# Digits are shadow through highlight; a dot leaves the pixel transparent.
HAIR_SHAPES = {
    "swept": (
        "....334...",
        "..2344432.",
        ".123333221",
    ),
    "crop": (
        "..........",
        "..........",
        "..122321..",
        ".122222110",
    ),
    "tie": (
        "....111...",
        "...122321.",
        ".211233321",
        ".321122210",
        "12........",
        "01........",
        ".2........",
    ),
}
ACCESSORY_SHAPES = {
    "round-glasses": (
        "6f4b36 b68e62 e2bc7f",
        (
            "..........",
            "..........",
            "..........",
            "....2..2..",
            "...1.01.1.",
            "....1..1..",
        ),
    ),
    "headband": (
        "552e34 974955 c9796a efba79",
        (
            "..........",
            "..........",
            ".012322210",
            ".0........",
        ),
    ),
    "cap": (
        "283137 394b4d 59706a 7d8872",
        (
            "...12221..",
            "..1232221.",
            ".011110000",
        ),
    ),
}


def rgba(hex_color):
    return (*bytes.fromhex(hex_color), 255)


def colors(palette):
    return [rgba(hex_color) for hex_color in palette.split()]


def option_list(entries):
    return [{"id": key, "label": label} for key, label in entries.items()]


def color_options(entries):
    return [{"id": "natural", "label": "기본"}] + [
        {"id": key, "label": label, "swatch": "#" + palette.split()[3]}
        for key, (label, palette) in entries.items()
    ]


CATALOG = {
    "frameWidth": FRAME_WIDTH, "frameHeight": FRAME_HEIGHT, "frames": FRAME_COUNT,
    "jobs": option_list(JOBS), "hairColors": color_options(HAIR), "outfitColors": color_options(OUTFITS),
    "hairStyles": option_list(HAIR_STYLES), "accessories": option_list(ACCESSORIES),
}


def frame_image():
    return Image.new("RGBA", (FRAME_WIDTH, FRAME_HEIGHT))


def eyes(frame):
    for y in range(3, 8):
        dark = [x for x in range(FRAME_WIDTH) if frame.getpixel((x, y)) == (0, 0, 0, 255)]
        if len(dark) == 2 and dark[1] - dark[0] == 3:
            return dark[0], y
    raise ValueError("Unexpected upstream hero eye placement")


def place_shape(image, eye, shape, palette):
    left, top = eye[0] - 4, eye[1] - 4
    for dy, row in enumerate(shape):
        for dx, pixel in enumerate(row):
            x, y = left + dx, top + dy
            if pixel != "." and 0 <= x < FRAME_WIDTH and 0 <= y < FRAME_HEIGHT:
                image.putpixel((x, y), palette[int(pixel)])


def extract_layers(frame, job, eye, tier):
    hair_palette, accents = SOURCE_HAIR[job]
    hair_tones = {color: i for i, color in enumerate(colors(hair_palette))}
    hair_tones.update((rgba(color), tone) for color, tone in accents.items())
    if tier == 0:
        cloth_palette = DUELIST_CLOTH if job == "duelist" else SOURCE_CLOTH
    else:
        cloth_palette = CLOTH_ARMOR if tier == 1 else ""
    cloth_tones = {color: i for i, color in enumerate(colors(cloth_palette))}
    base = frame.copy()
    hair, cloth = {}, {}
    for y in range(FRAME_HEIGHT):
        for x in range(FRAME_WIDTH):
            point = (x, y)
            pixel = frame.getpixel(point)
            if y <= eye[1] + 3 and pixel in hair_tones:
                hair[point] = (pixel, hair_tones[pixel])
            elif y >= 8 and pixel in cloth_tones:
                cloth[point] = (pixel, cloth_tones[pixel])
            else:
                continue
            base.putpixel(point, (0, 0, 0, 0))
    return base, hair, cloth


def fabric_layer(cloth, color):
    palette = colors(OUTFITS[color][1]) if color != "natural" else None
    image = frame_image()
    for point, (source, tone) in cloth.items():
        image.putpixel(point, source if palette is None else palette[tone])
    return image


def hairstyle_layer(job, eye, hair, style, color):
    palette = colors(SOURCE_HAIR[job][0] if color == "natural" else HAIR[color][1])
    skin = rgba("6c4540" if job == "duelist" else "dcb497")
    eye_x, eye_y = eye
    image = frame_image()
    for point, (source, tone) in hair.items():
        x, y = point
        replace_crown = style != "original" and y <= eye_y - (2 if style == "swept" else 1)
        trim_locks = style in ("crop", "tie") and job != "warrior" and y >= eye_y and x < eye_x
        if replace_crown:
            continue
        if trim_locks:
            if x == eye_x - 1 and y < eye_y + 2:
                image.putpixel(point, skin)
        else:
            image.putpixel(point, source if color == "natural" else palette[tone])
    if style != "original":
        place_shape(image, eye, HAIR_SHAPES[style], palette)
    return image


def frame_layers(source, job, frame_index, tier=0):
    left = frame_index * FRAME_WIDTH
    top = tier * FRAME_HEIGHT
    frame = source.crop((left, top, left + FRAME_WIDTH, top + FRAME_HEIGHT))
    if frame_index in DEATH_FRAMES:
        return [frame]
    # The second idle cell is drawn facing left by upstream.
    mirrored = frame_index == 1
    if mirrored:
        frame = ImageOps.mirror(frame)
    eye = eyes(frame)
    base, hair, cloth = extract_layers(frame, job, eye, tier)
    layers = [base]
    layers.extend(fabric_layer(cloth, entry["id"]) for entry in CATALOG["outfitColors"])
    layers.extend(hairstyle_layer(job, eye, hair, style, entry["id"])
                  for style in HAIR_STYLES for entry in CATALOG["hairColors"])
    layers.append(frame_image())  # The none accessory has a transparent row.
    for palette, shape in ACCESSORY_SHAPES.values():
        accessory = frame_image()
        place_shape(accessory, eye, shape, colors(palette))
        layers.append(accessory)
    return [ImageOps.mirror(layer) for layer in layers] if mirrored else layers


def build_atlas(source_path, output_path, job):
    rows = 1 + len(CATALOG["outfitColors"]) + len(HAIR_STYLES) * len(CATALOG["hairColors"]) + len(ACCESSORIES)
    atlas = Image.new("RGBA", (FRAME_COUNT * FRAME_WIDTH, rows * FRAME_HEIGHT))
    with Image.open(source_path) as png:
        source = png.convert("RGBA")
    for frame in range(FRAME_COUNT):
        for row, layer in enumerate(frame_layers(source, job, frame)):
            atlas.paste(layer, (frame * FRAME_WIDTH, row * FRAME_HEIGHT))
    atlas.save(output_path, optimize=True)


def build_adventure_atlas(source_path, output_path, job):
    with Image.open(source_path) as png:
        source = png.convert("RGBA")
    layers = 1 + len(CATALOG["outfitColors"]) + len(HAIR_STYLES) * len(CATALOG["hairColors"]) + len(ACCESSORIES)
    width, height = source.size
    atlas = Image.new("RGBA", (LAYER_COLUMNS * width, ((layers + LAYER_COLUMNS - 1) // LAYER_COLUMNS) * height))
    for tier in range(7):
        for frame in range(ADVENTURE_FRAME_COUNT):
            for row, layer in enumerate(frame_layers(source, job, frame, tier)):
                x = row % LAYER_COLUMNS * width + frame * FRAME_WIDTH
                y = row // LAYER_COLUMNS * height + tier * FRAME_HEIGHT
                atlas.paste(layer, (x, y))
    atlas.save(output_path, optimize=True)


def main():
    repo = Path(__file__).resolve().parents[2]
    upstream = repo / "core/src/main/assets/sprites"
    play = repo / "web-client/play"
    avatars = play / "assets/avatars"
    avatars.mkdir(parents=True, exist_ok=True)
    adventure_avatars = upstream / "inn-avatars"
    adventure_avatars.mkdir(exist_ok=True)
    layout = [str(LAYER_COLUMNS)]
    for option in ("outfitColors", "hairStyles", "hairColors", "accessories"):
        layout.append(",".join(entry["id"] for entry in CATALOG[option]))
    (adventure_avatars / "layers.txt").write_text("\n".join(layout) + "\n", encoding="utf-8", newline="\n")
    for job in JOBS:
        build_atlas(upstream / f"{job}.png", avatars / f"{job}.png", job)
        build_adventure_atlas(upstream / f"{job}.png", adventure_avatars / f"{job}.png", job)

    data = json.dumps(CATALOG, ensure_ascii=False, indent=2)
    (avatars / "catalog.json").write_text(data + "\n", encoding="utf-8", newline="\n")
    module = """// SPDX-License-Identifier: GPL-3.0-or-later
// Generated by scripts/generate-avatars.py.
export const AvatarCatalog = Object.freeze(CATALOG_DATA);

const options = {
  job: AvatarCatalog.jobs,
  hairColor: AvatarCatalog.hairColors,
  outfitColor: AvatarCatalog.outfitColors,
  hairStyle: AvatarCatalog.hairStyles,
  accessory: AvatarCatalog.accessories,
};

export function normalizeAppearance(value) {
  const appearance = {};
  for (const [field, choices] of Object.entries(options)) {
    const choice = choices.find(({ id }) => id === value?.[field]);
    appearance[field] = choice?.id ?? choices[0].id;
  }
  return appearance;
}
"""
    (play / "avatar-catalog.mjs").write_text(module.replace("CATALOG_DATA", data), encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
