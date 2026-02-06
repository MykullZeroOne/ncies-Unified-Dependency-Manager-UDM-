package com.maddrobot.plugins.udm.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.maddrobot.plugins.udm.gradle.manager.model.UnifiedPackage
import com.maddrobot.plugins.udm.gradle.manager.model.PackageSource
import com.maddrobot.plugins.udm.gradle.manager.model.VulnerabilityInfo
import java.awt.datatransfer.StringSelection

/**
 * Context menu actions for package items in the Unified Dependency Manager.
 * Provides version management, navigation, copy, and dependency analysis actions.
 */

// ========== Data Key for Selected Package ==========

val SELECTED_PACKAGE_KEY = DataKey.create<UnifiedPackage>("UDM_SELECTED_PACKAGE")
val SELECTED_PACKAGES_KEY = DataKey.create<List<UnifiedPackage>>("UDM_SELECTED_PACKAGES")

// ========== Version Actions ==========

/**
 * Action to upgrade a package to a newer version.
 */
class UpgradePackageAction(
    private val pkg: UnifiedPackage,
    private val onUpgrade: (UnifiedPackage, String?) -> Unit
) : DumbAwareAction(
    "Upgrade ${pkg.name} to ${pkg.latestVersion}",
    "Upgrade this package to the latest version",
    AllIcons.Actions.Upload
) {
    override fun actionPerformed(e: AnActionEvent) {
        onUpgrade(pkg, pkg.latestVersion)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * Action to downgrade a package to an older version.
 */
class DowngradePackageAction(
    private val pkg: UnifiedPackage,
    private val onDowngrade: (UnifiedPackage) -> Unit
) : DumbAwareAction(
    "Change Version",
    "Select a different version to install",
    AllIcons.Actions.Download
) {
    override fun actionPerformed(e: AnActionEvent) {
        onDowngrade(pkg)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * Action to install a package that is not yet installed.
 */
class InstallPackageAction(
    private val pkg: UnifiedPackage,
    private val onInstall: (UnifiedPackage) -> Unit
) : DumbAwareAction(
    "Install Package",
    "Install this package to selected modules",
    AllIcons.Actions.Install
) {
    override fun actionPerformed(e: AnActionEvent) {
        onInstall(pkg)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * Action to remove a package from a specific module.
 */
class RemoveFromModuleAction(
    private val pkg: UnifiedPackage,
    private val moduleName: String,
    private val onRemove: (UnifiedPackage, String) -> Unit
) : DumbAwareAction(
    "Remove from $moduleName",
    "Remove this package from the $moduleName module",
    AllIcons.Actions.GC
) {
    override fun actionPerformed(e: AnActionEvent) {
        onRemove(pkg, moduleName)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * Action to remove a package from all modules.
 */
class RemoveFromAllModulesAction(
    private val pkg: UnifiedPackage,
    private val onRemove: (UnifiedPackage) -> Unit
) : DumbAwareAction(
    "Remove",
    "Remove this package",
    AllIcons.Actions.GC
) {
    override fun actionPerformed(e: AnActionEvent) {
        onRemove(pkg)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

// ========== Navigation Actions ==========

/**
 * Action to open package page in browser.
 */
class ViewOnRepositoryAction(
    private val pkg: UnifiedPackage
) : DumbAwareAction(
    "View on Repository",
    "Open the package page in your browser",
    AllIcons.General.Web
) {
    override fun actionPerformed(e: AnActionEvent) {
        val url = getPackageUrl(pkg)
        if (url != null) {
            BrowserUtil.browse(url)
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    private fun getPackageUrl(pkg: UnifiedPackage): String? {
        return when (pkg.source) {
            PackageSource.MAVEN_CENTRAL, PackageSource.MAVEN_INSTALLED, PackageSource.GRADLE_INSTALLED ->
                "https://search.maven.org/artifact/${pkg.publisher}/${pkg.name}"
            PackageSource.NPM ->
                "https://www.npmjs.com/package/${pkg.name}"
            PackageSource.GRADLE_PLUGIN ->
                "https://plugins.gradle.org/plugin/${pkg.id}"
            else -> pkg.homepage
        }
    }
}

/**
 * Action to open the installation folder in file explorer.
 */
class OpenInstallationFolderAction(
    private val pkg: UnifiedPackage,
    private val onOpen: (UnifiedPackage) -> Unit
) : DumbAwareAction(
    "Open Installation Folder",
    "Open the package installation folder in file explorer",
    AllIcons.Actions.MenuOpen
) {
    override fun actionPerformed(e: AnActionEvent) {
        onOpen(pkg)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * Action to search for package on web.
 */
class SearchPackageOnWebAction(
    private val pkg: UnifiedPackage
) : DumbAwareAction(
    "Search on Web",
    "Search for this package on the web",
    AllIcons.Actions.Search
) {
    override fun actionPerformed(e: AnActionEvent) {
        BrowserUtil.browse("https://www.google.com/search?q=${pkg.id}+maven")
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

// ========== Copy Actions ==========

/**
 * Action to copy Maven XML coordinate.
 */
class CopyMavenCoordinateAction(
    private val pkg: UnifiedPackage
) : DumbAwareAction(
    "Maven (XML)",
    "Copy the Maven XML dependency declaration",
    AllIcons.FileTypes.Xml
) {
    override fun actionPerformed(e: AnActionEvent) {
        val version = pkg.installedVersion ?: pkg.latestVersion ?: return
        val xml = """
            <dependency>
                <groupId>${pkg.publisher}</groupId>
                <artifactId>${pkg.name}</artifactId>
                <version>$version</version>
            </dependency>
        """.trimIndent()
        copyToClipboard(xml)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * Action to copy Gradle Groovy coordinate.
 */
class CopyGradleGroovyCoordinateAction(
    private val pkg: UnifiedPackage
) : DumbAwareAction(
    "Gradle (Groovy)",
    "Copy the Gradle Groovy DSL dependency declaration",
    AllIcons.Nodes.Gvariable
) {
    override fun actionPerformed(e: AnActionEvent) {
        val version = pkg.installedVersion ?: pkg.latestVersion ?: return
        val coordinate = "implementation '${pkg.publisher}:${pkg.name}:$version'"
        copyToClipboard(coordinate)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * Action to copy Gradle Kotlin coordinate.
 */
class CopyGradleKotlinCoordinateAction(
    private val pkg: UnifiedPackage
) : DumbAwareAction(
    "Gradle (Kotlin DSL)",
    "Copy the Gradle Kotlin DSL dependency declaration",
    AllIcons.FileTypes.JavaClass
) {
    override fun actionPerformed(e: AnActionEvent) {
        val version = pkg.installedVersion ?: pkg.latestVersion ?: return
        val coordinate = "implementation(\"${pkg.publisher}:${pkg.name}:$version\")"
        copyToClipboard(coordinate)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * Action to copy NPM coordinate.
 */
class CopyNpmCoordinateAction(
    private val pkg: UnifiedPackage
) : DumbAwareAction(
    "NPM",
    "Copy the NPM package.json dependency declaration",
    AllIcons.FileTypes.JavaScript
) {
    override fun actionPerformed(e: AnActionEvent) {
        val version = pkg.installedVersion ?: pkg.latestVersion ?: return
        val coordinate = "\"${pkg.name}\": \"^$version\""
        copyToClipboard(coordinate)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * Action to copy full package metadata as JSON.
 */
class CopyFullMetadataAction(
    private val pkg: UnifiedPackage
) : DumbAwareAction(
    "Full Metadata (JSON)",
    "Copy complete package information as JSON",
    AllIcons.FileTypes.Json
) {
    override fun actionPerformed(e: AnActionEvent) {
        val status = pkg.getStatus()
        val json = """
            {
                "id": "${pkg.id}",
                "name": "${pkg.name}",
                "groupId": "${pkg.publisher}",
                "installedVersion": ${pkg.installedVersion?.let { "\"$it\"" } ?: "null"},
                "latestVersion": ${pkg.latestVersion?.let { "\"$it\"" } ?: "null"},
                "description": ${pkg.description?.let { "\"${it.replace("\"", "\\\"")}\"" } ?: "null"},
                "homepage": ${pkg.homepage?.let { "\"$it\"" } ?: "null"},
                "license": ${pkg.license?.let { "\"$it\"" } ?: "null"},
                "modules": [${pkg.modules.joinToString(",") { "\"$it\"" }}],
                "source": "${pkg.source}",
                "isInstalled": ${pkg.isInstalled},
                "hasUpdate": ${status.hasUpdate},
                "isVulnerable": ${status.isVulnerable},
                "isTransitive": ${status.isTransitive},
                "isDeprecated": ${status.isDeprecated},
                "isPrerelease": ${status.isPrerelease}
            }
        """.trimIndent()
        copyToClipboard(json)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

// ========== Dependency Analysis Actions ==========

/**
 * Action to show what this package depends on.
 */
class ShowDependencyTreeAction(
    private val pkg: UnifiedPackage,
    private val onShowTree: (UnifiedPackage) -> Unit
) : DumbAwareAction(
    "Show Dependencies",
    "Show what this package depends on",
    AllIcons.Hierarchy.Subtypes
) {
    override fun actionPerformed(e: AnActionEvent) {
        onShowTree(pkg)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * Action to show what depends on this package.
 */
class ShowReverseDependentsAction(
    private val pkg: UnifiedPackage,
    private val onShowDependents: (UnifiedPackage) -> Unit
) : DumbAwareAction(
    "Show Dependents",
    "Show what depends on this package",
    AllIcons.Hierarchy.Supertypes
) {
    override fun actionPerformed(e: AnActionEvent) {
        onShowDependents(pkg)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * Action to explain why a transitive dependency exists.
 */
class WhyInstalledAction(
    private val pkg: UnifiedPackage,
    private val onExplain: (UnifiedPackage) -> Unit
) : DumbAwareAction(
    "Why Installed?",
    "Explain why this transitive dependency exists",
    AllIcons.Actions.Help
) {
    override fun actionPerformed(e: AnActionEvent) {
        onExplain(pkg)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * Action to manage exclusions for a dependency.
 */
class ManageExclusionsAction(
    private val pkg: UnifiedPackage,
    private val onManageExclusions: (UnifiedPackage) -> Unit
) : DumbAwareAction(
    "Manage Exclusions",
    "Add or remove transitive dependency exclusions",
    AllIcons.Actions.Cancel
) {
    override fun actionPerformed(e: AnActionEvent) {
        onManageExclusions(pkg)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

// ========== Multi-Select Actions ==========

/**
 * Action to remove all selected packages.
 */
class RemoveSelectedAction(
    private val packages: List<UnifiedPackage>,
    private val onRemove: (List<UnifiedPackage>) -> Unit
) : DumbAwareAction(
    "Remove ${packages.filter { it.isInstalled }.size} Selected",
    "Remove all selected packages",
    AllIcons.General.Remove
) {
    override fun actionPerformed(e: AnActionEvent) {
        val installedPackages = packages.filter { it.isInstalled }
        if (installedPackages.isNotEmpty()) {
            onRemove(installedPackages)
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * Action to upgrade all selected packages with updates.
 */
class UpgradeSelectedAction(
    private val packages: List<UnifiedPackage>,
    private val onUpgrade: (List<UnifiedPackage>) -> Unit
) : DumbAwareAction(
    "Upgrade ${packages.filter { it.getStatus().hasUpdate }.size} Selected",
    "Upgrade all selected packages with available updates",
    AllIcons.Actions.Upload
) {
    override fun actionPerformed(e: AnActionEvent) {
        val packagesWithUpdates = packages.filter { it.getStatus().hasUpdate }
        if (packagesWithUpdates.isNotEmpty()) {
            onUpgrade(packagesWithUpdates)
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

// ========== Vulnerability Actions ==========

/**
 * Action to view the security advisory for a vulnerable package.
 */
class ViewAdvisoryAction(
    cveLabel: String,
    severityLabel: String,
    private val vulnInfo: VulnerabilityInfo,
    private val onViewAdvisory: (VulnerabilityInfo) -> Unit
) : DumbAwareAction(
    "View Advisory: $cveLabel ($severityLabel)",
    "Open the security advisory in your browser",
    AllIcons.General.Warning
) {
    override fun actionPerformed(e: AnActionEvent) {
        onViewAdvisory(vulnInfo)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * Action to update a package to the version that fixes a vulnerability.
 */
class UpdateToFixAction(
    private val fixVersion: String,
    private val pkg: UnifiedPackage,
    private val onUpgrade: (UnifiedPackage, String?) -> Unit
) : DumbAwareAction(
    "Update to Fix ($fixVersion)",
    "Update this package to the version that fixes the vulnerability",
    AllIcons.Actions.Execute
) {
    override fun actionPerformed(e: AnActionEvent) {
        onUpgrade(pkg, fixVersion)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

// ========== Helper Functions ==========

private fun copyToClipboard(text: String) {
    CopyPasteManager.getInstance().setContents(StringSelection(text))
}

// ========== Context Menu Action Group Builder ==========

/**
 * Builder for creating package context menu action groups.
 */
object PackageContextMenuBuilder {

    /**
     * Create a context menu action group for a selected package.
     */
    fun createContextMenu(
        project: Project,
        selectedPackage: UnifiedPackage?,
        selectedPackages: List<UnifiedPackage>,
        callbacks: ContextMenuCallbacks
    ): ActionGroup {
        val group = DefaultActionGroup()

        if (selectedPackage == null) return group

        val status = selectedPackage.getStatus()
        val isMultiSelect = selectedPackages.size > 1

        // Version Actions
        group.addSeparator("Version Actions")
        if (!selectedPackage.isInstalled) {
            group.add(InstallPackageAction(selectedPackage, callbacks.onInstall))
        }
        if (status.hasUpdate) {
            group.add(UpgradePackageAction(selectedPackage, callbacks.onUpgrade))
        }
        if (selectedPackage.isInstalled) {
            group.add(DowngradePackageAction(selectedPackage, callbacks.onDowngrade))

            // Module-specific remove actions
            if (selectedPackage.modules.size > 1) {
                val removeGroup = DefaultActionGroup("Remove from Module", true)
                for (module in selectedPackage.modules) {
                    removeGroup.add(RemoveFromModuleAction(selectedPackage, module, callbacks.onRemoveFromModule))
                }
                removeGroup.addSeparator()
                removeGroup.add(RemoveFromAllModulesAction(selectedPackage, callbacks.onRemoveFromAll))
                group.add(removeGroup)
            } else {
                group.add(RemoveFromAllModulesAction(selectedPackage, callbacks.onRemoveFromAll))
            }
        }

        // Vulnerability Actions
        val vulnInfo = selectedPackage.vulnerabilityInfo
        if (vulnInfo != null) {
            group.addSeparator("Vulnerability")
            val cveLabel = vulnInfo.cveId ?: "Unknown"
            val severityLabel = vulnInfo.severity.name
            group.add(ViewAdvisoryAction(cveLabel, severityLabel, vulnInfo, callbacks.onViewAdvisory))

            // "Update to Fix" if a fix version is available
            if (vulnInfo.fixedVersion != null && selectedPackage.isInstalled) {
                group.add(UpdateToFixAction(vulnInfo.fixedVersion, selectedPackage, callbacks.onUpgrade))
            }
        }

        // Navigation Actions
        group.addSeparator("Navigation")
        group.add(ViewOnRepositoryAction(selectedPackage))
        if (selectedPackage.isInstalled) {
            group.add(OpenInstallationFolderAction(selectedPackage, callbacks.onOpenFolder))
        }
        group.add(SearchPackageOnWebAction(selectedPackage))

        // Copy Actions
        group.addSeparator("Copy")
        val copyGroup = DefaultActionGroup("Copy Coordinate", true)
        copyGroup.add(CopyMavenCoordinateAction(selectedPackage))
        copyGroup.add(CopyGradleGroovyCoordinateAction(selectedPackage))
        copyGroup.add(CopyGradleKotlinCoordinateAction(selectedPackage))
        if (selectedPackage.source == PackageSource.NPM) {
            copyGroup.add(CopyNpmCoordinateAction(selectedPackage))
        }
        copyGroup.addSeparator()
        copyGroup.add(CopyFullMetadataAction(selectedPackage))
        group.add(copyGroup)

        // Dependency Analysis
        group.addSeparator("Analysis")
        group.add(ShowDependencyTreeAction(selectedPackage, callbacks.onShowDependencyTree))
        if (selectedPackage.isInstalled) {
            group.add(ShowReverseDependentsAction(selectedPackage, callbacks.onShowDependents))
        }
        if (status.isTransitive) {
            group.add(WhyInstalledAction(selectedPackage, callbacks.onWhyInstalled))
        }
        // Exclusion management for installed Gradle/Maven dependencies and plugins
        val isExclusionCapable = selectedPackage.isInstalled && (
            selectedPackage.source == PackageSource.GRADLE_INSTALLED ||
            selectedPackage.source == PackageSource.MAVEN_INSTALLED ||
            selectedPackage.source == PackageSource.GRADLE_PLUGIN_INSTALLED ||
            selectedPackage.source == PackageSource.MAVEN_PLUGIN_INSTALLED
        )
        if (isExclusionCapable) {
            group.add(ManageExclusionsAction(selectedPackage, callbacks.onManageExclusions))
        }

        // Multi-select actions
        if (isMultiSelect) {
            group.addSeparator("Batch Actions")
            val installedPackages = selectedPackages.filter { it.isInstalled }
            val packagesWithUpdates = selectedPackages.filter { it.getStatus().hasUpdate }
            if (installedPackages.size > 1) {
                group.add(RemoveSelectedAction(selectedPackages, callbacks.onRemoveSelected))
            }
            if (packagesWithUpdates.size > 1) {
                group.add(UpgradeSelectedAction(selectedPackages, callbacks.onUpgradeSelected))
            }
        }

        return group
    }

    /**
     * Callbacks for context menu actions.
     */
    data class ContextMenuCallbacks(
        val onInstall: (UnifiedPackage) -> Unit = {},
        val onUpgrade: (UnifiedPackage, String?) -> Unit = { _, _ -> },
        val onDowngrade: (UnifiedPackage) -> Unit = {},
        val onRemoveFromModule: (UnifiedPackage, String) -> Unit = { _, _ -> },
        val onRemoveFromAll: (UnifiedPackage) -> Unit = {},
        val onOpenFolder: (UnifiedPackage) -> Unit = {},
        val onShowDependencyTree: (UnifiedPackage) -> Unit = {},
        val onShowDependents: (UnifiedPackage) -> Unit = {},
        val onWhyInstalled: (UnifiedPackage) -> Unit = {},
        val onRemoveSelected: (List<UnifiedPackage>) -> Unit = {},
        val onUpgradeSelected: (List<UnifiedPackage>) -> Unit = {},
        val onViewAdvisory: (VulnerabilityInfo) -> Unit = {},
        val onManageExclusions: (UnifiedPackage) -> Unit = {}
    )
}
