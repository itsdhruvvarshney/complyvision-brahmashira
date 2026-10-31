import numpy as np


def box_height_px(box):
    """
    Supports OCR rectangle:
    [x1, y1, x2, y2]
    """

    if len(box) == 4 and not isinstance(box[0], (list, tuple)):
        x1, y1, x2, y2 = box
        return abs(y2 - y1)

    # Polygon format:
    # [[x,y], [x,y], [x,y], [x,y]]
    points = np.array(box, dtype=float)

    left_height = np.linalg.norm(
        points[3] - points[0]
    )

    right_height = np.linalg.norm(
        points[2] - points[1]
    )

    return float(
        (left_height + right_height) / 2
    )


def estimate_text_height_mm(
    box,
    pixels_per_mm
):
    if not pixels_per_mm:
        return None

    height_px = box_height_px(box)

    height_mm = (
        height_px / pixels_per_mm
    )

    return {
        "height_px": round(
            float(height_px),
            2
        ),
        "height_mm": round(
            float(height_mm),
            3
        )
    }