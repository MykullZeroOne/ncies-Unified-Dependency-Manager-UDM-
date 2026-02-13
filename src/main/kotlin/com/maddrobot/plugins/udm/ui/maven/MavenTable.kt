package com.maddrobot.plugins.udm.ui.maven

import com.intellij.codeInsight.hints.presentation.MouseButton
import com.intellij.openapi.ide.CopyPasteManager
import com.maddrobot.plugins.udm.PackageFinderBundle.message
import com.maddrobot.plugins.udm.action.DependencyActionGroup
import com.maddrobot.plugins.udm.maven.Dependency
import com.maddrobot.plugins.udm.maven.DependencyFormat
import com.maddrobot.plugins.udm.maven.DependencyScope
import com.maddrobot.plugins.udm.maven.MavenRepositorySource
import com.maddrobot.plugins.udm.ui.PaginatedTable
import com.maddrobot.plugins.udm.util.showInformationNotification
import java.awt.event.MouseEvent

/**
 * Maven dependency table
 *
 * madd robot tech
 * @LastModified: 2025-07-13
 * @since 2025-01-18
 */
class MavenTable : PaginatedTable<Dependency>(MavenDependencyTableModel()) {

    var dependencyFormat: DependencyFormat = DependencyFormat.GradleGroovyDeclaration

    var dependencyScope: DependencyScope = DependencyScope.COMPILE

    override fun mouseClickedInTable(e: MouseEvent?, selectedRow: Int) {
        // Get the currently selected row's data
        val selectedDependency = tableModel.getItem(selectedRow)

        // On left-button double-click, copy the dependency declaration
        if (e?.clickCount == 2) {
            // Copy the selected dependency to the clipboard
            copyDependencyToClipboard(selectedDependency)
        }
        // On right-click, show a context menu
        if (e?.let { MouseButton.fromEvent(it) } == MouseButton.Right) {
            DependencyActionGroup.showContextMenu(e, selectedDependency)
        }
    }

    fun refreshTable(data: List<Dependency>, mavenRepositorySource: MavenRepositorySource) {
        tableModel as MavenDependencyTableModel
        when (mavenRepositorySource) {
            MavenRepositorySource.CENTRAL -> tableModel.switchToCentralDependencyColumnInfo()
            MavenRepositorySource.LOCAL -> tableModel.switchToLocalDependencyColumnInfo()
            MavenRepositorySource.NEXUS -> tableModel.switchToNexusDependencyColumnInfo()
        }

        tableModel.updateTableData(data)
        refreshTable(1)
    }

    private fun copyDependencyToClipboard(dependency: Dependency) {
        when (dependencyFormat) {
            DependencyFormat.GradleGroovyDeclaration ->
                CopyPasteManager.copyTextToClipboard(dependency.getGradleGroovyDeclaration(dependencyScope))

            DependencyFormat.GradleKotlinDeclaration ->
                CopyPasteManager.copyTextToClipboard(dependency.getGradleKotlinDeclaration(dependencyScope))

            DependencyFormat.MavenDeclaration ->
                CopyPasteManager.copyTextToClipboard(dependency.getMavenDeclaration(dependencyScope))
        }

        showInformationNotification(message("notification.copyToClipboard"))
    }
}
