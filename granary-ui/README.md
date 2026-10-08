# Granary web UI

Vue 3 + TypeScript single-page application for the Granary artifact repository manager. It is built with
Vite, vue-router (history mode), Pinia, vue-i18n and tested with Vitest. The production bundle is packaged by
Maven under `/static` in the server jar, so the UI is served by the same process as the API and the
repositories, with no external requests at runtime (system fonts, inline SVG icons).

## Development

Requirements: Node 22 and npm 10 (Maven downloads its own copy into `target/node`, so a system-wide Node is
only needed for working on the UI directly).

```sh
cd granary-ui
npm ci
npm run dev          # http://localhost:5173, proxies /api and /repository to http://localhost:8081
```

Point the dev server at another backend with `VITE_PROXY_TARGET=http://localhost:8093 npm run dev`.

Other scripts:

| Script              | Purpose                                                     |
| ------------------- | ----------------------------------------------------------- |
| `npm test`          | Runs the Vitest suite once (`vitest run`)                   |
| `npm run test:watch`| Vitest in watch mode                                        |
| `npm run typecheck` | `vue-tsc --noEmit` over sources and tests                   |
| `npm run build`     | Type-checks, then builds the production bundle into `dist/` |
| `npm run preview`   | Serves `dist/` locally                                      |

The Maven module runs `npm ci`, `npm test` (test phase) and `npm run build` (prepare-package) through the
frontend-maven-plugin. Pass `-Dui.skip=true` to build the server without Node.

## Layout

```
src/
  main.ts            app bootstrap (Pinia, i18n, router, 401 handling)
  App.vue            chooses between the bare login page and the application shell
  router.ts          routes, session guard, privilege guard, document titles
  i18n.ts            locale list, detection, RTL handling, vue-i18n instance
  api/               fetch wrapper (client.ts), typed endpoint functions (index.ts), DTO types (types.ts)
  stores/            Pinia stores: auth (token, privileges), locale, toast, system
  components/        reusable building blocks (forms, dialogs, pickers, connect panel, layout shell)
  views/             one component per route
  composables/       useFormatters (Intl bound to the UI locale), useApiError (error translation)
  utils/             pure helpers: error mapping, formatting, connect snippets
  locales/           en.json, de.json, fr.json, tr.json, fa.json
  styles/main.css    design tokens (light/dark), layout and component styles
tests/               Vitest specs (jsdom + @vue/test-utils)
```

## Adding a language

1. Copy `src/locales/en.json` to `src/locales/<code>.json` and translate every value. Keep the `{placeholders}`
   and avoid the vue-i18n special characters `@` and `|` (or escape them as `{'@'}`).
2. Register the locale in `src/i18n.ts`: import the file, add the code to `SUPPORTED_LOCALES`, its native
   name to `LOCALE_NAMES`, the BCP 47 tag to `INTL_TAGS` and, for a right-to-left script, add it to
   `RTL_LOCALES`.
3. Run `npm test`: the locale tests fail if a key is missing, empty or has different placeholders than
   English.

API error codes are translated under `errors.<code>` (dots become nesting, e.g. `errors.repository.name.taken`);
an unknown code falls back to the English message sent by the server.
