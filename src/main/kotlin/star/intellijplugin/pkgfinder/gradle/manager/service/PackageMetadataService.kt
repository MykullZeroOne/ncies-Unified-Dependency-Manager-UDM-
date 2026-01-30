package star.intellijplugin.pkgfinder.gradle.manager.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.w3c.dom.Element
import org.w3c.dom.Node
import star.intellijplugin.pkgfinder.util.HttpRequestHelper
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Service for fetching detailed package metadata from Maven repositories.
 * Tries project-configured repositories first, then falls back to Maven Central.
 * Caches metadata for installed dependencies to avoid repeated network calls.
 */
@Service(Service.Level.PROJECT)
class PackageMetadataService(private val project: Project) {
    private val log = Logger.getInstance(javaClass)
    private val repoService by lazy { RepositoryDiscoveryService.getInstance(project) }

    // Cache for fetched metadata
    private val metadataCache = mutableMapOf<String, PackageDetails>()

    companion object {
        fun getInstance(project: Project): PackageMetadataService =
            project.getService(PackageMetadataService::class.java)

        private const val MAVEN_CENTRAL_POM_BASE = "https://repo.maven.org/maven2"
        private const val MAVEN_CENTRAL_SEARCH_API = "https://search.maven.org/solrsearch/select"
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
     * Fetch package details asynchronously.
     * Returns cached data if available, otherwise fetches from repository.
     */
    fun getPackageDetails(
        groupId: String,
        artifactId: String,
        version: String?,
        callback: (PackageDetails?) -> Unit
    ) {
        val cacheKey = "$groupId:$artifactId"

        // Check cache first
        metadataCache[cacheKey]?.let {
            callback(it)
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val details = fetchPackageDetails(groupId, artifactId, version)
                if (details != null) {
                    metadataCache[cacheKey] = details
                }

                ApplicationManager.getApplication().invokeLater {
                    callback(details)
                }
            } catch (e: Exception) {
                log.warn("Failed to fetch package details for $cacheKey", e)
                ApplicationManager.getApplication().invokeLater {
                    callback(null)
                }
            }
        }
    }

    /**
     * Fetch package details synchronously (for background threads).
     */
    fun getPackageDetailsSync(
        groupId: String,
        artifactId: String,
        version: String?
    ): PackageDetails? {
        val cacheKey = "$groupId:$artifactId"

        // Check cache first
        metadataCache[cacheKey]?.let { return it }

        return try {
            val details = fetchPackageDetails(groupId, artifactId, version)
            if (details != null) {
                metadataCache[cacheKey] = details
            }
            details
        } catch (e: Exception) {
            log.warn("Failed to fetch package details for $cacheKey", e)
            null
        }
    }

    /**
     * Pre-fetch and cache metadata for a list of installed dependencies.
     */
    fun prefetchInstalledDependencies(dependencies: List<DependencyCoordinates>) {
        ApplicationManager.getApplication().executeOnPooledThread {
            for (dep in dependencies) {
                val cacheKey = "${dep.groupId}:${dep.artifactId}"
                if (!metadataCache.containsKey(cacheKey)) {
                    try {
                        val details = fetchPackageDetails(dep.groupId, dep.artifactId, dep.version)
                        if (details != null) {
                            metadataCache[cacheKey] = details
                        }
                    } catch (e: Exception) {
                        log.debug("Failed to prefetch metadata for $cacheKey: ${e.message}")
                    }
                }
            }
        }
    }

    /**
     * Get cached details if available.
     */
    fun getCachedDetails(groupId: String, artifactId: String): PackageDetails? {
        return metadataCache["$groupId:$artifactId"]
    }

    /**
     * Clear the metadata cache.
     */
    fun clearCache() {
        metadataCache.clear()
    }

    private fun fetchPackageDetails(groupId: String, artifactId: String, version: String?): PackageDetails? {
        // First try to get info from Maven Central search API (faster, has description)
        val searchDetails = fetchFromMavenCentralSearch(groupId, artifactId)

        // Then get additional info from POM (has more details like URL, license)
        val pomDetails = if (version != null) {
            fetchFromPom(groupId, artifactId, version)
        } else {
            // Try to get latest version from search results
            searchDetails?.latestVersion?.let { fetchFromPom(groupId, artifactId, it) }
        }

        // Merge the two sources
        return mergeDetails(searchDetails, pomDetails, groupId, artifactId)
    }

