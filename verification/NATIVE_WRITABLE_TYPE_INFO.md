# Complementary Native Objective-C metadata

Swift Export binds the Kotlin `MutableList` protocol to an Objective-C name. Native's collection exporter subsequently binds its Foundation converter. These occupy different slots of one `WritableTypeInfo`: the adapter is slot 3 and the converter is slot 0.

The original external/cached path tries to define the same LLVM global twice. The local/uncached path permits only one whole-value replacement and discards the complementary converter through its existing override-error handling. The source patch composes disjoint non-null fields atomically. It retains symbol identity, linkage, visibility, dependency tracking, and existing local conflict diagnostics. Malformed types or initializer shapes remain errors. Dynamic shared-library cache stores are unchanged and not covered by this repair.

The input source is pinned to JetBrains Kotlin revision `890ac1d94fdb80eb85f0eeb5be5e4352df987b2f`; its exact URL and hash are in `producer/native-writable-type-info/source-lock.json`. The installed 2.4.21 Native compiler is an immutable input. All six baseline classes must reproduce its complete non-debug class attributes. Exactly two classes may change, and every other JAR entry must remain byte-identical.

```sh
tools/producer build-native-writable-type-info-candidate \
  --output "$PWD/build/native-writable-type-info-acceptance-02" \
  --native-home /path/to/kotlin-native-prebuilt-macos-aarch64-2.4.21 \
  --source /path/to/pinned/WritableTypeInfo.kt
```

The Gradle-backed command strictly compiles the source, audits class attributes, reproduces the baseline collision, and checks one LLVM global with both converter and adapter pointers. It runs Swift 6 with warnings as errors against the repaired static library, checking actual `NSMutableArray` conversion, mutation in both directions, and original Kotlin object identity. It also checks rejection of duplicate class attachments. All Native reproducer commands retain `-Werror` and `-Xverify-bitcode`.

The standard Serde consumer suite has passed both Swift Debug and Release with this candidate (full run21, and again in run22 where two unrelated JavaScript fixture cases failed). That does not establish the separate uncached compiler fixture: its Release verifier currently runs before Codegen, where the compiler has emitted bodyless internal declarations. LLVM rejects those incomplete declarations before the metadata merge runs. The private-class negative fixture encounters the same earlier failure. Automatic approval review denied removing `-Xverify-bitcode`; that command did not execute. Preserve these failures as unresolved diagnostics, not successful negative tests.

For a complete Serde consumer run, use a real repository-owned copy of the Native SDK with only its compiler JAR replaced. Preserve executable modes; verify every source/copy file hash and reject symlinks or hardlinks. Do not modify the installed SDK or consumer template. The command-scoped `-Pkotlin.native.home` property is supported by KGP and forwarded to both nested Swift configurations:

```sh
./gradlew test --continue \
  --init-script ../kotlin-swift-export-tooling/verification/native-forwarding-consumer.init.gradle \
  --init-script ../kotlin-swift-export-tooling/verification/native-writable-type-info-consumer.init.gradle \
  -Pkotlin.native.home=/absolute/path/to/repository/build/native-home
```

The forwarding init script is separately required for the faithful deprecated API bridge. The full suite includes JS, Wasm, Android, Apple simulators, and real Linux/Windows virtual machines, followed by the configured Debug/Release Swift gates. Its terminal result is separate from the isolated compiler proof. No validation settings are weakened by the compiler candidate or init script.

The metadata init script reads acceptance-02, verifies the copied compiler hash, and declares it as an input to Native compile/link tasks. This forces recompilation when the candidate changes without modifying compiler options.
