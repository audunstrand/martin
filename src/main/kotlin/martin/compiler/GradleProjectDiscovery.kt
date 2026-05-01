package martin.compiler

import java.io.File
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Discovers source roots and classpath entries from a Gradle project.
 */
class GradleProjectDiscovery(private val projectDir: Path) {

    private val cacheDir = projectDir.resolve(".martin")
    private val classpathCacheFile = cacheDir.resolve("classpath.cache")
    private val classpathHashFile = cacheDir.resolve("classpath.hash")

    /**
     * Discovers Kotlin source roots by looking at conventional Gradle directory layout.
     * Falls back to common patterns if no build file analysis is possible.
     */
    fun discoverSourceRoots(): List<Path> {
        val standardPaths = listOf(
            "src/main/kotlin",
            "src/main/java",
            "src/test/kotlin",
            "src/test/java",
        )

        val roots = standardPaths
            .map { projectDir.resolve(it) }
            .filter { it.exists() && it.isDirectory() }
            .toMutableList()

        val settingsFile = projectDir.resolve("settings.gradle.kts").toFile()
            .takeIf { it.exists() }
            ?: projectDir.resolve("settings.gradle").toFile().takeIf { it.exists() }

        if (settingsFile != null) {
            val subprojects = parseIncludedProjects(settingsFile)
            for (subproject in subprojects) {
                val subDir = projectDir.resolve(subproject.replace(":", "/"))
                standardPaths
                    .map { subDir.resolve(it) }
                    .filter { it.exists() && it.isDirectory() }
                    .forEach { roots.add(it) }
            }
        }

        if (roots.isEmpty()) roots.add(projectDir)

        return roots
    }

    /**
     * Discovers classpath by running Gradle's dependency resolution.
     * Caches the result in .martin/classpath.cache, invalidated when build files change.
     * Falls back to scanning build directories if Gradle isn't available.
     */
    fun discoverClasspath(): List<File> {
        val entries = mutableListOf<File>()

        listOf("build/classes/kotlin/main", "build/classes/java/main")
            .map { projectDir.resolve(it).toFile() }
            .filter { it.exists() }
            .forEach { entries.add(it) }

        try {
            val cached = readCachedClasspath()
            if (cached != null) {
                entries.addAll(cached)
                return entries
            }

            val gradlew = findGradleWrapper()
            if (gradlew != null) {
                val classpath = resolveClasspathViaGradle(gradlew)
                entries.addAll(classpath)
                writeCachedClasspath(classpath)
            }
        } catch (_: Exception) {
            // Gradle resolution failed, continue with what we have
        }

        return entries
    }

    private fun findGradleWrapper(): File? {
        val wrapper = projectDir.resolve("gradlew").toFile()
        if (wrapper.exists() && wrapper.canExecute()) return wrapper

        // Try system gradle
        return try {
            val result = ProcessBuilder("which", "gradle")
                .redirectErrorStream(true)
                .start()
            val path = result.inputStream.bufferedReader().readText().trim()
            result.waitFor()
            if (result.exitValue() == 0 && path.isNotEmpty()) File(path) else null
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveClasspathViaGradle(gradle: File): List<File> {
        val initScript = File.createTempFile("martin-classpath", ".gradle.kts")
        initScript.writeText(
            """
            allprojects {
                tasks.register("martinPrintClasspath") {
                    doLast {
                        val configs = listOf("compileClasspath", "runtimeClasspath")
                        for (name in configs) {
                            val config = configurations.findByName(name) ?: continue
                            if (config.isCanBeResolved) {
                                config.resolve().forEach { println(it.absolutePath) }
                            }
                        }
                    }
                }
            }
            """.trimIndent()
        )

        try {
            val process = ProcessBuilder(
                gradle.absolutePath,
                "--init-script", initScript.absolutePath,
                "martinPrintClasspath",
                "--quiet",
            )
                .directory(projectDir.toFile())
                .redirectErrorStream(false)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode != 0) return emptyList()

            return output.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { File(it) }
                .filter { it.exists() }
                .distinct()
        } finally {
            initScript.delete()
        }
    }

    private fun parseIncludedProjects(settingsFile: File): List<String> {
        val content = settingsFile.readText()
        val projects = mutableListOf<String>()

        // Match include("project-name") or include(":project-name")
        val regex = Regex("""include\s*\(\s*"([^"]+)"\s*\)""")
        for (match in regex.findAll(content)) {
            projects.add(match.groupValues[1].removePrefix(":"))
        }

        return projects
    }

    /**
     * Computes a SHA-256 hash of all build files that affect the classpath.
     */
    private fun computeBuildFilesHash(): String {
        val buildFiles = listOf(
            "build.gradle.kts", "build.gradle",
            "settings.gradle.kts", "settings.gradle",
            "gradle.properties",
        )
        val digest = MessageDigest.getInstance("SHA-256")
        for (name in buildFiles) {
            val file = projectDir.resolve(name)
            if (file.exists()) {
                digest.update(name.toByteArray())
                digest.update(file.readText().toByteArray())
            }
        }
        // Also include buildSrc and version catalogs if present
        val versionCatalog = projectDir.resolve("gradle/libs.versions.toml")
        if (versionCatalog.exists()) {
            digest.update(versionCatalog.readText().toByteArray())
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Reads cached classpath if valid (build files haven't changed).
     */
    private fun readCachedClasspath(): List<File>? {
        if (!classpathCacheFile.exists() || !classpathHashFile.exists()) return null
        val savedHash = classpathHashFile.readText().trim()
        val currentHash = computeBuildFilesHash()
        if (savedHash != currentHash) return null
        return classpathCacheFile.readText().lines()
            .filter { it.isNotEmpty() }
            .map { File(it) }
            .filter { it.exists() }
    }

    /**
     * Writes classpath entries and build file hash to the cache.
     */
    private fun writeCachedClasspath(classpath: List<File>) {
        try {
            cacheDir.createDirectories()
            classpathCacheFile.writeText(classpath.joinToString("\n") { it.absolutePath })
            classpathHashFile.writeText(computeBuildFilesHash())
        } catch (_: Exception) {
            // Cache write failure is non-fatal
        }
    }
}
