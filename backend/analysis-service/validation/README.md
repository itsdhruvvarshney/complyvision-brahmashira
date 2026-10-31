# Manual validation dataset

`ground_truth.csv` is an intentionally empty annotation template. Add 20–50 independently reviewed package images before treating benchmark scores as meaningful. Image paths are relative to the repository root.

Use exact visible values for MRP, net-quantity value/unit, and manufacturer. For rule columns use only `PASS`, `FAIL`, `REVIEW`, `NOT_APPLICABLE`, or `UNKNOWN`. A blank value or `UNKNOWN` is excluded from that metric denominator; the benchmark never converts missing labels into a pass or fail.

Suggested `quality_category` values are `clear`, `glare`, `blur`, `low_resolution`, `tilt`, `partial_package`, and `missing_calibration_marker`. Categories are descriptive slices, not automatic ground truth.

Run from the repository root:

```bash
.venv/bin/python benchmark_validation.py
```

The command reuses `PackageAnalyzer` and writes ignored artifacts to `results/validation/benchmark.json`, `benchmark.csv`, and `benchmark.md`. Review the false-pass table first: a predicted `PASS` against a manually verified `FAIL` or `REVIEW` is treated as the highest-risk error.
