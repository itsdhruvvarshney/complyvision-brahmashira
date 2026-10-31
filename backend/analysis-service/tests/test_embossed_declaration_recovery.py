import cv2
import numpy as np

from cv.ocr import recover_embossed_declaration_items


class FakeOCR:
    def __init__(self):
        self.calls = 0

    def predict(self, _image, **_kwargs):
        self.calls += 1
        return [{
            "rec_texts": ["10/25", "CG069,L", "₹43.00", "₹0.48/ml"],
            "rec_scores": [0.91, 0.90, 0.93, 0.89],
            "rec_boxes": [
                [20, 80, 100, 110],
                [20, 120, 130, 150],
                [20, 160, 120, 190],
                [20, 200, 140, 230],
            ],
        }]


def test_recovery_is_gated_and_preserves_absolute_geometry(tmp_path):
    image_path = tmp_path / "package.png"
    cv2.imwrite(str(image_path), np.full((900, 600, 3), 180, dtype=np.uint8))
    cue = [
        {"text": "Mfd., Batch No., MRP", "confidence": .98, "box": [50, 300, 260, 330]},
        {"text": "See", "confidence": .98, "box": [270, 300, 330, 330]},
        {"text": "Below", "confidence": .98, "box": [270, 335, 380, 365]},
    ]
    ocr = FakeOCR()

    result, metadata = recover_embossed_declaration_items(image_path, cue, ocr)

    assert metadata["attempted"] is True
    assert metadata["recovered"] is True
    assert ocr.calls == 2
    recovered = [item for item in result if item.get("recovered_from_declaration_crop")]
    assert {item["text"] for item in recovered} == {"10/25", "CG069,L", "₹43.00", "₹0.48/ml"}
    assert min(item["box"][1] for item in recovered) > 330


def test_recovery_does_not_run_without_see_below(tmp_path):
    image_path = tmp_path / "package.png"
    cv2.imwrite(str(image_path), np.full((300, 300, 3), 180, dtype=np.uint8))
    ocr = FakeOCR()
    items = [{"text": "MFD MRP", "confidence": .98, "box": [10, 10, 100, 30]}]

    result, metadata = recover_embossed_declaration_items(image_path, items, ocr)

    assert result == items
    assert metadata["attempted"] is False
    assert ocr.calls == 0
