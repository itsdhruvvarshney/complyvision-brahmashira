from copy import deepcopy
from unittest.mock import patch

import pytest

from services.analyzer import PackageAnalyzer, _merge_field_candidates
from services.declaration_extractor import enhance_extracted_fields
from services.mrp_extractor import correct_mrp
from services.ocr_ensemble import _merge_passes
from test_reporting import _batch_result


def test_enhancement_preserves_reliable_declarations_and_rejects_quantity_as_price():
    evidence = [{'raw_text': text, 'confidence': 0.99, 'box': [0, i*30, 300, i*30+24]}
                for i, text in enumerate(['Manufactured By', 'MRP', 'Net Weight: 250 g'])]
    fields = {'product': 'VALID SHAMPOO', 'mrp': {'value': 60, 'confidence': 0.99}, 'ocr_evidence': evidence}
    result = correct_mrp(enhance_extracted_fields(fields))
    assert result['product'] == fields['product']
    assert result['mrp'] == fields['mrp']
    missing = correct_mrp(enhance_extracted_fields({'ocr_evidence': evidence}))
    assert not missing.get('mrp')
    assert not missing.get('product')


def test_mrp_correction_prefers_recovered_embossed_price_over_barcode_digit():
    evidence = [
        {'raw_text': 'Mfd., Batch No., MRP', 'confidence': .99, 'box': [200, 560, 450, 620]},
        {'raw_text': '4', 'confidence': 1.0, 'box': [635, 740, 655, 765]},
        {
            'raw_text': '43.00',
            'confidence': .99,
            'box': [330, 1220, 590, 1300],
            'recovered_from_declaration_crop': True,
        },
    ]
    result = correct_mrp({'ocr_evidence': evidence})
    assert result['mrp']['value'] == 43.0
    assert result['mrp']['source_text'] == '43.00'


def test_ensemble_preserves_primary_text_and_requires_numeric_agreement():
    box = [0, 0, 200, 30]
    result = _merge_passes([
        [{'raw_text': 'MRP 60.00', 'confidence': .9, 'box': box}],
        [{'text': 'MRP 60.00', 'confidence': .92, 'box': box}],
    ])
    assert len(result) == 1
    assert result[0]['text'] == 'MRP 60.00'
    assert result[0]['ocr_votes'] == 2
    assert result[0]['confidence'] == .92
    assert _merge_field_candidates({'mrp': {'value': 60, 'confidence': .95}}, {'mrp': {'value': 600, 'confidence': .99}})['mrp']['value'] == 60
    disagree = _merge_passes([
        [{'text': 'MRP 60.00', 'confidence': .9, 'box': box}],
        [{'text': 'MRP 600.00', 'confidence': .92, 'box': box}],
    ])
    assert len(disagree) == 2
    assert all(item['ocr_votes'] == 1 for item in disagree)


@pytest.mark.parametrize('failed_contrast,expected', [(False, 'REVIEW'), (True, 'FAIL')])
def test_analyzer_retains_non_core_rule_outcomes(tmp_path, failed_contrast, expected):
    (tmp_path / "package.jpg").write_bytes(b"image processor is injected")
    batch = deepcopy(_batch_result())
    if failed_contrast:
        for target in batch['contrast_evidence']['targets'].values():
            target.update(contrast_ratio=1.1, lab_color_difference=3.0, confidence=.95)
    analyzer = PackageAnalyzer(
        ocr_factory=lambda: object(),
        image_processor=lambda *_args, **_kwargs: batch,
        evidence_builder=lambda *_args: [],
    )
    with patch('services.analyzer.run_ocr_ensemble', return_value=([], {})):
        report = analyzer.analyze_package(tmp_path / 'package.jpg')
    assert report['summary']['overall_status'] == expected


@pytest.mark.parametrize('height,expected', [
    (3.0, 'PASS'), (0.2, 'FAIL'), (None, 'REVIEW'), ('bad', 'REVIEW'),
    (float('nan'), 'REVIEW'), (float('inf'), 'REVIEW'),
])
def test_rule7_uses_good_confidence_measurement(height, expected):
    from rules.engine import evaluate_rule
    rule = {'field_name': 'net_quantity_font_height'}
    measurement = {'status': 'OK', 'estimated_numeral_height_mm': height, 'confidence': .99}
    result = evaluate_rule(rule, {'net_quantity': {'value': 250, 'unit': 'ML'},
                                  'net_quantity_font_height_measurement': measurement})
    assert result['status'] == expected
    assert result['value'] == measurement
    count = evaluate_rule(rule, {'net_quantity': {'value': 2, 'unit': 'N'},
                                'net_quantity_font_height_measurement': measurement})
    assert count['status'] == 'NOT_APPLICABLE'


def test_rule7_reporting_measurement_does_not_mutate_input_fields():
    from reporting.report import build_rule_results
    batch = _batch_result()
    batch['glyph_measurement']['measurement_confidence'] = 0.90
    fields = deepcopy(batch['extracted_fields'])
    before = deepcopy(fields)
    results = build_rule_results(fields, {}, batch)
    assert fields == before
    assert next(row for row in results if row['rule_id'] == 'LM-R7-001')['status'] == 'PASS'
