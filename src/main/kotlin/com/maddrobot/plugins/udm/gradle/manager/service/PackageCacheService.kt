package com.maddrobot.plugins.udm.gradle.manager.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Caching service for package information to reduce network calls and handle timeouts gracefully.
 * Provides TTL-based caching for:
 * - Latest versions (from Maven Central, etc.)
 * - Search results
 * - Package metadata
 */
@Service(Service.Level.PROJECT)
class PackageCacheService(private val project: Project) {
    private val log = Logger.getInstance(javaClass)

    companion object {
        fun getInstance(project: Project): PackageCacheService =
            project.getService(PackageCacheService::class.java)

        // Default TTL values (in seconds)
        const val VERSION_CACHE_TTL_SECONDS = 3600L      // 1 hour for version lookups
        const val SEARCH_CACHE_TTL_SECONDS = 300L        // 5 minutes for search results
        const val METADATA_CACHE_TTL_SECONDS = 86400L    // 24 hours for package metadata
    }

    // Cache entry with timestamp
    data class CacheEntry<T>(
        val value: T,
        val timestamp: Instant,
        val ttlSeconds: Long
    ) {
        fun isExpired(): Boolean = Instant.now().isAfter(timestamp.plusSeconds(ttlSeconds))
    }

    // Version cache: "groupId:artifactId" -> version
    private val versionCache = ConcurrentHashMap<String, CacheEntry<String?>>()

    // Search results cache: "repoId:query" -> list of results (as JSON strings for flexibility)
    private val searchCache = ConcurrentHashMap<String, CacheEntry<String>>()

    // Package metadata cache: "groupId:artifactId:version" -> metadata JSON
    private val metadataCache = ConcurrentHashMap<String, CacheEntry<String>>()

    // Statistics
    data class CacheStats(
        val versionCacheSize: Int,
        val versionCacheHits: Long,
        val versionCacheMisses: Long,
        val searchCacheSize: Int,
        val searchCacheHits: Long,
        val searchCacheMisses: Long,
        val metadataCacheSize: Int,
        val metadataCacheHits: Long,
        val metadataCacheMisses: Long
    ) {
        val totalSize: Int get() = versionCacheSize + searchCacheSize + metadataCacheSize
        val totalHits: Long get() = versionCacheHits + searchCacheHits + metadataCacheHits
        val totalMisses: Long get() = versionCacheMisses + searchCacheMisses + metadataCacheMisses
        val hitRate: Double get() = if (totalHits + totalMisses > 0) {
            totalHits.toDouble() / (totalHits + totalMisses)
        } else 0.0
    }

    private var versionHits = 0L
    private var versionMisses = 0L
    private var searchHits = 0L
    private var searchMisses = 0L
    private var metadataHits = 0L
    private var metadataMisses = 0L

    // ========== Version Cache ==========

    /**
     * Get cached latest version for a dependency.
     * Returns null if not cached or expired.
     */
    fun getCachedVersion(groupId: String, artifactId: String): String? {
        val key = "$groupId:$artifactId"
        val entry = versionCache[key]

        return if (entry != null && !entry.isExpired()) {
            versionHits++
            log.debug("Version cache HIT for $key")
            entry.value
        } else {
            versionMisses++
            if (entry != null) {
                log.debug("Version cache EXPIRED for $key")
                versionCache.remove(key)
            }
            null
        }
    }

    /**
     * Cache the latest version for a dependency.
     */
    fun cacheVersion(groupId: String, artifactId: String, version: String?) {
        val key = "$groupId:$artifactId"
        versionCache[key] = CacheEntry(version, Instant.now(), VERSION_CACHE_TTL_SECONDS)
        log.debug("Cached version for $key: $version")
    }

    /**
     * Check if a version is cached (even if null/not found).
     */
    fun hasVersionCached(groupId: String, artifactId: String): Boolean {
        val key = "$groupId:$artifactId"
        val entry = versionCache[key]
        return entry != null && !entry.isExpired()
    }

    // ========== Search Results Cache ==========

    /**
     * Get cached search results for a query.
     */
    fun getCachedSearchResults(repositoryId: String, query: String): String? {
        val key = "$repositoryId:$query"
        val entry = searchCache[key]

        return if (entry != null && !entry.isExpired()) {
            searchHits++
            log.debug("Search cache HIT for $key")
            entry.value
        } else {
            searchMisses++
            if (entry != null) {
                searchCache.remove(key)
            }
            null
        }
    }

