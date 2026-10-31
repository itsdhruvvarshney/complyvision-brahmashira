from types import SimpleNamespace

from services.rapidocr_adapter import RAPIDOCR_PARAMS, RapidOCRAdapter


class FakeRapidOCR:
    def __init__(self):
        self.calls = []

    def __call__(self, image, **kwargs):
        self.calls.append((image, kwargs))
        return SimpleNamespace(
            txts=("Net Qty 1 L",),
            scores=(0.98,),
            boxes=([[1, 2], [9, 2], [9, 8], [1, 8]],),
            word_results=((
                ("Net", 0.99, [[1, 2], [3, 2], [3, 8], [1, 8]]),
            ),),
        )


def test_adapter_emits_pipeline_compatible_results():
    engine = FakeRapidOCR()

    result = RapidOCRAdapter(engine).predict("package.jpg", return_word_box=True)

    assert result[0]["rec_texts"] == ["Net Qty 1 L"]
    assert result[0]["rec_scores"] == [0.98]
    assert result[0]["text_word"] == [["Net"]]
    assert result[0]["text_word_boxes"][0][0][0] == [1, 2]
    assert engine.calls == [
        ("package.jpg", {"use_cls": False, "return_word_box": True})
    ]


def test_testing_configuration_is_memory_bounded():
    assert RAPIDOCR_PARAMS["Global.use_cls"] is False
    assert RAPIDOCR_PARAMS["Global.max_side_len"] == 736
    assert RAPIDOCR_PARAMS["EngineConfig.onnxruntime.intra_op_num_threads"] == 1
    assert RAPIDOCR_PARAMS["EngineConfig.onnxruntime.inter_op_num_threads"] == 1
    assert RAPIDOCR_PARAMS["Rec.rec_batch_num"] == 1
