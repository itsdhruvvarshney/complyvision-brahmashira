# ComplyVision

ComplyVision is a package-label inspection workspace that helps reviewers examine product packaging against prototype Legal Metrology declarations. It combines OCR, image analysis, extracted evidence, and rule checks in one review flow.

> **Prototype:** ComplyVision supports human review. It is not legal advice, an official compliance determination, or a compliance certificate. OCR may misread label text, so extracted values and rule results need review.

## Project purpose

Product-label information is often small, dense, and difficult to verify manually. ComplyVision turns a package image into a structured set of candidate declarations—such as product identity, quantity, price, dates, manufacturer, and consumer-care details—and shows the text evidence behind those candidates.

The workspace is designed to make the review understandable: each declaration has a value, extraction confidence, status, applicable rule context, and supporting evidence when available. Reviewers can search and filter declarations, inspect evidence, correct values, flag issues, and export a report.

## Inspection workflow

1. A reviewer provides an image of a package label.
2. The analysis service validates the image and runs OCR and image-processing steps.
3. Extractors map recognized text to candidate package declarations and evidence regions.
4. Image-quality checks and available computer-vision measurements are added to the analysis.
5. The rule engine evaluates supported declarations against the bundled prototype rules.
6. The workspace presents results for review, including missing or uncertain declarations.

The analysis can report OCR text and regions, extracted fields, rule outcomes, image-quality indicators, and measurements or calibration data when available. OCR quality depends on image clarity, label layout, and text size; an extracted value should not be treated as verified simply because it has a confidence score.

## Main components

| Component | Responsibility |
| --- | --- |
| React workspace | Image viewing, annotation tools, evidence overlays, declaration review, corrections, and exports |
| FastAPI analysis service | Image intake and orchestration of the package-analysis pipeline |
| OCR and extraction services | Recognizing label text and mapping it to declaration candidates |
| OpenCV utilities | Image-quality checks and available package or text measurements |
| Rule engine | Comparing supported extracted declarations with the bundled prototype rule data |
| Express account API | Email OTP verification and prototype registration flow |

The frontend communicates with the analysis service for image evaluation and with the account API for OTP and registration actions. The analysis service processes each submitted image as a request and returns a structured report.

## Review model

Declarations are shown as **Passed**, **Review**, or **Failed** based on the available rule result and whether a required value was detected. Missing optional declarations remain reviewable. Confidence describes extraction evidence, not legal certainty; the workspace applies a conservative cap when required declarations are missing or need review.

The package image and its extracted regions are displayed together so reviewers can compare a declaration with the label. Corrections and issue flags support the human review workflow. JSON, CSV, and printable report exports provide ways to inspect or share the current evaluation result.

## Data and persistence

There is no database in this project. The analysis service is stateless and does not persist submitted images or reports. The browser stores inspection history and review corrections in local storage for that browser profile; saved history does not retain the uploaded image. OTPs, email-verification tokens, and prototype registrations are kept in the account API process's memory and are lost when that process restarts.

This persistence model is for a local prototype. It does not provide shared organizational history, durable account records, or multi-instance session storage.

## Rule and compliance scope

The repository includes prototype Legal Metrology rule data and mappings for supported declarations. Rule coverage and extraction behavior are limited to the data and logic currently implemented in the project. A **Passed** result means the configured check passed against the extracted evidence; it does not certify the package or establish that every applicable legal requirement has been evaluated.

## Project structure

```text
frontend/
  src/pages/                Application screens, including the inspection workspace
  src/services/             Analysis client, local history, and declaration model
  src/components/           Shared application and workspace components
backend/
  otp-server.js             Email OTP and prototype registration API
  analysis-service/
    api/                    Analysis API interface
    services/               OCR, extraction, and analysis orchestration
    cv/                     OpenCV checks and measurement utilities
    rules/                  Prototype rule evaluation
    reporting/              Structured inspection report construction
    data/                   Bundled prototype rule data
```
