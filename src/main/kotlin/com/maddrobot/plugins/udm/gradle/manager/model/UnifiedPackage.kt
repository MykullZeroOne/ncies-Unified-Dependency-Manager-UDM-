package com.maddrobot.plugins.udm.gradle.manager.model

import com.maddrobot.plugins.udm.gradle.manager.DependencyUpdate
import com.maddrobot.plugins.udm.gradle.manager.InstalledDependency
import com.maddrobot.plugins.udm.gradle.manager.InstalledPlugin
import com.maddrobot.plugins.udm.gradle.manager.PluginSyntax
import com.maddrobot.plugins.udm.gradle.manager.PluginUpdate
import com.maddrobot.plugins.udm.maven.CentralDependency
import com.maddrobot.plugins.udm.maven.Dependency
import com.maddrobot.plugins.udm.maven.manager.MavenInstalledDependency
import com.maddrobot.plugins.udm.maven.manager.MavenInstalledPlugin
import com.maddrobot.plugins.udm.maven.manager.MavenPluginUpdate
import com.maddrobot.plugins.udm.npm.NpmObject

/**
 * Unified package model that abstracts dependencies from different sources
 * (Gradle, Maven Central, NPM, Nexus, etc.) into a single representation.
 */
/**
 * Represents a repository where a package is available.
 */
data class AvailableRepository(
    val id: String,
    val name: String,
    val url: String,
    val version: String?
)

/**
 * Information about a security vulnerability.
 */
data class VulnerabilityInfo(
    val cveId: String?,                // CVE identifier (e.g., CVE-2023-12345)
    val severity: VulnerabilitySeverity,
    val description: String?,
    val affectedVersions: String?,     // Version range affected
    val fixedVersion: String?,         // Version that fixes the vulnerability
    val advisoryUrl: String?           // Link to security advisory
)

/**
 * Vulnerability severity levels.
 */
enum class VulnerabilitySeverity {
    CRITICAL,
    HIGH,
    MEDIUM,
    LOW,
    UNKNOWN
}

/**
 * Package status information for UI display.
 */
data class PackageStatus(
    val hasUpdate: Boolean = false,
    val isVulnerable: Boolean = false,
    val isTransitive: Boolean = false,
    val isDeprecated: Boolean = false,
    val isPrerelease: Boolean = false,
    val isPinned: Boolean = false,        // Version is pinned/locked
    val updateVersion: String? = null,
    val vulnerabilityInfo: VulnerabilityInfo? = null,
    val deprecationMessage: String? = null
)

data class UnifiedPackage(
    val name: String,               // artifactId or package name
    val publisher: String,          // groupId or publisher
    val installedVersion: String?,  // null if not installed
    val latestVersion: String?,     // null if unknown
    val description: String?,
    val homepage: String?,
    val license: String?,
    val scope: String?,             // configuration (Gradle) or scope (Maven)
    val modules: List<String>,      // which modules use this dependency
    val source: PackageSource,
    val metadata: PackageMetadata,
    val isTransitive: Boolean = false,  // true if this is a transitive (indirect) dependency
    val availableRepositories: List<AvailableRepository> = emptyList(),  // repos where this package is available
    // New status fields
    val isDeprecated: Boolean = false,
    val deprecationMessage: String? = null,
    val vulnerabilityInfo: VulnerabilityInfo? = null,
    val isPinned: Boolean = false         // true if version is locked/pinned
) {
    val id: String get() = "$publisher:$name"
    val displayName: String get() = name
    val hasUpdate: Boolean get() = installedVersion != null && latestVersion != null && installedVersion != latestVersion
    val isInstalled: Boolean get() = installedVersion != null
    val hasMultipleRepositories: Boolean get() = availableRepositories.size > 1
    val isVulnerable: Boolean get() = vulnerabilityInfo != null

    /**
     * Check if the installed version is a prerelease (alpha, beta, RC, SNAPSHOT, etc.)
     */
    val isPrerelease: Boolean get() {
        val version = installedVersion ?: latestVersion ?: return false
        val lowerVersion = version.lowercase()
        return lowerVersion.contains("alpha") ||
            lowerVersion.contains("beta") ||
            lowerVersion.contains("-rc") ||
            lowerVersion.contains(".rc") ||
            lowerVersion.contains("snapshot") ||
            lowerVersion.contains("-m") ||    // Milestone releases
            lowerVersion.contains(".m") ||
            lowerVersion.contains("-dev") ||
            lowerVersion.contains("-pre")
    }

    /**
     * Get comprehensive status information for UI display.
     */
    fun getStatus(): PackageStatus {
        return PackageStatus(
            hasUpdate = hasUpdate,
            isVulnerable = isVulnerable,
            isTransitive = isTransitive,
            isDeprecated = isDeprecated,
            isPrerelease = isPrerelease,
            isPinned = isPinned,
            updateVersion = if (hasUpdate) latestVersion else null,
            vulnerabilityInfo = vulnerabilityInfo,
            deprecationMessage = deprecationMessage
        )
    }
}

