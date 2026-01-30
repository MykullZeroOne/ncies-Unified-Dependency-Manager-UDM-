package star.intellijplugin.pkgfinder.gradle.manager.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.plugins.groovy.lang.psi.GroovyFile
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrMethodCall
import org.w3c.dom.Element
import star.intellijplugin.pkgfinder.setting.PackageFinderSetting
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Service for discovering repository configurations from various sources:
 * - Maven settings.xml
 * - Gradle build files (repositories block)
 * - Plugin custom settings
 */
@Service(Service.Level.PROJECT)
class RepositoryDiscoveryService(private val project: Project) {
    private val log = Logger.getInstance(javaClass)

    companion object {
        fun getInstance(project: Project): RepositoryDiscoveryService =
            project.getService(RepositoryDiscoveryService::class.java)

        // Well-known repositories
        val MAVEN_CENTRAL = RepositoryConfig(
            id = "mavenCentral",
            name = "Maven Central",
            url = "https://repo.maven.org/maven2",
            type = RepositoryType.MAVEN_CENTRAL
        )

        val GOOGLE_MAVEN = RepositoryConfig(
            id = "google",
            name = "Google Maven",
            url = "https://maven.google.com",
            type = RepositoryType.MAVEN
        )

        val GRADLE_PLUGIN_PORTAL = RepositoryConfig(
            id = "gradlePluginPortal",
            name = "Gradle Plugin Portal",
            url = "https://plugins.gradle.org/m2",
            type = RepositoryType.GRADLE_PLUGIN_PORTAL
        )

        val JITPACK = RepositoryConfig(
            id = "jitpack",
            name = "JitPack",
            url = "https://jitpack.io",
            type = RepositoryType.JITPACK
        )

        val NPM_REGISTRY = RepositoryConfig(
            id = "npmjs",
            name = "NPM Registry",
            url = "https://registry.npmjs.com",
            type = RepositoryType.NPM
        )
    }

    /**
     * Get all configured repositories from all sources.
     */
    fun getConfiguredRepositories(): List<RepositoryConfig> {
        return buildList {
            // Always include well-known repos
            add(MAVEN_CENTRAL)
            add(GOOGLE_MAVEN)
            add(GRADLE_PLUGIN_PORTAL)
            add(NPM_REGISTRY)

            // Add from Maven settings
            addAll(getMavenSettingsRepositories())

            // Add from Gradle files
            addAll(getGradleRepositories())

            // Add custom repositories from plugin settings
            addAll(getCustomRepositories())
        }.distinctBy { it.url.lowercase().trimEnd('/') }
    }

    /**
     * Get repositories from Maven settings.xml (~/.m2/settings.xml)
     */
    fun getMavenSettingsRepositories(): List<RepositoryConfig> {
        val settingsFile = getMavenSettingsFile() ?: return emptyList()

        return try {
            val repos = mutableListOf<RepositoryConfig>()
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(settingsFile)

            // First, parse the <servers> section to get credentials
            val credentials = parseMavenServers(doc)

            // Parse <profiles>/<profile>/<repositories>/<repository>
            val profiles = doc.getElementsByTagName("profile")
            for (i in 0 until profiles.length) {
                val profile = profiles.item(i) as? Element ?: continue
                val repositories = profile.getElementsByTagName("repository")

                for (j in 0 until repositories.length) {
                    val repo = repositories.item(j) as? Element ?: continue
                    val id = repo.getElementsByTagName("id").item(0)?.textContent ?: "repo-$j"
                    val url = repo.getElementsByTagName("url").item(0)?.textContent ?: continue
                    val creds = credentials[id]

                    repos.add(
                        RepositoryConfig(
                            id = id,
                            name = repo.getElementsByTagName("name").item(0)?.textContent ?: id,
                            url = url,
                            type = guessRepositoryType(url),
                            source = RepositorySource.MAVEN_SETTINGS,
                            username = creds?.first,
                            password = creds?.second
                        )
                    )
                }
            }

            // Parse <mirrors>/<mirror>
            val mirrors = doc.getElementsByTagName("mirror")
            for (i in 0 until mirrors.length) {
                val mirror = mirrors.item(i) as? Element ?: continue
                val id = mirror.getElementsByTagName("id").item(0)?.textContent ?: "mirror-$i"
                val url = mirror.getElementsByTagName("url").item(0)?.textContent ?: continue
                val mirrorOf = mirror.getElementsByTagName("mirrorOf").item(0)?.textContent
                val creds = credentials[id]

                repos.add(
                    RepositoryConfig(
                        id = id,
                        name = mirror.getElementsByTagName("name").item(0)?.textContent ?: id,
                        url = url,
                        type = guessRepositoryType(url),
                        source = RepositorySource.MAVEN_SETTINGS,
                        mirrorOf = mirrorOf,
                        username = creds?.first,
                        password = creds?.second
                    )
                )
            }

            repos
        } catch (e: Exception) {
            log.warn("Failed to parse Maven settings.xml", e)
            emptyList()
        }
    }