    private fun fetchFromMavenCentralSearch(groupId: String, artifactId: String): SearchDetails? {
        val query = "g:\"$groupId\" AND a:\"$artifactId\""
        val url = "$MAVEN_CENTRAL_SEARCH_API?q=${java.net.URLEncoder.encode(query, "UTF-8")}&rows=1&wt=json"

        return when (val result = HttpRequestHelper.getForObject(url) { it }) {
            is HttpRequestHelper.RequestResult.Success -> {
                result.data?.let { parseSearchResponse(it) }
            }
            is HttpRequestHelper.RequestResult.Error -> {
                log.debug("Maven Central search failed: ${result.exception.message}")
                null
            }
        }
    }

    private fun parseSearchResponse(json: String): SearchDetails? {
        return try {
            // Simple JSON parsing without external library
            val docsStart = json.indexOf("\"docs\":[")
            if (docsStart == -1) return null

            val docStart = json.indexOf("{", docsStart)
            val docEnd = json.indexOf("}", docStart)
            if (docStart == -1 || docEnd == -1) return null

            val doc = json.substring(docStart, docEnd + 1)

            SearchDetails(
                latestVersion = extractJsonString(doc, "latestVersion") ?: extractJsonString(doc, "v"),
                description = null, // Maven Central search doesn't return description
                timestamp = extractJsonLong(doc, "timestamp")
            )
        } catch (e: Exception) {
            log.debug("Failed to parse search response: ${e.message}")
            null
        }
    }

    /**
     * Fetch POM from configured repositories in priority order.
     * Tries project-specific repos first, then Maven Central as fallback.
     */
    private fun fetchFromPom(groupId: String, artifactId: String, version: String): PomDetails? {
        val groupPath = groupId.replace(".", "/")
        val repoUrls = getOrderedRepositoryUrls()

        for (repoUrl in repoUrls) {
            val pomUrl = "$repoUrl/$groupPath/$artifactId/$version/$artifactId-$version.pom"
            log.debug("Trying to fetch POM from: $pomUrl")

            when (val result = HttpRequestHelper.getForObject(pomUrl) { it }) {
                is HttpRequestHelper.RequestResult.Success -> {
                    result.data?.let { content ->
                        log.debug("Successfully fetched POM from: $repoUrl")
                        return parsePomFile(content)
                    }
                }
                is HttpRequestHelper.RequestResult.Error -> {
                    log.debug("POM not found at $repoUrl for $groupId:$artifactId:$version")
                }
            }
        }

        log.debug("POM not found in any repository for $groupId:$artifactId:$version")
        return null
    }