/**
 * Identifies the source/type of the package.
 */
enum class PackageSource {
    GRADLE_INSTALLED,           // Installed via Gradle
    MAVEN_INSTALLED,            // Installed via Maven (pom.xml)
    MAVEN_CENTRAL,              // Search result from Maven Central
    NPM,                        // Search result from NPM Registry
    NEXUS,                      // Search result from Nexus
    LOCAL_MAVEN,                // From local Maven repository
    GRADLE_PLUGIN,              // Gradle plugin from Plugin Portal (search result)
    GRADLE_PLUGIN_INSTALLED,    // Installed Gradle plugin (from plugins {} block)
    MAVEN_PLUGIN_INSTALLED      // Installed Maven plugin (from pom.xml build/plugins)
}

/**
 * Source-specific metadata for packages.
 */
sealed class PackageMetadata {

    /**
     * Metadata for dependencies installed via Gradle.
     */
    data class GradleMetadata(
        val buildFile: String,
        val offset: Int,
        val length: Int,
        val isFromVersionCatalog: Boolean,
        val catalogKey: String?,
        val configuration: String
    ) : PackageMetadata()

    /**
     * Metadata for dependencies installed via Maven (pom.xml).
     */
    data class MavenInstalledMetadata(
        val pomFile: String,
        val offset: Int,
        val length: Int,
        val scope: String,
        val optional: Boolean
    ) : PackageMetadata()

    /**
     * Metadata for packages from Maven Central.
     */
    data class MavenMetadata(
        val packaging: String?,
        val timestamp: Long?,
        val extensions: List<String>
    ) : PackageMetadata()

    /**
     * Metadata for packages from NPM Registry.
     */
    data class NpmMetadata(
        val monthlyDownloads: Int?,
        val weeklyDownloads: Int?,
        val publisherEmail: String?,
        val publisherUsername: String?,
        val dependents: Int?
    ) : PackageMetadata()

    /**
     * Metadata for packages from Nexus repository.
     */
    data class NexusMetadata(
        val uploaderIp: String?,
        val downloadUrls: Map<String, String>  // extension -> downloadUrl
    ) : PackageMetadata()

    /**
     * Empty metadata for packages with no additional info.
     */
    data object Empty : PackageMetadata()

    /**
     * Metadata for installed Gradle plugins (from plugins {} block).
     */
    data class GradlePluginMetadata(
        val buildFile: String,
        val offset: Int,
        val length: Int,
        val pluginSyntax: PluginSyntax,
        val isKotlinShorthand: Boolean,
        val isApplied: Boolean
    ) : PackageMetadata()

    /**
     * Metadata for installed Maven plugins (from pom.xml build/plugins).
     */
    data class MavenPluginMetadata(
        val pomFile: String,
        val offset: Int,
        val length: Int,
        val phase: String?,
        val goals: List<String>,
        val inherited: Boolean,
        val isFromPluginManagement: Boolean,
        val configuration: Map<String, String> = emptyMap()
    ) : PackageMetadata()
}

/**
 * Adapters for converting various package types to UnifiedPackage.
 */
object PackageAdapters {

    /**
     * Convert an installed Gradle dependency to a UnifiedPackage.
     * @param dep The installed dependency
     * @param update Optional update information if an update is available
     */
    fun fromInstalledDependency(dep: InstalledDependency, update: DependencyUpdate? = null): UnifiedPackage {
        return UnifiedPackage(
            name = dep.artifactId,
            publisher = dep.groupId,
            installedVersion = dep.version,
            latestVersion = update?.latestVersion,
            description = null,
            homepage = null,
            license = null,
            scope = dep.configuration,
            modules = listOf(dep.moduleName),
            source = PackageSource.GRADLE_INSTALLED,
            metadata = PackageMetadata.GradleMetadata(
                buildFile = dep.buildFile,
                offset = dep.offset,
                length = dep.length,
                isFromVersionCatalog = dep.isFromVersionCatalog,
                catalogKey = dep.catalogKey,
                configuration = dep.configuration
            )
        )
    }

