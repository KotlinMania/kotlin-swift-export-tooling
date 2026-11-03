# Java build-tool migration validation

The maintained producer pipeline now runs through `tools/producer` and the Java classes in `buildSrc/src/main/java/io/github/kotlinmania/build/`. The 19 former Python scripts, standalone class-linkage auditor, helper unittest, and bytecode cache have been removed. Gradle imports `ProducerCli` for the distribution gate, and the workflows call the Java launcher. Java 21 build logic uses libraries bundled with the pinned Gradle distribution.

Local validation completed on 2026-10-26:

| Check | Result |
| --- | --- |
| Java compilation with `-Xlint:all -Werror` | Passed |
| `tools/producer check` | 218 checks passed |
| Four independent builds from locked km.3 compiler/source inputs | Passed all source, class inventory, reviewed attribute, and archive delta checks; the final builds used the precise JSON parser and explicit compiler source order |
| Exact compiler-host member linkage | 144 references verified for each build |
| Main component reproducibility | All four builds produced SHA256 `4240be980f3a94bbb250bcf34d73a9b9585d73296edf09fe42bdf494377b0310` |
| Complete distributions | All six files from all four builds were identical and matched `verification/release-artifacts.json` |
| Normal Gradle `verifyDistribution` | Passed against existing local frozen artifacts |
| Java launcher after removing the old scripts | Checks and distribution validation passed |

The rebuilds reused 17 verified existing cache files, totaling 113,322,113 bytes, directly through a SHA256-to-path seed index. Only eight absent, pinned public artifacts were retrieved into `build/inputs`; no SDK or existing compiler cache was copied. Reports and logs are under `build/reports/java-migration/`. Reproduction outputs are under `build/java-reproduction-a`, `build/java-reproduction-b`, `build/java-reproduction-classic`, `build/java-reproduction-final`, and their matching `build/java-distribution-*` directories. The final JSON parser retains large integers and scalar values. The six collection compiler inputs use the explicit original source order with exact inventory validation; parser object order cannot change it. Source patches are applied in explicit recipe order, and archive entries and artifact metadata use stable ordering.

The checks include real JVM class compilation and linkage failures, the actual Foundation collection source patch round trip, immutable archive/POM/SBOM reproduction, SDK identity and tampering rejection, named Swift-result policies, compiler mode/warning/target checks, failing/skipped XML test reports, intentional Native-boundary process signals versus ordinary exit codes, timeout cleanup, output isolation, and generated Gradle init-policy execution. Synthetic result fixtures exercise validators; they do not constitute a Native or Swift consumer acceptance run.

No full Native/Swift consumer was executed for this producer migration. The pinned consumer checkout, compiler/SDK prerequisites, actual named runtime cases, and Debug/Release requirements remain mandatory for consumer acceptance. No signing operation, remote publication, independent Maven signature retrieval, push, or PR was performed. Publication gates and remote retrieval were ported and inspected; this local validation does not claim those routes were executed.

The already-published km.4 helper ZIP remains an immutable input. Its historical interpreter source is hashed in memory and is neither extracted nor executed. Java performs SDK preparation, retaining the complete SDK inventory and exact header-change checks. Frozen release documentation and helper bytes are reproduced exactly; separate Java provenance receipts identify the actual Java source files and the current versus frozen README fingerprints. No old release fingerprint or consumer source pin was rewritten to accommodate the port.

Commands used for the actual compiler reproduction:

```sh
tools/producer check
tools/producer build --output build/java-reproduction-a --cache build/inputs \
  --seeds build/reports/java-migration/input-seeds.json
tools/producer build --output build/java-reproduction-b --cache build/inputs \
  --seeds build/reports/java-migration/input-seeds.json
tools/producer distribution --build build/java-reproduction-a --output build/java-distribution-a
tools/producer distribution --build build/java-reproduction-b --output build/java-distribution-b
tools/producer check-distribution --distribution build/java-distribution-a \
  --comparison build/java-distribution-b
./gradlew verifyDistribution --offline --no-daemon --max-workers=2 --no-configuration-cache
```

Each reproduction output directory must be new. The seed index is machine-local evidence and is not required for normal builds; every input is still checked against the checked-in lock whether reused or retrieved.
