package com.maddrobot.plugins.udm.gradle.manager.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.maddrobot.plugins.udm.util.HttpRequestHelper

/**
 * Service for fetching transitive dependencies (dependency tree) for packages.
 * Tries project-configured repositories first, then falls back to Maven Central.
 */
@Service(Service.Level.PROJECT)
class TransitiveDependencyService(private val project: Project) {
    private val log = Logger.getInstance(javaClass)
    private val repoService by lazy { RepositoryDiscoveryService.getInstance(project) }

    // Cache for fetched dependencies to avoid repeated network calls
    private val dependencyCache = mutableMapOf<String, List<TransitiveDependency>>()

    companion object {
        fun getInstance(project: Project): TransitiveDependencyService =
            project.getService(TransitiveDependencyService::class.java)

        private const val MAVEN_CENTRAL_BASE = "https://repo.maven.org/maven2"
    }

    /**
     * Get repository URLs ordered by priority (project repos first, Maven Central last).
     */
    private fun getOrderedRepositoryUrls(): List<String> {
        val repos = repoService.getConfiguredRepositories()
            .filter { it.type != RepositoryType.NPM && it.type != RepositoryType.GRADLE_PLUGIN_PORTAL }
            .filter { it.enabled }

        // Project-specific repos first (from Gradle/Maven settings), built-in repos last
        val projectRepos = repos.filter { it.source != RepositorySource.BUILTIN }
        val builtinRepos = repos.filter { it.source == RepositorySource.BUILTIN }

        // Ensure Maven Central is always last as the fallback
        val mavenCentral = builtinRepos.find { it.type == RepositoryType.MAVEN_CENTRAL }
        val otherBuiltin = builtinRepos.filter { it.type != RepositoryType.MAVEN_CENTRAL }

        return (projectRepos + otherBuiltin + listOfNotNull(mavenCentral))
            .map { it.url.trimEnd('/') }
            .distinct()
    }

