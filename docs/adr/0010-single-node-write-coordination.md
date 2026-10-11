# 10. Single-node write coordination and blob compaction

Date: 2026-10-10 · Status: accepted

## Context

Concurrent writers of the same thing are normal for a repository manager: a CI matrix uploads
the same `maven-metadata.xml` from sixteen jobs, a fresh proxy sees a hundred builds ask for the
same missing jar at once, and two uploads in one component create it simultaneously. The
find-or-create logic in `StorageService` let two transactions both miss the row and both insert
it, so one failed with a duplicate-key error that surfaced as `500`. Proxies fetched the same
file from the upstream once per concurrent request.

The options weighed:

* **Catch the duplicate key and re-read in the same transaction.** Does not work on PostgreSQL,
  which aborts the whole transaction after a failed statement.
* **Insert in a `REQUIRES_NEW` transaction.** Needs a second pooled connection per writer
  (pool exhaustion under exactly the load that causes the races) and commits the row
  independently of the caller, which may still roll back.
* **Database locks (`SELECT … FOR UPDATE`, advisory locks).** Cannot lock a row that does not
  exist yet; advisory locks are PostgreSQL-only, and H2 is the default database.
* **In-JVM locks per key**, held until the transaction commits.

The content-addressed blob store (ADR 0003) shares one file between every asset with the same
bytes, so deleting an asset cannot delete its blob directly without racing a concurrent upload
of the same bytes. Blobs were therefore never deleted and disks only grew.

## Decision

* Granary runs as a single node. Authentication stays stateless (ADR 0007), but write
  coordination happens in the JVM.
* `StorageLocks` keeps one lock per asset path and per component coordinates (no striping, so
  unrelated keys never collide), taken in a fixed order (path, then component), released in
  `TransactionSynchronization.afterCompletion` so the next writer sees the committed row, and
  acquired with a bounded wait. A wait that times out fails with `409 storage.conflict`, as do
  constraint violations and locking failures that still reach the web layer; the unique
  constraints remain the last line of defence. A path key is always taken before the writer
  opens its transaction and a component key only inside it, so a thread holding a pooled
  connection never waits for a thread that is waiting for a connection, however small the pool.
* `ProxyService` lets one request per repository and path fetch from the upstream while the
  others wait, bounded, for the result to be committed. A fetch inside a caller's own
  transaction does not take part: it could not publish its result before that transaction
  commits, and it would wait while holding the transaction's connection.
* Blobs are reclaimed by compaction rather than on delete: a blob is deleted only when no asset
  of a repository using its store (or another store configured at the same directory, however
  its path is spelled) references it and its file is older than a grace period.
* A write stores its blob before it commits the row that references it, and may wait for a
  lock or a connection in between. `StorageService` therefore pins the blob in the JVM from the
  moment it is placed until that row is committed or the write has failed; compaction skips
  pinned blobs whatever their age. Storing existing bytes also refreshes the file's
  modification time. The pin check, the age check, a last reference query and the delete happen
  under the same per-blob lock as placing, refreshing and pinning, so a write either finds the
  file gone and puts it back, or keeps it. The grace period remains as the margin for references
  that do not come from a write through `StorageService.store`.

## Consequences

* Concurrent uploads and proxy misses no longer fail or multiply upstream traffic, on H2 and
  PostgreSQL alike, with no extra database connections.
* Running two instances against one database and blob store is not supported: writes on
  different instances are only protected by the database constraints (`409` instead of
  corruption), and compaction on one instance could delete a blob that an upload on the other
  is re-using. Supporting several nodes later means replacing `KeyedLocks` with a distributed
  lock and running compaction on one node only.
* Deleted content occupies disk until the next compaction (daily by default, or on demand).
* A repository's blob store cannot change once it holds content, because compaction decides
  per store which blobs are still referenced.
