# Contrast validation dataset

This directory holds human-labeled validation data for the LM-R9-002
engineering prototype. The labels describe visual readability only; they are
not Legal Metrology judgments.

## Adding data

1. Put real package photographs in `validation/contrast/images/`.
2. Add one annotation per declaration target to `annotations.json`. A package
   containing both MRP and net quantity normally has two annotations.
3. Run `.venv/bin/python validate_contrast_dataset.py` from the repository root.

Images already stored elsewhere in the repository can remain there. Point the
runner at that directory, for example `.venv/bin/python
validate_contrast_dataset.py --images-dir samples`, and keep
`image_filename` relative to the selected directory.

Do not add a manual polygon unless automatic OCR localization fails. Polygon
coordinates are image pixels in clockwise or counter-clockwise order.

## Human labels

- `CLEAR_CONTRAST`: a human can easily distinguish the declaration numerals
  from the immediate background.
- `LOW_CONTRAST`: numeral and local background are visibly similar enough that
  readability is clearly poor.
- `UNCERTAIN`: glare, shadow, reflection, gradient, texture, blur, image
  quality, or ambiguous localization prevents reliable classification.

## Annotation fields

```json
{
  "id": "package_01_mrp",
  "image_filename": "package_01.jpg",
  "declaration_type": "MRP",
  "target_type": "MRP",
  "expected_target_text": "349.00",
  "human_label": "CLEAR_CONTRAST",
  "annotation_notes": "Black numerals on a matte white label",
  "glare_present": false,
  "gradient_background": false,
  "textured_background": false,
  "unusual_text_color": false,
  "coverage_categories": ["black_text_on_light", "small_mrp_print"],
  "manual_target_polygon": null
}
```

Supported coverage-category names are documented in `annotations.json`. They
are descriptive metadata rather than classifier inputs.

Generated reports are written to `results/contrast_validation.json`,
`results/contrast_validation.csv`, `results/contrast_threshold_analysis.csv`,
and `results/contrast_validation_failures.txt`. Debug overlays are written to
`validation/contrast/debug/`.
