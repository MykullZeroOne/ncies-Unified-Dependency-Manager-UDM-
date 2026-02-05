package com.maddrobot.plugins.udm.setting

import com.maddrobot.plugins.udm.maven.DependencyFormat
import com.maddrobot.plugins.udm.maven.DependencyScope
import com.maddrobot.plugins.udm.maven.MavenRepositorySource

/**
 * @author drawsta
 * @LastModified: 2025-09-07
 * @since 2025-07-08
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
    companion object {
        fun getInstance(): PackageFinderSettingState = PackageFinderSetting.instance.state
    }
}
