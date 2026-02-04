package com.maddrobot.plugins.udm.gradle.manager.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTabbedPane
import com.maddrobot.plugins.udm.gradle.manager.GradleDependencyManagerService
import com.maddrobot.plugins.udm.ui.borderPanel
import javax.swing.JPanel
import java.awt.BorderLayout

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
        tabbedPane.addTab(com.maddrobot.plugins.udm.PackageFinderBundle.message("gradle.manager.tab.installed"), installedTab.contentPanel)
        tabbedPane.addTab(com.maddrobot.plugins.udm.PackageFinderBundle.message("gradle.manager.tab.updates"), updatesTab.contentPanel)
        tabbedPane.addTab(com.maddrobot.plugins.udm.PackageFinderBundle.message("gradle.manager.tab.browse"), browseTab.contentPanel)
    }
}
