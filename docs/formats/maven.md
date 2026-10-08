# Maven 2 repositories

Format id: `maven2`. Repository URL: `http://host:8081/repository/<name>/`.

## Hosted

* **Version policy** `RELEASE`, `SNAPSHOT` or `MIXED` decides what `PUT` accepts
  (`400 repository.maven.policy.release|snapshot` otherwise).
* **Redeploy** of a release artifact is refused with `400 repository.maven.redeploy` unless
  *allow redeploy* is set on the repository. Snapshots are always replaceable.
* `maven-metadata.xml` at artifact level (versions, latest, release) and at snapshot-version
  level (timestamp, build number, snapshotVersions) is **generated** from the stored components,
  so it is always consistent even after deletes. A client-uploaded metadata file is accepted
  and merged, but never trusted over the stored content.
* Checksum sidecars (`.md5`, `.sha1`, `.sha256`, `.sha512`) are **computed on demand** when the
  client did not upload them, and **verified** against the artifact when it did
  (`400 repository.maven.checksumMismatch`).
* Directory listings (`GET .../org/example/`) return an HTML index like Maven Central does.

## Proxy

* Artifacts are immutable and cached forever.
* `maven-metadata.xml` and `.sha1`/`.md5` of metadata are re-validated after *metadata max age*.
* `remoteUrl` should end with the repository root, e.g. `https://repo1.maven.org/maven2/`.
  Authenticated upstreams accept a username/password or a bearer token.

## Group

* Artifacts: first member that has the path wins, members in the configured order.
* `maven-metadata.xml`: merged across all members, versions sorted with Maven's own
  `ComparableVersion` rules, `latest`/`release` recomputed, newest snapshot build wins.

## Client configuration

See the README for `settings.xml`, `distributionManagement` and Gradle snippets. For
`mvn deploy` the server id in `settings.xml` must match the id in `distributionManagement`.
