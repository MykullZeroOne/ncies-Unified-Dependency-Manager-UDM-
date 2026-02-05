package com.maddrobot.plugins.udm.maven.manager

/**
 * Represents a plugin installed in a Maven pom.xml file.
 * Maven plugins are defined in the <build>/<plugins> or <build>/<pluginManagement> sections.
 */
data class MavenInstalledPlugin(
    val groupId: String,            // e.g., "org.apache.maven.plugins"
    val artifactId: String,         // e.g., "maven-compiler-plugin"
    val version: String?,           // Can be null if inherited from parent or pluginManagement
    val moduleName: String,         // Which module this plugin is in
    val pomFile: String,            // Full path to pom.xml
    val offset: Int = -1,           // Character offset in the file (for targeted edits)
    val length: Int = 0,            // Length of the plugin declaration
    val phase: String? = null,      // Execution phase (e.g., "compile", "test")
    val goals: List<String> = emptyList(),  // Plugin goals (e.g., ["compile", "testCompile"])
    val inherited: Boolean = true,  // Whether this plugin is inherited by child modules
    val isFromPluginManagement: Boolean = false,  // Whether this is from pluginManagement section
    val configuration: Map<String, String> = emptyMap()  // Current <configuration> key-value pairs
) {
    /**
     * Unique identifier for the plugin.
     */
    val id: String get() = "$groupId:$artifactId"

    /**
     * Full name including version if available.
     */
    val fullName: String get() = if (version != null) "$groupId:$artifactId:$version" else id

    /**
     * Display name for UI (uses artifactId as primary identifier).
     */
    val displayName: String get() = artifactId

    /**
     * Common Maven plugin group ID.
     */
    companion object {
        const val MAVEN_PLUGINS_GROUP = "org.apache.maven.plugins"
        const val CODEHAUS_PLUGINS_GROUP = "org.codehaus.mojo"
    }
}

/**
 * Represents an available update for a Maven plugin.
 */
data class MavenPluginUpdate(
    val installed: MavenInstalledPlugin,
    val latestVersion: String
) {
    val hasUpdate: Boolean get() = installed.version != null && installed.version != latestVersion
}
