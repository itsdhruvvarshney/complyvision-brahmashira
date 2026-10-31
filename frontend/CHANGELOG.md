# Inspection Workspace Redesign

## 2026-11-01

1. Constrained inspector/grid children with `min-width: 0`, wrapping declaration values and explicit scroll regions.
2. Made the declaration list and evidence drawer independently scrollable.
3. Normalized inspector padding and inter-section spacing.
4. Added image fit-to-panel, real scale display, zoom, pan, and reset controls.
5. Replaced list-overlapping custom hover content with short native tooltips and a quality popover.
6. Added a single selected card state, visible selected accent, keyboard selection, and linked image boxes.
7. Added Rose Water identity vocabulary and rejection of unlabelled comma-separated ingredient lists; added a Dabur Gulabari sample test.
8. Split manufacturer output into name, manufacturing address, licence number, and registered office address when the OCR text supports it.
9. Consolidated consumer phone and email into one consumer-care declaration.
10. Derived status tiles from the same normalized declaration list rendered by the inspector.
11. Defined mandatory-field confidence, missing-field treatment, and a review cap in the UI and README.
12. Added text-and-icon status badges and per-declaration explanations.
13. Expanded the selected evidence drawer with raw OCR, normalized value, confidence, rule details, validation reason, crop, corrections, and audit history.
14. Listed missing declaration names as visible status chips.
15. Formatted dates with a named month and exposed the active timezone.
16. Added skew and edge-bearing tile contrast estimates alongside blur, brightness, glare, and resolution checks.
17. Explained whether an ArUco scale reference was detected and when measurements may be unavailable.
18. Added status-colored outlined boxes, numbered badges, visibility controls, and a fill-opacity slider.

The layout also adds responsive inspector behavior, keyboard shortcuts, correction undo/redo, retry/cancel evaluation controls, and JSON/CSV/print-to-PDF exports.
