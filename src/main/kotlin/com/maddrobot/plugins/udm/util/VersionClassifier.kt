package com.maddrobot.plugins.udm.util

/**
 * Utility for classifying and comparing version strings.
 */
object VersionClassifier {
    private val prereleaseTokens = listOf(
        "alpha",
        "beta",
        "-rc",
        ".rc",
        "snapshot",
        "-m",
        ".m",
        "-dev",
        "-pre"
    )

    fun isPrerelease(version: String?): Boolean {
        if (version.isNullOrBlank()) return false
        val lowerVersion = version.lowercase()
        return prereleaseTokens.any { lowerVersion.contains(it) }
    }

    fun isNewerThan(newVersion: String, currentVersion: String): Boolean {
        return compareVersions(newVersion, currentVersion) > 0
    }

    fun selectLatestStable(versions: List<String>): String? {
        val stableVersions = versions.filterNot { isPrerelease(it) }
        if (stableVersions.isEmpty()) return null
        return stableVersions.maxWithOrNull { a, b -> compareVersions(a, b) }
    }

    fun selectLatest(versions: List<String>): String? {
        if (versions.isEmpty()) return null
        return versions.maxWithOrNull { a, b -> compareVersions(a, b) }
    }

    fun compareVersions(v1: String, v2: String): Int {
        val parts1 = v1.split("[.\\-_]".toRegex())
            .mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
        val parts2 = v2.split("[.\\-_]".toRegex())
            .mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }

        for (i in 0 until maxOf(parts1.size, parts2.size)) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) return p1.compareTo(p2)
        }

        return v1.compareTo(v2)
    }
}
