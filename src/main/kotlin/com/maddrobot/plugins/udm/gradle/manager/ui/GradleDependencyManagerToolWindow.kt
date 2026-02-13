package com.maddrobot.plugins.udm.gradle.manager.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTabbedPane
import com.maddrobot.plugins.udm.gradle.manager.GradleDependencyManagerService
import com.maddrobot.plugins.udm.ui.borderPanel
import javax.swing.JPanel
import java.awt.BorderLayout

/**
 * A tool window class for managing Gradle dependencies in an IntelliJ-based IDE environment.
 * Provides three main functionalities: viewing installed dependencies, checking for updates,
 * and browsing dependencies for addition to a project.
 *
 * @constructor Initializes the Gradle dependency manager tool window with the given project and disposable parent context.
 * @param project The project instance where the tool window operates.
 * @param parentDisposable The disposable parent associated with this tool window for resource cleanup.
 *
 * Properties:
 * - `contentPanel`: The main UI panel containing the tabbed interface for dependency management.
 * - `installedTab`: A tab displaying the list of installed dependencies and allowing management actions like removal.
 * - `updatesTab`: A tab displaying available updates for managed dependencies along with update actions.
 * - `browseTab`: A tab leveraging the Maven tool window to browse dependencies for potential addition.
 *
 * Functionality:
 * - Initializes the `contentPanel` with a tabbed interface that contains the "Installed", "Updates", and "Browse" tabs.
 * - Sets up the action listeners for tabs, such as switching to the "Browse" tab when adding new dependencies.
 * - Automatically refreshes the state of dependencies through the `GradleDependencyManagerService` upon initialization.
 *
 * Remarks:
 * This tool window streamlines Gradle dependency management for developers, helping to maintain
 * dependency health within the project environment.
 */
class GradleDependencyManagerToolWindow(val project: Project, parentDisposable: Disposable) {
    val contentPanel: JPanel
    private val tabbedPane = JBTabbedPane()
    private val service = GradleDependencyManagerService.getInstance(project)

    private val installedTab = InstalledTab(project)
    private val updatesTab = UpdatesTab(project)

    // Reuse MavenToolWindow for browse for now, but we might want a specialized one later
    private val browseTab = com.maddrobot.plugins.udm.ui.maven.MavenToolWindow(parentDisposable)

    init {
        installedTab.onAddClicked = {
            tabbedPane.selectedIndex = 2 // Browse tab
        }

        contentPanel = borderPanel {
            add(tabbedPane, BorderLayout.CENTER)
        }

        setupTabs()

        // Initial refresh
        service.refresh()
    }

    private fun setupTabs() {
        tabbedPane.addTab(
            com.maddrobot.plugins.udm.PackageFinderBundle.message("gradle.manager.tab.installed"),
            installedTab.contentPanel
        )
        tabbedPane.addTab(
            com.maddrobot.plugins.udm.PackageFinderBundle.message("gradle.manager.tab.updates"),
            updatesTab.contentPanel
        )
        tabbedPane.addTab(
            com.maddrobot.plugins.udm.PackageFinderBundle.message("gradle.manager.tab.browse"),
            browseTab.contentPanel
        )
    }
}