    /**
     * Convert an installed Maven dependency to a UnifiedPackage.
     * @param dep The installed Maven dependency
     * @param latestVersion Optional latest version if known
     */
    fun fromMavenInstalledDependency(dep: MavenInstalledDependency, latestVersion: String? = null): UnifiedPackage {
        return UnifiedPackage(
            name = dep.artifactId,
            publisher = dep.groupId,
            installedVersion = dep.version,
            latestVersion = latestVersion,
            description = null,
            homepage = null,
            license = null,
            scope = dep.scope,
            modules = listOf(dep.moduleName),
            source = PackageSource.MAVEN_INSTALLED,
            metadata = PackageMetadata.MavenInstalledMetadata(
                pomFile = dep.pomFile,
                offset = dep.offset,
                length = dep.length,
                scope = dep.scope,
                optional = dep.optional
            )
        )
    }

    /**
     * Convert a Maven Central dependency to a UnifiedPackage.
     */
    fun fromCentralDependency(dep: CentralDependency): UnifiedPackage {
        return UnifiedPackage(
            name = dep.artifactId,
            publisher = dep.groupId,
            installedVersion = null,
            latestVersion = dep.version,
            description = null,
            homepage = null,
            license = null,
            scope = null,
            modules = emptyList(),
            source = PackageSource.MAVEN_CENTRAL,
            metadata = PackageMetadata.MavenMetadata(
                packaging = dep.packaging,
                timestamp = dep.timestamp,
                extensions = dep.ec
            )
        )
    }

    /**
     * Convert a generic Maven dependency to a UnifiedPackage.
     */
    fun fromDependency(dep: Dependency, source: PackageSource = PackageSource.MAVEN_CENTRAL): UnifiedPackage {
        return when (dep) {
            is CentralDependency -> fromCentralDependency(dep)
            else -> UnifiedPackage(
                name = dep.artifactId,
                publisher = dep.groupId,
                installedVersion = null,
                latestVersion = dep.version,
                description = null,
                homepage = null,
                license = null,
                scope = null,
                modules = emptyList(),
                source = source,
                metadata = PackageMetadata.Empty
            )
        }
    }

    /**
     * Convert an NPM package to a UnifiedPackage.
     */
    fun fromNpmObject(npm: NpmObject): UnifiedPackage {
        val pkg = npm.`package`
        return UnifiedPackage(
            name = pkg.packageName,
            publisher = npm.`package`.publisher?.username ?: "Unknown",
            installedVersion = null,
            latestVersion = pkg.version,
            description = pkg.description.takeIf { it != "N/A" },
            homepage = null,
            license = pkg.license.takeIf { it != "N/A" },
            scope = null,
            modules = emptyList(),
            source = PackageSource.NPM,
            metadata = PackageMetadata.NpmMetadata(
                monthlyDownloads = npm.downloads.monthly,
                weeklyDownloads = npm.downloads.weekly,
                publisherEmail = npm.`package`.publisher?.email,
                publisherUsername = npm.`package`.publisher?.username,
                dependents = npm.dependents
            )
        )
    }

    /**
     * Merge installed dependency information with a search result.
     * This is used when we have both the search result and know the package is installed.
     */
    fun mergeWithInstalled(
        searchResult: UnifiedPackage,
        installed: InstalledDependency,
        modules: List<String> = listOf(installed.moduleName)
    ): UnifiedPackage {
        return searchResult.copy(
            installedVersion = installed.version,
            scope = installed.configuration,
            modules = modules,
            metadata = when (searchResult.metadata) {
                is PackageMetadata.MavenMetadata -> searchResult.metadata
                is PackageMetadata.NpmMetadata -> searchResult.metadata
                else -> PackageMetadata.GradleMetadata(
                    buildFile = installed.buildFile,
                    offset = installed.offset,
                    length = installed.length,
                    isFromVersionCatalog = installed.isFromVersionCatalog,
                    catalogKey = installed.catalogKey,
                    configuration = installed.configuration
                )
            }
        )
    }

