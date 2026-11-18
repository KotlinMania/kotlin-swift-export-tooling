# KotlinMania Swift export tooling

Version-pinned repairs for Kotlin/Native's direct Swift export pipeline. This is host compiler tooling, not a Kotlin Multiplatform application dependency. Kotlin/Native still produces native KLIBs and ARM64 machine code; Swift compiles and calls the generated bindings directly.

The release coordinate is `io.github.kotlinmania:swift-export-embeddable:2.4.20-km.5`. Release availability is established by the tagged release and independently retrieved Maven artifacts, not by a locally built file. The source branch and earlier diagnostic receipts alone are not publication evidence.

Version `2.4.20-km.5` builds on `2.4.20-km.4` and adds:
- protocol SPI default witness exclusion, empty `Swift.Never` abstract setter bodies, and deprecated reverse bridge call suppression (`producer/protocol-spi-defaults/`),
- normalized signed/unsigned `NSNumber` bridging for nullable unsigned integers (`UByte?`, `UShort?`, `UInt?`, `ULong?`) (`producer/nullable-unsigned/`),
- removal of redundant callback `as! [Any]` casts in `AsNSArray` (`producer/km5/`),
- reverse collection bridge `Any -> [String]` casting in `SirBridgeProviderImpl` (`producer/reverse-collection-cast/`), and
- file-level `DEPRECATION` suppression in `KotlinBridgePrinter` so bridges referencing `@Deprecated(WARNING)` classes, constructors, casts, and functions compile under `-Werror` while preserving `@Deprecated` and `@available(*, deprecated)` (`producer/deprecation-bridge/`).

It retains km.3's one-step typed `ByteArray` constructors with unchanged public Swift signatures. The constructor fixture exercises six Swift behavior cases and seven C boundary processes, including checked bounds and receiver rejection. Other primitive-array constructors are unchanged. Every product library needs its own actual published-input behavior checks; producer fixture results are not fleet acceptance.

## Compatibility

This version targets **KGP 2.4.20 and Kotlin/Native 2.4.20**, initially on macOS ARM64 with Swift 6.3.3 and macOS 15 deployment. Generated dependency sources compile in Swift **5 language mode**; authored consumer tests compile in Swift **6 language mode**. Both retain warnings as errors, as does the generated Native Kotlin bridge. Swift tools-version 5.9 is a manifest/API minimum and package default, not an older compiler installation.

The original mutable `KotlinError.wrapped` property, reference identity, copy semantics and lazy message behavior remain. No unchecked Sendable conformance or warning suppression is added. This mixed-language configuration is not proof that arbitrary mutable Kotlin objects are safe to transfer between actors. Invariant non-null String arrays have a concrete typed view with checked Native storage, including supported nested collection and synchronous callback conversions. Arrays of other element types, general reverse asynchronous callbacks, recursive generic bounds, public Throwable cause/subtype round trips and old precompiled-client ABI remain outside the demonstrated acceptance. Production selection requires independent exact-head validation in each consumer; fixture passes do not establish another library’s acceptance.

## Installation

Use the published version only on the exporter tool configuration. Keep KGP, Native and ordinary published product dependencies aligned; do not put compiler tooling in `commonMain`.

```kotlin
configurations.configureEach {
    if (name == "swiftExportClasspath") {
        dependencies.add(project.dependencies.create(
            "io.github.kotlinmania:swift-export-embeddable:2.4.20-km.5"
        ))
    }
}
```

Replace the existing official exporter provider rather than adding a second one. Verify the resolved worker classpath and lock its checksums. The POM retains the required official worker dependencies, including the IntelliJ coroutine runtime. Do not substitute Maven local, sibling sources or an unverified version.

Use an Xcode configuration with `SWIFT_VERSION = 5`, `SWIFT_TREAT_WARNINGS_AS_ERRORS = YES` and `GCC_TREAT_WARNINGS_AS_ERRORS = YES` for generated dependencies. Keep the authored Swift package/test target explicitly in mode 6. Do not apply a global `-swift-version 6` argument to every dependency. Repository policies that force all generated targets to mode 6 need an explicit reviewed configuration change; this is not permission to delete arbitrary Gradle actions or edit generated APIs.

### Local validation install

A local Maven install exists only so a consumer can exercise the exact verified release bytes through normal dependency resolution before adoption, using the same standard `maven-publish` flow as serde-kotlin. It is never a publication and never substitutes the published immutable release. It needs a verified distribution directory first — from CI or a local reproducible build (Reproducibility and [PUBLISHING](verification/PUBLISHING.md)):

