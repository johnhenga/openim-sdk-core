# Vendored OpenIM protocol definitions

Proto sources copied verbatim from
[openimsdk/protocol](https://github.com/openimsdk/protocol) at tag
**v0.0.73-alpha.12** — the exact version pinned by the Go SDK's `go.mod` —
restricted to the packages the SDK imports: `sdkws`, `msg`, `conversation`,
`user`, `relation`, `group`, `third`, `push`, `auth`, `wrapperspb`.

Kotlin classes are generated into `commonMain` by the Wire Gradle plugin
(see `core/build.gradle.kts`). Do not edit these files; to upgrade, bump the
Go dependency first, re-copy the same tag here, and regenerate.
