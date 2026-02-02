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
        // Load Gradle properties for credential resolution
        val gradleProperties = loadGradleProperties()

        return buildList {
            // Always include well-known repos
            add(MAVEN_CENTRAL)
            add(GOOGLE_MAVEN)
            add(GRADLE_PLUGIN_PORTAL)
            add(NPM_REGISTRY)

            // Add from Maven settings.xml
            addAll(getMavenSettingsRepositories())

            // Add from Maven pom.xml files
            addAll(getMavenPomRepositories())

            // Add from Gradle files (with credential resolution)
            addAll(getGradleRepositories(gradleProperties))

            // Add custom repositories from plugin settings
            addAll(getCustomRepositories())
        }.distinctBy { it.url.lowercase().trimEnd('/') }
    }

    /**
     * Load Gradle properties from both user home and project directory.
     * Properties from project-level override user-level.
     */
    private fun loadGradleProperties(): Map<String, String> {
        val properties = mutableMapOf<String, String>()

        // Load user-level gradle.properties (~/.gradle/gradle.properties)
        val userHome = System.getProperty("user.home")
        val userGradleProps = File("$userHome/.gradle/gradle.properties")
        if (userGradleProps.exists()) {
            try {
                java.util.Properties().apply {
                    userGradleProps.inputStream().use { load(it) }
                }.forEach { key, value ->
                    properties[key.toString()] = value.toString()
                }
                log.info("Loaded ${properties.size} properties from user gradle.properties")
            } catch (e: Exception) {
                log.warn("Failed to load user gradle.properties", e)
            }
        }

        // Load project-level gradle.properties (overrides user-level)
        val basePath = project.basePath
        if (basePath != null) {
            val projectGradleProps = File("$basePath/gradle.properties")
            if (projectGradleProps.exists()) {
                try {
                    java.util.Properties().apply {
                        projectGradleProps.inputStream().use { load(it) }
                    }.forEach { key, value ->
                        properties[key.toString()] = value.toString()
                    }
                    log.info("Loaded properties from project gradle.properties")
                } catch (e: Exception) {
                    log.warn("Failed to load project gradle.properties", e)
                }
            }
        }

        return properties
    }

    /**
     * Get repositories defined in Maven pom.xml files.
     */
    fun getMavenPomRepositories(): List<RepositoryConfig> {
        val basePath = project.basePath ?: return emptyList()
        val repos = mutableListOf<RepositoryConfig>()

        // Get credentials from Maven settings.xml for matching
        val settingsFile = getMavenSettingsFile()
        val credentials = if (settingsFile != null) {
            try {
                val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(settingsFile)
                parseMavenServers(doc)
            } catch (e: Exception) {
                emptyMap()
            }
        } else {
            emptyMap()
        }

        // Find all pom.xml files
        fun findPomFiles(dir: File): List<File> {
            val pomFiles = mutableListOf<File>()
            if (!dir.isDirectory) return pomFiles

            dir.listFiles()?.forEach { file ->
                when {
                    file.name == "pom.xml" -> pomFiles.add(file)
                    file.isDirectory && file.name !in listOf("target", ".idea", "node_modules", ".git", "build") -> {
                        pomFiles.addAll(findPomFiles(file))
                    }
                }
            }
            return pomFiles
        }

        val pomFiles = findPomFiles(File(basePath))

        for (pomFile in pomFiles) {
            try {
                val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pomFile)

                // Parse <repositories>/<repository> elements
                val repositories = doc.getElementsByTagName("repository")
                for (i in 0 until repositories.length) {
                    val repo = repositories.item(i) as? Element ?: continue
                    val parent = repo.parentNode

                    // Only process repositories under <repositories>, not <pluginRepositories>
                    if (parent?.nodeName != "repositories") continue

                    val id = repo.getElementsByTagName("id").item(0)?.textContent ?: "pom-repo-$i"
                    val url = repo.getElementsByTagName("url").item(0)?.textContent ?: continue
                    val creds = credentials[id]

                    repos.add(
                        RepositoryConfig(
                            id = id,
                            name = repo.getElementsByTagName("name").item(0)?.textContent ?: id,
                            url = url,
                            type = guessRepositoryType(url),
                            source = RepositorySource.MAVEN_SETTINGS, // Using MAVEN_SETTINGS as closest match
                            username = creds?.first,
                            password = creds?.second
                        )
                    )
                }

                // Also parse <distributionManagement>/<repository> for deployment repos
                val distMgmt = doc.getElementsByTagName("distributionManagement")
                if (distMgmt.length > 0) {
                    val distElement = distMgmt.item(0) as? Element
                    val distRepos = distElement?.getElementsByTagName("repository")
                    if (distRepos != null) {
                        for (i in 0 until distRepos.length) {
                            val repo = distRepos.item(i) as? Element ?: continue
                            if (repo.parentNode?.nodeName != "distributionManagement") continue

                            val id = repo.getElementsByTagName("id").item(0)?.textContent ?: "dist-repo-$i"
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
                }
            } catch (e: Exception) {
                log.warn("Failed to parse pom.xml: ${pomFile.path}", e)
            }
        }

        return repos.distinctBy { it.url.lowercase().trimEnd('/') }
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
            log.info("Found ${servers.length} server entries in Maven settings.xml")

            for (i in 0 until servers.length) {
                val server = servers.item(i) as? Element ?: continue
                val id = server.getElementsByTagName("id").item(0)?.textContent ?: continue
                val username = server.getElementsByTagName("username").item(0)?.textContent ?: ""
                val password = server.getElementsByTagName("password").item(0)?.textContent ?: ""

                // Check for encrypted password (Maven encryption format)
                val isEncrypted = password.startsWith("{") && password.endsWith("}")
                if (isEncrypted) {
                    log.warn("Server '$id' has encrypted password - plugin cannot decrypt Maven encrypted passwords. Please use plain text PAT.")
                }

                if (username.isNotBlank() || password.isNotBlank()) {
                    credentials[id] = Pair(username, password)
                    // Log masked credentials for debugging
                    val maskedPassword = if (password.length > 4) {
                        "${password.take(2)}${"*".repeat(password.length - 4)}${password.takeLast(2)}"
                    } else {
                        "****"
                    }
                    log.info("Found Maven server credentials for: '$id' (username='$username', password=$maskedPassword, length=${password.length})")
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to parse Maven servers section", e)
        }

        log.info("Total credentials found: ${credentials.size} - Server IDs: ${credentials.keys.joinToString(", ")}")
        return credentials
    }

    /**
     * Get repositories from Gradle build files.
     */
    fun getGradleRepositories(gradleProperties: Map<String, String> = emptyMap()): List<RepositoryConfig> {
        val repos = mutableListOf<RepositoryConfig>()
        val basePath = project.basePath ?: return emptyList()

        // Check settings.gradle(.kts) first
        listOf("settings.gradle", "settings.gradle.kts").forEach { fileName ->
            val file = LocalFileSystem.getInstance().findFileByPath("$basePath/$fileName")
            if (file != null) {
                repos.addAll(parseGradleRepositories(file.path, RepositorySource.GRADLE_SETTINGS, gradleProperties))
            }
        }

        // Check root build.gradle(.kts)
        listOf("build.gradle", "build.gradle.kts").forEach { fileName ->
            val file = LocalFileSystem.getInstance().findFileByPath("$basePath/$fileName")
            if (file != null) {
                repos.addAll(parseGradleRepositories(file.path, RepositorySource.GRADLE_BUILD, gradleProperties))
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

    private fun parseGradleRepositories(
        filePath: String,
        source: RepositorySource,
        gradleProperties: Map<String, String> = emptyMap()
    ): List<RepositoryConfig> {
        val repos = mutableListOf<RepositoryConfig>()
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return emptyList()
        val psiFile = PsiManager.getInstance(project).findFile(virtualFile) ?: return emptyList()

        when (psiFile) {
            is GroovyFile -> repos.addAll(parseGroovyRepositories(psiFile, source, gradleProperties))
            is KtFile -> repos.addAll(parseKotlinRepositories(psiFile, source, gradleProperties))
        }

        return repos
    }

    private fun parseGroovyRepositories(
        file: GroovyFile,
        source: RepositorySource,
        gradleProperties: Map<String, String> = emptyMap()
    ): List<RepositoryConfig> {
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
                    // Parse maven { url "..." credentials { ... } }
                    val args = call.closureArguments.firstOrNull()
                    if (args != null) {
                        val urlCall = PsiTreeUtil.findChildrenOfType(args, GrMethodCall::class.java)
                            .find { it.invokedExpression.text == "url" || it.invokedExpression.text == "setUrl" }

                        if (urlCall != null) {
                            val urlArg = urlCall.argumentList?.expressionArguments?.firstOrNull()
                            val url = urlArg?.text?.removeSurrounding("'")?.removeSurrounding("\"")
                            if (url != null && url.startsWith("http")) {
                                // Extract credentials if present
                                val (username, password) = extractGroovyCredentials(args, gradleProperties)

                                repos.add(
                                    RepositoryConfig(
                                        id = generateRepoId(url),
                                        name = extractRepoName(url),
                                        url = url,
                                        type = guessRepositoryType(url),
                                        source = source,
                                        username = username,
                                        password = password
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

    /**
     * Extract credentials from a Groovy maven { credentials { ... } } block.
     * Handles patterns like:
     * - credentials { username = "user"; password = "pass" }
     * - credentials { username = project.findProperty("repoUser") as String }
     * - credentials { username = repoUser; password = repoPassword }
     */
    private fun extractGroovyCredentials(
        closure: com.intellij.psi.PsiElement,
        gradleProperties: Map<String, String>
    ): Pair<String?, String?> {
        var username: String? = null
        var password: String? = null

        // Find credentials { ... } block
        val credentialsCall = PsiTreeUtil.findChildrenOfType(closure, GrMethodCall::class.java)
            .find { it.invokedExpression.text == "credentials" }

        if (credentialsCall != null) {
            val credsClosure = credentialsCall.closureArguments.firstOrNull()
            if (credsClosure != null) {
                val text = credsClosure.text

                // Extract username
                username = extractGroovyAssignment(text, "username", gradleProperties)

                // Extract password
                password = extractGroovyAssignment(text, "password", gradleProperties)
            }
        }

        return Pair(username, password)
    }

    /**
     * Extract a value from a Groovy assignment like: username = "value" or username = propName
     */
    private fun extractGroovyAssignment(text: String, varName: String, gradleProperties: Map<String, String>): String? {
        // Pattern 1: varName = "literal" or varName = 'literal'
        val literalPattern = """$varName\s*=\s*["']([^"']+)["']""".toRegex()
        literalPattern.find(text)?.let { return it.groupValues[1] }

        // Pattern 2: varName = project.findProperty("propName") as String ?: "default"
        val findPropertyPattern = """$varName\s*=\s*project\.findProperty\s*\(\s*["']([^"']+)["']\s*\)""".toRegex()
        findPropertyPattern.find(text)?.let { match ->
            val propName = match.groupValues[1]
            return gradleProperties[propName]
        }

        // Pattern 3: varName = propName (where propName is a variable referencing gradle.properties)
        val variablePattern = """$varName\s*=\s*(\w+)(?:\s|;|$|\})""".toRegex()
        variablePattern.find(text)?.let { match ->
            val propName = match.groupValues[1]
            // Check if this is a known gradle property
            if (propName !in listOf("null", "true", "false")) {
                return gradleProperties[propName]
            }
        }

        // Pattern 4: providers.gradleProperty("propName").get()
        val providerPattern = """$varName\s*=\s*providers\.gradleProperty\s*\(\s*["']([^"']+)["']\s*\)""".toRegex()
        providerPattern.find(text)?.let { match ->
            val propName = match.groupValues[1]
            return gradleProperties[propName]
        }

        return null
    }

    private fun parseKotlinRepositories(
        file: KtFile,
        source: RepositorySource,
        gradleProperties: Map<String, String> = emptyMap()
    ): List<RepositoryConfig> {
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
                    // Parse maven { url = uri("...") credentials { ... } } or maven("...")
                    val lambdaArg = call.lambdaArguments.firstOrNull()?.getLambdaExpression()
                    if (lambdaArg != null) {
                        var foundUrl: String? = null

                        PsiTreeUtil.findChildrenOfType(lambdaArg, KtCallExpression::class.java).forEach { innerCall ->
                            val innerName = innerCall.calleeExpression?.text
                            if (innerName == "uri" || innerName == "setUrl") {
                                val urlArg = innerCall.valueArguments.firstOrNull()?.getArgumentExpression()
                                if (urlArg is KtStringTemplateExpression) {
                                    foundUrl = urlArg.entries.joinToString("") { it.text }
                                }
                            }
                        }

                        if (foundUrl != null && foundUrl!!.startsWith("http")) {
                            // Extract credentials if present
                            val (username, password) = extractKotlinCredentials(lambdaArg, gradleProperties)

                            repos.add(
                                RepositoryConfig(
                                    id = generateRepoId(foundUrl!!),
                                    name = extractRepoName(foundUrl!!),
                                    url = foundUrl!!,
                                    type = guessRepositoryType(foundUrl!!),
                                    source = source,
                                    username = username,
                                    password = password
                                )
                            )
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

    /**
     * Extract credentials from a Kotlin DSL maven { credentials { ... } } block.
     * Handles patterns like:
     * - credentials { username = "user"; password = "pass" }
     * - credentials { username = property("repoUser").toString() }
     * - credentials { username = extra["repoUser"] as String }
     */
    private fun extractKotlinCredentials(
        lambdaExpr: com.intellij.psi.PsiElement,
        gradleProperties: Map<String, String>
    ): Pair<String?, String?> {
        var username: String? = null
        var password: String? = null

        // Find credentials { ... } block
        val credentialsCall = PsiTreeUtil.findChildrenOfType(lambdaExpr, KtCallExpression::class.java)
            .find { it.calleeExpression?.text == "credentials" }

        if (credentialsCall != null) {
            val credsClosure = credentialsCall.lambdaArguments.firstOrNull()?.getLambdaExpression()
            if (credsClosure != null) {
                val text = credsClosure.text

                // Extract username
                username = extractKotlinAssignment(text, "username", gradleProperties)

                // Extract password
                password = extractKotlinAssignment(text, "password", gradleProperties)
            }
        }

        return Pair(username, password)
    }

    /**
     * Extract a value from a Kotlin assignment like: username = "value" or username = property("name")
     */
    private fun extractKotlinAssignment(text: String, varName: String, gradleProperties: Map<String, String>): String? {
        // Pattern 1: varName = "literal"
        val literalPattern = """$varName\s*=\s*"([^"]+)"""".toRegex()
        literalPattern.find(text)?.let { return it.groupValues[1] }

        // Pattern 2: varName = property("propName").toString()
        val propertyPattern = """$varName\s*=\s*property\s*\(\s*"([^"]+)"\s*\)""".toRegex()
        propertyPattern.find(text)?.let { match ->
            val propName = match.groupValues[1]
            return gradleProperties[propName]
        }

        // Pattern 3: varName = extra["propName"] as String
        val extraPattern = """$varName\s*=\s*extra\s*\[\s*"([^"]+)"\s*\]""".toRegex()
        extraPattern.find(text)?.let { match ->
            val propName = match.groupValues[1]
            return gradleProperties[propName]
        }

        // Pattern 4: varName = project.findProperty("propName")?.toString()
        val findPropertyPattern = """$varName\s*=\s*project\.findProperty\s*\(\s*"([^"]+)"\s*\)""".toRegex()
        findPropertyPattern.find(text)?.let { match ->
            val propName = match.groupValues[1]
            return gradleProperties[propName]
        }

        // Pattern 5: varName = providers.gradleProperty("propName").get()
        val providerPattern = """$varName\s*=\s*providers\.gradleProperty\s*\(\s*"([^"]+)"\s*\)""".toRegex()
        providerPattern.find(text)?.let { match ->
            val propName = match.groupValues[1]
            return gradleProperties[propName]
        }

        return null
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