```sh
./gradlew publishToMavenLocal -PdistributionDirectory=build/distribution-a
# isolated local repository, keeping the default one clean:
./gradlew publishToMavenLocal -PdistributionDirectory=build/distribution-a \
  -Dmaven.repo.local=/absolute/path/to/isolated-repository
```

A `verifyDistribution` gate runs before any publish task: the distribution receipt must match the frozen `verification/release-artifacts.json` fingerprints, every file must verify byte-for-byte by SHA-256, and the POM identity is checked; the fingerprints were frozen only after the deep archive and licensing audits passed, so unverified bytes are refused. When no local distribution exists, the build first retrieves the published release artifacts from Maven Central and verifies each byte against the same frozen fingerprints, so the bare command works without a local reproduction. The publication carries the six verified artifacts in `maven-publish`'s standard Maven layout, and the generated POM is replaced with the frozen verified POM bytes, so the local install is byte-identical to the audited release — the POM is itself a verified release byte carrying the worker dependency closure. Reinstalling overwrites a previous local install (standard Gradle behavior). Consume the local repository restricted to `io.github.kotlinmania`, as serde-kotlin's validation consumers do:

```kotlin
dependencyResolutionManagement {
    repositories {
        exclusiveContent {
            forRepository { mavenLocal() }
            filter { includeGroup("io.github.kotlinmania") }
        }
        mavenCentral()
    }
}
```

Run the consumer with the same `-Dmaven.repo.local` path when using an isolated repository. Adoption decisions still require the independently published and verified Central release.

### Separate Native SDK header

The exporter alone is insufficient: the matching Native SDK needs the reviewed retained-return declaration. Resolve the `native-sdk-helper` ZIP classifier of the same coordinate using normal dependency resolution and retain it as a checksum-verified input in the project build directory. The Java build tools prepare the SDK from its locked metadata and header patch. The already-published km.4 archive includes historical interpreter source; Java verifies those immutable bytes in memory and never extracts or executes that source. It does not contain an SDK or Native binaries.

```sh
mkdir -p build/native-sdk
tools/producer prepare-native-sdk \
  --helper build/distribution-a/swift-export-embeddable-2.4.20-km.5-native-sdk-helper.zip \
  --source "$OFFICIAL_KOTLIN_NATIVE_2_4_20_HOME" \
  --destination "$PWD/build/native-sdk/kotlin-native-2.4.20-km.5" \
  --receipt "$PWD/build/native-sdk/input-audit.json"
./gradlew embedSwiftExportForXcode \
  -Pkotlin.native.home="$PWD/build/native-sdk/kotlin-native-2.4.20-km.5"
```

Keep Native automatic dependency caches outside that SDK copy. The compiler's
supported cache-output option preserves caching and warning policy:

```kotlin
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink>().configureEach {
    val cache = layout.buildDirectory.dir("native-auto-cache/$name")
    toolOptions.freeCompilerArgs.add(cache.map { "-Xauto-cache-dir=${it.asFile.absolutePath}" })
}
```

Without this setting, a cold SDK can receive generated dependency-cache archives
under `klib/cache` during linking. Those are build outputs, not changes made by
the header helper. Release verification requires the entire prepared SDK input
inventory to remain unchanged during the consumer build and tests.

The source is a trusted matching official SDK, read-only. The helper pins the original header, compiler launcher, compiler component and configuration; inventories every input; rejects symlinks, existing destinations, nested/overlapping trees and unrecognized compiler/header identities; copies the SDK; applies exactly the header patch; verifies every other file and the unchanged source tree; and writes the complete audit. It never overwrites installed compiler files. The receipt proves copy integrity, not an independent signature verification of an arbitrary user-supplied SDK.

Original header SHA256: `14390b9a40bcd8f924909692249ca95468cd3de36157be762371b1673dab86f3`.
Corrected header SHA256: `99186d1dd029a8ba5b66dae390a3aec0d6d0e5293eff641d85daab5e212723c3`.

## Reproducibility

`inputs.lock.json` pins official Kotlin revision `890ac1d94fdb80eb85f0eeb5be5e4352df987b2f`, the official exporter, every source/patch and compiler dependency. Source patches are applied with no fuzzy context and checked before and after application. No full Kotlin source checkout or frozen Rust source is vendored.

```sh
tools/producer build --output build/reproduction-a --cache build/inputs
tools/producer build --output build/reproduction-b --cache build/inputs
cmp build/reproduction-a/swift-export-embeddable-2.4.20-km.5.jar \
    build/reproduction-b/swift-export-embeddable-2.4.20-km.5.jar
tools/producer distribution --build build/reproduction-a --output build/distribution-a
tools/producer distribution --build build/reproduction-b --output build/distribution-b
tools/producer check-distribution --distribution build/distribution-a --comparison build/distribution-b
tools/producer check
```

