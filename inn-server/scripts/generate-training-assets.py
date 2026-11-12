# SPDX-License-Identifier: GPL-3.0-or-later
"""Draw the ATB practice sprites shared by the browser and Windows clients."""

from pathlib import Path

from PIL import Image


TILE = 16
PALETTE = {
    "k": "#25201f", "d": "#4b2d27", "w": "#795139", "o": "#aa7546", "h": "#d4a56a",
    "s": "#c59243", "l": "#f3ce78", "c": "#e4cdb0", "p": "#ae9580",
    "r": "#ab5444", "b": "#6b3633", "t": "#2f5356", "g": "#537e77",
    "m": "#83b3a4", "i": "#b5d9be", "a": "#773e60", "v": "#d27997",
    "e": "#f2b2b0",
}

# Three transparent 16×16 cells. Anchoring the feet on the same row keeps the
# props aligned with the dungeon tiles at both clients' pixel scales.
SPRITES = (
    (  # Straw dummy with a hat, outstretched arms, and a stitched target.
        "......kkkk......",
        ".....kdoohk.....",
        "....kwoohhhk....",
        "...kkdwwowdkk...",
        "....klslslk.....",
        "....klksklk.....",
        ".kkkkscsckkkkk..",
        "kwsslcpcclssowk.",
        "klslcpbpcslshok.",
        ".kkkkprrpkkkkk..",
        ".....pbbpk......",
        "....kcscppk.....",
        "....kkkwwkk.....",
        "......kwwk......",
        "....kkdoodkk....",
        "...kkkkkkkkkk...",
    ),
    (  # Open ration chest with three loaves.
        "................",
        "................",
        "................",
        ".....kkk........",
        "...kkhoskkkk....",
        "..klhhskslhsk...",
        "..koshklhhoskk..",
        ".kkdkkoskkkkkkk.",
        ".khohohohohhohk.",
        ".kwowowwwowwowk.",
        ".koddkkhkkddook.",
        ".kwowkccpkowowk.",
        ".kdwdkkkdkdwdwk.",
        ".kwowowowowodwk.",
        ".kkkkkkkkkkkkkk.",
        "................",
    ),
    (  # Recovery satchel with a bright cross and a stoppered flask.
        "................",
        "..........kkkk..",
        "..........kook..",
        ".........kkcikk.",
        ".........kcaeak.",
        "........kcevvak.",
        "...kkkkkkcevavk.",
        "..kgmmmtkavvaak.",
        ".ktmmimtkkkakk..",
        ".kgmiimggtmggk..",
        ".kgiiiiiitmgmk..",
        ".kgmiimggggmmk..",
        ".ktmmimtggmmgk..",
        "..kgmmgggmmtk...",
        "...kkkkkkkkkk...",
        "................",
    ),
)


def main():
    atlas = Image.new("RGBA", (TILE * len(SPRITES), TILE))
    for column, rows in enumerate(SPRITES):
        assert len(rows) == TILE
        for y, row in enumerate(rows):
            assert len(row) == TILE, (column, y, row)
            for x, pixel in enumerate(row):
                if pixel != ".":
                    color = bytes.fromhex(PALETTE[pixel][1:])
                    atlas.putpixel((column * TILE + x, y), (*color, 255))
    output = Path(__file__).resolve().parents[1] / "worker/play/assets/training.png"
    atlas.save(output, optimize=True)


if __name__ == "__main__":
    main()
