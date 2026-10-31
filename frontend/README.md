# React + Vite

## Run the app and OTP API

Install the backend dependencies once with `npm install --prefix ../backend`. Copy `../backend/.env.example` to `../backend/.env`, then set your SMTP credentials and a long random `OTP_SECRET`. Run `npm run dev` from this folder to start both Vite and the OTP API. Email verification cannot send mail until the SMTP values in `backend/.env` are valid.

The login page calls `/api/otp/send` and `/api/otp/verify`; Vite proxies these requests to the local backend on port 3000.

## Inspection workspace

`WorkspacePage` composes the inspection header/actions, annotation rail, image canvas, declaration inspector, and selected-evidence drawer. The canvas keeps the source image at its native pixel dimensions and applies a measured fit scale; zoom, pan, front/back selection, evidence overlays, and tool selection are local view state. The inspector reads a single normalized declaration array produced by `workspaceModel.js` and uses it for cards, filters, missing-field chips, status totals, and the confidence meter.

The interaction state is `selectedFieldId` (`selected` in the page), `hoveredFieldId` (represented by CSS hover/focus rather than stored state), `tool`, `zoom`, `pan`, `side` (`front`/`back`), declaration filters, box visibility/opacity, and the inspector drawer state. A declaration is Passed when its linked rule passes; Failed when a mandatory declaration is missing or its rule fails; otherwise it needs Review. Missing optional declarations are Review. Confidence is the mean extraction confidence across mandatory fields, with missing values treated as 0 and the score capped at 84% when any mandatory field is missing, below 85%, or needs review.

Run `npm test` for the declaration-model tests and `python3 -m unittest discover -s tests -v` from `backend/analysis-service` for the product identity and image-quality helper tests.

This template provides a minimal setup to get React working in Vite with HMR and some Oxlint rules.

Currently, two official plugins are available:

- [@vitejs/plugin-react](https://github.com/vitejs/vite-plugin-react/blob/main/packages/plugin-react) uses [Oxc](https://oxc.rs)
- [@vitejs/plugin-react-swc](https://github.com/vitejs/vite-plugin-react/blob/main/packages/plugin-react-swc) uses [SWC](https://swc.rs/)

## React Compiler

The React Compiler is not enabled on this template because of its impact on dev & build performances. To add it, see [this documentation](https://react.dev/learn/react-compiler/installation).

## Expanding the Oxlint configuration

If you are developing a production application, we recommend using TypeScript with type-aware lint rules enabled. Check out the [TS template](https://github.com/vitejs/vite/tree/main/packages/create-vite/template-react-ts) for information on how to integrate TypeScript and Oxlint's TypeScript related rules in your project.
