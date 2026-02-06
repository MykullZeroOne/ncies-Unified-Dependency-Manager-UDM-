package com.maddrobot.plugins.udm.gradle.manager

import com.maddrobot.plugins.udm.gradle.manager.model.DependencyExclusion

/**
 * Represents a dependency installed in a Gradle project.
 */
data class InstalledDependency(
    val groupId: String,
    val artifactId: String,
    val version: String,
    val configuration: String,
    val moduleName: String,
    val buildFile: String,
    val isFromVersionCatalog: Boolean = false,
    val catalogKey: String? = null,
    val offset: Int = -1,
    val length: Int = 0,
    val exclusions: List<DependencyExclusion> = emptyList()
) {
    val id: String get() = "$groupId:$artifactId"
    val fullName: String get() = "$groupId:$artifactId:$version"
}