    /**
     * Group installed Gradle dependencies by their ID and aggregate modules.
     * This handles cases where the same dependency is used in multiple modules.
     */
    fun aggregateByPackage(
        dependencies: List<InstalledDependency>,
        updates: List<DependencyUpdate>
    ): List<UnifiedPackage> {
        val updateMap = updates.associateBy { it.installed.id }

        return dependencies
            .groupBy { it.id }
            .map { (id, deps) ->
                val firstDep = deps.first()
                val update = updateMap[id]
                val modules = deps.map { it.moduleName }.distinct()

                UnifiedPackage(
                    name = firstDep.artifactId,
                    publisher = firstDep.groupId,
                    installedVersion = firstDep.version,
                    latestVersion = update?.latestVersion,
                    description = null,
                    homepage = null,
                    license = null,
                    scope = firstDep.configuration,
                    modules = modules,
                    source = PackageSource.GRADLE_INSTALLED,
                    metadata = PackageMetadata.GradleMetadata(
                        buildFile = firstDep.buildFile,
                        offset = firstDep.offset,
                        length = firstDep.length,
                        isFromVersionCatalog = firstDep.isFromVersionCatalog,
                        catalogKey = firstDep.catalogKey,
                        configuration = firstDep.configuration
                    )
                )
            }
    }

    /**
     * Group installed Maven dependencies by their ID and aggregate modules.
     */
    fun aggregateMavenByPackage(
        dependencies: List<MavenInstalledDependency>,
        latestVersions: Map<String, String> = emptyMap()
    ): List<UnifiedPackage> {
        return dependencies
            .groupBy { it.id }
            .map { (id, deps) ->
                val firstDep = deps.first()
                val modules = deps.map { it.moduleName }.distinct()
                val latestVersion = latestVersions[id]

                UnifiedPackage(
                    name = firstDep.artifactId,
                    publisher = firstDep.groupId,
                    installedVersion = firstDep.version,
                    latestVersion = latestVersion,
                    description = null,
                    homepage = null,
                    license = null,
                    scope = firstDep.scope,
                    modules = modules,
                    source = PackageSource.MAVEN_INSTALLED,
                    metadata = PackageMetadata.MavenInstalledMetadata(
                        pomFile = firstDep.pomFile,
                        offset = firstDep.offset,
                        length = firstDep.length,
                        scope = firstDep.scope,
                        optional = firstDep.optional
                    )
                )
            }
    }

    /**
     * Merge installed Maven dependency with search result.
     */
    fun mergeWithMavenInstalled(
        searchResult: UnifiedPackage,
        installed: MavenInstalledDependency,
        modules: List<String> = listOf(installed.moduleName)
    ): UnifiedPackage {
        return searchResult.copy(
            installedVersion = installed.version,
            scope = installed.scope,
            modules = modules,
            source = PackageSource.MAVEN_INSTALLED,
            metadata = PackageMetadata.MavenInstalledMetadata(
                pomFile = installed.pomFile,
                offset = installed.offset,
                length = installed.length,
                scope = installed.scope,
                optional = installed.optional
            )
        )
    }

    /**
     * Convert an installed Gradle plugin to a UnifiedPackage.
     * @param plugin The installed plugin
     * @param update Optional update information if an update is available
     */
    fun fromInstalledPlugin(plugin: InstalledPlugin, update: PluginUpdate? = null): UnifiedPackage {
        // For plugins, we use pluginId as 'name' and extract group from pluginId if possible
        val parts = plugin.pluginId.split(".")
        val publisher = if (parts.size > 2) parts.dropLast(1).joinToString(".") else plugin.pluginId
        val name = if (parts.size > 2) parts.last() else plugin.pluginId

        return UnifiedPackage(
            name = plugin.pluginId,  // Use full pluginId as name for clarity
            publisher = publisher,
            installedVersion = plugin.version,
            latestVersion = update?.latestVersion,
            description = null,
            homepage = "https://plugins.gradle.org/plugin/${plugin.pluginId}",
            license = null,
            scope = "plugin",
            modules = listOf(plugin.moduleName),
            source = PackageSource.GRADLE_PLUGIN_INSTALLED,
            metadata = PackageMetadata.GradlePluginMetadata(
                buildFile = plugin.buildFile,
                offset = plugin.offset,
                length = plugin.length,
                pluginSyntax = plugin.pluginSyntax,
                isKotlinShorthand = plugin.isKotlinShorthand,
                isApplied = plugin.isApplied
            )
        )
    }

