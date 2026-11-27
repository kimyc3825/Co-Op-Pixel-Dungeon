# SPDX-License-Identifier: GPL-3.0-or-later
"""Hand-placed pixel maps for the original 16x18 co-op avatar base (draft 2).

Every grid here was typed by hand for this draft; nothing is traced or copied
from Shattered Pixel Dungeon or any other asset.  One character = one pixel.

Character legend (shared by every grid)
---------------------------------------
  .            transparent
  a b c d e    skin ramp        (a = outline ... e = highlight)
  h i j k l    hair ramp        (h = outline ... l = highlight)
  p q r s t    outfit primary   (recolourable, p = outline)
  u v w x y    class secondary  (fixed per class)
  1 2 3 4      leather          (1 = outline)
  5 6 7 8 9    metal            (5 = outline)
  X Y Z        gold             (X dark, Z light)
  K            eye / ink dark
  W            white
  m n o        wood (bow, staff, shield rim)
  Zone chars are only used inside body parts. The class decides which ramp a
  zone uses, so one body drawing serves bare skin, cloth, leather and plate.
  A B C D      torso zone   (outline, dark, mid, light)
  E F G H      sleeve zone
  L M N O      hip/thigh zone
  Q R S T      shin zone
  U V I J      foot zone
"""

# ---------------------------------------------------------------- body -----
# Head: 8x7, 3/4 view facing right, light from the upper left.
HEAD = {
    "open": (
        "..aaaa..",
        ".adeeda.",
        "adeeddda",
        "acddKdKa",
        "abddKdKa",
        ".abcddba",
        "..aaaaa.",
    ),
    "closed": (
        "..aaaa..",
        ".adeeda.",
        "adeeddda",
        "acddddda",
        "abdKKdKa",
        ".abcddba",
        "..aaaaa.",
    ),
}

# Torso: 6x4 at the torso anchor (5, 11).  Shoulders are columns 0 and 5.
TORSO = (
    "ADDCBA",
    "ADCCBA",
    "ACCCBA",
    "ACCBBA",
)

# Legs: 10x5 grids at the legs anchor (3, 15); hips are columns 2-7.
# The near leg (screen left) is lit, the far leg is a step darker.
LEGS = {
    "stand": (
        "..LONNML..",
        "..LONMML..",
        "..LONLML..",
        "..QTSQRQ..",
        "..UJIVUIVU",
    ),
    # near leg forward (right), far leg back (left)
    "strideA": (
        "..LONNML..",
        ".LMLONNL..",
        ".LML.LONL.",
        "QRQ...QTSQ",
        "UVU...UJIVU",
    ),
    "passA": (
        "..LONNML..",
        "..LONMML..",
        "..LONLMML.",
        "..QTSQURU.",
        "..UJIVU...",
    ),
    # far leg forward, near leg back
    "strideB": (
        "..LONNML..",
        ".LONLLMML.",
        "LONL..LMML",
        "QTSQ...QRQ",
        "UJIU...UIVU",
    ),
    "passB": (
        "..LONNML..",
        "..LOONML..",
        "..LONNMLL.",
        "..UJIVQRQ.",
        "......UIVU",
    ),
    "lunge": (
        "..LONNML...",
        ".LMLLONNL..",
        "LML...LONL.",
        "QRQ....QTSQ",
        "UVU....UJIVU",
    ),
    "kneel": (
        "..LONNML..",
        "..LOONNNL.",
        ".QLLONNNL.",
        ".QRQ.QTSQ.",
        "UVU..UJIVU",
    ),
}

