"""Generates a printable ArUco calibration card (DICT_4X4_50, id 0) at true physical size.

    python tools/make_aruco_card.py                # 20 mm marker -> aruco_card_20mm.png (600 dpi)
    python tools/make_aruco_card.py --mm 30 --dpi 300

Print at 100 % scale ("actual size", no fit-to-page) and check the marker with a ruler. Place the card
flat, next to the package label and in the same plane, when photographing.
"""

from __future__ import annotations

import argparse

import cv2
import numpy as np


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--mm", type=float, default=20.0, help="marker side length in millimetres")
    ap.add_argument("--dpi", type=int, default=600)
    ap.add_argument("--id", type=int, default=0)
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    px_per_mm = args.dpi / 25.4
    side = int(round(args.mm * px_per_mm))
    quiet = int(round(5 * px_per_mm))  # 5 mm white quiet zone around the marker
    dictionary = cv2.aruco.getPredefinedDictionary(cv2.aruco.DICT_4X4_50)
    marker = cv2.aruco.generateImageMarker(dictionary, args.id, side)

    card = np.full((side + 2 * quiet + int(9 * px_per_mm), side + 2 * quiet), 255, np.uint8)
    card[quiet:quiet + side, quiet:quiet + side] = marker
    label = f"ComplyVision {args.mm:g} mm  #{args.id}"
    scale = px_per_mm * 0.09
    cv2.putText(card, label, (quiet, quiet + side + int(6 * px_per_mm)), cv2.FONT_HERSHEY_SIMPLEX, scale, 0,
                max(1, int(px_per_mm * 0.25)), cv2.LINE_AA)
    cv2.rectangle(card, (0, 0), (card.shape[1] - 1, card.shape[0] - 1), 160, max(1, int(px_per_mm * 0.15)))

    out = args.out or f"aruco_card_{args.mm:g}mm.png"
    # Embed the DPI so printers reproduce the physical size
    ok, buf = cv2.imencode(".png", card)
    from PIL import Image
    import io

    Image.open(io.BytesIO(buf.tobytes())).save(out, dpi=(args.dpi, args.dpi))
    print(f"Wrote {out}: {args.mm:g} mm marker at {args.dpi} dpi ({side}px). Print at 100% scale.")


if __name__ == "__main__":
    main()
