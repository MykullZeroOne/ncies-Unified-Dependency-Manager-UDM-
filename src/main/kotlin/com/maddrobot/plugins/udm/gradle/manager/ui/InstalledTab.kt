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

class InstalledTab(private val project: Project) {
    private val model = InstalledDependencyTableModel()
    private val table = TableView(model)
    private val service = GradleDependencyManagerService.getInstance(project)
    private val modifier = GradleDependencyModifier(project)

    init {
        project.messageBus.connect().subscribe(GradleDependencyManagerService.DEPENDENCY_CHANGE_TOPIC, object : GradleDependencyManagerService.DependencyChangeListener {
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
            .addExtraAction(object : com.intellij.openapi.actionSystem.AnAction(message("gradle.manager.button.refresh"), message("gradle.manager.button.refresh"), com.intellij.icons.AllIcons.Actions.Refresh) {
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
