pluginManagement {
    // kotlinmania:maven-mode:v1 begin
    val mavenProperties =
        java.util.Properties().apply {
            val source = settingsDir.resolve("maven.properties")
            require(source.isFile) { "Missing Maven mode configuration: $source" }
            source.inputStream().use { load(it) }
        }
    val localMaven = mavenProperties.getProperty("maven.local")?.trim()?.toBooleanStrictOrNull()
    require(localMaven != null) { "maven.properties must set maven.local=true or false" }
    val localMavenValidation =
        providers
            .gradleProperty("project.repositories.mavenLocal")
            .map { it.trim().toBooleanStrict() }
            .getOrElse(false)
    val configuredLocalRepository =
        mavenProperties
            .getProperty("maven.localRepository")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { File(it) }
            ?: File(System.getProperty("user.home"), ".m2/repository")
    require(configuredLocalRepository.isAbsolute) { "maven.localRepository must be an absolute path" }
    val localRepository =
        if (localMavenValidation) {
            val requested = providers.systemProperty("maven.repo.local").orNull
            require(requested != null && File(requested).isAbsolute) {
                "Local Maven validation requires -Dmaven.repo.local=/absolute/path/to/isolated-repository"
            }
            File(requested)
        } else {
            configuredLocalRepository
        }
    // Ordinary mode comes from the file. Explicit validation retains its isolated command-line path.
    System.setProperty("maven.repo.local", localRepository.absolutePath)
    settings.extra["mavenLocalEnabled"] = localMaven
    settings.extra["mavenLocalValidation"] = localMavenValidation
    // kotlinmania:maven-mode:v1 end

    repositories {
        if (localMaven && !localMavenValidation) mavenLocal()
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

val localMavenEnabled = extra["mavenLocalEnabled"] as Boolean
val localMavenValidation = extra["mavenLocalValidation"] as Boolean

dependencyResolutionManagement {
    repositories {
        if (localMavenValidation) {
            exclusiveContent {
                forRepository { mavenLocal { name = "kotlinManiaLocalValidation" } }
                filter { includeGroup("io.github.kotlinmania") }
            }
        } else if (localMavenEnabled) {
            mavenLocal()
        }
        mavenCentral()
    }
}

rootProject.name = "kotlin-swift-export-tooling"

// kotlinmania:maven-project-repositories:v1 begin
gradle.beforeProject {
    repositories {
        if (localMavenValidation) {
            exclusiveContent {
                forRepository { mavenLocal { name = "kotlinManiaLocalValidation" } }
                filter { includeGroup("io.github.kotlinmania") }
            }
        } else if (localMavenEnabled) {
            mavenLocal()
        }
    }
    buildscript.repositories {
        if (localMavenValidation) {
            exclusiveContent {
                forRepository { mavenLocal { name = "kotlinManiaLocalValidation" } }
                filter { includeGroup("io.github.kotlinmania") }
            }
        } else if (localMavenEnabled) {
            mavenLocal()
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
// kotlinmania:maven-project-repositories:v1 end
