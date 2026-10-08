# 5. One module per format behind a small SPI

Date: 2026-10-08 · Status: accepted

## Context

Granary should eventually support as many formats as a commercial repository manager, each
with its own quirks, without the core growing an `if format == …` in every method.

## Decision

A format is a Maven module containing one `FormatHandler` bean. The handler owns everything
protocol-specific: path parsing, which paths are metadata, read/write classification, document
generation and merging. The core provides repository storage, proxy caching, group resolution
and security as services the handler composes. `FormatRegistry` discovers handlers at start-up
and the UI, the REST API and the dispatcher are format-agnostic.

## Consequences

* A new format never touches the core; the server module only needs the dependency.
* Format modules can be excluded from a build to produce a smaller server.
* Cross-format features (search, cleanup policies) are implemented once against components and
  assets, not per protocol.
