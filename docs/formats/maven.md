# Maven 2 repositories

Format id: `maven2`. Repository URL: `http://host:8081/repository/<name>/`.

Verified with Maven 3.9 and Gradle. Every client that reads and writes the Maven 2 layout works
the same way; the *Connect* panel also has configuration for Android builds, sbt and Leiningen.

## Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| `GET`, `HEAD` | `<group path>/<artifactId>/<version>/<artifactId>-<version>[-<classifier>].<ext>` | An artifact (jar, pom, module file, `.asc` signature, …); timestamped snapshot files included |
| `GET`, `HEAD` | `<group path>/<artifactId>/maven-metadata.xml` | Artifact-level metadata (versions, latest, release) |
| `GET`, `HEAD` | `<group path>/<artifactId>/<version>-SNAPSHOT/maven-metadata.xml` | Snapshot-version metadata (timestamp, build number) |
| `GET`, `HEAD` | `….md5`, `….sha1`, `….sha256`, `….sha512` | Checksum sidecars of any of the above |
| `GET` | any path ending with `/` | HTML directory listing |
| `PUT` | any file path | Deploy (hosted only) |
| `DELETE` | a file, or a directory path ending with `/` | Remove one file or everything below the directory (hosted, and cached files of a proxy) |

Other files (`.index/` files, archetype catalogs) are stored and served verbatim.

## Standard repositories

| Name | Type | Upstream / members | Version policy |
|------|------|--------------------|----------------|
| `maven-central` | proxy | `https://repo1.maven.org/maven2/` | `RELEASE` |
| `maven-google` | proxy | `https://maven.google.com/` (Android, Jetpack) | `RELEASE` |
| `clojars` | proxy | `https://repo.clojars.org/` | `MIXED` |
| `gradle-plugins` | proxy | `https://plugins.gradle.org/m2/` (Gradle Plugin Portal) | `RELEASE` |
| `maven-releases` | hosted | | `RELEASE` |
| `maven-snapshots` | hosted | | `SNAPSHOT` |
| `maven-public` | group | `maven-releases`, `maven-snapshots`, `maven-central`, `maven-google`, `clojars`, `gradle-plugins` | `MIXED` |

## Hosted

* **Version policy** `RELEASE`, `SNAPSHOT` or `MIXED` decides what `PUT` accepts
  (`400 repository.maven.policy.release|snapshot` otherwise).
* **Redeploy** of a release artifact is refused with `400 repository.maven.redeploy` unless
  *allow redeploy* is set on the repository. Snapshots are always replaceable.
* `maven-metadata.xml` uploaded by the client (Maven and Gradle upload it with every deploy) is
  stored and served as uploaded. When none was uploaded, artifact-level metadata (versions,
  latest, release) is **generated** from the stored components.
* Checksum sidecars (`.md5`, `.sha1`, `.sha256`, `.sha512`) are **computed on demand** when the
  client did not upload them, and **verified** against the artifact when it did
  (`400 repository.maven.checksumMismatch`).
* Every artifact is part of the component `(groupId, artifactId, version)`, and so are the
  uploaded checksum sidecars of artifacts: deleting the component in the UI or through the API
  removes them together. Metadata and its checksums belong to no component.
* Directory listings (`GET .../org/example/`) return an HTML index like Maven Central does.

## Proxy

* Artifacts are immutable and cached forever.
* `maven-metadata.xml` and the checksums of metadata are re-validated after *metadata max age*.
* `remoteUrl` should end with the repository root, e.g. `https://repo1.maven.org/maven2/`.
  Authenticated upstreams accept a username/password or a bearer token.
* Cached artifacts and their checksum sidecars are linked to the same component
  `(groupId, artifactId, version)` a deployment to a hosted repository records, so what builds
  downloaded shows up in the component browser and in search. `maven-metadata.xml`, its checksums
  and other files outside a version directory are not components. Downloads through a group are
  recorded in the proxy member that cached them, never in the group.
* Deleting a cached component removes its files from the cache; the next request fetches them
  again, or answers `503` while the proxy is offline. Files cached by an earlier Granary version,
  which recorded no components, are linked the first time they are served, even offline.

## Group

* Artifacts: first member that has the path wins, members in the configured order.
* `maven-metadata.xml`: merged across all members, versions sorted with Maven's own
  `ComparableVersion` rules, `latest`/`release` recomputed, newest snapshot build wins. Its
  checksums are computed from the merged document.
* Groups are read-only: `PUT` and `DELETE` answer `405 repository.readOnly`; deploy to a hosted
  member.

## Client configuration

The *Connect* panel of a repository shows ready-to-paste configuration with the server's URL and
your user name: `~/.m2/settings.xml` (mirror and credentials), `distributionManagement` for
`pom.xml`, `mvn deploy` and `mvn deploy:deploy-file` commands, Gradle `settings.gradle(.kts)`,
`gradle.properties` and publishing blocks, an Android variant, `build.sbt` with
`~/.sbt/.credentials`, and Leiningen's `project.clj`. The README shows the minimal
`settings.xml` and `distributionManagement`. For `mvn deploy` the server id in `settings.xml`
must match the id in `distributionManagement`.

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            name = "granary"
            url = uri("http://granary.example.com:8081/repository/maven-public/")
            credentials(PasswordCredentials::class) // granaryUsername / granaryPassword in ~/.gradle/gradle.properties
        }
    }
}
```

Gradle refuses plain `http` repositories unless `isAllowInsecureProtocol = true` is added to the
`maven { }` block; serve Granary over HTTPS in production.

## Settings

Maven has no format settings; the repository's *version policy* and *allow redeploy* fields
cover what the format needs.

## Offline behaviour

* Cached artifacts are served without contacting the upstream.
* Cached `maven-metadata.xml` is re-validated after *metadata max age* and served stale while the
  upstream is unreachable or the proxy (or the whole server) is in offline mode; a group merges
  whatever its members have cached.
* A file that is not cached answers `503 upstream.offline` (offline mode) or
  `503 upstream.unavailable` (upstream unreachable) instead of `404`, so the build fails with a
  transfer error that is retried later rather than a "not found" that is remembered.
* Concurrent requests for the same uncached file make one upstream request.

## Limitations

* No cleanup policies yet: old snapshot builds stay until they are deleted by hand
  (`DELETE` of a version directory).
* Snapshot-version metadata is not generated: it is served as the client uploaded it.
* No Maven indexer (`.index/`) generation; index files of a proxy are cached as ordinary files.
