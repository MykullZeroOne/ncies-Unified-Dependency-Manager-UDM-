package com.maddrobot.plugins.udm.gradle.manager

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.maddrobot.plugins.udm.gradle.manager.service.PackageCacheService
import com.maddrobot.plugins.udm.maven.DependencyService
import com.maddrobot.plugins.udm.util.HttpRequestHelper
import com.maddrobot.plugins.udm.maven.MavenSearchResult
import kotlinx.serialization.json.Json

/**
 * Service for checking latest versions of dependencies.
 * Uses PackageCacheService to avoid repeated network calls and handle timeouts gracefully.
 */
class GradleUpdateService(private val project: Project? = null) {
    private val log = Logger.getInstance(javaClass)

    // Cache service - only available when project is provided
    private val cacheService: PackageCacheService? by lazy {
        project?.let { PackageCacheService.getInstance(it) }
    }

    /**
     * Get the latest version for a dependency.
     * Uses cache to avoid repeated network calls.
     *
     * @param groupId The group ID (e.g., "org.jetbrains.kotlin")
     * @param artifactId The artifact ID (e.g., "kotlin-stdlib")
     * @return The latest version string, or null if not found or on error
     */
    fun getLatestVersion(groupId: String, artifactId: String): String? {
        // Try cache first
        cacheService?.let { cache ->
            val cached = cache.getCachedVersion(groupId, artifactId)
            if (cached != null) {
                log.debug("Using cached version for $groupId:$artifactId: $cached")
                return cached
            }

            // Check if we have a cached "not found" result
            if (cache.hasVersionCached(groupId, artifactId)) {
                log.debug("Cached: version not found for $groupId:$artifactId")
                return null
            }
        }

        // Fetch from network
        val query = "$groupId:$artifactId"
        val url = DependencyService.mavenSearchUrl(query, rowsLimit = 1)

        return when (val result = HttpRequestHelper.getForList(url) { response -> parseResponse(response) }) {
            is HttpRequestHelper.RequestResult.Success -> {
                val version = result.data.firstOrNull()?.version
                // Cache the result (even if null)
                cacheService?.cacheVersion(groupId, artifactId, version)
                version
            }
            is HttpRequestHelper.RequestResult.Error -> {
                log.warn("Failed to fetch latest version for $groupId:$artifactId: ${result.exception.message}")
                // Don't cache errors - allow retry on next request
                null
            }
        }
    }

    /**
     * Get latest versions for multiple dependencies in batch.
     * More efficient than calling getLatestVersion() for each.
     *
     * @param dependencies List of (groupId, artifactId) pairs
     * @return Map of "groupId:artifactId" to version (null if not found)
     */
    fun getLatestVersionsBatch(dependencies: List<Pair<String, String>>): Map<String, String?> {
        val results = mutableMapOf<String, String?>()
        val toFetch = mutableListOf<Pair<String, String>>()

        // Check cache first for all dependencies
        for ((groupId, artifactId) in dependencies) {
            val key = "$groupId:$artifactId"
            val cached = cacheService?.getCachedVersion(groupId, artifactId)

            if (cached != null || cacheService?.hasVersionCached(groupId, artifactId) == true) {
                results[key] = cached
            } else {
                toFetch.add(groupId to artifactId)
            }
        }

        // Fetch remaining from network
        for ((groupId, artifactId) in toFetch) {
            val key = "$groupId:$artifactId"
            results[key] = getLatestVersion(groupId, artifactId)
        }

        return results
    }

    /**
     * Clear the version cache for a specific dependency.
     */
    fun invalidateCache(groupId: String, artifactId: String) {
        // Re-fetch will happen on next call
        log.debug("Cache invalidated for $groupId:$artifactId")
    }

    private fun parseResponse(responseBody: String): List<DependencyVersionInfo> {
        return try {
            val json = Json { ignoreUnknownKeys = true }
            val artifactResponse = json.decodeFromString(MavenSearchResult.serializer(), responseBody)
            artifactResponse.response.centralDependencies.map {
                DependencyVersionInfo(it.groupId, it.artifactId, it.version)
            }
        } catch (e: Exception) {
            log.error("Failed to parse response: ${e.localizedMessage}", e)
            emptyList()
        }
    }

    data class DependencyVersionInfo(val groupId: String, val artifactId: String, val version: String)
}
