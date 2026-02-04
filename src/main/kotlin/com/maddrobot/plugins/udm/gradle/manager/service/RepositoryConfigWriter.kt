package com.maddrobot.plugins.udm.gradle.manager.service

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Service for writing repository configurations to build files.
 * Supports writing to:
 * - Gradle settings.gradle(.kts) or build.gradle(.kts)
 * - Maven ~/.m2/settings.xml
 */
@Service(Service.Level.PROJECT)
class RepositoryConfigWriter(private val project: Project) {
    private val log = Logger.getInstance(javaClass)

    companion object {
        fun getInstance(project: Project): RepositoryConfigWriter =
            project.getService(RepositoryConfigWriter::class.java)
    }

    /**
     * Add a repository to Gradle configuration.
     * Returns the modified content for preview, or null if failed.
     *
     * @param repo The repository to add
     * @param targetFile The target file (settings.gradle, build.gradle, etc.)
     * @return Pair of (original content, new content) for preview, or null if failed
     */
    fun getGradleRepositoryAddition(repo: RepositoryConfig, targetFile: VirtualFile): Pair<String, String>? {
        val document = FileDocumentManager.getInstance().getDocument(targetFile) ?: return null
        val text = document.text
        val isKotlin = targetFile.name.endsWith(".kts")

        // Generate repository declaration
        val repoDeclaration = generateGradleRepoDeclaration(repo, isKotlin)

        // Find the repositories block
        val newContent = addToRepositoriesBlock(text, repoDeclaration, isKotlin)
            ?: createRepositoriesBlock(text, repoDeclaration, isKotlin)

        // If credentials are provided, also save to gradle.properties
        if (repo.username != null || repo.password != null) {
            saveGradleCredentials(repo)
        }

        return Pair(text, newContent)
    }

    /**
     * Save credentials to gradle.properties (user-level ~/.gradle/gradle.properties)
     */
    private fun saveGradleCredentials(repo: RepositoryConfig) {
        val gradleDir = File(System.getProperty("user.home"), ".gradle")
        if (!gradleDir.exists()) {
            gradleDir.mkdirs()
        }

        val propsFile = File(gradleDir, "gradle.properties")
        val props = java.util.Properties()

        // Load existing properties
        if (propsFile.exists()) {
            propsFile.inputStream().use { props.load(it) }
        }

        // Add credentials
        if (repo.username != null) {
            props.setProperty("${repo.id}User", repo.username)
        }
        if (repo.password != null) {
            props.setProperty("${repo.id}Password", repo.password)
        }

        // Save
        propsFile.outputStream().use {
            props.store(it, "Gradle properties - credentials managed by UDM plugin")
        }

        log.info("Saved credentials for ${repo.id} to ~/.gradle/gradle.properties")
    }

    /**
     * Apply Gradle repository changes.
     */
    fun applyGradleChanges(targetFile: VirtualFile, newContent: String, commandName: String) {
        val document = FileDocumentManager.getInstance().getDocument(targetFile) ?: return

        WriteCommandAction.runWriteCommandAction(project, commandName, null, {
            document.setText(newContent)
        })
    }

    /**
     * Add a repository to Maven settings.xml.
     * Returns the modified content for preview, or null if failed.
     *
     * @param repo The repository to add
     * @return Pair of (original content, new content) for preview, or null if failed
     */
    fun getMavenRepositoryAddition(repo: RepositoryConfig): Pair<String, String>? {
        val settingsFile = getMavenSettingsFile()

        return if (settingsFile != null && settingsFile.exists()) {
            val originalContent = settingsFile.readText()
            val newContent = addToMavenSettings(originalContent, repo)
            if (newContent != null) Pair(originalContent, newContent) else null
        } else {
            // Create new settings.xml
            val newContent = createMavenSettings(repo)
            Pair("", newContent)
        }
    }

    /**
     * Apply Maven settings.xml changes.
     */
    fun applyMavenChanges(newContent: String) {
        val settingsDir = File(System.getProperty("user.home"), ".m2")
        if (!settingsDir.exists()) {
            settingsDir.mkdirs()
        }

        val settingsFile = File(settingsDir, "settings.xml")
        settingsFile.writeText(newContent)
    }

    /**
     * Remove a repository from Gradle configuration.
     * Returns the modified content for preview, or null if failed.
     */
    fun getGradleRepositoryRemoval(repo: RepositoryConfig, targetFile: VirtualFile): Pair<String, String>? {
        val document = FileDocumentManager.getInstance().getDocument(targetFile) ?: return null
        val text = document.text

        // Find and remove the repository declaration
        val newContent = removeGradleRepository(text, repo)

        return if (newContent != text) Pair(text, newContent) else null
    }

