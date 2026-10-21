import org.gradle.plugins.signing.Sign
import groovy.json.JsonSlurper
import java.security.MessageDigest
import org.gradle.api.attributes.Bundling
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.Usage

plugins {
    base
    signing
}

group = "io.github.kotlinmania"
version = "2.4.20-km.1"
val releaseCoordinate = "${project.group}:swift-export-embeddable:${project.version}"

val distributionDirectory = providers.gradleProperty("distributionDirectory")
    .map { file(it) }.getOrElse(layout.buildDirectory.dir("distribution-a").get().asFile)

repositories {
    maven { url = uri(providers.gradleProperty("releaseRepository").getOrElse("build/resolution-repository")) }
    mavenCentral()
}

val releaseWorker = configurations.create("releaseWorker") {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
    }
}
dependencies {
    add(releaseWorker.name, releaseCoordinate)
}

tasks.register("verifyWorkerPom") {
    description = "Resolves the actual release POM and verifies the complete sixteen-component worker closure."
    doLast {
        val lock = JsonSlurper().parse(file("inputs.lock.json")) as Map<*, *>
        val rows = (lock["tools"] as List<*>).map { it as Map<*, *> }.filter { it["scope"] == "worker" }
        val expected = rows.associate { row ->
            val own = row["artifact"] == "swift-export-embeddable"
            val coordinate = if (own) releaseCoordinate else "${row["group"]}:${row["artifact"]}:${row["version"]}"
            val hash = if (own) {
                val release = JsonSlurper().parse(file("verification/release-artifacts.json")) as Map<*, *>
                release["main_component_sha256"].toString()
            } else row["sha256"].toString()
            coordinate to hash
        }
        val actual = releaseWorker.resolvedConfiguration.resolvedArtifacts.associate { artifact ->
            artifact.moduleVersion.id.toString() to MessageDigest.getInstance("SHA-256")
                .digest(artifact.file.readBytes()).joinToString("") { "%02x".format(it) }
        }
        require(actual == expected) { "Resolved release worker dependency identities or hashes differ: $actual" }
        val output = layout.buildDirectory.file("resolved-worker.json").get().asFile
        output.parentFile.mkdirs()
        output.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(actual)) + "\n")
        logger.lifecycle("Verified all ${actual.size} actual release worker components")
    }
}

signing {
    val key = providers.environmentVariable("SIGNING_KEY").orNull
    if (key != null) {
        useInMemoryPgpKeys(
            providers.environmentVariable("SIGNING_KEY_ID").orNull,
            key,
            providers.environmentVariable("SIGNING_KEY_PASSWORD").orNull,
        )
    }
}

tasks.register<Sign>("signRelease") {
    description = "Signs verified release files using an existing key held in memory."
    val artifacts = fileTree(distributionDirectory) {
        include("*.jar", "*.pom", "*-native-sdk-helper.zip", "*-sbom.json")
    }
    sign(*artifacts.files.toTypedArray())
    doFirst {
        require(artifacts.files.size == 6) { "Expected exactly six verified release artifacts" }
        require(providers.environmentVariable("SIGNING_KEY").isPresent) { "An existing signing key is required" }
    }
}
