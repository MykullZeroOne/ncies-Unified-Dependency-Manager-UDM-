package com.maddrobot.plugins.udm.gradle.manager.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.net.HttpConnectionUtils
import com.maddrobot.plugins.udm.util.HttpRequestHelper
import org.apache.maven.model.io.xpp3.MavenXpp3Reader
import java.io.File
import java.io.StringReader
import java.net.HttpURLConnection
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Service for fetching transitive dependencies (dependency tree) for packages.
 * Tries project-configured repositories first, then falls back to Maven Central.
 */
@Service(Service.Level.PROJECT)
class TransitiveDependencyService(private val project: Project) : Disposable {
    private val log = Logger.getInstance(javaClass)
    private val repoService by lazy { RepositoryDiscoveryService.getInstance(project) }

    // Cache for fetched dependencies to avoid repeated network calls (accessed from multiple threads)
    private val dependencyCache = java.util.concurrent.ConcurrentHashMap<String, List<TransitiveDependency>>()

    companion object {
        fun getInstance(project: Project): TransitiveDependencyService =
            project.getService(TransitiveDependencyService::class.java)

        private const val MAVEN_CENTRAL_BASE = "https://repo.maven.org/maven2"

        // Shorter timeouts for batch analysis (exclusion suggestions)
        private const val FAST_CONNECT_TIMEOUT = 3_000
        private const val FAST_READ_TIMEOUT = 5_000

        // Connectivity pre-check timeout (very short - just testing if host is reachable)
        private const val PING_TIMEOUT = 2_000

        // Local cache paths
        private val LOCAL_M2_REPO = File(System.getProperty("user.home"), ".m2/repository")
        private val GRADLE_CACHE_DIR = File(System.getProperty("user.home"), ".gradle/caches/modules-2/files-2.1")
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
     * Fast variant of getTransitiveDependenciesSync for batch analysis.
     * Resolution order: local .m2 cache -> pre-checked remote repos (short timeouts).
     *
     * @param failedRepos Shared mutable set tracking repo URLs that have timed out or errored.
     *                    Repos in this set are skipped, saving 8-20s per dep per failed repo.
     */
    fun getTransitiveDependenciesFastSync(
        groupId: String,
        artifactId: String,
        version: String,
        failedRepos: MutableSet<String>
    ): List<TransitiveDependency> {
        val cacheKey = "$groupId:$artifactId:$version"

        // Check in-memory cache first
        dependencyCache[cacheKey]?.let { return it }

        return try {
            // Try local .m2 repository first (instant, no network)
            val localPom = readLocalPom(groupId, artifactId, version)
            val pomContent = localPom ?: fetchPomFast(groupId, artifactId, version, failedRepos)
                ?: return emptyList()

            val dependencies = parsePomDependencies(pomContent)
            dependencyCache[cacheKey] = dependencies
            dependencies
        } catch (e: Exception) {
            log.debug("Fast fetch failed for $cacheKey: ${e.message}")
            emptyList()
        }
    }

    /**
     * Local-only variant for exclusion suggestion analysis.
     * Reads POMs exclusively from local caches (~/.m2/repository and ~/.gradle/caches).
     * No network calls at all — completes in milliseconds.
     *
     * @return List of transitive deps, or null if POM file was not found locally.
     */
    fun getTransitiveDependenciesLocalSync(
        groupId: String,
        artifactId: String,
        version: String
    ): List<TransitiveDependency>? {
        val cacheKey = "$groupId:$artifactId:$version"

        dependencyCache[cacheKey]?.let { return it }

        return try {
            val pomContent = readLocalPom(groupId, artifactId, version)
                ?: readGradleCachePom(groupId, artifactId, version)
                ?: return null  // POM not found locally

            val dependencies = parsePomDependencies(pomContent)
            dependencyCache[cacheKey] = dependencies
            dependencies
        } catch (e: Exception) {
            log.debug("Local POM read failed for $cacheKey: ${e.message}")
            null
        }
    }

    /**
     * Read POM from local ~/.m2/repository if it exists.
     * This is instant (disk read) and covers any dependency that's been resolved before.
     */
    private fun readLocalPom(groupId: String, artifactId: String, version: String): String? {
        if (!LOCAL_M2_REPO.isDirectory) return null

        val groupPath = groupId.replace('.', File.separatorChar)
        val pomFile = File(LOCAL_M2_REPO, "$groupPath/$artifactId/$version/$artifactId-$version.pom")

        return try {
            if (pomFile.isFile && pomFile.length() > 0) {
                pomFile.readText()
            } else {
                null
            }
        } catch (e: Exception) {
            log.debug("Failed to read local POM ${pomFile.path}: ${e.message}")
            null
        }
    }

    /**
     * Read POM from Gradle's local cache (~/.gradle/caches/modules-2/files-2.1/).
     * Gradle stores files under {group}/{artifact}/{version}/{sha1hash}/{file}.
     * We scan the version directory for any hash subdirectory containing the POM.
     */
    private fun readGradleCachePom(groupId: String, artifactId: String, version: String): String? {
        if (!GRADLE_CACHE_DIR.isDirectory) return null

        // Gradle cache uses dotted group IDs as directory names (e.g. "com.google.guava/guava/31.1-jre")
        val versionDir = File(GRADLE_CACHE_DIR, "$groupId/$artifactId/$version")

        return try {
            if (!versionDir.isDirectory) return null

            // Scan hash subdirectories for the POM file
            val pomFileName = "$artifactId-$version.pom"
            versionDir.listFiles()?.forEach { hashDir ->
                if (hashDir.isDirectory) {
                    val pomFile = File(hashDir, pomFileName)
                    if (pomFile.isFile && pomFile.length() > 0) {
                        return pomFile.readText()
                    }
                }
            }
            null
        } catch (e: Exception) {
            log.debug("Failed to read Gradle cache POM for $groupId:$artifactId:$version: ${e.message}")
            null
        }
    }

    /**
     * Pre-check connectivity to remote repository URLs before batch fetching.
     * All repos are probed in PARALLEL with a hard Future.get() timeout to guard
     * against DNS blackholes and other hangs that socket timeouts don't cover.
     * Returns the set of repo URLs that are unreachable.
     */
    fun probeRepositories(): Set<String> {
        val repoUrls = getOrderedRepositoryUrls()
        if (repoUrls.isEmpty()) return emptySet()

        val unreachable = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val executor = Executors.newFixedThreadPool(repoUrls.size.coerceAtMost(8))

        try {
            val futures = repoUrls.map { repoUrl ->
                repoUrl to executor.submit<Unit> {
                    try {
                        val conn = HttpConnectionUtils.openHttpConnection(repoUrl)
                        conn.connectTimeout = PING_TIMEOUT
                        conn.readTimeout = PING_TIMEOUT
                        conn.requestMethod = "HEAD"
                        conn.instanceFollowRedirects = true
                        conn.responseCode  // triggers the actual connection
                        // Any response (200, 401, 403, 404) means the host is reachable
                    } catch (e: Exception) {
                        log.info("Repository unreachable: $repoUrl (${e.javaClass.simpleName}: ${e.message})")
                        unreachable.add(repoUrl)
                    }
                }
            }

            // Hard timeout per probe - covers DNS blackholes that socket timeout can't catch
            for ((repoUrl, future) in futures) {
                try {
                    future.get(PING_TIMEOUT + 1000L, TimeUnit.MILLISECONDS)
                } catch (e: java.util.concurrent.TimeoutException) {
                    future.cancel(true)
                    log.info("Repository probe timed out (hard limit): $repoUrl")
                    unreachable.add(repoUrl)
                } catch (e: Exception) {
                    log.debug("Repository probe failed for $repoUrl: ${e.message}")
                    unreachable.add(repoUrl)
                }
            }
        } finally {
            executor.shutdownNow()
        }

        if (unreachable.isNotEmpty()) {
            log.info("Pre-check: ${unreachable.size} of ${repoUrls.size} repos unreachable, will be skipped")
        }
        return unreachable
    }

    private fun fetchPomFast(
        groupId: String,
        artifactId: String,
        version: String,
        failedRepos: MutableSet<String>
    ): String? {
        val repoUrls = getOrderedRepositoryUrls()

        for (repoUrl in repoUrls) {
            // Skip repos that already failed (unreachable, auth errors, etc.)
            if (repoUrl in failedRepos) continue

            val pomUrl = buildPomUrl(repoUrl, groupId, artifactId, version)
            try {
                val conn = HttpConnectionUtils.openHttpConnection(pomUrl)
                conn.connectTimeout = FAST_CONNECT_TIMEOUT
                conn.readTimeout = FAST_READ_TIMEOUT
                conn.instanceFollowRedirects = true

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    return conn.inputStream.bufferedReader().use { it.readText() }
                }
                // 404 is normal (artifact not in this repo) - don't mark repo as failed
                if (conn.responseCode != HttpURLConnection.HTTP_NOT_FOUND) {
                    failedRepos.add(repoUrl)
                }
            } catch (e: java.net.SocketTimeoutException) {
                failedRepos.add(repoUrl)
                log.debug("Fast POM fetch timed out from $repoUrl, skipping for remaining deps")
            } catch (e: java.net.ConnectException) {
                failedRepos.add(repoUrl)
                log.debug("Fast POM fetch connection refused from $repoUrl, skipping for remaining deps")
            } catch (e: Exception) {
                log.debug("Fast POM fetch failed from $repoUrl: ${e.message}")
            }
        }
        return null
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
            val reader = MavenXpp3Reader()
            val model = reader.read(StringReader(pomContent))

            val dependencies = mutableListOf<TransitiveDependency>()
            val properties = model.properties

            // Resolve ${property} references using the POM's <properties> and model fields
            fun resolveProperty(value: String?): String? {
                if (value == null) return null
                if (!value.contains("\${")) return value

                var resolved = value
                val propertyPattern = Regex("\\$\\{([^}]+)}")
                for (match in propertyPattern.findAll(value)) {
                    val propName = match.groupValues[1]
                    val propValue = when (propName) {
                        "project.version" -> model.version ?: model.parent?.version
                        "project.groupId" -> model.groupId ?: model.parent?.groupId
                        "project.artifactId" -> model.artifactId
                        else -> properties?.getProperty(propName)
                    }
                    if (propValue != null) {
                        resolved = resolved?.replace(match.value, propValue)
                    }
                }
                return resolved
            }

            // Parse direct dependencies from <dependencies> section
            for (dep in model.dependencies) {
                val scope = dep.scope ?: "compile"
                if (scope in listOf("test", "provided")) continue

                val groupId = resolveProperty(dep.groupId) ?: continue
                val artifactId = resolveProperty(dep.artifactId) ?: continue
                val version = resolveProperty(dep.version)

                dependencies.add(
                    TransitiveDependency(
                        groupId = groupId,
                        artifactId = artifactId,
                        version = version,
                        scope = scope,
                        optional = dep.isOptional
                    )
                )
            }

            // Also check dependencyManagement for additional deps (some POMs define deps only there)
            model.dependencyManagement?.dependencies?.let { managedDeps ->
                val directIds = dependencies.map { "${it.groupId}:${it.artifactId}" }.toSet()
                for (dep in managedDeps) {
                    val scope = dep.scope ?: "compile"
                    if (scope in listOf("test", "provided")) continue

                    val groupId = resolveProperty(dep.groupId) ?: continue
                    val artifactId = resolveProperty(dep.artifactId) ?: continue
                    val depId = "$groupId:$artifactId"

                    // Only add managed deps that aren't already in direct deps
                    if (depId !in directIds && dep.type != "pom") {
                        dependencies.add(
                            TransitiveDependency(
                                groupId = groupId,
                                artifactId = artifactId,
                                version = resolveProperty(dep.version),
                                scope = scope,
                                optional = dep.isOptional
                            )
                        )
                    }
                }
            }

            dependencies
        } catch (e: Exception) {
            log.warn("Failed to parse POM: ${e.message}")
            emptyList()
        }
    }

    /**
     * Clear the dependency cache.
     */
    fun clearCache() {
        dependencyCache.clear()
    }

    override fun dispose() {
        clearCache()
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
