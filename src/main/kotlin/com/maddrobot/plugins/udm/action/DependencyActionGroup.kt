package com.maddrobot.plugins.udm.action

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.maddrobot.plugins.udm.gradle.manager.GradleDependencyScanner
import com.maddrobot.plugins.udm.gradle.manager.GradleDependencyModifier
import com.maddrobot.plugins.udm.gradle.manager.ui.AddDependencyDialog
import com.maddrobot.plugins.udm.gradle.manager.ui.PreviewDiffDialog
import com.maddrobot.plugins.udm.maven.CentralDependency
import com.maddrobot.plugins.udm.maven.Dependency
import com.maddrobot.plugins.udm.maven.DependencyService.mavenDownloadUrl
import com.maddrobot.plugins.udm.maven.LocalDependency
import com.maddrobot.plugins.udm.maven.NexusDependency
import java.awt.event.MouseEvent

/**
 * Maven Dependency 表格行右键菜单 Action
 *
 * @author drawsta
 * @LastModified: 2025-09-08
 * @since 2025-01-26
 */
object DependencyActionGroup : ActionGroup() {

    private lateinit var selectedDependency: com.maddrobot.plugins.udm.maven.Dependency

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val project = e?.project
        val baseActions = when (selectedDependency) {
            is CentralDependency -> {
                val centralDependency = selectedDependency as CentralDependency

                // 建立 ec -> downloadUrl 映射
                val ecMap = centralDependency.ec.associateWith { ec ->
                    mavenDownloadUrl(
                        group = centralDependency.groupId,
                        artifactId = centralDependency.artifactId,
                        version = centralDependency.version,
                        ec = ec
                    )
                }

                listOfNotNull(
                    ecMap["-sources.jar"]?.let { DownloadArtifactAction.forSource(centralDependency, it) },
                    ecMap[".jar"]?.let { DownloadArtifactAction.forJar(centralDependency, it) },
                    ecMap["-javadoc.jar"]?.let { DownloadArtifactAction.forJavadoc(centralDependency, it) },
                    SearchInMavenRepositoryAction(selectedDependency)
                )
            }

            is LocalDependency -> {
                val localDependency = selectedDependency as LocalDependency
                val pomFilePath = localDependency.pomFilePath
                listOf(
                    OpenContainingFolderAction(pomFilePath)
                )
            }

            is NexusDependency -> {
                val downloadInfos = (selectedDependency as NexusDependency).downloadInfos
                // 建立 extension -> downloadUrl 映射
                val extMap = downloadInfos.associateBy { it.extension }

                listOfNotNull(
                    // fixme: extension key does not contain sources.jar and javadoc.jar
                    extMap["sources.jar"]?.let {
                        DownloadArtifactAction.forSource(
                            selectedDependency,
                            it.downloadUrl
                        )
                    },
                    extMap["jar"]?.let { DownloadArtifactAction.forJar(selectedDependency, it.downloadUrl) },
                    extMap["javadoc.jar"]?.let {
                        DownloadArtifactAction.forJavadoc(
                            selectedDependency,
                            it.downloadUrl
                        )
                    },
                    extMap["pom"]?.let { DownloadArtifactAction.forPom(selectedDependency, it.downloadUrl) },
                    extMap["module"]?.let {
                        DownloadArtifactAction.forModule(
                            selectedDependency,
                            it.downloadUrl
                        )
                    }
                )
            }

            else -> emptyList()
        }.toMutableList()

        if (project != null) {
            baseActions.add(0, AddToProjectAction(project, selectedDependency))
        }

        return baseActions.toTypedArray()
    }

    class AddToProjectAction(private val project: com.intellij.openapi.project.Project, private val dependency: Dependency) : AnAction("Add to Project") {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun actionPerformed(e: AnActionEvent) {
            val scanner = GradleDependencyScanner(project)
            val modifier = GradleDependencyModifier(project)
            val moduleFiles = scanner.getModuleBuildFiles()
            val modules = moduleFiles.keys.toList()
            
            val dialog = AddDependencyDialog(project, dependency.groupId, dependency.artifactId, listOf(dependency.version), modules)
            if (dialog.showAndGet()) {
                val selectedBuildFile = moduleFiles[dialog.selectedModule]?.path ?: return
                val virtualFile = moduleFiles[dialog.selectedModule] ?: return
                val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return
                
                val originalContent = document.text
                val newContent = modifier.getAddedContent(selectedBuildFile, dependency.groupId, dependency.artifactId, dialog.selectedVersion, dialog.selectedConfiguration)
                
                if (newContent != null) {
                    val previewDialog = PreviewDiffDialog(project, selectedBuildFile, originalContent, newContent)
                    if (previewDialog.showAndGet()) {
                        modifier.applyChanges(selectedBuildFile, newContent, "Add Dependency")
                    }
                }
            }
        }
    }

    fun showContextMenu(e: MouseEvent, selectedDependency: Dependency) {
        this.selectedDependency = selectedDependency
        val popupMenu =
            ActionManager.getInstance().createActionPopupMenu("DependencyContextMenu", DependencyActionGroup)
        popupMenu.component.show(e.component, e.x, e.y)
    }
}