    /**
     * Parse the <servers> section from Maven settings.xml to get credentials.
     * Returns a map of server ID to (username, password) pairs.
     */
    private fun parseMavenServers(doc: org.w3c.dom.Document): Map<String, Pair<String, String>> {
        val credentials = mutableMapOf<String, Pair<String, String>>()

        try {
            val servers = doc.getElementsByTagName("server")
            for (i in 0 until servers.length) {
                val server = servers.item(i) as? Element ?: continue
                val id = server.getElementsByTagName("id").item(0)?.textContent ?: continue
                val username = server.getElementsByTagName("username").item(0)?.textContent ?: ""
                val password = server.getElementsByTagName("password").item(0)?.textContent ?: ""

                if (username.isNotBlank() || password.isNotBlank()) {
                    credentials[id] = Pair(username, password)
                    log.info("Found Maven server credentials for: $id")
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to parse Maven servers section", e)
        }

        return credentials
    }

    /**
     * Get repositories from Gradle build files.
     */
    fun getGradleRepositories(): List<RepositoryConfig> {
        val repos = mutableListOf<RepositoryConfig>()
        val basePath = project.basePath ?: return emptyList()

        // Check settings.gradle(.kts) first
        listOf("settings.gradle", "settings.gradle.kts").forEach { fileName ->
            val file = LocalFileSystem.getInstance().findFileByPath("$basePath/$fileName")
            if (file != null) {
                repos.addAll(parseGradleRepositories(file.path, RepositorySource.GRADLE_SETTINGS))
            }
        }

        // Check root build.gradle(.kts)
        listOf("build.gradle", "build.gradle.kts").forEach { fileName ->
            val file = LocalFileSystem.getInstance().findFileByPath("$basePath/$fileName")
            if (file != null) {
                repos.addAll(parseGradleRepositories(file.path, RepositorySource.GRADLE_BUILD))
            }
        }

        return repos.distinctBy { it.url.lowercase().trimEnd('/') }
    }

    /**
     * Get custom repositories from plugin settings.
     */
    fun getCustomRepositories(): List<RepositoryConfig> {
        val repos = mutableListOf<RepositoryConfig>()
        val settings = PackageFinderSetting.instance

        // Add Nexus server if configured
        if (settings.nexusServerUrl.isNotBlank()) {
            repos.add(
                RepositoryConfig(
                    id = "nexus-custom",
                    name = "Nexus (Custom)",
                    url = settings.nexusServerUrl,
                    type = RepositoryType.NEXUS,
                    source = RepositorySource.PLUGIN_SETTINGS
                )
            )
        }

        // Future: Add support for custom repos list in settings
        return repos
    }

    private fun getMavenSettingsFile(): File? {
        // Try user settings first
        val userHome = System.getProperty("user.home")
        val userSettings = File("$userHome/.m2/settings.xml")
        if (userSettings.exists()) return userSettings

        // Try M2_HOME
        val m2Home = System.getenv("M2_HOME")
        if (m2Home != null) {
            val globalSettings = File("$m2Home/conf/settings.xml")
            if (globalSettings.exists()) return globalSettings
        }

        return null
    }

    private fun parseGradleRepositories(filePath: String, source: RepositorySource): List<RepositoryConfig> {
        val repos = mutableListOf<RepositoryConfig>()
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return emptyList()
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return emptyList()

        when (psiFile) {
            is GroovyFile -> repos.addAll(parseGroovyRepositories(psiFile, source))
            is KtFile -> repos.addAll(parseKotlinRepositories(psiFile, source))
        }

        return repos
    }

    private fun parseGroovyRepositories(file: GroovyFile, source: RepositorySource): List<RepositoryConfig> {
        val repos = mutableListOf<RepositoryConfig>()

        PsiTreeUtil.findChildrenOfType(file, GrMethodCall::class.java).forEach { call ->
            val methodName = call.invokedExpression.text
            when (methodName) {
                "mavenCentral" -> {
                    // Standard Maven Central
                }
                "google" -> {
                    repos.add(GOOGLE_MAVEN.copy(source = source))
                }
                "jcenter" -> {
                    // JCenter is deprecated, but still found in older projects
                    repos.add(
                        RepositoryConfig(
                            id = "jcenter",
                            name = "JCenter (Deprecated)",
                            url = "https://jcenter.bintray.com",
                            type = RepositoryType.MAVEN,
                            source = source
                        )
                    )
                }
                "gradlePluginPortal" -> {
                    repos.add(GRADLE_PLUGIN_PORTAL.copy(source = source))
                }
                "maven" -> {
                    // Parse maven { url "..." }
                    val args = call.closureArguments.firstOrNull()
                    if (args != null) {
                        val urlCall = PsiTreeUtil.findChildrenOfType(args, GrMethodCall::class.java)
                            .find { it.invokedExpression.text == "url" || it.invokedExpression.text == "setUrl" }

                        if (urlCall != null) {
                            val urlArg = urlCall.argumentList?.expressionArguments?.firstOrNull()
                            val url = urlArg?.text?.removeSurrounding("'")?.removeSurrounding("\"")
                            if (url != null && url.startsWith("http")) {
                                repos.add(
                                    RepositoryConfig(
                                        id = generateRepoId(url),
                                        name = extractRepoName(url),
                                        url = url,
                                        type = guessRepositoryType(url),
                                        source = source
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        return repos
    }

    private fun parseKotlinRepositories(file: KtFile, source: RepositorySource): List<RepositoryConfig> {
        val repos = mutableListOf<RepositoryConfig>()

        PsiTreeUtil.findChildrenOfType(file, KtCallExpression::class.java).forEach { call ->
            val methodName = call.calleeExpression?.text ?: ""
            when (methodName) {
                "mavenCentral" -> {
                    // Standard Maven Central
                }
                "google" -> {
                    repos.add(GOOGLE_MAVEN.copy(source = source))
                }
                "gradlePluginPortal" -> {
                    repos.add(GRADLE_PLUGIN_PORTAL.copy(source = source))
                }
                "maven" -> {
                    // Parse maven { url = uri("...") } or maven("...")
                    val lambdaArg = call.lambdaArguments.firstOrNull()?.getLambdaExpression()
                    if (lambdaArg != null) {
                        PsiTreeUtil.findChildrenOfType(lambdaArg, KtCallExpression::class.java).forEach { innerCall ->
                            val innerName = innerCall.calleeExpression?.text
                            if (innerName == "uri" || innerName == "setUrl") {
                                val urlArg = innerCall.valueArguments.firstOrNull()?.getArgumentExpression()
                                if (urlArg is KtStringTemplateExpression) {
                                    val url = urlArg.entries.joinToString("") { it.text }
                                    if (url.startsWith("http")) {
                                        repos.add(
                                            RepositoryConfig(
                                                id = generateRepoId(url),
                                                name = extractRepoName(url),
                                                url = url,
                                                type = guessRepositoryType(url),
                                                source = source
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Simple maven("url") syntax
                    val simpleArg = call.valueArguments.firstOrNull()?.getArgumentExpression()
                    if (simpleArg is KtStringTemplateExpression) {
                        val url = simpleArg.entries.joinToString("") { it.text }
                        if (url.startsWith("http")) {
                            repos.add(
                                RepositoryConfig(
                                    id = generateRepoId(url),
                                    name = extractRepoName(url),
                                    url = url,
                                    type = guessRepositoryType(url),
                                    source = source
                                )
                            )
                        }
                    }
                }
            }
        }

        return repos
    }

    private fun guessRepositoryType(url: String): RepositoryType {
        val lowerUrl = url.lowercase()
        return when {
            lowerUrl.contains("repo.maven.org") || lowerUrl.contains("central") -> RepositoryType.MAVEN_CENTRAL
            lowerUrl.contains("maven.google.com") -> RepositoryType.MAVEN
            lowerUrl.contains("plugins.gradle.org") -> RepositoryType.GRADLE_PLUGIN_PORTAL
            lowerUrl.contains("jitpack.io") -> RepositoryType.JITPACK
            lowerUrl.contains("nexus") || lowerUrl.contains("sonatype") -> RepositoryType.NEXUS
            lowerUrl.contains("artifactory") || lowerUrl.contains("jfrog") -> RepositoryType.ARTIFACTORY
            lowerUrl.contains("pkgs.dev.azure.com") || lowerUrl.contains("visualstudio.com/_packaging") -> RepositoryType.AZURE_ARTIFACTS
            lowerUrl.contains("npmjs") || lowerUrl.contains("npm") -> RepositoryType.NPM
            else -> RepositoryType.CUSTOM
        }
    }

    private fun generateRepoId(url: String): String {
        return url.removePrefix("https://")
            .removePrefix("http://")
            .replace("/", "-")
            .replace(".", "-")
            .take(30)
    }

    private fun extractRepoName(url: String): String {
        return try {
            java.net.URL(url).host
                .removePrefix("www.")
                .split(".")
                .firstOrNull()
                ?.replaceFirstChar { it.uppercase() }
                ?: url
        } catch (e: Exception) {
            url
        }
    }
}

/**
 * Repository configuration data class.
 */
data class RepositoryConfig(
    val id: String,
    val name: String,
    val url: String,
    val type: RepositoryType,
    val source: RepositorySource = RepositorySource.BUILTIN,
    val username: String? = null,
    val password: String? = null,
    val mirrorOf: String? = null,
    val enabled: Boolean = true
)

/**
 * Types of supported repositories.
 */
enum class RepositoryType {
    MAVEN_CENTRAL,
    MAVEN,
    NEXUS,
    ARTIFACTORY,
    AZURE_ARTIFACTS,
    JITPACK,
    GRADLE_PLUGIN_PORTAL,
    NPM,
    CUSTOM
}

/**
 * Source of the repository configuration.
 */
enum class RepositorySource {
    BUILTIN,            // Well-known default repos
    MAVEN_SETTINGS,     // From ~/.m2/settings.xml
    GRADLE_SETTINGS,    // From settings.gradle(.kts)
    GRADLE_BUILD,       // From build.gradle(.kts)
    PLUGIN_SETTINGS     // From plugin configuration
}