    private fun parsePomFile(pomContent: String): PomDetails? {
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(pomContent.byteInputStream())

            val root = doc.documentElement

            PomDetails(
                name = getDirectChildText(root, "name"),
                description = getDirectChildText(root, "description"),
                url = getDirectChildText(root, "url"),
                inceptionYear = getDirectChildText(root, "inceptionYear"),
                licenses = parseLicenses(doc),
                developers = parseDevelopers(doc),
                organization = parseOrganization(doc),
                scm = parseScm(doc)
            )
        } catch (e: Exception) {
            log.debug("Failed to parse POM: ${e.message}")
            null
        }
    }

    private fun parseLicenses(doc: org.w3c.dom.Document): List<LicenseInfo> {
        val licenses = mutableListOf<LicenseInfo>()
        val licenseElements = doc.getElementsByTagName("license")

        for (i in 0 until licenseElements.length) {
            val element = licenseElements.item(i) as? Element ?: continue
            if (element.parentNode?.nodeName == "licenses") {
                licenses.add(
                    LicenseInfo(
                        name = getChildText(element, "name"),
                        url = getChildText(element, "url")
                    )
                )
            }
        }
        return licenses
    }

    private fun parseDevelopers(doc: org.w3c.dom.Document): List<DeveloperInfo> {
        val developers = mutableListOf<DeveloperInfo>()
        val devElements = doc.getElementsByTagName("developer")

        for (i in 0 until devElements.length) {
            val element = devElements.item(i) as? Element ?: continue
            developers.add(
                DeveloperInfo(
                    id = getChildText(element, "id"),
                    name = getChildText(element, "name"),
                    email = getChildText(element, "email"),
                    organization = getChildText(element, "organization")
                )
            )
        }
        return developers
    }

    private fun parseOrganization(doc: org.w3c.dom.Document): OrganizationInfo? {
        val orgElements = doc.getElementsByTagName("organization")
        if (orgElements.length > 0) {
            val element = orgElements.item(0) as? Element ?: return null
            return OrganizationInfo(
                name = getChildText(element, "name"),
                url = getChildText(element, "url")
            )
        }
        return null
    }

    private fun parseScm(doc: org.w3c.dom.Document): ScmInfo? {
        val scmElements = doc.getElementsByTagName("scm")
        if (scmElements.length > 0) {
            val element = scmElements.item(0) as? Element ?: return null
            return ScmInfo(
                url = getChildText(element, "url"),
                connection = getChildText(element, "connection"),
                developerConnection = getChildText(element, "developerConnection")
            )
        }
        return null
    }

    private fun mergeDetails(
        search: SearchDetails?,
        pom: PomDetails?,
        groupId: String,
        artifactId: String
    ): PackageDetails? {
        if (search == null && pom == null) return null

        return PackageDetails(
            groupId = groupId,
            artifactId = artifactId,
            name = pom?.name ?: artifactId,
            description = pom?.description?.trim()?.takeIf { it.isNotEmpty() },
            url = pom?.url ?: pom?.scm?.url,
            latestVersion = search?.latestVersion,
            licenses = pom?.licenses ?: emptyList(),
            developers = pom?.developers ?: emptyList(),
            organization = pom?.organization,
            scm = pom?.scm,
            inceptionYear = pom?.inceptionYear
        )
    }

    private fun getDirectChildText(element: Element, tagName: String): String? {
        val children = element.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType == Node.ELEMENT_NODE && child.nodeName == tagName) {
                return child.textContent?.trim()?.takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    private fun getChildText(element: Element, tagName: String): String? {
        val nodes = element.getElementsByTagName(tagName)
        if (nodes.length > 0) {
            for (i in 0 until nodes.length) {
                if (nodes.item(i).parentNode == element) {
                    return nodes.item(i).textContent?.trim()?.takeIf { it.isNotEmpty() }
                }
            }
        }
        return null
    }

    private fun extractJsonString(json: String, key: String): String? {
        val pattern = "\"$key\"\\s*:\\s*\"([^\"]*)\""
        val regex = Regex(pattern)
        return regex.find(json)?.groupValues?.get(1)
    }

    private fun extractJsonLong(json: String, key: String): Long? {
        val pattern = "\"$key\"\\s*:\\s*(\\d+)"
        val regex = Regex(pattern)
        return regex.find(json)?.groupValues?.get(1)?.toLongOrNull()
    }
}

/**
 * Complete package details fetched from repositories.
 */
data class PackageDetails(
    val groupId: String,
    val artifactId: String,
    val name: String,
    val description: String?,
    val url: String?,
    val latestVersion: String?,
    val licenses: List<LicenseInfo>,
    val developers: List<DeveloperInfo>,
    val organization: OrganizationInfo?,
    val scm: ScmInfo?,
    val inceptionYear: String?
) {
    val displayName: String get() = name.takeIf { it != artifactId } ?: artifactId
    val licenseNames: String get() = licenses.mapNotNull { it.name }.joinToString(", ").ifEmpty { null } ?: "Unknown"
    val homepage: String? get() = url ?: scm?.url
    val publisherName: String get() = organization?.name ?: developers.firstOrNull()?.name ?: groupId
}

data class LicenseInfo(
    val name: String?,
    val url: String?
)

data class DeveloperInfo(
    val id: String?,
    val name: String?,
    val email: String?,
    val organization: String?
)

data class OrganizationInfo(
    val name: String?,
    val url: String?
)

data class ScmInfo(
    val url: String?,
    val connection: String?,
    val developerConnection: String?
)

data class DependencyCoordinates(
    val groupId: String,
    val artifactId: String,
    val version: String?
)

// Internal data classes for parsing
private data class SearchDetails(
    val latestVersion: String?,
    val description: String?,
    val timestamp: Long?
)

private data class PomDetails(
    val name: String?,
    val description: String?,
    val url: String?,
    val inceptionYear: String?,
    val licenses: List<LicenseInfo>,
    val developers: List<DeveloperInfo>,
    val organization: OrganizationInfo?,
    val scm: ScmInfo?
)