# Arms are (grid, anchor) where anchor is the shoulder pixel inside the grid.
# Near arm (screen left, nearer the viewer) is drawn over the torso; the far
# arm (screen right) is drawn behind it.
NEAR_ARM = {
    "side":   ((".E", "EH", "EG", "ac", ".a"), (1, 0)),
    "fwd":    ((".E.", "EHE", ".EGa", "..ca", "...a"), (1, 0)),
    "back":   (("..E", ".EH", "EGE", "ca.", "a.."), (2, 0)),
    "windup": (("aa...", "dca..", ".aEE.", "..EHE", "...EG"), (3, 3)),
    "punch":  ((".EEEE.aa.", "EHGGGEcda", ".EEEE.aba", "......aa."), (1, 1)),
    "half":   ((".EE..", "EHGEa", ".EEdc", "...aa"), (1, 1)),
    "reach":  ((".E..", "EHE.", ".EGE", "..Eda", "...ca"), (1, 0)),
}
FAR_ARM = {
    "side":   (("FE", "FE", "FE", "ba", "a."), (0, 0)),
    "fwd":    (("FE.", "FFE", ".Fba", "..a."), (0, 0)),
    "back":   (("FE", "FE", "ba"), (0, 0)),
    "guard":  (("FE.", "FFE", "Fbaa", ".aa."), (0, 0)),
    "reach":  (("FE..", "FFE.", ".FFE", "..bba", "...a."), (0, 0)),
}

# ---------------------------------------------------------------- hair -----
# Hair "front" grids are 12 wide at head origin + (-2, -2); "back" grids are
# 14 wide at head origin + (-4, -2) so tails can reach past the near arm.
# "front" is drawn over the face, "back" behind the whole body.
HAIR = {
    "none": {"front": (), "back": ()},
    "crop": {
        "front": (
            "............",
            "....hhhh....",
            "...hkllkh...",
            "..hkllkkjh..",
            "..hjkjjhjh..",
            "..hjh.......",
            "..hh........",
        ),
        "back": (),
    },
    "spiky": {
        "front": (
            "...h.h.h....",
            "...hkhlhh...",
            "..hkllkkjh..",
            ".hkllkkjjjh.",
            "..hkjhjjhjh.",
            ".hjjh.......",
            "..hh........",
        ),
        "back": (),
    },
    "swept": {
        "front": (
            "............",
            "...hhhhh....",
            "..hklllkh...",
            "..hkkllkkh..",
            "..hjkkkkkjh.",
            "..hjhh..hh..",
            "..hh........",
        ),
        "back": (),
    },
    "bob": {
        "front": (
            "............",
            "....hhhh....",
            "...hkllkh...",
            "..hkllkkjh..",
            "..hkjhjjjh..",
            "..hkjh...hh.",
            "..hjjh......",
            "..hjjh......",
            "...hh.......",
        ),
        "back": (),
    },
    "long": {
        "front": (
            "............",
            "....hhhh....",
            "...hkllkh...",
            "..hkllkkjh..",
            "..hkkhjjjh..",
            "..hkjh......",
            "..hjjh......",
            "..hjh.......",
        ),
        "back": (
            "..............",
            "..............",
            "....hh........",
            "...hkh........",
            "..hkjh........",
            "..hjjh........",
            "..hjjh........",
            ".hjjih........",
            ".hjiih........",
            ".hjih.........",
            ".hiih.........",
            ".hih..........",
            "..hh..........",
        ),
    },
    "ponytail": {
        "front": (
            "............",
            "....hhhh....",
            "...hkllkh...",
            "..hkllkkjh..",
            "..hkjhjjjh..",
            "..hjh.......",
            "..hh........",
        ),
        "back": (
            "..............",
            "..............",
            "...hh.........",
            "..hkkh........",
            ".hkljh........",
            "hkjh..........",
            "hjjh..........",
            "hjih..........",
            ".hih..........",
            ".hh...........",
        ),
    },
    "bun": {
        "lift": 2,
        "front": (
            "....hhh.....",
            "...hklkh....",
            "...hjkjh....",
            "...hhhhhh...",
            "..hjkllkkh..",
            "..hjkkkkjjh.",
            "..hjjjjhjh..",
            "..hjh.......",
            "..hh........",
        ),
        "back": (),
    },
}

