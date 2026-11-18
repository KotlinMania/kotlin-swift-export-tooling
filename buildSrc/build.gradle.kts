import groovy.json.JsonSlurper
import java.security.MessageDigest

plugins { java }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }

dependencies {
    implementation(gradleApi())
    implementation(localGroovy())
    implementation(fileTree(gradle.gradleHomeDir!!.resolve("lib")) {
        include("commons-compress-*.jar", "commons-io-*.jar", "commons-codec-*.jar", "commons-lang3-*.jar")
    })
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.register("exportProducerClasspath") {
    dependsOn(tasks.classes)
    val destination = layout.buildDirectory.file("producer-classpath.txt")
    outputs.file(destination)
    doLast {
        destination.get().asFile.writeText(sourceSets.main.get().runtimeClasspath.asPath)
    }
}

val checkNullableUnsignedConsumer = tasks.register<JavaExec>("checkNullableUnsignedConsumer") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("io.github.kotlinmania.build.NullableUnsignedConsumerTests")
}

tasks.register("checkProducer") {
    dependsOn(tasks.classes, checkNullableUnsignedConsumer)
    doLast {
        val repoRoot = projectDir.parentFile
        fun sha256(targetFile: File): String =
            MessageDigest.getInstance("SHA-256")
                .digest(targetFile.readBytes())
                .joinToString("") { b -> "%02x".format(b) }
        val spiLockFile = repoRoot.resolve("producer/protocol-spi-defaults/source-lock.json")
        val spiPatchFile = repoRoot.resolve("producer/protocol-spi-defaults/SirProtocolFromKtSymbol.kt.patch")
        val spiVarPatch = repoRoot.resolve("producer/protocol-spi-defaults/SirVariableFromKtSymbol.kt.patch")
        val spiFnPatch = repoRoot.resolve("producer/protocol-spi-defaults/SirFunctionFromKtSymbol.kt.patch")
        require(spiLockFile.isFile && spiPatchFile.isFile && spiVarPatch.isFile && spiFnPatch.isFile)
        val spiLock = JsonSlurper().parse(spiLockFile) as Map<*, *>
        require(sha256(spiPatchFile) == spiLock["patch_sha256"].toString()) { "SPI protocol patch SHA-256 mismatch" }
        require(sha256(spiVarPatch) == spiLock["variable_patch_sha256"].toString()) { "SPI variable patch SHA-256 mismatch" }
        require(sha256(spiFnPatch) == spiLock["function_patch_sha256"].toString()) { "SPI function patch SHA-256 mismatch" }

        val km5LockFile = repoRoot.resolve("producer/km5/source-lock.json")
        require(km5LockFile.isFile) { "Missing producer/km5/source-lock.json" }
        val km5Lock = JsonSlurper().parse(km5LockFile) as Map<*, *>
        val providers = km5Lock["providers_sources"] as Map<*, *>
        for (name in listOf("TypeBridging.kt", "SirBridgeProviderImpl.kt")) {
            val row = providers[name] as Map<*, *>
            require(sha256(repoRoot.resolve(row["patch"].toString())) == row["patch_sha256"].toString()) {
                "km5 patch SHA-256 mismatch for $name"
            }
        }
        val printer = km5Lock["printer_source"] as Map<*, *>
        require(sha256(repoRoot.resolve(printer["patch"].toString())) == printer["patch_sha256"].toString()) {
            "km5 printer patch SHA-256 mismatch"
        }

        val receiptFile = repoRoot.resolve("build/protocol-spi-defaults-acceptance-01/result.json")
        if (receiptFile.isFile) {
            val receipt = JsonSlurper().parse(receiptFile) as Map<*, *>
            val producerJar = file(receipt["producer"].toString())
            require(producerJar.isFile && sha256(producerJar) == receipt["producer_sha256"].toString()) {
                "Candidate producer JAR SHA-256 mismatch"
            }
            require(receipt["baseline_non_debug_identical"] == true && receipt["unselected_entries_byte_identical"] == true) {
                "Baseline or unselected archive entry parity failed"
            }
            val behavior = receipt["behavior_verification"] as Map<*, *>
            for (flag in listOf(
                "baseline_reproduced_spi_duplicates",
                "baseline_reproduced_serialization_duplicates",
                "candidate_single_declaration_per_signature",
                "candidate_strict_swift_werror_passed",
                "candidate_runtime_dispatch_passed",
                "kotlinx_serialization_1_11_0_verified",
            )) {
                require(behavior[flag] == true) { "Missing or failed behavior verification check: $flag" }
            }
            val runtimeBinary = repoRoot.resolve("build/protocol-spi-defaults-acceptance-01/behavior/candidate-swift-build/verify-protocol-spi-defaults")
            require(runtimeBinary.isFile && sha256(runtimeBinary) == behavior["runtime_binary_sha256"].toString()) {
                "Compiled runtime verification binary missing or changed"
            }
            val proc = ProcessBuilder(runtimeBinary.absolutePath)
                .directory(runtimeBinary.parentFile)
                .redirectErrorStream(true)
                .start()
            val output = proc.inputStream.bufferedReader().readText()
            require(proc.waitFor() == 0 && output.contains("PASS protocol-spi-defaults runtime dispatch and serialization protocol resolution")) {
                "Runtime dispatch binary failed: $output"
            }
        }
        logger.lifecycle("PASS checkProducer: Java build (-Xlint:all -Werror), lock/patch SHA-256 integrity, and nullable-unsigned checks verified")
    }
}
