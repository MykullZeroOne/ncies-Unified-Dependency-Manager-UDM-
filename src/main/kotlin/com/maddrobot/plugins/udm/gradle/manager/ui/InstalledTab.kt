package com.maddrobot.plugins.udm.gradle.manager.ui

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.TableView
import com.maddrobot.plugins.udm.PackageFinderBundle.message
import com.maddrobot.plugins.udm.gradle.manager.GradleDependencyManagerService
import com.maddrobot.plugins.udm.gradle.manager.GradleDependencyModifier
import com.maddrobot.plugins.udm.ui.borderPanel
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Represents a UI component for managing installed Gradle dependencies in a project.
 *
 * The `InstalledTab` class provides an interface to display, add, remove, and refresh
 * the list of installed dependencies within a Gradle-based project. It interacts with
 * the `GradleDependencyManagerService` to fetch and manage dependency data, as well as
 * the `GradleDependencyModifier` to make modifications to the project's build files.
 *
 * Features:
 * - Displays a table of installed dependencies using `InstalledDependencyTableModel`.
 * - Allows the user to add new dependencies.
 * - Supports removing selected dependencies with a preview of changes.
 * - Provides a refresh action to update the list of dependencies.
 *
 * Internally, the dependency list is updated via a subscription to the `DEPENDENCY_CHANGE_TOPIC`
 * message bus event.
 *
 * @constructor Creates an `InstalledTab` initialized with the given project. It sets up
 *              the data model, UI components, and event listeners for managing dependencies.
 *
 * @param project The IntelliJ Platform project to which this tab is associated.
 */
class InstalledTab(private val project: Project) {
    private val model = InstalledDependencyTableModel()
    private val table = TableView(model)
    private val service = GradleDependencyManagerService.getInstance(project)
    private val modifier = GradleDependencyModifier(project)

    init {
        project.messageBus.connect().subscribe(
            GradleDependencyManagerService.DEPENDENCY_CHANGE_TOPIC,
            object : GradleDependencyManagerService.DependencyChangeListener {
                override fun onDependenciesChanged() {
                    model.items = service.installedDependencies
                }
            })
    }

    var onAddClicked: (() -> Unit)? = null

    val contentPanel: JPanel = borderPanel {
        val decorator = ToolbarDecorator.createDecorator(table)
            .setAddAction {
                onAddClicked?.invoke()
            }
            .setRemoveAction {
                val selected = table.selectedObject
                if (selected != null) {
                    val virtualFile = LocalFileSystem.getInstance().findFileByPath(selected.buildFile)
                    val document = virtualFile?.let { FileDocumentManager.getInstance().getDocument(it) }
                    if (document != null) {
                        val originalContent = document.text
                        val newContent = modifier.getRemovedContent(selected)
                        if (newContent != null) {
                            val dialog = PreviewDiffDialog(project, selected.buildFile, originalContent, newContent)
                            if (dialog.showAndGet()) {
                                modifier.applyChanges(selected, newContent, "Remove Dependency")
                            }
                        }
                    }
                }
            }
            .addExtraAction(object : com.intellij.openapi.actionSystem.AnAction(
                message("gradle.manager.button.refresh"),
                message("gradle.manager.button.refresh"),
                com.intellij.icons.AllIcons.Actions.Refresh
            ) {
                override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                    service.refresh()
                }

                override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
            })

        add(decorator.createPanel(), BorderLayout.CENTER)
    }

    fun refresh() {
        model.items = service.installedDependencies
    }
}
