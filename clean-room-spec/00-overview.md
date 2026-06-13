# Clean-Room Port of OpenIM SDK Core to Kotlin Multiplatform — Overview

## What this is

A **functional specification** of the AGPL-licensed `openim-sdk-core` (Go),
produced by **Team A** (the spec / "dirty" team) so that a separate **Team B**
can implement a Kotlin Multiplatform port **without ever reading the original
Go source**. It captures ideas, interfaces, protocol facts, data shapes, and
observable behaviors — not source expression.

| Doc | Contents |
|---|---|
| `01-public-api.md` | Public SDK function surface, listener interfaces, error→callback mapping, marshaling convention. |
| `02-data-models.md` | Message/content/conversation/relation data models (JSON field contracts) + callback payloads. |
| `03-wire-protocol.md` | WebSocket transport, sync flow, REST endpoints, protocol constants. |
| `04-persistence-and-sync.md` | Local SQLite schema, generic + version-based sync engine, caching. |

## ⚠️ Two things to settle before Team B writes any code

### 1. The actual license
The repo `LICENSE` file is **AGPL-3.0**, but `README.md` carries an **Apache-2.0**
badge. These imply very different obligations:
- If genuinely **Apache-2.0**, a clean room is largely unnecessary — you can fork
  and port directly with attribution.
- If **AGPL-3.0**, the clean room matters because a derivative work inherits AGPL's
  network-copyleft (§13: serving over a network triggers source disclosure).

Resolve this against the canonical upstream and file headers, **with counsel**,
before relying on this exercise. This document is not legal advice.

### 2. The gob wire-format risk
The WebSocket application envelope is **Go `gob`-encoded** (then optionally gzipped).
gob is Go-specific and not trivially reproducible from Kotlin. Either implement a
gob-compatible codec for the one fixed envelope struct, or switch the server to the
JSON/text variant (`isMsgResp=true`). **Confirm against the target server build** —
this is the single biggest interop unknown. See `03-wire-protocol.md` §0.

## Clean-room methodology (keep the wall intact)

1. **Team A (this side)** may read the Go source and produce/maintain this spec.
   The spec must contain ideas and interfaces only — no code, no verbatim comments,
   no copied file/function structure.
2. **Team B (implementers)** work *only* from this spec and independent sources
   (the running OpenIM server, public docs, the independently-published OpenIM
   protobuf/`protocol` definitions). Team B members must not have had access to the
   Go source.
3. **Provenance records:** keep dated authorship logs on both sides; this spec is the
   audited hand-off artifact between them.

### Independently-licensed inputs Team B may use directly
- The OpenIM **server API** behavior (it's the external system being targeted).
- The OpenIM **`protocol` repo** protobuf definitions for the matching server
  version — these define the inner `data` payloads for both WS frames and REST,
  and are *not* vendored in this repo. Verify their license (often permissive);
  if so, Team B can consume them without going through Team A.

## What is safely extractable (recap)
Wire protocols, server API contracts, data/DB schemas, the public SDK API surface,
observable state machines and behaviors, numeric protocol constants, and anything
genuinely public. **Not** extractable: the Go source, line-by-line translations,
structure/sequence/organization copied wholesale, comments, author-chosen identifiers
and magic strings, creative (non-protocol-dictated) algorithms.

## Hand-off note
For a defensible clean room, deliver this `clean-room-spec/` directory to Team B as a
**standalone artifact in a separate repository**. Team B's KMP repo should never
contain or reference the Go source. The spec is written self-contained so it lifts out
cleanly.