    private fun generateGradleRepoDeclaration(repo: RepositoryConfig, isKotlin: Boolean): String {
        val indent = "        "
        return when {
            repo.url.contains("repo.maven.org/maven2") -> {
                if (isKotlin) "${indent}mavenCentral()" else "${indent}mavenCentral()"
            }
            repo.url.contains("maven.google.com") -> {
                if (isKotlin) "${indent}google()" else "${indent}google()"
            }
            repo.url.contains("plugins.gradle.org/m2") -> {
                if (isKotlin) "${indent}gradlePluginPortal()" else "${indent}gradlePluginPortal()"
            }
            else -> {
                if (repo.username != null && repo.password != null) {
                    if (isKotlin) {
                        """
                        |${indent}maven {
                        |${indent}    url = uri("${repo.url}")
                        |${indent}    credentials {
                        |${indent}        username = findProperty("${repo.id}User")?.toString() ?: ""
                        |${indent}        password = findProperty("${repo.id}Password")?.toString() ?: ""
                        |${indent}    }
                        |${indent}}
                        """.trimMargin()
                    } else {
                        """
                        |${indent}maven {
                        |${indent}    url '${repo.url}'
                        |${indent}    credentials {
                        |${indent}        username = findProperty('${repo.id}User') ?: ''
                        |${indent}        password = findProperty('${repo.id}Password') ?: ''
                        |${indent}    }
                        |${indent}}
                        """.trimMargin()
                    }
                } else {
                    if (isKotlin) {
                        """${indent}maven { url = uri("${repo.url}") }"""
                    } else {
                        """${indent}maven { url '${repo.url}' }"""
                    }
                }
            }
        }
    }

    private fun addToRepositoriesBlock(text: String, repoDeclaration: String, isKotlin: Boolean): String? {
        // Try to find repositories block in dependencyResolutionManagement (for settings.gradle)
        val dependencyResolutionMatch = Regex(
            """(dependencyResolutionManagement\s*\{[^}]*repositories\s*\{)([^}]*)(\})"""
        ).find(text)

        if (dependencyResolutionMatch != null) {
            val existingRepos = dependencyResolutionMatch.groupValues[2]
            // Check if repo already exists
            if (existingRepos.contains(repoDeclaration.trim()) ||
                (repoDeclaration.contains("mavenCentral") && existingRepos.contains("mavenCentral"))) {
                return null // Already exists
            }
            val newRepos = existingRepos.trimEnd() + "\n$repoDeclaration\n    "
            return text.replaceRange(
                dependencyResolutionMatch.groups[2]!!.range,
                newRepos
            )
        }

        // Try to find standalone repositories block
        val reposMatch = Regex(
            """(repositories\s*\{)([^}]*)(\})"""
        ).find(text)

        if (reposMatch != null) {
            val existingRepos = reposMatch.groupValues[2]
            if (existingRepos.contains(repoDeclaration.trim())) {
                return null // Already exists
            }
            val newRepos = existingRepos.trimEnd() + "\n$repoDeclaration\n"
            return text.replaceRange(
                reposMatch.groups[2]!!.range,
                newRepos
            )
        }

        return null // No repositories block found
    }

    private fun createRepositoriesBlock(text: String, repoDeclaration: String, isKotlin: Boolean): String {
        val newBlock = if (isKotlin) {
            """
            |
            |repositories {
            |$repoDeclaration
            |}
            """.trimMargin()
        } else {
            """
            |
            |repositories {
            |$repoDeclaration
            |}
            """.trimMargin()
        }

        // Append to end of file
        return text.trimEnd() + "\n" + newBlock + "\n"
    }

    private fun removeGradleRepository(text: String, repo: RepositoryConfig): String {
        var result = text

        // Remove URL-based maven declarations
        val urlPatterns = listOf(
            Regex("""maven\s*\{\s*url\s*=?\s*['"]?${Regex.escape(repo.url)}['"]?\s*\}"""),
            Regex("""maven\s*\{\s*url\s*=?\s*uri\s*\(\s*['"]${Regex.escape(repo.url)}['"]\s*\)\s*\}"""),
            Regex("""maven\s*\(\s*['"]${Regex.escape(repo.url)}['"]\s*\)""")
        )

        for (pattern in urlPatterns) {
            result = pattern.replace(result) { "" }
        }

        // Remove well-known repository functions
        when {
            repo.url.contains("repo.maven.org/maven2") -> {
                result = Regex("""mavenCentral\s*\(\s*\)""").replace(result) { "" }
            }
            repo.url.contains("maven.google.com") -> {
                result = Regex("""google\s*\(\s*\)""").replace(result) { "" }
            }
            repo.url.contains("plugins.gradle.org/m2") -> {
                result = Regex("""gradlePluginPortal\s*\(\s*\)""").replace(result) { "" }
            }
        }

        // Clean up empty lines
        result = result.replace(Regex("\n\\s*\n\\s*\n"), "\n\n")

        return result
    }

