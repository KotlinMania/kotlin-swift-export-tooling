# Recursive Foundation collection conversion

The source correction preserves Kotlin `Map<String, List<String>>` as Swift
`[String: [String]]`. Clang already imports the corresponding
`NSDictionary<NSString *, NSArray<NSString *> *> *` with that exact shape.
Generated direct calls therefore use their imported value without a redundant
forced cast. The same identity check recurses through String lists and
String-keyed dictionaries. Other conversions retain their original path.

Synchronous callback implementation parameters use the existing erased
implementation representation. Their checked conversion to the declared
Dictionary remains necessary and is retained. This does not introduce a public
erased type or establish general generic fidelity.

The diagnostic is built over independently retrieved, immutable
`2.4.20-km.3`. Five selected classes are rebuilt from pinned source. Each
unmodified baseline matches its published parent with the exact original module
metadata, all non-debug attributes are retained, 144 member references resolve,
and all unselected parent archive entries remain byte-identical. The complete
baseline and candidate compilation inventories each contain 168 classes.

## Executed isolated behavior

At source `f304ad3a12de1c457674d12b1b06731e4752daed`, diagnostic component
`cefd0ce7c9e7256929d8d0096c08d97c8249ae3f30637c346e422e902815a9b4`
compiled the actual Native main and generated bridge, linked an ARM64 archive,
and embedded successfully. SwiftPM exited zero. Eleven named cases each passed
once with zero failures or skips:

- Kotlin-created dictionaries, duplicate values and Unicode
- Swift-created dictionary round trips
- Empty list versus missing key
- Nullable dictionary: nil versus empty
- Nested dictionaries
- Lists of dictionaries
- Mutation and value semantics
- Temporary source owner released inside an autorelease pool
- Typed synchronous dictionary callback
- Nullable synchronous dictionary callback
- Kotlin-created returned closure invoked by Swift

Ten actual product/test frontend records retain warnings as errors and macOS 15
targeting: six generated-dependency records use Swift mode 5, two authored test
records use mode 6, and two generated runner records have no explicit mode.
Two manifest compilation frontends are separate. All 40,932 prepared SDK files,
85 frozen source/tool/recipe inputs, generated files and archive pins matched
before checker correction.

The original wrapper exited one after successful Swift execution because its
copied checker expected a KotlinStdlib module that this fixture does not emit.
The original recipe, failure and logs are preserved. Independent offline
verification classified the existing runtime evidence without rerunning tests.
The checker now requires the modules actually defined by this fixture and
handles whitespace in generated type spellings.

Workspace evidence directory:
`automation-artifacts/2026-10-21-foundation-collection-identity/`.
The actual runtime receipt is
`native-swift-v1/verified-runtime-result-v1.json`, SHA256
`4d8081697651950c1f5f9993cace262187b64526b46065bab56e5b83ae4c1d84`.
The original failure is `native-swift-v1.runner.log`; the scoped process return
is `native-swift-v1/local-slot-return.json`.

This is isolated fixture evidence. It does not establish Wiremock product
acceptance, publication, all-module Swift 6, arbitrary generic relationships,
malformed foreign-input behavior or concurrent access safety. The published
km.3 artifacts and SDK header contract remain unchanged.

## Reproducible release candidate

The km.4 layer builds over the immutable km.3 binary and complete sources,
retaining the selected classes' exact module identities. JDK 25.0.4 and
OpenJDK 21.0.12.1 builds produced identical components, SHA256
`4240be980f3a94bbb250bcf34d73a9b9585d73296edf09fe42bdf494377b0310`.
All six Maven artifact pairs matched byte-for-byte. Distribution verification
checked Apache licensing, notices, complete source and helper contents, and the
absence of bundled Rust source and Native SDK binaries. The typed class payload
is byte-identical to the executed diagnostic; release provenance metadata is an
explicit additional archive delta.

Gradle's actual `verifyWorkerPom` resolved the staged candidate POM and verified
all sixteen worker identities and hashes. Six helper regression tests passed.
The first Gradle metadata startup was denied its local locking socket by the
sandbox before any task ran; the separately logged permitted execution passed.
This is staged publisher validation, not independent public Maven retrieval.

The release workflow retains existing Native/Swift regressions and adds the
eleven collection cases. Exact-final PR CI, exact-main CI, immutable publication
and independent public Maven verification are separate release gates.
