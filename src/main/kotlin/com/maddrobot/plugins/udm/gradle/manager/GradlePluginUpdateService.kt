package com.maddrobot.plugins.udm.gradle.manager

import com.intellij.openapi.diagnostic.Logger
import org.jsoup.Jsoup
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Service for checking updates for installed Gradle plugins via the Gradle Plugin Portal.
 */
object GradlePluginUpdateService {

    private val log = Logger.getInstance(javaClass)

    private const val GRADLE_PLUGIN_PORTAL_URL = "https://plugins.gradle.org/plugin"
    private const val USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
    private const val TIMEOUT_MS = 30_000

    // Cache for plugin versions (pluginId -> latestVersion)
    private val versionCache = ConcurrentHashMap<String, CachedVersion>()
    private const val CACHE_TTL_MS = 3600_000L // 1 hour

    private data class CachedVersion(
        val version: String?,
        val timestamp: Long
    )

    /**
     * Enriched plugin portal information including website URL and description.
     */
    data class PluginPortalInfo(
        val latestVersion: String?,
        val websiteUrl: String?,
        val description: String?
    )

    // Cache for enriched portal info
    private val portalInfoCache = ConcurrentHashMap<String, CachedPortalInfo>()

    private data class CachedPortalInfo(
        val info: PluginPortalInfo?,
        val timestamp: Long
    )

    /**
     * Get the latest version of a plugin from the Gradle Plugin Portal.
     * Results are cached for 1 hour.
     *
     * @param pluginId The plugin ID (e.g., "com.google.devtools.ksp")
     * @return The latest version string, or null if not found or on error
     */
    fun getLatestVersion(pluginId: String): String? {
        // Check cache first
        val cached = versionCache[pluginId]
        if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) {
            return cached.version
        }

        // Fetch from Gradle Plugin Portal
        val version = fetchLatestVersion(pluginId)

        // Cache the result (including null to avoid repeated failed lookups)
        versionCache[pluginId] = CachedVersion(version, System.currentTimeMillis())

        return version
    }

    /**
     * Check for updates for a list of installed plugins.
     *
     * @param plugins List of installed plugins to check
     * @return List of PluginUpdate objects for plugins that have updates available
     */
    fun checkForUpdates(plugins: List<InstalledPlugin>): List<PluginUpdate> {
        val updates = mutableListOf<PluginUpdate>()

        for (plugin in plugins) {
            // Skip plugins without versions
            if (plugin.version == null) continue

            val latestVersion = getLatestVersion(plugin.pluginId)
            if (latestVersion != null && latestVersion != plugin.version) {
                // Compare versions - only add if latest is actually newer
                if (isNewerVersion(latestVersion, plugin.version)) {
                    updates.add(PluginUpdate(plugin, latestVersion))
                }
            }
        }

        return updates
    }

    /**
     * Get enriched portal info for a plugin (version, website URL, description).
     * Returns cached value if available.
     */
    fun getPluginPortalInfo(pluginId: String): PluginPortalInfo? {
        val cached = portalInfoCache[pluginId]
        if (cached != null && System.currentTimeMillis() - cached.timestamp < CACHE_TTL_MS) {
            return cached.info
        }

        val info = fetchPluginPortalInfo(pluginId)
        portalInfoCache[pluginId] = CachedPortalInfo(info, System.currentTimeMillis())
        return info
    }

    /**
     * Fetch enriched info from the Gradle Plugin Portal page.
     */
    private fun fetchPluginPortalInfo(pluginId: String): PluginPortalInfo? {
        return try {
            val url = "$GRADLE_PLUGIN_PORTAL_URL/$pluginId"
            val document = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(TIMEOUT_MS)
                .get()

            val version = extractVersionFromDocument(document)
            val description = document.select("meta[name=description]").attr("content")
                .takeIf { it.isNotBlank() }

            // Extract website/VCS URL from the page
            val websiteUrl = document.select("a[href*='github.com'], a[href*='gitlab.com']").firstOrNull()?.attr("href")
                ?: document.select("a.plugin-link, a[rel='noopener']").firstOrNull()?.attr("href")

            PluginPortalInfo(
                latestVersion = version,
                websiteUrl = websiteUrl,
                description = description
            )
        } catch (e: Exception) {
            log.debug("Failed to fetch portal info for $pluginId: ${e.message}")
            null
        }
    }

    /**
     * Fetch the latest version from the Gradle Plugin Portal.
     */
    private fun fetchLatestVersion(pluginId: String): String? {
        return try {
            val url = "$GRADLE_PLUGIN_PORTAL_URL/$pluginId"
            val document = Jsoup.connect(url)
                .userAgent(USER_AGENT)
                .timeout(TIMEOUT_MS)
                .get()

            extractVersionFromDocument(document)

        } catch (e: IOException) {
            log.debug("Failed to fetch plugin version for $pluginId: ${e.message}")
            null
        } catch (e: Exception) {
            log.warn("Unexpected error fetching plugin version for $pluginId", e)
            null
        }
    }

    /**
     * Extract version from a parsed Gradle Plugin Portal document.
     */
    private fun extractVersionFromDocument(document: org.jsoup.nodes.Document): String? {
        // The plugin page has a "latest version" badge or version info
        // Try multiple selectors as the page structure may vary

        // Try the version badge
        var version = document.select(".version-info .latest-version").text().takeIf { it.isNotBlank() }

        // Try the version header
        if (version == null) {
            version = document.select("h3.version").text()
                .removePrefix("Version ")
                .takeIf { it.isNotBlank() }
        }

        // Try the version in the usage block
        if (version == null) {
            val usageCode = document.select("pre code.kotlin, pre code.groovy").text()
            val versionPattern = Regex("""version\s*["']([^"']+)["']""")
            version = versionPattern.find(usageCode)?.groupValues?.getOrNull(1)
        }

        // Try meta description
        if (version == null) {
            val metaDesc = document.select("meta[name=description]").attr("content")
            val versionPattern = Regex("""version\s+(\S+)""")
            version = versionPattern.find(metaDesc)?.groupValues?.getOrNull(1)
        }

        return version?.trim()
    }

    /**
     * Simple version comparison - checks if newVersion is newer than currentVersion.
     * Handles common version formats like "1.0.0", "1.0.0-SNAPSHOT", etc.
     */
    private fun isNewerVersion(newVersion: String, currentVersion: String): Boolean {
        // Simple string comparison if versions are identical
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
            // Release versions > release candidates > snapshots
            val newQualifier = newParts.second
            val currentQualifier = currentParts.second

            if (newQualifier == null && currentQualifier != null) {
                // New is release, current has qualifier (e.g., -RC1)
                return true
            }
            if (newQualifier != null && currentQualifier == null) {
                // Current is release, new has qualifier
                return false
            }

            // Both have qualifiers or neither does - string compare
            return newVersion > currentVersion

        } catch (e: Exception) {
            // Fall back to string comparison
            return newVersion > currentVersion
        }
    }

    /**
     * Parse version string into numeric parts and qualifier.
     * E.g., "1.2.3-RC1" -> Pair([1, 2, 3], "RC1")
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
        portalInfoCache.clear()
    }

    /**
     * Get cache statistics for debugging.
     */
    fun getCacheStats(): Map<String, Any> {
        return mapOf(
            "size" to versionCache.size,
            "entries" to versionCache.keys.toList()
        )
    }
}
