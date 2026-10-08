# 2. Java 25 LTS and Spring Boot 4

Date: 2026-10-08 · Status: accepted

## Context

The requirement was the newest *stable* Java. Java 25 is the current long-term-support release
(September 2025), Spring Boot 4.1 is the current stable Boot line and supports it. Virtual
threads make a repository manager, which spends its life waiting on disks and upstreams, cheap
to scale.

## Decision

Build on Java 25 with `--release 25`, Spring Boot 4.1 (Spring Framework 7, Spring Security 7,
Hibernate 7, Jackson 3), virtual threads enabled. Keep the Boot parent so that minor upgrades
are a single version bump.

## Consequences

* Contributors need JDK 25; the Docker build does not.
* Jackson 3 (`tools.jackson`) and JUnit 6 are used throughout; older snippets from the internet
  need package renames.
