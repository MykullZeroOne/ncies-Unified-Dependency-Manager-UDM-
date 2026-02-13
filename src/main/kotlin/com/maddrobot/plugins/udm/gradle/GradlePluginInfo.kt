package com.maddrobot.plugins.udm.gradle

/**
 * Represents information about a Gradle plugin retrieved from the Gradle Plugin Portal.
 *
 * @property pluginName The name of the plugin.
 * @property latestVersion The latest released version of the plugin.
 * @property releaseDate The release date of the latest version in string format.
 * @property description A brief description of the plugin.
 */
data class GradlePluginInfo(
    val pluginName: String,
    val latestVersion: String,
    val releaseDate: String,
    val description: String,
)
