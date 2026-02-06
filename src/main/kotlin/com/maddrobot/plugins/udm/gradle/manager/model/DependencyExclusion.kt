package com.maddrobot.plugins.udm.gradle.manager.model

/**
 * Represents a dependency exclusion (transitive dependency to exclude).
 * Used for both Gradle and Maven dependency exclusion management.
 */
data class DependencyExclusion(
    val groupId: String,
    val artifactId: String? = null  // null = exclude all artifacts from this group
) {
    val id: String get() = if (artifactId != null) "$groupId:$artifactId" else groupId
    val displayName: String get() = if (artifactId != null) "$groupId:$artifactId" else "$groupId:*"
}
