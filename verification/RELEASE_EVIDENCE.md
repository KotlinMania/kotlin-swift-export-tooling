# Release candidate 2.4.20-km.1

Two complete clean source builds produced the same component SHA256:
`e1b871c93dd9556fb7235cd05f70b3c2bc7d32f2cb1b69f1d9161918fd088f3a`.
The frozen six-file release inventory is [release-artifacts.json](release-artifacts.json).
Every original baseline member, access flag and instruction audit passed. The
candidate's non-debug class fingerprints match the previously reviewed source
correction. Sources, documentation, helper, POM and SBOM also reproduce exactly.

An independent real ProcMacro2 consumer at
`3d8718c2412be6aa99175ab88663edec8dbfa6db` compiled the Native library and generated
bridge, linked its ARM64 static archive, and executed these unchanged Swift tests:

- `tokenStreamParsingAndFormatting`
- `tokenStreamParseFailure`
- `literalAndPunctExport`

All three passed once with zero failures/skips. Twelve actual dependency frontend
records use Swift 5; two authored test frontend records use Swift 6. Two generated
runner records have no explicit language-mode flag and are recorded separately.
All sixteen product/test records retain warnings as errors and macOS 15 targeting.
Two manifest-configuration frontends are separate. The same-version 282 public
declaration records and C header matched. This is scoped behavior evidence, not
automatic Array, arbitrary reverse callback or precompiled-client ABI proof.

The independent consumer receipt SHA256 is
`d3efd67db7e934bc08c3009f21e28028d066c9ec3726d2b8e7982eafa2e3577e`;
the independent receipt/input recheck is
`73f7786053078f88de249ca517850768a4b4c5dbbcec02fb8cea1742094c38ac`.
Raw local evidence is retained under workspace
`automation-artifacts/2026-10-21-proc-macro2-release-e1b871c/` and
`automation-artifacts/2026-10-21-swift-tooling-release/`.

The first helper execution audited 9,719 SDK files: exactly the header changed,
all binaries and the source SDK remained unchanged. Its receipt SHA256 is
`e9caac5c120f889268fc4c9ea216cdb5bfdac5cf7e5c0610ca5688abf27d259c`.
The helper source hash was observed afterward, not frozen before that execution.
Release CI closes that provenance gap by extracting the actual helper ZIP,
freezing its source before execution and auditing the freshly downloaded official
SDK before running the independent consumer.

Publication is established only by successful exact-final PR/main CI, an
immutable Central upload, independent retrieval of the frozen files and detached
signature verification. Local artifacts are not publication evidence.