    /**
     * Cache search results for a query.
     */
    fun cacheSearchResults(repositoryId: String, query: String, resultsJson: String) {
        val key = "$repositoryId:$query"
        searchCache[key] = CacheEntry(resultsJson, Instant.now(), SEARCH_CACHE_TTL_SECONDS)
        log.debug("Cached search results for $key (${resultsJson.length} chars)")
    }

    // ========== Metadata Cache ==========

    /**
     * Get cached package metadata.
     */
    fun getCachedMetadata(groupId: String, artifactId: String, version: String): String? {
        val key = "$groupId:$artifactId:$version"
        val entry = metadataCache[key]

        return if (entry != null && !entry.isExpired()) {
            metadataHits++
            log.debug("Metadata cache HIT for $key")
            entry.value
        } else {
            metadataMisses++
            if (entry != null) {
                metadataCache.remove(key)
            }
            null
        }
    }

    /**
     * Cache package metadata.
     */
    fun cacheMetadata(groupId: String, artifactId: String, version: String, metadataJson: String) {
        val key = "$groupId:$artifactId:$version"
        metadataCache[key] = CacheEntry(metadataJson, Instant.now(), METADATA_CACHE_TTL_SECONDS)
        log.debug("Cached metadata for $key")
    }

    // ========== Cache Management ==========

    /**
     * Get current cache statistics.
     */
    fun getStats(): CacheStats {
        // Clean expired entries first
        cleanExpired()

        return CacheStats(
            versionCacheSize = versionCache.size,
            versionCacheHits = versionHits,
            versionCacheMisses = versionMisses,
            searchCacheSize = searchCache.size,
            searchCacheHits = searchHits,
            searchCacheMisses = searchMisses,
            metadataCacheSize = metadataCache.size,
            metadataCacheHits = metadataHits,
            metadataCacheMisses = metadataMisses
        )
    }

    /**
     * Clear all caches.
     */
    fun clearAll() {
        versionCache.clear()
        searchCache.clear()
        metadataCache.clear()
        resetStats()
        log.info("All caches cleared")
    }

    /**
     * Clear only the version cache.
     */
    fun clearVersionCache() {
        versionCache.clear()
        versionHits = 0
        versionMisses = 0
        log.info("Version cache cleared")
    }

    /**
     * Clear only the search cache.
     */
    fun clearSearchCache() {
        searchCache.clear()
        searchHits = 0
        searchMisses = 0
        log.info("Search cache cleared")
    }

    /**
     * Clear only the metadata cache.
     */
    fun clearMetadataCache() {
        metadataCache.clear()
        metadataHits = 0
        metadataMisses = 0
        log.info("Metadata cache cleared")
    }

    /**
     * Remove expired entries from all caches.
     */
    fun cleanExpired() {
        val versionRemoved = versionCache.entries.removeIf { it.value.isExpired() }
        val searchRemoved = searchCache.entries.removeIf { it.value.isExpired() }
        val metadataRemoved = metadataCache.entries.removeIf { it.value.isExpired() }

        if (versionRemoved || searchRemoved || metadataRemoved) {
            log.debug("Cleaned expired cache entries")
        }
    }

    private fun resetStats() {
        versionHits = 0
        versionMisses = 0
        searchHits = 0
        searchMisses = 0
        metadataHits = 0
        metadataMisses = 0
    }

    /**
     * Get a formatted summary of the cache for display.
     */
    fun getFormattedSummary(): String {
        val stats = getStats()
        return buildString {
            appendLine("=== Package Cache Statistics ===")
            appendLine()
            appendLine("Version Cache:")
            appendLine("  Entries: ${stats.versionCacheSize}")
            appendLine("  Hits: ${stats.versionCacheHits}, Misses: ${stats.versionCacheMisses}")
            appendLine("  TTL: ${VERSION_CACHE_TTL_SECONDS / 60} minutes")
            appendLine()
            appendLine("Search Cache:")
            appendLine("  Entries: ${stats.searchCacheSize}")
            appendLine("  Hits: ${stats.searchCacheHits}, Misses: ${stats.searchCacheMisses}")
            appendLine("  TTL: ${SEARCH_CACHE_TTL_SECONDS / 60} minutes")
            appendLine()
            appendLine("Metadata Cache:")
            appendLine("  Entries: ${stats.metadataCacheSize}")
            appendLine("  Hits: ${stats.metadataCacheHits}, Misses: ${stats.metadataCacheMisses}")
            appendLine("  TTL: ${METADATA_CACHE_TTL_SECONDS / 3600} hours")
            appendLine()
            appendLine("Overall Hit Rate: ${String.format("%.1f", stats.hitRate * 100)}%")
        }
    }
}