    /**
     * Convert an installed Maven plugin to a UnifiedPackage.
     * @param plugin The installed Maven plugin
     * @param update Optional update information if an update is available
     */
    fun fromMavenInstalledPlugin(plugin: MavenInstalledPlugin, update: MavenPluginUpdate? = null): UnifiedPackage {
        val homepage = when {
            plugin.groupId.startsWith("org.apache.maven.plugins") ->
                "https://maven.apache.org/plugins/${plugin.artifactId}/"
            else ->
                "https://search.maven.org/artifact/${plugin.groupId}/${plugin.artifactId}"
        }
        return UnifiedPackage(
            name = plugin.artifactId,
            publisher = plugin.groupId,
            installedVersion = plugin.version,
            latestVersion = update?.latestVersion,
            description = null,
            homepage = homepage,
            license = null,
            scope = "plugin",
            modules = listOf(plugin.moduleName),
            source = PackageSource.MAVEN_PLUGIN_INSTALLED,
            metadata = PackageMetadata.MavenPluginMetadata(
                pomFile = plugin.pomFile,
                offset = plugin.offset,
                length = plugin.length,
                phase = plugin.phase,
                goals = plugin.goals,
                inherited = plugin.inherited,
                isFromPluginManagement = plugin.isFromPluginManagement,
                configuration = plugin.configuration
            )
        )
    }

    /**
     * Group installed Gradle plugins by their ID and aggregate modules.
     * This handles cases where the same plugin is used in multiple modules.
     */
    fun aggregatePluginsByPackage(
        plugins: List<InstalledPlugin>,
        updates: List<PluginUpdate>
    ): List<UnifiedPackage> {
        val updateMap = updates.associateBy { it.installed.pluginId }

        return plugins
            .groupBy { it.pluginId }
            .map { (pluginId, pluginList) ->
                val firstPlugin = pluginList.first()
                val update = updateMap[pluginId]
                val modules = pluginList.map { it.moduleName }.distinct()

                val parts = pluginId.split(".")
                val publisher = if (parts.size > 2) parts.dropLast(1).joinToString(".") else pluginId

                UnifiedPackage(
                    name = pluginId,
                    publisher = publisher,
                    installedVersion = firstPlugin.version,
                    latestVersion = update?.latestVersion,
                    description = null,
                    homepage = "https://plugins.gradle.org/plugin/$pluginId",
                    license = null,
                    scope = "plugin",
                    modules = modules,
                    source = PackageSource.GRADLE_PLUGIN_INSTALLED,
                    metadata = PackageMetadata.GradlePluginMetadata(
                        buildFile = firstPlugin.buildFile,
                        offset = firstPlugin.offset,
                        length = firstPlugin.length,
                        pluginSyntax = firstPlugin.pluginSyntax,
                        isKotlinShorthand = firstPlugin.isKotlinShorthand,
                        isApplied = firstPlugin.isApplied
                    )
                )
            }
    }

    /**
     * Group installed Maven plugins by their ID and aggregate modules.
     */
    fun aggregateMavenPluginsByPackage(
        plugins: List<MavenInstalledPlugin>,
        updates: List<MavenPluginUpdate>
    ): List<UnifiedPackage> {
        val updateMap = updates.associateBy { it.installed.id }

        return plugins
            .groupBy { it.id }
            .map { (id, pluginList) ->
                val firstPlugin = pluginList.first()
                val update = updateMap[id]
                val modules = pluginList.map { it.moduleName }.distinct()

                val homepage = when {
                    firstPlugin.groupId.startsWith("org.apache.maven.plugins") ->
                        "https://maven.apache.org/plugins/${firstPlugin.artifactId}/"
                    else ->
                        "https://search.maven.org/artifact/${firstPlugin.groupId}/${firstPlugin.artifactId}"
                }

                UnifiedPackage(
                    name = firstPlugin.artifactId,
                    publisher = firstPlugin.groupId,
                    installedVersion = firstPlugin.version,
                    latestVersion = update?.latestVersion,
                    description = null,
                    homepage = homepage,
                    license = null,
                    scope = "plugin",
                    modules = modules,
                    source = PackageSource.MAVEN_PLUGIN_INSTALLED,
                    metadata = PackageMetadata.MavenPluginMetadata(
                        pomFile = firstPlugin.pomFile,
                        offset = firstPlugin.offset,
                        length = firstPlugin.length,
                        phase = firstPlugin.phase,
                        goals = firstPlugin.goals,
                        inherited = firstPlugin.inherited,
                        isFromPluginManagement = firstPlugin.isFromPluginManagement,
                        configuration = firstPlugin.configuration
                    )
                )
            }
    }
}
