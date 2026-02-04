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
    private val onUpgrade: (UnifiedPackage, String?) -> Unit
) : DumbAwareAction(
    "Upgrade Package",
    "Upgrade this package to the latest version",
    AllIcons.Actions.Upload
) {
    override fun actionPerformed(e: AnActionEvent) {
        val pkg = e.getData(SELECTED_PACKAGE_KEY) ?: return
        onUpgrade(pkg, pkg.latestVersion)
    }

    override fun update(e: AnActionEvent) {
        val pkg = e.getData(SELECTED_PACKAGE_KEY)
        val status = pkg?.getStatus()
        e.presentation.isEnabledAndVisible = pkg != null && status?.hasUpdate == true

        if (pkg != null && status?.hasUpdate == true) {
            e.presentation.text = "Upgrade ${pkg.name} from ${pkg.installedVersion} → ${pkg.latestVersion}"
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * Action to downgrade a package to an older version.
 */
class DowngradePackageAction(
    private val onDowngrade: (UnifiedPackage) -> Unit
) : DumbAwareAction(
    "Downgrade Package",
    "Select an older version to install",
    AllIcons.Actions.Download
) {
    override fun actionPerformed(e: AnActionEvent) {
        val pkg = e.getData(SELECTED_PACKAGE_KEY) ?: return
        onDowngrade(pkg)
    }

    override fun update(e: AnActionEvent) {
        val pkg = e.getData(SELECTED_PACKAGE_KEY)
        e.presentation.isEnabledAndVisible = pkg?.isInstalled == true
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * Action to install a package that is not yet installed.
 */
class InstallPackageAction(
    private val onInstall: (UnifiedPackage) -> Unit
) : DumbAwareAction(
    "Install Package",
    "Install this package to selected modules",
    AllIcons.Actions.Install
) {
    override fun actionPerformed(e: AnActionEvent) {
        val pkg = e.getData(SELECTED_PACKAGE_KEY) ?: return
        onInstall(pkg)
    }

    override fun update(e: AnActionEvent) {
        val pkg = e.getData(SELECTED_PACKAGE_KEY)
        e.presentation.isEnabledAndVisible = pkg != null && !pkg.isInstalled
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * Action to remove a package from a specific module.
 */
class RemoveFromModuleAction(
    private val moduleName: String,
    private val onRemove: (UnifiedPackage, String) -> Unit
) : DumbAwareAction(
    "Remove from $moduleName",
    "Remove this package from the $moduleName module",
    AllIcons.Actions.GC
) {
    override fun actionPerformed(e: AnActionEvent) {
        val pkg = e.getData(SELECTED_PACKAGE_KEY) ?: return
        onRemove(pkg, moduleName)
    }

    override fun update(e: AnActionEvent) {
        val pkg = e.getData(SELECTED_PACKAGE_KEY)
        e.presentation.isEnabledAndVisible = pkg?.isInstalled == true && pkg.modules.contains(moduleName)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * Action to remove a package from all modules.
 */
class RemoveFromAllModulesAction(
    private val onRemove: (UnifiedPackage) -> Unit
) : DumbAwareAction(
    "Remove from All Modules",
    "Remove this package from all modules",
    AllIcons.Actions.GC
) {
    override fun actionPerformed(e: AnActionEvent) {
        val pkg = e.getData(SELECTED_PACKAGE_KEY) ?: return
        onRemove(pkg)
    }

    override fun update(e: AnActionEvent) {
        val pkg = e.getData(SELECTED_PACKAGE_KEY)
        e.presentation.isEnabledAndVisible = pkg?.isInstalled == true && pkg.modules.size > 1
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

// ========== Navigation Actions ==========

/**
 * Action to open package page in browser.
 */
class ViewOnRepositoryAction : DumbAwareAction(
    "View on Repository",
    "Open the package page in your browser",
    AllIcons.General.Web
) {
    override fun actionPerformed(e: AnActionEvent) {
        val pkg = e.getData(SELECTED_PACKAGE_KEY) ?: return
        val url = getPackageUrl(pkg)
        if (url != null) {
            BrowserUtil.browse(url)
        }
    }

    override fun update(e: AnActionEvent) {
        val pkg = e.getData(SELECTED_PACKAGE_KEY)
        e.presentation.isEnabledAndVisible = pkg != null
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
    private val onOpen: (UnifiedPackage) -> Unit
) : DumbAwareAction(
    "Open Installation Folder",
    "Open the package installation folder in file explorer",
    AllIcons.Actions.MenuOpen
) {
    override fun actionPerformed(e: AnActionEvent) {
        val pkg = e.getData(SELECTED_PACKAGE_KEY) ?: return
        onOpen(pkg)
    }

    override fun update(e: AnActionEvent) {
        val pkg = e.getData(SELECTED_PACKAGE_KEY)
        e.presentation.isEnabledAndVisible = pkg?.isInstalled == true &&
            (pkg.source == PackageSource.LOCAL_MAVEN || pkg.source == PackageSource.GRADLE_INSTALLED)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * Action to search for package on web.
 */
class SearchPackageOnWebAction : DumbAwareAction(
    "Search on Web",
    "Search for this package on the web",
    AllIcons.Actions.Search
) {
    override fun actionPerformed(e: AnActionEvent) {
        val pkg = e.getData(SELECTED_PACKAGE_KEY) ?: return
        BrowserUtil.browse("https://www.google.com/search?q=${pkg.id}+maven")
    }

    override fun update(e: AnActionEvent) {
        val pkg = e.getData(SELECTED_PACKAGE_KEY)
        e.presentation.isEnabledAndVisible = pkg != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

// ========== Copy Actions ==========

/**
 * Action to copy Maven XML coordinate.
 */
class CopyMavenCoordinateAction : DumbAwareAction(
    "Copy Maven Coordinate (XML)",
    "Copy the Maven XML dependency declaration",
    AllIcons.FileTypes.Xml
) {
    override fun actionPerformed(e: AnActionEvent) {
        val pkg = e.getData(SELECTED_PACKAGE_KEY) ?: return
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

    override fun update(e: AnActionEvent) {
        val pkg = e.getData(SELECTED_PACKAGE_KEY)
        e.presentation.isEnabledAndVisible = pkg != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * Action to copy Gradle Groovy coordinate.
 */
class CopyGradleGroovyCoordinateAction : DumbAwareAction(
    "Copy Gradle Coordinate (Groovy)",
    "Copy the Gradle Groovy DSL dependency declaration",
    AllIcons.Nodes.Gvariable
) {
    override fun actionPerformed(e: AnActionEvent) {
        val pkg = e.getData(SELECTED_PACKAGE_KEY) ?: return
        val version = pkg.installedVersion ?: pkg.latestVersion ?: return
        val coordinate = "implementation '${pkg.publisher}:${pkg.name}:$version'"
        copyToClipboard(coordinate)
    }

    override fun update(e: AnActionEvent) {
        val pkg = e.getData(SELECTED_PACKAGE_KEY)
        e.presentation.isEnabledAndVisible = pkg != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * Action to copy Gradle Kotlin coordinate.
 */
class CopyGradleKotlinCoordinateAction : DumbAwareAction(
    "Copy Gradle Coordinate (Kotlin DSL)",
    "Copy the Gradle Kotlin DSL dependency declaration",
    AllIcons.FileTypes.JavaClass
) {
    override fun actionPerformed(e: AnActionEvent) {
        val pkg = e.getData(SELECTED_PACKAGE_KEY) ?: return
        val version = pkg.installedVersion ?: pkg.latestVersion ?: return
        val coordinate = "implementation(\"${pkg.publisher}:${pkg.name}:$version\")"
        copyToClipboard(coordinate)
    }

    override fun update(e: AnActionEvent) {
        val pkg = e.getData(SELECTED_PACKAGE_KEY)
        e.presentation.isEnabledAndVisible = pkg != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * Action to copy NPM coordinate.
 */
class CopyNpmCoordinateAction : DumbAwareAction(
    "Copy NPM Coordinate",
    "Copy the NPM package.json dependency declaration",
    AllIcons.FileTypes.JavaScript
) {
    override fun actionPerformed(e: AnActionEvent) {
        val pkg = e.getData(SELECTED_PACKAGE_KEY) ?: return
        val version = pkg.installedVersion ?: pkg.latestVersion ?: return
        val coordinate = "\"${pkg.name}\": \"^$version\""
        copyToClipboard(coordinate)
    }

    override fun update(e: AnActionEvent) {
        val pkg = e.getData(SELECTED_PACKAGE_KEY)
        e.presentation.isEnabledAndVisible = pkg != null && pkg.source == PackageSource.NPM
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * Action to copy full package metadata as JSON.
 */
class CopyFullMetadataAction : DumbAwareAction(
    "Copy Full Metadata (JSON)",
    "Copy complete package information as JSON",
    AllIcons.FileTypes.Json
) {
    override fun actionPerformed(e: AnActionEvent) {
        val pkg = e.getData(SELECTED_PACKAGE_KEY) ?: return
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

    override fun update(e: AnActionEvent) {
        val pkg = e.getData(SELECTED_PACKAGE_KEY)
        e.presentation.isEnabledAndVisible = pkg != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

// ========== Dependency Analysis Actions ==========

/**
 * Action to show what this package depends on.
 */
class ShowDependencyTreeAction(
    private val onShowTree: (UnifiedPackage) -> Unit
) : DumbAwareAction(
    "Show Dependencies",
    "Show what this package depends on",
    AllIcons.Hierarchy.Subtypes
) {
    override fun actionPerformed(e: AnActionEvent) {
        val pkg = e.getData(SELECTED_PACKAGE_KEY) ?: return
        onShowTree(pkg)
    }

    override fun update(e: AnActionEvent) {
        val pkg = e.getData(SELECTED_PACKAGE_KEY)
        e.presentation.isEnabledAndVisible = pkg != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * Action to show what depends on this package.
 */
class ShowReverseDependentsAction(
    private val onShowDependents: (UnifiedPackage) -> Unit
) : DumbAwareAction(
    "Show Dependents",
    "Show what depends on this package",
    AllIcons.Hierarchy.Supertypes
) {
    override fun actionPerformed(e: AnActionEvent) {
        val pkg = e.getData(SELECTED_PACKAGE_KEY) ?: return
        onShowDependents(pkg)
    }

    override fun update(e: AnActionEvent) {
        val pkg = e.getData(SELECTED_PACKAGE_KEY)
        e.presentation.isEnabledAndVisible = pkg?.isInstalled == true
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * Action to explain why a transitive dependency exists.
 */
class WhyInstalledAction(
    private val onExplain: (UnifiedPackage) -> Unit
) : DumbAwareAction(
    "Why Installed?",
    "Explain why this transitive dependency exists",
    AllIcons.Actions.Help
) {
    override fun actionPerformed(e: AnActionEvent) {
        val pkg = e.getData(SELECTED_PACKAGE_KEY) ?: return
        onExplain(pkg)
    }

    override fun update(e: AnActionEvent) {
        val pkg = e.getData(SELECTED_PACKAGE_KEY)
        val status = pkg?.getStatus()
        e.presentation.isEnabledAndVisible = status?.isTransitive == true
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

// ========== Multi-Select Actions ==========

/**
 * Action to remove all selected packages.
 */
class RemoveSelectedAction(
    private val onRemove: (List<UnifiedPackage>) -> Unit
) : DumbAwareAction(
    "Remove Selected",
    "Remove all selected packages",
    AllIcons.General.Remove
) {
    override fun actionPerformed(e: AnActionEvent) {
        val packages = e.getData(SELECTED_PACKAGES_KEY) ?: return
        onRemove(packages)
    }

    override fun update(e: AnActionEvent) {
        val packages = e.getData(SELECTED_PACKAGES_KEY)
        val installedPackages = packages?.filter { it.isInstalled } ?: emptyList()
        e.presentation.isEnabledAndVisible = installedPackages.size > 1
        if (installedPackages.isNotEmpty()) {
            e.presentation.text = "Remove ${installedPackages.size} Selected"
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT
}

/**
 * Action to upgrade all selected packages with updates.
 */
class UpgradeSelectedAction(
    private val onUpgrade: (List<UnifiedPackage>) -> Unit
) : DumbAwareAction(
    "Upgrade Selected",
    "Upgrade all selected packages with available updates",
    AllIcons.Actions.Upload
) {
    override fun actionPerformed(e: AnActionEvent) {
        val packages = e.getData(SELECTED_PACKAGES_KEY) ?: return
        val packagesWithUpdates = packages.filter { it.getStatus().hasUpdate }
        onUpgrade(packagesWithUpdates)
    }

    override fun update(e: AnActionEvent) {
        val packages = e.getData(SELECTED_PACKAGES_KEY)
        val packagesWithUpdates = packages?.filter { it.getStatus().hasUpdate } ?: emptyList()
        e.presentation.isEnabledAndVisible = packagesWithUpdates.size > 1
        if (packagesWithUpdates.isNotEmpty()) {
            e.presentation.text = "Upgrade ${packagesWithUpdates.size} Selected"
        }
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
            group.add(InstallPackageAction(callbacks.onInstall))
        }
        if (status.hasUpdate) {
            group.add(UpgradePackageAction(callbacks.onUpgrade))
        }
        if (selectedPackage.isInstalled) {
            group.add(DowngradePackageAction(callbacks.onDowngrade))

            // Module-specific remove actions
            if (selectedPackage.modules.size > 1) {
                val removeGroup = DefaultActionGroup("Remove from Module", true)
                for (module in selectedPackage.modules) {
                    removeGroup.add(RemoveFromModuleAction(module, callbacks.onRemoveFromModule))
                }
                removeGroup.addSeparator()
                removeGroup.add(RemoveFromAllModulesAction(callbacks.onRemoveFromAll))
                group.add(removeGroup)
            } else {
                group.add(RemoveFromAllModulesAction(callbacks.onRemoveFromAll))
            }
        }

        // Navigation Actions
        group.addSeparator("Navigation")
        group.add(ViewOnRepositoryAction())
        if (selectedPackage.isInstalled) {
            group.add(OpenInstallationFolderAction(callbacks.onOpenFolder))
        }
        group.add(SearchPackageOnWebAction())

        // Copy Actions
        group.addSeparator("Copy")
        val copyGroup = DefaultActionGroup("Copy Coordinate", true)
        copyGroup.add(CopyMavenCoordinateAction())
        copyGroup.add(CopyGradleGroovyCoordinateAction())
        copyGroup.add(CopyGradleKotlinCoordinateAction())
        if (selectedPackage.source == PackageSource.NPM) {
            copyGroup.add(CopyNpmCoordinateAction())
        }
        copyGroup.addSeparator()
        copyGroup.add(CopyFullMetadataAction())
        group.add(copyGroup)

        // Dependency Analysis
        group.addSeparator("Analysis")
        group.add(ShowDependencyTreeAction(callbacks.onShowDependencyTree))
        if (selectedPackage.isInstalled) {
            group.add(ShowReverseDependentsAction(callbacks.onShowDependents))
        }
        if (status.isTransitive) {
            group.add(WhyInstalledAction(callbacks.onWhyInstalled))
        }

        // Multi-select actions
        if (isMultiSelect) {
            group.addSeparator("Batch Actions")
            group.add(RemoveSelectedAction(callbacks.onRemoveSelected))
            group.add(UpgradeSelectedAction(callbacks.onUpgradeSelected))
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
        val onUpgradeSelected: (List<UnifiedPackage>) -> Unit = {}
    )
}
