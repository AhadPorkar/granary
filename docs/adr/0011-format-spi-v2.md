# 11. Format SPI v2: templates, connect snippets and format settings

Date: 2026-10-11 · Status: accepted, extends [ADR 0005](0005-format-spi.md)

## Context

ADR 0005 put each format behind `FormatHandler`, which covered the protocol: path parsing,
read/write classification, metadata paths and request handling. Three things every format needs
were still outside the handler in 0.1.0:

* **Standard repositories.** `StandardRepositories` hard-coded the Maven, npm and NuGet
  proxy/hosted/group trios, so a new format had to edit the core to appear in the one-click setup.
* **Client configuration.** The UI held the `settings.xml`, `.npmrc` and `nuget` snippets in
  TypeScript. With twenty more formats, each with several clients (pip, uv, Poetry, twine; docker,
  podman, containerd, Kubernetes; cargo's `config.toml` and environment variables), the UI would
  have had to know every protocol, and the snippets could drift from what the server implements.
* **Format-specific options.** Formats need a few per-repository switches that mean nothing to
  other formats: which Docker repository answers at `/v2/`, cargo's upstream download URL, Go's
  checksum database, Helm's chart size limit. Adding a column per option, or a generic
  free-for-all map nobody validates, were the obvious alternatives.

## Decision

`FormatHandler` gains four default methods, so existing handlers keep compiling:

* `templates()` returns `RepositoryTemplate` records (name, format, type, remote URL, version
  policy, members, description, format settings), members before the groups that contain them.
  `POST /api/v1/repositories/standard` creates the templates of every installed format whose name
  is still free and reports what it created; it is safe to repeat. Template names share one
  namespace across formats.
* `connectSnippets(ConnectContext)` returns `ConnectSnippet` records (id, English title, syntax
  language, target file name or none, code). The context carries the repository, its absolute URL,
  the server root, the caller's user name (or `<username>` for anonymous callers), the placeholder
  `<api-token>` and whether anonymous read is on. `GET /api/v1/repositories/{name}/connect` renders
  them for any caller who may read the repository; the UI's *Connect* panel only displays and
  copies them. Snippets never contain a real secret.
* `settings()` returns `SettingDescriptor` records (key, label, type `STRING`/`BOOLEAN`/`INTEGER`/
  `URL`, default, description, the repository types it applies to). Keys are prefixed with the
  format id (`docker.rootRepository`, `cargo.downloadUrl`); the eight formats of the raw module share
  the prefix `raw.`. Values live in the repository's
  `formatSettings` object, a map of strings stored as JSON text in `repositories.format_settings`
  (migration `V2__format_settings`). The core stores whatever map it is given; the handler's
  `validate(Repository)` checks its own keys and values and refuses bad ones with `400`.
  `Repository.setting(key[, default])` and `settingEnabled(key, default)` read them, trimmed, with
  blank values treated as unset.
* `routeRoot(RootRequest)` lets a format claim requests at the server root; see ADR 0012.

`GET /api/v1/formats` lists every installed format with its id, display name, supported repository
types, setting descriptors and template names, which is all the UI needs to build the repository
form: it offers only the supported types and renders an editor per setting.

## Consequences

* A format is still one module: its standard repositories, client documentation in the UI and
  options ship with it, and neither the core nor the UI changes when one is added. Fourteen format
  modules (21 format ids) were added this way in 0.2.0, and the Maven, npm and NuGet modules moved
  their standard repositories and snippets into their handlers.
* Snippets are generated from the same code that implements the protocol and are unit tested per
  format, so they show real commands, real file names and the server's own URL.
* Snippet titles, format names and setting labels come from the server in English. The UI keeps
  translations of format names and setting texts in its locale files and shows the English text for
  anything it has no translation for, so a new format works before it is translated; snippet titles
  and comments stay English.
* Settings are strings. The declared type is a hint for the editor; correctness is the handler's
  job, which keeps unusual values (cargo's `none`, comma-separated globs) possible without core
  changes. A key a handler does not declare is stored but has no effect.
* A template whose name is already taken, by any format, is skipped rather than renamed, so
  template names must be chosen to be unique across formats.