The launcher compiles the Java build tools with the pinned Gradle distribution and its bundled libraries. Java 21 is required for the build logic; no separate interpreter or third-party package manager is used. Generated files and temporary directories stay under this repository's `build/`. An optional `--seeds` JSON map from SHA256 to existing local files lets the compiler use verified binary caches directly, without copying SDK or toolchain caches; every selected file is still hashed and included in the compiler input audit. The build tools are also imported directly by the Gradle distribution gate. Current Java source fingerprints are recorded in separate provenance receipts. Reproducing the already-published km.4 artifacts retains their pinned release documentation and helper bytes; current usage instructions live here, and no frozen fingerprint is rewritten to accommodate the port.

The build recompiles unmodified baselines and requires exact member descriptors/access flags and complete normalized instruction graphs. It rebuilds the selected producer classes from authoritative source, keeps unselected official classes unchanged, and checks all non-debug class attributes against reviewed fingerprints. Only source/line/local-variable debug maps and the exact validated invisible Kotlin SourceDebugExtension SMAP annotation are excluded from that comparison; annotations, Kotlin metadata, frames and executable instructions remain. Fail-closed auditor tests reject malformed/duplicate debug annotations and preserve actual field changes. Complete-source debug maps can differ from the earlier incremental diagnostic artifact; that raw mismatch is recorded rather than relabeled as byte equality.

The archive has deterministic entry ordering, permissions and timestamps. The km.2 Array layer builds over the immutable verified km.1 component, with its 159-class baseline and 30 selected class fingerprints preserved. The km.3 constructor layer builds over immutable km.2, verifies eight complete baseline constructor classes, locks eleven selected classes including five additions, checks every retained/new member reference, and preserves every excluded parent entry byte-for-byte. Previous source patches and worker inputs remain pinned. License/provenance additions and metadata replacement are explicit. Two clean build hashes must match. Native and actual Swift behavior gates are separate from these host-tool checks and must execute nonzero named cases. Package manifest compilation and SwiftPM's generated runner are classified separately from authored consumer and dependency frontends.

## Licensing and maintenance

Apache License 2.0 applies; see [LICENSE](LICENSE), [NOTICE](NOTICE) and preserved [upstream notices](licenses/upstream/NOTICE.txt). Upstream copyrights remain in the source diffs and rebuilt sources. The Maven POM must declare the same license. External worker dependencies retain their own licenses and are resolved separately. SDK binaries and local Rust reference tools are not published here. This is independently maintained tooling, not an official JetBrains release.

Each update needs a new immutable version, pinned compatible toolchain, reviewed source and archive deltas, reproducible builds, licensing/provenance checks, actual Native linking and real Swift consumer behavior. Required CI runs at the final PR revision and merged main before release; released Maven contents and POM are independently fetched and verified before consumer adoption. No new credentials or persistent secret settings are configured by the build.

Rollback restores the recorded immutable tool/configuration selection and regenerates coherent outputs. It does not modify installed tools, erase prior evidence or turn a known stock failure into a pass. Keep warning and nonzero-test gates enabled.

## Typed String arrays

The producer checks in the concrete Swift ownership view and Native storage implementation under `producer/arrays/`. Generation maps invariant non-null String arrays to that view and emits matching Swift, Kotlin and C declarations together. No generated source is rewritten after emission. The Swift view owns one retained Native reference and releases it on destruction. Calls copy an independent retained reference while the view is alive; the Native conversion checks its storage type and consumes that copy exactly once. Optional nil creates no reference. Collection boxing retains its own storage object. Swift wrapper identity is not promised; equality and hashing preserve the underlying array identity.

The isolated reviewed candidate passed 27 named Swift behaviors and a separate Native boundary probe: one positive mutation/alias case plus 13 intentional bounds/type rejection processes. Generated dependencies used Swift 5, authored tests Swift 6, and both Swift and Native compilation retained warnings as errors. These checks include Native weak-root release, destruction of an associated Objective-C witness, and temporary direct/nullable/callback ownership transfers. Release acceptance additionally requires clean reproducible builds, current real consumer tests, exact-final and main CI, and independent published artifact verification.

The new pointer ownership contract requires coherent regenerated Swift and Native components. It is not interchangeable with earlier diagnostic borrowing variants merely because their C signatures also used pointers. No general concurrent access, abandoned asynchronous delivery, arbitrary generic relationship or precompiled-client compatibility is claimed.
