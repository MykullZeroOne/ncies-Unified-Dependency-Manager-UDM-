package com.maddrobot.plugins.udm.gradle.manager

/**
 * Represents the parsed configuration of a Gradle plugin extension block in a build file.
 */
data class GradlePluginConfig(
    val extensionName: String,
    val properties: List<ConfigProperty>,
    val rawText: String?,
    val blockOffset: Int,
    val blockLength: Int
)

/**
 * A single property within a Gradle plugin extension block.
 */
data class ConfigProperty(
    val name: String,
    val value: String,
    val isNested: Boolean = false,
    val nestingPath: String? = null
)

/**
 * Maps known Gradle plugin IDs to their extension block names.
 * Used to pre-populate the extension name field in the configuration dialog.
 */
object KnownPluginExtensions {
    val PLUGIN_TO_EXTENSION: Map<String, String> = mapOf(
        "org.jetbrains.kotlin.jvm" to "kotlin",
        "org.jetbrains.kotlin.android" to "kotlin",
        "com.google.devtools.ksp" to "ksp",
        "jacoco" to "jacoco",
        "application" to "application",
        "java" to "java",
        "java-library" to "java",
        "maven-publish" to "publishing",
        "signing" to "signing",
        "com.github.johnrengelman.shadow" to "shadowJar",
        "com.google.protobuf" to "protobuf",
        "org.springframework.boot" to "springBoot",
        "com.android.application" to "android",
        "com.android.library" to "android"
    )

    fun getExtensionName(pluginId: String): String? = PLUGIN_TO_EXTENSION[pluginId]
}
