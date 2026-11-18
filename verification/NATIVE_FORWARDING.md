# Native forwarding for deprecated Swift exports

Kotlin source bridges call the exported function directly. When that function is deprecated, Native correctly diagnoses the generated source call under `-Werror`, even if the bridge is also deprecated. Changing only bridge metadata does not solve that compiler gate.

This source-derived producer correction emits a private external target binding with the original generic Kotlin signature. The paired compiler extension resolves exactly one original callable, compares alpha-renamed bounds and parameter/return types, and rewrites the binding call to that original symbol before Native lowering. Argument expressions, concrete type arguments and existing Swift/C/Native conversions are preserved. It does not substitute an implementation or filter diagnostics. The original API and its Kotlin/Swift deprecation metadata stay intact.

The current scope is top-level warning-deprecated functions without receivers, context parameters, varargs, suspension or reified parameters. Other forms retain their existing bridge and diagnostics. A missing plugin fails at Native linking. Bad signatures and residual binding/marker references fail verification. Private source-only binding metadata remains in the KLIB; executable IR and public ABI contain no binding helpers.

## Reproduce the candidate

Use the repository's existing Gradle-backed launcher. `--seeds` is an optional JSON map from SHA-256 to an already cached local input; every selected source/tool is checked against its pin.

```sh
tools/producer build-native-forwarding-candidate \
  --output "$PWD/build/native-forwarding-acceptance-01" \
  --cache "$PWD/build/inputs" \
  --component "$PWD/build/java-distribution-final/swift-export-embeddable-2.4.20-km.4.jar" \
  --native-home /path/to/kotlin-native-prebuilt-macos-aarch64-2.4.21
```

The command rebuilds and audits the unmodified upstream baseline, applies the exact producer patch, checks all non-debug class attributes and dependency references, builds the compiler extension with warnings as errors, then compiles and runs a separate Native library and downstream consumer. It checks generic/null/object identity, callback count, mutation, exception identity and overload selection. Negative controls cover authored deprecated calls, missing plugins, wrong signatures and surviving reflection/marker references. Results and all compiler commands are under the selected output directory.

## Validate the consumer

From the consumer repository, use the paired artifacts for this command only:

```sh
./gradlew test --continue --init-script ../kotlin-swift-export-tooling/verification/native-forwarding-consumer.init.gradle
```

The init script reads the verified `build/native-forwarding-acceptance-01/result.json`, checks both artifact hashes, replaces only the frozen exporter component and enables the extension only for generated Native bridge compilation. It preserves the consumer's canonical build file and warnings-as-errors policy. Debug and Release nested Swift gate invocations inherit the init script.

No publication, Maven-cache replacement or installed Native SDK modification is part of this workflow. Passing the Native fixture does not establish that the consumer's complete Swift and platform gates pass; inspect that command's final result separately.
