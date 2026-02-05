package com.maddrobot.plugins.udm.maven.manager

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

/**
 * Service for checking updates for installed Maven plugins via Maven Central API.
 */
object MavenPluginUpdateService {

    private val log = Logger.getInstance(javaClass)
    private val gson = Gson()

    private const val MAVEN_CENTRAL_SEARCH_URL = "https://search.maven.org/solrsearch/select"
    private const val TIMEOUT_MS = 10_000

    // Cache for plugin versions (pluginId -> latestVersion)
    private val versionCache = ConcurrentHashMap<String, CachedVersion>()
    private const val CACHE_TTL_MS = 3600_000L // 1 hour

    private data class CachedVersion(
        val version: String?,
        val timestamp: Long
    )

    /**
     * Get the latest version of a Maven plugin from Maven Central.
     * Results are cached for 1 hour.
     *
     * @param groupId The plugin group ID
     * @param artifactId The plugin artifact ID
     * @return The latest version string, or null if not found or on error
     */
    fun getLatestVersion(groupId: String, artifactId: String): String? {
        val cacheKey = "$groupId:$artifactId"

        // Check cache first
        val cached = versionCache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) {
            return cached.version
        }

        // Fetch from Maven Central
        val version = fetchLatestVersion(groupId, artifactId)

        // Cache the result (including null to avoid repeated failed lookups)
        versionCache[cacheKey] = CachedVersion(version, System.currentTimeMillis())

        return version
    }

    /**
     * Check for updates for a list of installed plugins.
     *
     * @param plugins List of installed plugins to check
     * @return List of MavenPluginUpdate objects for plugins that have updates available
     */
    fun checkForUpdates(plugins: List<MavenInstalledPlugin>): List<MavenPluginUpdate> {
        val updates = mutableListOf<MavenPluginUpdate>()

        for (plugin in plugins) {
            // Skip plugins without versions
            if (plugin.version == null) continue

            val latestVersion = getLatestVersion(plugin.groupId, plugin.artifactId)
            if (latestVersion != null && latestVersion != plugin.version) {
                // Compare versions - only add if latest is actually newer
                if (isNewerVersion(latestVersion, plugin.version)) {
                    updates.add(MavenPluginUpdate(plugin, latestVersion))
                }
            }
        }

        return updates
    }

    /**
     * Fetch the latest version from Maven Central.
     */
    private fun fetchLatestVersion(groupId: String, artifactId: String): String? {
        return try {
            // Build search URL
            val query = "g:\"$groupId\" AND a:\"$artifactId\""
            val url = "$MAVEN_CENTRAL_SEARCH_URL?q=${java.net.URLEncoder.encode(query, "UTF-8")}&rows=1&wt=json"

            val connection = URI.create(url).toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/json")

            if (connection.responseCode != 200) {
                log.debug("Maven Central returned ${connection.responseCode} for $groupId:$artifactId")
                return null
            }

            val response = connection.inputStream.bufferedReader().readText()
            val json = gson.fromJson(response, JsonObject::class.java)

            val responseNode = json.getAsJsonObject("response")
            val numFound = responseNode?.get("numFound")?.asInt ?: 0

            if (numFound > 0) {
                val docs = responseNode.getAsJsonArray("docs")
                if (docs != null && docs.size() > 0) {
                    val firstDoc = docs[0].asJsonObject
                    return firstDoc.get("latestVersion")?.asString
                        ?: firstDoc.get("v")?.asString
                }
            }

            null

        } catch (e: Exception) {
            log.debug("Failed to fetch plugin version for $groupId:$artifactId: ${e.message}")
            null
        }
    }

    /**
     * Simple version comparison - checks if newVersion is newer than currentVersion.
     */
    private fun isNewerVersion(newVersion: String, currentVersion: String): Boolean {
        if (newVersion == currentVersion) return false

        try {
            val newParts = parseVersionParts(newVersion)
            val currentParts = parseVersionParts(currentVersion)

            // Compare numeric parts
            val maxParts = maxOf(newParts.first.size, currentParts.first.size)
            for (i in 0 until maxParts) {
                val newPart = newParts.first.getOrElse(i) { 0 }
                val currentPart = currentParts.first.getOrElse(i) { 0 }

                if (newPart > currentPart) return true
                if (newPart < currentPart) return false
            }

            // If numeric parts are equal, compare qualifiers
            val newQualifier = newParts.second
            val currentQualifier = currentParts.second

            if (newQualifier == null && currentQualifier != null) {
                // New is release, current has qualifier
                return true
            }
            if (newQualifier != null && currentQualifier == null) {
                // Current is release, new has qualifier
                return false
            }

            return newVersion > currentVersion

        } catch (e: Exception) {
            return newVersion > currentVersion
        }
    }

    /**
     * Parse version string into numeric parts and qualifier.
     */
    private fun parseVersionParts(version: String): Pair<List<Int>, String?> {
        val qualifierIndex = version.indexOfFirst { it == '-' || it == '+' }
        val numericPart: String
        val qualifier: String?

        if (qualifierIndex != -1) {
            numericPart = version.substring(0, qualifierIndex)
            qualifier = version.substring(qualifierIndex + 1)
        } else {
            numericPart = version
            qualifier = null
        }

        val parts = numericPart.split(".")
            .mapNotNull { it.toIntOrNull() }

        return Pair(parts, qualifier)
    }

    /**
     * Clear the version cache.
     */
    fun clearCache() {
        versionCache.clear()
    }
}