    private fun addToMavenSettings(originalContent: String, repo: RepositoryConfig): String? {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(originalContent.byteInputStream())

            val settings = doc.documentElement

            // Add server credentials if provided
            if (repo.username != null || repo.password != null) {
                addMavenServerCredentials(doc, settings, repo)
            }

            // Find or create profiles element
            var profiles = settings.getElementsByTagName("profiles").item(0) as? Element
            if (profiles == null) {
                profiles = doc.createElement("profiles")
                settings.appendChild(profiles)
            }

            // Find or create a profile for custom repos
            var profile = findCustomReposProfile(profiles)
            if (profile == null) {
                profile = doc.createElement("profile")
                val profileId = doc.createElement("id")
                profileId.textContent = "custom-repos"
                profile.appendChild(profileId)
                profiles.appendChild(profile)

                // Also add to activeProfiles
                var activeProfiles = settings.getElementsByTagName("activeProfiles").item(0) as? Element
                if (activeProfiles == null) {
                    activeProfiles = doc.createElement("activeProfiles")
                    settings.appendChild(activeProfiles)
                }
                val activeProfile = doc.createElement("activeProfile")
                activeProfile.textContent = "custom-repos"
                activeProfiles.appendChild(activeProfile)
            }

            // Find or create repositories element within profile
            var repositories = profile.getElementsByTagName("repositories").item(0) as? Element
            if (repositories == null) {
                repositories = doc.createElement("repositories")
                profile.appendChild(repositories)
            }

            // Add the repository
            val repository = doc.createElement("repository")
            val id = doc.createElement("id")
            id.textContent = repo.id
            repository.appendChild(id)

            val url = doc.createElement("url")
            url.textContent = repo.url
            repository.appendChild(url)

            val releases = doc.createElement("releases")
            val releasesEnabled = doc.createElement("enabled")
            releasesEnabled.textContent = "true"
            releases.appendChild(releasesEnabled)
            repository.appendChild(releases)

            val snapshots = doc.createElement("snapshots")
            val snapshotsEnabled = doc.createElement("enabled")
            snapshotsEnabled.textContent = "true"
            snapshots.appendChild(snapshotsEnabled)
            repository.appendChild(snapshots)

            repositories.appendChild(repository)

            documentToString(doc)
        } catch (e: Exception) {
            log.error("Failed to modify Maven settings.xml", e)
            null
        }
    }

    /**
     * Add server credentials to the <servers> section of settings.xml
     */
    private fun addMavenServerCredentials(doc: Document, settings: Element, repo: RepositoryConfig) {
        // Find or create servers element
        var servers = settings.getElementsByTagName("servers").item(0) as? Element
        if (servers == null) {
            servers = doc.createElement("servers")
            // Insert servers before profiles if possible
            val profiles = settings.getElementsByTagName("profiles").item(0)
            if (profiles != null) {
                settings.insertBefore(servers, profiles)
            } else {
                settings.appendChild(servers)
            }
        }

        // Check if server already exists
        val existingServers = servers.getElementsByTagName("server")
        for (i in 0 until existingServers.length) {
            val server = existingServers.item(i) as? Element ?: continue
            val serverId = server.getElementsByTagName("id").item(0)?.textContent
            if (serverId == repo.id) {
                // Update existing server
                server.getElementsByTagName("username").item(0)?.textContent = repo.username ?: ""
                server.getElementsByTagName("password").item(0)?.textContent = repo.password ?: ""
                return
            }
        }

        // Create new server entry
        val server = doc.createElement("server")

        val id = doc.createElement("id")
        id.textContent = repo.id
        server.appendChild(id)

        if (repo.username != null) {
            val username = doc.createElement("username")
            username.textContent = repo.username
            server.appendChild(username)
        }

        if (repo.password != null) {
            val password = doc.createElement("password")
            password.textContent = repo.password
            server.appendChild(password)
        }

        servers.appendChild(server)
    }

    private fun createMavenSettings(repo: RepositoryConfig): String {
        val serversSection = if (repo.username != null || repo.password != null) {
            """
  <servers>
    <server>
      <id>${repo.id}</id>
      ${if (repo.username != null) "<username>${repo.username}</username>" else ""}
      ${if (repo.password != null) "<password>${repo.password}</password>" else ""}
    </server>
  </servers>
"""
        } else {
            ""
        }

        return """<?xml version="1.0" encoding="UTF-8"?>
<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"
          xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
          xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 http://maven.apache.org/xsd/settings-1.0.0.xsd">
$serversSection  <profiles>
    <profile>
      <id>custom-repos</id>
      <repositories>
        <repository>
          <id>${repo.id}</id>
          <url>${repo.url}</url>
          <releases><enabled>true</enabled></releases>
          <snapshots><enabled>true</enabled></snapshots>
        </repository>
      </repositories>
    </profile>
  </profiles>
  <activeProfiles>
    <activeProfile>custom-repos</activeProfile>
  </activeProfiles>
</settings>
"""
    }

    private fun findCustomReposProfile(profiles: Element): Element? {
        val profileList = profiles.getElementsByTagName("profile")
        for (i in 0 until profileList.length) {
            val profile = profileList.item(i) as? Element ?: continue
            val idElement = profile.getElementsByTagName("id").item(0)
            if (idElement?.textContent == "custom-repos") {
                return profile
            }
        }
        return null
    }

    private fun documentToString(doc: Document): String {
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.INDENT, "yes")
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2")
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")

        val writer = StringWriter()
        transformer.transform(DOMSource(doc), StreamResult(writer))
        return writer.toString()
    }

    private fun getMavenSettingsFile(): File? {
        val userHome = System.getProperty("user.home")
        return File("$userHome/.m2/settings.xml")
    }

    /**
     * Get the best target file for adding a Gradle repository.
     * Prefers settings.gradle(.kts) for dependencyResolutionManagement,
     * falls back to root build.gradle(.kts).
     */
    fun getRecommendedGradleTarget(): VirtualFile? {
        val basePath = project.basePath ?: return null

        // Prefer settings.gradle.kts
        listOf("settings.gradle.kts", "settings.gradle").forEach { fileName ->
            val file = LocalFileSystem.getInstance().findFileByPath("$basePath/$fileName")
            if (file != null) return file
        }

        // Fall back to build.gradle
        listOf("build.gradle.kts", "build.gradle").forEach { fileName ->
            val file = LocalFileSystem.getInstance().findFileByPath("$basePath/$fileName")
            if (file != null) return file
        }

        return null
    }

    /**
     * Add a repository to project's pom.xml.
     * Returns the modified content for preview, or null if failed.
     *
     * @param repo The repository to add
     * @param pomFile The target pom.xml file
     * @return Pair of (original content, new content) for preview, or null if failed
     */
    fun getPomRepositoryAddition(repo: RepositoryConfig, pomFile: VirtualFile): Pair<String, String>? {
        val document = FileDocumentManager.getInstance().getDocument(pomFile) ?: return null
        val text = document.text

        return try {
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(text.byteInputStream())

            val projectElement = doc.documentElement

            // Find or create repositories element
            var repositories = projectElement.getElementsByTagName("repositories").item(0) as? Element
            if (repositories == null) {
                repositories = doc.createElement("repositories")
                // Insert before dependencies if possible
                val dependencies = projectElement.getElementsByTagName("dependencies").item(0)
                if (dependencies != null) {
                    projectElement.insertBefore(repositories, dependencies)
                } else {
                    projectElement.appendChild(repositories)
                }
            }

            // Check if repository already exists
            val existingRepos = repositories.getElementsByTagName("repository")
            for (i in 0 until existingRepos.length) {
                val existing = existingRepos.item(i) as? Element ?: continue
                val existingUrl = existing.getElementsByTagName("url").item(0)?.textContent
                if (existingUrl?.trimEnd('/') == repo.url.trimEnd('/')) {
                    return null // Already exists
                }
            }

            // Add the repository
            val repository = doc.createElement("repository")

            val id = doc.createElement("id")
            id.textContent = repo.id
            repository.appendChild(id)

            if (repo.name.isNotBlank() && repo.name != repo.id) {
                val name = doc.createElement("name")
                name.textContent = repo.name
                repository.appendChild(name)
            }

            val url = doc.createElement("url")
            url.textContent = repo.url
            repository.appendChild(url)

            repositories.appendChild(repository)

            Pair(text, documentToString(doc))
        } catch (e: Exception) {
            log.error("Failed to modify pom.xml", e)
            null
        }
    }

    /**
     * Apply pom.xml repository changes.
     */
    fun applyPomChanges(pomFile: VirtualFile, newContent: String, commandName: String) {
        val document = FileDocumentManager.getInstance().getDocument(pomFile) ?: return

        WriteCommandAction.runWriteCommandAction(project, commandName, null, {
            document.setText(newContent)
        })
    }

    /**
     * Get the root pom.xml file if this is a Maven project.
     */
    fun getRootPomFile(): VirtualFile? {
        val basePath = project.basePath ?: return null
        return LocalFileSystem.getInstance().findFileByPath("$basePath/pom.xml")
    }

    /**
     * Check if this is a Maven project.
     */
    fun isMavenProject(): Boolean {
        return getRootPomFile() != null
    }

    /**
     * Check if this is a Gradle project.
     */
    fun isGradleProject(): Boolean {
        return getRecommendedGradleTarget() != null
    }
}
