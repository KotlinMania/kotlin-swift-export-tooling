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
        val lockFile = repoRoot.resolve("producer/protocol-spi-defaults/source-lock.json")
        val patchFile = repoRoot.resolve("producer/protocol-spi-defaults/SirProtocolFromKtSymbol.kt.patch")
        val receiptFile = repoRoot.resolve("build/protocol-spi-defaults-acceptance-01/result.json")
        require(lockFile.isFile && patchFile.isFile && receiptFile.isFile) {
            "Expected protocol-spi-defaults lock, patch, and acceptance receipt under ${repoRoot.absolutePath}"
        }
        fun sha256(targetFile: File): String =
            MessageDigest.getInstance("SHA-256")
                .digest(targetFile.readBytes())
                .joinToString("") { b -> "%02x".format(b) }
        val lock = JsonSlurper().parse(lockFile) as Map<*, *>
        val receipt = JsonSlurper().parse(receiptFile) as Map<*, *>
        require(sha256(patchFile) == lock["patch_sha256"].toString()) { "Patch SHA-256 mismatch" }
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
        logger.lifecycle("PASS checkProducer: Java build (-Xlint:all -Werror), class/closure audit, kotlinx.serialization 1.11.0 Swift export, and runtime dispatch verified")
    }
}
