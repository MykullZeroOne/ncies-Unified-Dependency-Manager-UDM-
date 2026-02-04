package com.maddrobot.plugins.udm.gradle.manager.ui

import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.TableView
import com.intellij.util.ui.ListTableModel
import com.maddrobot.plugins.udm.PackageFinderBundle.message
import com.maddrobot.plugins.udm.gradle.manager.DependencyUpdate
import com.maddrobot.plugins.udm.gradle.manager.GradleDependencyManagerService
import com.maddrobot.plugins.udm.gradle.manager.GradleDependencyModifier
import com.maddrobot.plugins.udm.ui.TableColumnInfo
import com.maddrobot.plugins.udm.ui.borderPanel
import java.awt.BorderLayout
import javax.swing.JPanel

class UpdatesTab(private val project: Project) {
    private val service = GradleDependencyManagerService.getInstance(project)
    private val modifier = GradleDependencyModifier(project)
    private val model = ListTableModel<DependencyUpdate>(
        TableColumnInfo<DependencyUpdate>(message("gradle.manager.column.artifactId")) { it.installed.artifactId },
        TableColumnInfo<DependencyUpdate>(message("gradle.manager.column.groupId")) { it.installed.groupId },
        TableColumnInfo<DependencyUpdate>(message("gradle.manager.column.version")) { it.installed.version },
        TableColumnInfo<DependencyUpdate>(message("gradle.manager.column.latestVersion")) { it.latestVersion },
        TableColumnInfo<DependencyUpdate>(message("gradle.manager.column.module")) { it.installed.moduleName }
    )
    private val table = TableView(model)

    init {
        project.messageBus.connect().subscribe(GradleDependencyManagerService.DEPENDENCY_CHANGE_TOPIC, object : GradleDependencyManagerService.DependencyChangeListener {
            override fun onDependenciesChanged() {
                model.items = service.dependencyUpdates
            }
        })
    }

    val contentPanel: JPanel = borderPanel {
        val decorator = ToolbarDecorator.createDecorator(table)
            .disableAddAction()
            .disableRemoveAction()
            .disableUpDownActions()
            .addExtraAction(object : com.intellij.openapi.actionSystem.AnAction(message("gradle.manager.button.refresh"), message("gradle.manager.button.refresh"), com.intellij.icons.AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                    service.refresh()
                }
                override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
            })
            .addExtraAction(object : com.intellij.openapi.actionSystem.AnAction(message("gradle.manager.button.update"), message("gradle.manager.button.update"), com.intellij.icons.AllIcons.Actions.Checked) {
                override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                    val selected = table.selectedObject
                    if (selected != null) {
                        val virtualFile = LocalFileSystem.getInstance().findFileByPath(selected.installed.buildFile)
                        val document = virtualFile?.let { FileDocumentManager.getInstance().getDocument(it) }
                        if (document != null) {
                            val originalContent = document.text
                            val newContent = modifier.getUpdatedContent(selected.installed, selected.latestVersion)
                            if (newContent != null) {
                                val dialog = PreviewDiffDialog(project, selected.installed.buildFile, originalContent, newContent)
                                if (dialog.showAndGet()) {
                                    modifier.applyChanges(selected.installed, newContent, "Update Dependency")
                                }
                            }
                        }
                    }
                }
                override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
            })
        
        add(decorator.createPanel(), BorderLayout.CENTER)
    }

    fun refresh() {
        model.items = service.dependencyUpdates
    }
}
