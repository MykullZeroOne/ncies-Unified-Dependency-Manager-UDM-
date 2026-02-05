package com.maddrobot.plugins.udm.gradle.manager

/**
 * Represents an installed Gradle plugin parsed from build.gradle or build.gradle.kts files.
 */
data class InstalledPlugin(
    val pluginId: String,           // e.g., "com.google.devtools.ksp", "org.jetbrains.kotlin.jvm"
    val version: String?,           // null for version-less plugins (e.g., `java-library`)
    val moduleName: String,         // Which module this plugin is in
    val buildFile: String,          // Full path to build.gradle or build.gradle.kts
    val offset: Int,                // Character offset in the file (for targeted edits)
    val length: Int,                // Length of the plugin declaration
    val pluginSyntax: PluginSyntax, // Which syntax variant is used
    val isKotlinShorthand: Boolean = false,  // true for kotlin("jvm") style
    val isApplied: Boolean = true   // true if plugin is applied (vs just declared)
) {
    /**
     * Unique identifier for the plugin (just the pluginId since versions may differ).
     */
    val id: String get() = pluginId

    /**
     * Full name including version if available.
     */
    val fullName: String get() = if (version != null) "$pluginId:$version" else pluginId

    /**
     * Display name for UI (uses short name if kotlin shorthand).
     */
    val displayName: String get() = when {
        isKotlinShorthand -> pluginId.removePrefix("org.jetbrains.kotlin.")
        else -> pluginId
    }
}

/**
 * Represents the various syntax forms for declaring Gradle plugins.
 */
enum class PluginSyntax {
    /**
     * Standard Kotlin DSL: id("com.example.plugin") version "1.0.0"
     */
    ID_VERSION,

    /**
     * Kotlin DSL without version: id("com.example.plugin")
     */
    ID_ONLY,

    /**
     * Kotlin shorthand: kotlin("jvm") version "1.9.0"
     */
    KOTLIN_SHORTHAND,

    /**
     * Backtick syntax for built-in plugins: `java-library`
     */
    BACKTICK,

    /**
     * Groovy DSL with version: id 'com.example.plugin' version '1.0.0'
     */
    GROOVY_ID_VERSION,

    /**
     * Groovy DSL without version: id 'com.example.plugin'
     */
    GROOVY_ID_ONLY,

    /**
     * Groovy shorthand for built-in plugins: java or 'java-library'
     */
    GROOVY_SHORTHAND,

    /**
     * Legacy apply plugin: apply plugin: 'com.example.plugin'
     */
    LEGACY_APPLY
}

/**
 * Represents an available update for a Gradle plugin.
 */
data class PluginUpdate(
    val installed: InstalledPlugin,
    val latestVersion: String
) {
    val hasUpdate: Boolean get() = installed.version != null && installed.version != latestVersion
}
