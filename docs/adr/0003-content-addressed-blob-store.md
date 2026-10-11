# 3. Content-addressed blob store

Date: 2026-10-08 · Status: accepted, amended by [ADR 0010](0010-single-node-write-coordination.md)

## Context

Artifacts are large, immutable and frequently duplicated (the same jar through two proxies,
the same tarball published to a hosted repository and cached from upstream). Crash safety
matters more than write speed.

## Decision

Store every blob under its SHA-256 (`content/ab/cd/<hash>`), writing to a temporary file in
the same directory and renaming into place. Rows in the `assets` table reference blobs by hash
and hold the repository path, content type, size, timestamps and origin. Deleting an asset
removes its row only; blobs that no asset references any more are reclaimed later by blob store
compaction (ADR 0010), because deleting them immediately would race uploads of the same bytes.

## Consequences

* Deduplication is free and checksums are known for every stored file.
* Blob stores can be copied with `rsync`; backup is a database dump plus the directory.
* Nothing in the layout is format-specific, so a future S3 blob store is a drop-in
  implementation of `BlobStore`.
