"""Compatibility adapter for memory-bounded OCR on the testing service."""

from __future__ import annotations

from typing import Any


RAPIDOCR_PARAMS = {
    "Global.use_cls": False,
    "Global.max_side_len": 736,
    "Global.log_level": "warning",
    "EngineConfig.onnxruntime.intra_op_num_threads": 1,
    "EngineConfig.onnxruntime.inter_op_num_threads": 1,
    "Cls.cls_batch_num": 1,
    "Rec.rec_batch_num": 1,
}


class RapidOCRAdapter:
    """Expose RapidOCR results through the subset of PaddleOCR used here."""

    def __init__(self, engine: Any | None = None) -> None:
        if engine is None:
            import onnxruntime
            from rapidocr import LangDet, LangRec, ModelType, OCRVersion, RapidOCR

            onnxruntime.disable_telemetry_events()
            engine = RapidOCR(params={
                **RAPIDOCR_PARAMS,
                "Det.ocr_version": OCRVersion.PPOCRV4,
                "Det.lang_type": LangDet.EN,
                "Det.model_type": ModelType.MOBILE,
                "Rec.ocr_version": OCRVersion.PPOCRV4,
                "Rec.lang_type": LangRec.EN,
                "Rec.model_type": ModelType.MOBILE,
            })
        self._engine = engine

    def predict(self, image: Any, *, return_word_box: bool = False) -> list[dict[str, Any]]:
        result = self._engine(
            image,
            use_cls=False,
            return_word_box=return_word_box,
        )
        texts = list(result.txts or ())
        if not texts:
            return []

        word_results = list(result.word_results or ())
        scores = result.scores if result.scores is not None else ()
        boxes = result.boxes if result.boxes is not None else ()
        words: list[list[str]] = []
        word_boxes: list[list[Any]] = []
        for index in range(len(texts)):
            line_words = word_results[index] if index < len(word_results) else ()
            words.append([str(word[0]) for word in line_words])
            word_boxes.append([word[2] for word in line_words])

        return [{
            "rec_texts": texts,
            "rec_scores": [float(score) for score in scores],
            "rec_boxes": list(boxes),
            "text_word": words if return_word_box else [],
            "text_word_boxes": word_boxes if return_word_box else [],
        }]
