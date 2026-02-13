package com.maddrobot.plugins.udm.setting

import com.maddrobot.plugins.udm.maven.DependencyFormat
import com.maddrobot.plugins.udm.maven.DependencyScope
import com.maddrobot.plugins.udm.maven.MavenRepositorySource

/**
 * Represents the state configuration for the Package Finder application.
 *
 * This class is used to store and manage the settings related to package management,
 * repository sources, dependency configurations, vulnerability scanning, and user preferences.
 * It provides a single point of access to the application settings and encapsulates
 * the relevant data fields.
 *
 * @property nexusServerUrl The base URL of the Nexus Server to be used for retrieving packages.
 * @property repoSource The source of the Maven repository. Defaults to the central repository.
 * @property dependencyScope The scope of dependencies to be managed, indicating their purpose in the build lifecycle.
 * @property dependencyFormat The format in which dependencies are declared (e.g., Groovy or Kotlin DSL).
 * @property enableVulnerabilityScanning Flag to enable or disable vulnerability scanning for dependencies.
 * @property vulnerabilityScanOnLoad Whether vulnerability scanning should be performed when loading dependencies.
 * @property githubToken Optional GitHub API token to enhance vulnerability scanning with advisory database access.
 * @property showPreviewBeforeChanges Flag indicating if a preview of changes should be shown before applying them.
 */
data class PackageFinderSettingState(
    var nexusServerUrl: String = "",
    var repoSource: MavenRepositorySource = MavenRepositorySource.CENTRAL,
    var dependencyScope: DependencyScope = DependencyScope.COMPILE,
    var dependencyFormat: DependencyFormat = DependencyFormat.GradleGroovyDeclaration,
    // Vulnerability scanning settings
    var enableVulnerabilityScanning: Boolean = true,
    var vulnerabilityScanOnLoad: Boolean = true,
    var githubToken: String? = null,  // Optional token for GitHub Advisory Database
    // General settings
    var showPreviewBeforeChanges: Boolean = false  // When true, shows diff preview before install/update/uninstall
) {
    /**
     * Companion object providing access to the singleton instance of the `PackageFinderSettingState`.
     */
    companion object {
        /**
         * Retrieves the current state of the PackageFinderSetting.
         *
         * @return the current instance of PackageFinderSettingState containing the configuration and settings.
         */
        fun getInstance(): PackageFinderSettingState = PackageFinderSetting.instance.state
    }
}
