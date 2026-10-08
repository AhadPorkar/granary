# 1. Record architecture decisions

Date: 2026-10-08 · Status: accepted

## Context

Granary is meant to be read, bought and extended by people who were not in the room when it was
designed. The reasons behind a decision are as important as the decision.

## Decision

Keep short ADRs in `docs/adr`, one per significant decision, in the Nygard format
(context, decision, consequences). Superseded ADRs stay and point at their replacement.

## Consequences

Contributors add an ADR with any change that alters a public protocol, storage layout, the
security model or a build-time dependency.
