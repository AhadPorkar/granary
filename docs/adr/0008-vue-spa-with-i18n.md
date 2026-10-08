# 8. Vue single-page UI with five locales

Date: 2026-10-08 · Status: accepted

## Context

The UI must be available in English, German, French, Turkish and Persian, with English as the
default, and Persian needs right-to-left layout.

## Decision

A Vue 3 + Vite application with vue-i18n; all strings live in per-locale JSON files, the
document direction follows the locale, dates and numbers use `Intl`. The build output is
packaged into the server jar, so the deliverable stays one binary. A test stub `index.html`
lets the server build and test without Node (`-Dui.skip=true`).

## Consequences

* Adding a locale is one JSON file and one line in the locale list.
* The server never renders HTML; the UI is a pure REST client, which keeps the API honest.
