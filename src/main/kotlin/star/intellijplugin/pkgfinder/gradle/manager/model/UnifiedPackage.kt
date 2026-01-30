package star.intellijplugin.pkgfinder.gradle.manager.model

import star.intellijplugin.pkgfinder.gradle.manager.DependencyUpdate
import star.intellijplugin.pkgfinder.gradle.manager.InstalledDependency
import star.intellijplugin.pkgfinder.maven.CentralDependency
import star.intellijplugin.pkgfinder.maven.Dependency
import star.intellijplugin.pkgfinder.maven.manager.MavenInstalledDependency
import star.intellijplugin.pkgfinder.npm.NpmObject

/**
 * Unified package model that abstracts dependencies from different sources
 * (Gradle, Maven Central, NPM, Nexus, etc.) into a single representation.
 */
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
    val isTransitive: Boolean = false  // true if this is a transitive (indirect) dependency
) {
    val id: String get() = "$publisher:$name"
    val displayName: String get() = name
    val hasUpdate: Boolean get() = installedVersion != null && latestVersion != null && installedVersion != latestVersion
    val isInstalled: Boolean get() = installedVersion != null
}

/**
 * Identifies the source/type of the package.
 */
enum class PackageSource {
    GRADLE_INSTALLED,   // Installed via Gradle
    MAVEN_INSTALLED,    // Installed via Maven (pom.xml)
    MAVEN_CENTRAL,      // Search result from Maven Central
    NPM,                // Search result from NPM Registry
    NEXUS,              // Search result from Nexus
    LOCAL_MAVEN,        // From local Maven repository
    GRADLE_PLUGIN       // Gradle plugin from Plugin Portal
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
}
