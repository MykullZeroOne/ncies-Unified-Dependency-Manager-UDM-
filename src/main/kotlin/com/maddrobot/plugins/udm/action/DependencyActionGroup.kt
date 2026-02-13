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
 * Represents an action group for handling dependency-related operations in an IntelliJ-based plugin.
 * It provides contextual actions for different types of dependencies, including central, local, and Nexus dependencies.
 */
object DependencyActionGroup : ActionGroup() {

    private lateinit var selectedDependency: com.maddrobot.plugins.udm.maven.Dependency

    /**
     * Specifies the thread on which updates for this action are processed.
     *
     * This method overrides the `getActionUpdateThread` from the `AnAction` class
     * to define that updates for the action are performed on a background thread (`BGT`).
     *
     * Background threads are suitable for performing non-UI tasks to ensure that
     * time-consuming updates do not block the UI thread.
     *
     * @return The thread type used for action updates. Always returns `ActionUpdateThread.BGT`.
     */
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    /**
     * Retrieves an array of actions based on the selected dependency type and optionally a given project.
     *
     * @param e An optional event parameter that provides the context, including the associated project.
     * @return An array of actions tailored to the type of the selected dependency, allowing operations such as downloading artifacts,
     *         searching in repositories, or opening containing folders, as well as actions specific to the project context if available.
     */
    override fun getChildren(e: AnActionEvent?): Array<AnAction> {
        val project = e?.project
        val baseActions = when (selectedDependency) {
            is CentralDependency -> {
                val centralDependency = selectedDependency as CentralDependency

                // Build ec -> downloadUrl mapping
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
                // Build extension -> downloadUrl mapping
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

    /**
     * An action that facilitates adding a dependency to a Gradle module in an IntelliJ-based project.
     *
     * This action scans the project's Gradle build files to identify the modules available for
     * dependency addition. Upon invocation, it presents a dialog to the user to select the target
     * module, dependency version, and configuration. After these selections, a preview is displayed
     * to show the changes before applying them to the appropriate Gradle build file.
     *
     * The action integrates with IntelliJ's infrastructure and uses background thread execution for
     * updates to ensure responsiveness.
     *
     * @property project The IntelliJ project in which the action is performed.
     * @property dependency The dependency information that needs to be added to the project.
     * @constructor Creates an instance of AddToProjectAction with the given project and dependency.
     */
    class AddToProjectAction(
        private val project: com.intellij.openapi.project.Project,
        private val dependency: Dependency
    ) : AnAction("Add to Project") {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun actionPerformed(e: AnActionEvent) {
            val scanner = GradleDependencyScanner(project)
            val modifier = GradleDependencyModifier(project)
            val moduleFiles = scanner.getModuleBuildFiles()
            val modules = moduleFiles.keys.toList()

            val dialog = AddDependencyDialog(
                project,
                dependency.groupId,
                dependency.artifactId,
                listOf(dependency.version),
                modules
            )
            if (dialog.showAndGet()) {
                val selectedBuildFile = moduleFiles[dialog.selectedModule]?.path ?: return
                val virtualFile = moduleFiles[dialog.selectedModule] ?: return
                val document = FileDocumentManager.getInstance().getDocument(virtualFile) ?: return

                val originalContent = document.text
                val newContent = modifier.getAddedContent(
                    selectedBuildFile,
                    dependency.groupId,
                    dependency.artifactId,
                    dialog.selectedVersion,
                    dialog.selectedConfiguration
                )

                if (newContent != null) {
                    val previewDialog = PreviewDiffDialog(project, selectedBuildFile, originalContent, newContent)
                    if (previewDialog.showAndGet()) {
                        modifier.applyChanges(selectedBuildFile, newContent, "Add Dependency")
                    }
                }
            }
        }
    }

    /**
     * Displays a context menu at the location of a mouse event. The context menu
     * contains actions related to the specified dependency.
     *
     * @param e the mouse event indicating where to show the context menu
     * @param selectedDependency the dependency for which the context menu is being shown
     */
    fun showContextMenu(e: MouseEvent, selectedDependency: Dependency) {
        this.selectedDependency = selectedDependency
        val popupMenu =
            ActionManager.getInstance().createActionPopupMenu("DependencyContextMenu", DependencyActionGroup)
        popupMenu.component.show(e.component, e.x, e.y)
    }
}