    /**
     * Fetch transitive dependencies for a package asynchronously.
     * @param groupId The group ID
     * @param artifactId The artifact ID
     * @param version The version
     * @param callback Called with the list of transitive dependencies
     */
    fun getTransitiveDependencies(
        groupId: String,
        artifactId: String,
        version: String,
        callback: (List<TransitiveDependency>) -> Unit
    ) {
        val cacheKey = "$groupId:$artifactId:$version"

        // Check cache first
        dependencyCache[cacheKey]?.let {
            callback(it)
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val pomContent = fetchPomFromRepositories(groupId, artifactId, version)
                val dependencies = if (pomContent != null) {
                    parsePomDependencies(pomContent)
                } else {
                    emptyList()
                }

                dependencyCache[cacheKey] = dependencies

                ApplicationManager.getApplication().invokeLater {
                    callback(dependencies)
                }
            } catch (e: Exception) {
                log.warn("Failed to fetch transitive dependencies for $cacheKey", e)
                ApplicationManager.getApplication().invokeLater {
                    callback(emptyList())
                }
            }
        }
    }

    /**
     * Fetch transitive dependencies synchronously (for use in background threads).
     */
    fun getTransitiveDependenciesSync(
        groupId: String,
        artifactId: String,
        version: String
    ): List<TransitiveDependency> {
        val cacheKey = "$groupId:$artifactId:$version"

        // Check cache first
        dependencyCache[cacheKey]?.let { return it }

        return try {
            val pomContent = fetchPomFromRepositories(groupId, artifactId, version) ?: return emptyList()

            val dependencies = parsePomDependencies(pomContent)
            dependencyCache[cacheKey] = dependencies
            dependencies
        } catch (e: Exception) {
            log.warn("Failed to fetch transitive dependencies for $cacheKey", e)
            emptyList()
        }
    }

    /**
     * Fetch the full dependency tree (recursively).
     * Be careful with depth to avoid too many network calls.
     */
    fun getDependencyTree(
        groupId: String,
        artifactId: String,
        version: String,
        maxDepth: Int = 2,
        callback: (DependencyTreeNode) -> Unit
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val tree = buildDependencyTree(groupId, artifactId, version, 0, maxDepth, mutableSetOf())
            ApplicationManager.getApplication().invokeLater {
                callback(tree)
            }
        }
    }

    private fun buildDependencyTree(
        groupId: String,
        artifactId: String,
        version: String,
        currentDepth: Int,
        maxDepth: Int,
        visited: MutableSet<String>
    ): DependencyTreeNode {
        val nodeId = "$groupId:$artifactId:$version"

        // Avoid circular dependencies
        if (nodeId in visited) {
            return DependencyTreeNode(groupId, artifactId, version, emptyList(), isCircular = true)
        }

        visited.add(nodeId)

        val children = if (currentDepth < maxDepth) {
            val deps = getTransitiveDependenciesSync(groupId, artifactId, version)
            deps.filter { it.scope in listOf("compile", "runtime", null) }
                .mapNotNull { dep ->
                    dep.version?.let { v ->
                        buildDependencyTree(dep.groupId, dep.artifactId, v, currentDepth + 1, maxDepth, visited)
                    }
                }
        } else {
            emptyList()
        }

        return DependencyTreeNode(groupId, artifactId, version, children)
    }

    private fun buildPomUrl(baseUrl: String, groupId: String, artifactId: String, version: String): String {
        val groupPath = groupId.replace(".", "/")
        return "$baseUrl/$groupPath/$artifactId/$version/$artifactId-$version.pom"
    }

    /**
     * Fetch POM from configured repositories in priority order.
     * Tries project-specific repos first, then Maven Central as fallback.
     */
    private fun fetchPomFromRepositories(groupId: String, artifactId: String, version: String): String? {
        val repoUrls = getOrderedRepositoryUrls()

        for (repoUrl in repoUrls) {
            val pomUrl = buildPomUrl(repoUrl, groupId, artifactId, version)
            log.debug("Trying to fetch POM from: $pomUrl")

            val result = fetchPomFromUrl(pomUrl)
            if (result != null) {
                log.debug("Successfully fetched POM from: $repoUrl")
                return result
            }
        }

        log.debug("POM not found in any repository for $groupId:$artifactId:$version")
        return null
    }

    private fun fetchPomFromUrl(url: String): String? {
        return when (val result = HttpRequestHelper.getForObject(url) { it }) {
            is HttpRequestHelper.RequestResult.Success -> result.data
            is HttpRequestHelper.RequestResult.Error -> {
                log.debug("Failed to fetch POM from $url: ${result.exception.message}")
                null
            }
        }
    }

    private fun parsePomDependencies(pomContent: String): List<TransitiveDependency> {
        return try {
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(pomContent.byteInputStream())

            val dependencies = mutableListOf<TransitiveDependency>()
            val dependencyElements = doc.getElementsByTagName("dependency")

            for (i in 0 until dependencyElements.length) {
                val depElement = dependencyElements.item(i)
                if (depElement.parentNode?.nodeName == "dependencies" &&
                    depElement.parentNode?.parentNode?.nodeName in listOf("project", null)) {

                    val groupId = getChildText(depElement, "groupId") ?: continue
                    val artifactId = getChildText(depElement, "artifactId") ?: continue
                    val version = getChildText(depElement, "version")
                    val scope = getChildText(depElement, "scope")
                    val optional = getChildText(depElement, "optional")?.toBoolean() ?: false

                    // Skip test and provided dependencies
                    if (scope in listOf("test", "provided")) continue

                    dependencies.add(
                        TransitiveDependency(
                            groupId = resolveProperty(groupId, doc),
                            artifactId = resolveProperty(artifactId, doc),
                            version = version?.let { resolveProperty(it, doc) },
                            scope = scope,
                            optional = optional
                        )
                    )
                }
            }

            dependencies
        } catch (e: Exception) {
            log.warn("Failed to parse POM", e)
            emptyList()
        }
    }

    private fun getChildText(parent: org.w3c.dom.Node, childName: String): String? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeName == childName) {
                return child.textContent?.trim()
            }
        }
        return null
    }

    private fun resolveProperty(value: String, doc: org.w3c.dom.Document): String {
        if (!value.startsWith("\${") || !value.endsWith("}")) {
            return value
        }

        val propertyName = value.substring(2, value.length - 1)

        // Try to find in <properties> block
        val propertiesElements = doc.getElementsByTagName("properties")
        if (propertiesElements.length > 0) {
            val props = propertiesElements.item(0)
            val propValue = getChildText(props, propertyName)
            if (propValue != null) {
                return propValue
            }
        }

        // Handle special properties
        return when (propertyName) {
            "project.version" -> {
                doc.getElementsByTagName("version").item(0)?.textContent?.trim() ?: value
            }
            "project.groupId" -> {
                doc.getElementsByTagName("groupId").item(0)?.textContent?.trim() ?: value
            }
            else -> value
        }
    }

    /**
     * Clear the dependency cache.
     */
    fun clearCache() {
        dependencyCache.clear()
    }

    /**
     * Get cache statistics for debugging.
     */
    fun getCacheStats(): Map<String, Any> {
        return mapOf(
            "size" to dependencyCache.size,
            "entries" to dependencyCache.keys.toList().take(20)
        )
    }
}

/**
 * Represents a transitive dependency from a POM file.
 */
data class TransitiveDependency(
    val groupId: String,
    val artifactId: String,
    val version: String?,
    val scope: String?,
    val optional: Boolean = false
) {
    val id: String get() = "$groupId:$artifactId"
    val fullId: String get() = version?.let { "$groupId:$artifactId:$it" } ?: id
}

/**
 * Represents a node in the dependency tree.
 */
data class DependencyTreeNode(
    val groupId: String,
    val artifactId: String,
    val version: String,
    val children: List<DependencyTreeNode>,
    val isCircular: Boolean = false
) {
    val id: String get() = "$groupId:$artifactId:$version"

    /**
     * Flatten the tree to a list of all dependencies.
     */
    fun flatten(): List<DependencyTreeNode> {
        val result = mutableListOf<DependencyTreeNode>()
        result.add(this)
        children.forEach { child ->
            result.addAll(child.flatten())
        }
        return result
    }

    /**
     * Get the total count of dependencies (including nested).
     */
    fun totalCount(): Int {
        return 1 + children.sumOf { it.totalCount() }
    }
}
