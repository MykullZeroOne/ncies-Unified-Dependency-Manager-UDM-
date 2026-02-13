package com.maddrobot.plugins.udm.maven

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.io.toCanonicalPath
import org.apache.maven.model.Model
import org.apache.maven.model.Parent
import org.apache.maven.model.io.xpp3.MavenXpp3Reader
import java.io.FileReader
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Local Maven dependency model
 *
 * madd robot tech
 * @LastModified: 2025-01-20
 * @since 2025-01-20
 */
data class LocalDependency(
    override val groupId: String,
    override val artifactId: String,
    override val version: String,
    val name: String,
    val description: String,
    val pomFilePath: String,
) : Dependency(groupId, artifactId, version) {
    companion object {
        private const val N_A = "N/A"
        private val MAVEN_LOCAL_REPOSITORY: Path = Path.of(System.getProperty("user.home"), ".m2/repository")
        private val log = Logger.getInstance(LocalDependency::class.java)

        // Factory method to simplify LocalDependency construction
        fun from(model: Model, pomFilePath: String): LocalDependency {
            val modelParent: Parent? = model.parent
            val artifactId: String = model.artifactId ?: N_A
            val groupId: String = model.groupId ?: modelParent?.groupId ?: N_A
            val version: String = model.version ?: modelParent?.version ?: N_A
            val name: String = model.name ?: N_A
            val description: String = model.description ?: N_A
            return LocalDependency(groupId, artifactId, version, name, description, pomFilePath)
        }

        fun loadData(repoPath: Path = MAVEN_LOCAL_REPOSITORY): List<Dependency> {
            if (!repoPath.toFile().isDirectory) {
                return emptyList()
            }

            return try {
                Files.walk(repoPath)
                    .parallel()
                    .filter { it.toString().endsWith(".pom") }
                    .toList()
                    .mapNotNull { parsePom(it) }
                    // order by artifactId ASC, version DESC
                    .sortedWith(
                        compareBy<Dependency> {
                            it.artifactId.compareTo(it.artifactId, ignoreCase = true)
                        }.thenByDescending { it.version }
                    )
            } catch (e: IOException) {
                log.error("Failed to load data from local maven repository: ${e.localizedMessage}", e)
                emptyList()
            }
        }

        /**
         * Extract Maven dependency metadata from the [Model]
         */
        private fun parsePom(pomPath: Path): Dependency? {
            return try {
                val pomFile = pomPath.toFile()
                FileReader(pomFile).use { fileReader ->
                    val mavenXpp3Reader = MavenXpp3Reader()
                    val model: Model = mavenXpp3Reader.read(fileReader)
                    from(model, pomPath.toCanonicalPath())
                }
            } catch (e: Exception) {
                log.warn("Failed to parse POM: ${e.localizedMessage}", e)
                null
            }
        }
    }
}