# ------------------------------------------------------------- headgear ----
# Same 12-wide placement as hair (head origin + (-2, -2)), but may start
# higher: the first tuple element is an extra upward offset in rows.
# "clip" hides hair-front pixels above that hair-grid row (hats hide crowns).
HEADGEAR = {
    "none": {"lift": 0, "clip": 0, "rows": ()},
    "hood": {
        "lift": 1, "clip": 4, "rows": (
            "....pppp....",
            "...pqsstp...",
            "..pqsttssp..",
            ".pqsssssrqp.",
            ".pqrp...prp.",
            ".pqp......p.",
            ".pqp........",
            ".pqp........",
            "..pp........",
        ),
    },
    "wizard": {
        "lift": 4, "clip": 5, "rows": (
            "......pp....",
            ".....pstp...",
            ".....psrp...",
            "....psrrp...",
            "....pstrrp..",
            "...pstsrrp..",
            "..XYYYZYYYX.",
            ".pqqrrrrqqqp",
            "..pppppppp..",
        ),
    },
    "helm": {
        "lift": 1, "clip": 5, "rows": (
            "....5555....",
            "...578875...",
            "..57899875..",
            "..56788765..",
            "..5655556655",
            "..565.......",
            "..55........",
        ),
    },
    "bandana": {
        "lift": 0, "clip": 0, "rows": (
            "............",
            "............",
            "............",
            "...pppppp...",
            ".prstttsrp..",
            "pqp.........",
            ".pp.........",
        ),
    },
    "feathercap": {
        "lift": 2, "clip": 4, "rows": (
            "........WW..",
            ".......WyW..",
            "....pppWp...",
            "...pssssp...",
            "..pqrsssrp..",
            ".pqqrrrrqqp.",
            "..pppppppp..",
        ),
    },
    "circlet": {
        "lift": 0, "clip": 0, "rows": (
            "............",
            "............",
            "............",
            "............",
            "..XYYZYYYX..",
            "............",
        ),
    },
}

# ------------------------------------------------------------ face items ---
# Placed at head origin (0, 0); 8 wide like the head.
FACE = {
    "none": (),
    "glasses": (
        "........",
        "........",
        "........",
        "...6W6W6",
        "...6.6.6",
    ),
    "eyepatch": (
        "........",
        "........",
        "....K..K",
        "...KKKK.",
        "....KK..",
    ),
    "mask": (
        "........",
        "........",
        "........",
        "........",
        "..pqrrsp",
        ".pqrrssp",
        "..ppppp.",
    ),
    "monocle": (
        "........",
        "........",
        "........",
        "......XY",
        "......X.",
        ".....X..",
    ),
    "freckles": (
        "........",
        "........",
        "........",
        "........",
        "........",
        "..b..bb.",
    ),
}

# ------------------------------------------------------------ back items ---
# Placed at torso anchor + (-5, -5); 10 wide.  Drawn behind everything.
BACK = {
    "none": (),
    "cape": (
        "..........",
        "..........",
        "..........",
        "..........",
        "....pp....",
        "...pstp...",
        "..pstrp...",
        "..psrrp...",
        ".psrrqp...",
        ".psrqqp...",
        "psrqqqp...",
        "pqqqqp....",
        "ppppp.....",
    ),
    "backpack": (
        "..........",
        "..........",
        "..........",
        "..........",
        "..........",
        ".1111.....",
        "123441....",
        "1YY331....",
        "1XX321....",
        "123221....",
        "122221....",
        ".1111.....",
    ),
    "quiver": (
        "..W.W.....",
        "..WWW.W...",
        "...WWWW...",
        "...1W1....",
        "..1341....",
        "..1341....",
        ".1342.....",
        ".1342.....",
        "1342......",
        "1321......",
        "111.......",
    ),
    "greatsword": (
        "....Y.....",
        "....1.....",
        "....1.....",
        "...XZX....",
        "...598....",
        "...585....",
        "..595.....",
        "..585.....",
        ".595......",
        ".585......",
        "595.......",
        "55........",
    ),
    "shield": (
        "..........",
        "..........",
        "..........",
        "..........",
        ".mmmm.....",
        "mnsstm....",
        "mnrXZm....",
        "mnrYrm....",
        "mnqrqm....",
        "mnqqqm....",
        ".mnnm.....",
        "..mm......",
    ),
}

# --------------------------------------------------------- class outfits ---
# zones: which ramp each body zone takes ("skin" leaves it in the body layer).
# overlay grids use material chars; "torso" sits at torso anchor + offset,
# "skirt" sits at the legs anchor and is chosen per leg pose family,
# "back" is drawn behind the body (coat tails, scarf tails).
OUTFITS = {
    # broad plate shoulders, tabard stripe, steel greaves
    "warrior": {
        "secondary": "steelcloth",
        "zones": {"torso": "metal", "sleeve": "metal", "thigh": "leather", "shin": "metal", "foot": "metal"},
        "torso": ((-1, 0), (
            "..5qr5..",
            ".57qrs75",
            ".578rq75",
            ".12YY21.",
        )),
        # pauldrons sit over the near arm, so they are stamped after it
        "shoulder": ((-3, -1), (
            ".555......55.",
            "57895...5875.",
            "56775....565.",
            ".555.........",
        )),
        "skirt": None,
        "back": None,
    },
    # floor-length flared robe, gold collar and sash
    "mage": {
        "secondary": "darkcloth",
        "zones": {"torso": "primary", "sleeve": "primary", "thigh": "primary", "shin": "primary", "foot": "darkcloth"},
        "torso": ((0, 0), (
            "pXZYXp",
            "",
            "",
            "pXYYXp",
        )),
        "skirt": {
            "still": (
                "..pqssrp..",
                "..pqsrrqp.",
                ".pqrsrrqp.",
                ".pppppppp.",
            ),
            "stride": (
                "..pqssrp..",
                ".pqrsrrqp.",
                ".pqrsrrqqp",
                "pppppppppp",
            ),
        },
        "back": None,
    },
    # slim dark leathers, scarf with a tail flying behind, belt pouches
    "rogue": {
        "secondary": "darkcloth",
        "zones": {"torso": "secondary", "sleeve": "secondary", "thigh": "secondary", "shin": "leather", "foot": "leather"},
        "torso": ((-1, -1), (
            ".pqsstp.",
            "pqrsstrp",
            ".pprrpp.",
            "........",
            ".123431.",
        )),
        "skirt": None,
        "back": ((-4, 0), (
            "pp....",
            "qrpp..",
            ".pqrp.",
            "..ppp.",
        )),
    },
    # sleeveless tunic with a short flared hem, bare arms, tall boots
    "huntress": {
        "secondary": "leafcloth",
        "zones": {"torso": "primary", "sleeve": "skin", "thigh": "skin", "shin": "leather", "foot": "leather"},
        "torso": ((0, 0), (
            "p1vw1p",
            "",
            "",
            "123321",
        )),
        "skirt": {
            "still": (
                ".pqrssrqp.",
                ".pqp..pqp.",
            ),
            "stride": (
                ".pqrssrqp.",
                "pqrp..prqp",
            ),
        },
        "back": None,
    },
    # fitted open coat over a white shirt, high collar, long coat tails
    "duelist": {
        "secondary": "whitecloth",
        "zones": {"torso": "secondary", "sleeve": "primary", "thigh": "darkcloth", "shin": "leather", "foot": "leather"},
        "torso": ((-1, -1), (
            ".pp..pp.",
            ".pqwxrp.",
            ".pqXwrp.",
            ".pqwxrp.",
            ".pq11rp.",
        )),
        "skirt": None,
        "back": ((-3, 3), (
            "..pqp",
            ".pqrp",
            ".pqp.",
            "pqrp.",
            "ppp..",
        )),
    },
    # round capelet over a white under-robe, tabard with a gold sun
    "cleric": {
        "secondary": "whitecloth",
        "zones": {"torso": "secondary", "sleeve": "secondary", "thigh": "secondary", "shin": "secondary", "foot": "leather"},
        "torso": ((-2, 0), (
            "..pqrssp..",
            ".pqrsZsrqp",
            ".ppqrYrqpp",
            "...pqrqp..",
        )),
        "skirt": {
            "still": (
                "...pqrp...",
                "...vpqpx..",
                "..uvpqpwu.",
                "..uuuuuuu.",
            ),
            "stride": (
                "...pqrp...",
                "..uvpqpxu.",
                ".uvwpqpwvu",
                ".uuuuuuuuu",
            ),
        },
        "back": None,
    },
}
