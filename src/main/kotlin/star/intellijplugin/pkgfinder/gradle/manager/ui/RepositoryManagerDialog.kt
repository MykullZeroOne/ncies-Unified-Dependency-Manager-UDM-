package star.intellijplugin.pkgfinder.gradle.manager.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.observable.properties.PropertyGraph
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.JBColor
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.*
import com.intellij.util.ui.JBUI
import star.intellijplugin.pkgfinder.PackageFinderBundle.message
import star.intellijplugin.pkgfinder.gradle.manager.service.RepositoryConfig
import star.intellijplugin.pkgfinder.gradle.manager.service.RepositoryConfigWriter
import star.intellijplugin.pkgfinder.gradle.manager.service.RepositoryDiscoveryService
import star.intellijplugin.pkgfinder.gradle.manager.service.RepositorySource
import star.intellijplugin.pkgfinder.gradle.manager.service.RepositoryType
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.*
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.TreePath

/**
 * Dialog for managing repository configurations.
 * Allows users to view, add, edit, and remove repositories.
 */
class RepositoryManagerDialog(
    private val project: Project
) : DialogWrapper(project) {

    private val discoveryService = RepositoryDiscoveryService.getInstance(project)
    private val configWriter = RepositoryConfigWriter.getInstance(project)

    private var repositories = mutableListOf<RepositoryConfig>()
    private val repositoryListModel = DefaultListModel<RepositoryConfig>()
    private val repositoryList = JList(repositoryListModel)

    init {
        title = message("unified.repo.manager.title")
        setOKButtonText(message("unified.repo.manager.button.close"))
        setCancelButtonText(message("unified.repo.manager.button.cancel"))
        init()
        loadRepositories()
    }

    override fun createCenterPanel(): JComponent {
        return JPanel(BorderLayout()).apply {
            preferredSize = Dimension(600, 450)

            // Repository list with decorator (Add, Edit, Remove buttons)
            repositoryList.apply {
                cellRenderer = RepositoryListCellRenderer()
                selectionMode = ListSelectionModel.SINGLE_SELECTION
            }

            val decorator = ToolbarDecorator.createDecorator(repositoryList)
                .setAddAction { addRepository() }
                .setRemoveAction { removeRepository() }
                .setEditAction { editRepository() }
                .addExtraAction(object : com.intellij.openapi.actionSystem.AnAction(
                    message("unified.repo.manager.button.test"),
                    message("unified.repo.manager.button.test.tooltip"),
                    AllIcons.Actions.Lightning
                ) {
                    override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) {
                        testConnection()
                    }
                    override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.EDT
                })

            val listPanel = decorator.createPanel()

            // Info panel at bottom
            val infoPanel = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.emptyTop(8)
                add(JBLabel(message("unified.repo.manager.info")).apply {
                    foreground = JBColor.GRAY
                    font = font.deriveFont(font.size - 1f)
                }, BorderLayout.CENTER)
            }

            add(listPanel, BorderLayout.CENTER)
            add(infoPanel, BorderLayout.SOUTH)
        }
    }

    private fun loadRepositories() {
        repositories = discoveryService.getConfiguredRepositories().toMutableList()
        repositoryListModel.clear()
        repositories.forEach { repositoryListModel.addElement(it) }
    }

    private fun addRepository() {
        val dialog = AddEditRepositoryDialog(project, null)
        if (dialog.showAndGet()) {
            val repo = dialog.getRepository()
            if (repo != null) {
                saveRepository(repo, dialog.saveTarget)
            }
        }
    }

    private fun editRepository() {
        val selected = repositoryList.selectedValue ?: return

        // Only allow editing custom repositories
        if (selected.source == RepositorySource.BUILTIN) {
            Messages.showWarningDialog(
                project,
                message("unified.repo.manager.edit.builtin.warning"),
                message("unified.repo.manager.edit.title")
            )
            return
        }

        val dialog = AddEditRepositoryDialog(project, selected)
        if (dialog.showAndGet()) {
            val repo = dialog.getRepository()
            if (repo != null) {
                // Remove old and save new
                removeRepositoryFromConfig(selected)
                saveRepository(repo, dialog.saveTarget)
            }
        }
    }

    private fun removeRepository() {
        val selected = repositoryList.selectedValue ?: return

        // Don't allow removing built-in repositories
        if (selected.source == RepositorySource.BUILTIN) {
            Messages.showWarningDialog(
                project,
                message("unified.repo.manager.remove.builtin.warning"),
                message("unified.repo.manager.remove.title")
            )
            return
        }

        val result = Messages.showYesNoDialog(
            project,
            message("unified.repo.manager.remove.confirm", selected.name),
            message("unified.repo.manager.remove.title"),
            Messages.getQuestionIcon()
        )

        if (result == Messages.YES) {
            removeRepositoryFromConfig(selected)
            loadRepositories()
        }
    }

    private fun testConnection() {
        val selected = repositoryList.selectedValue ?: return

        // HTTP HEAD request with authentication
        try {
            val url = java.net.URL(selected.url.trimEnd('/') + "/")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            // Add authentication if credentials are available
            if (selected.username != null || selected.password != null) {
                val credentials = "${selected.username ?: ""}:${selected.password ?: ""}"
                val encoded = java.util.Base64.getEncoder().encodeToString(credentials.toByteArray())
                connection.setRequestProperty("Authorization", "Basic $encoded")
            }

            val responseCode = connection.responseCode
            connection.disconnect()

            if (responseCode in 200..399) {
                val authInfo = if (selected.username != null || selected.password != null) {
                    " (authenticated)"
                } else {
                    " (no credentials)"
                }
                Messages.showInfoMessage(
                    project,
                    message("unified.repo.manager.test.success", selected.name) + authInfo,
                    message("unified.repo.manager.test.title")
                )
            } else {
                val hint = when (responseCode) {
                    401, 403 -> "\n\nHint: Authentication failed. Check your credentials."
                    404 -> "\n\nHint: Repository URL may be incorrect."
                    else -> ""
                }
                Messages.showWarningDialog(
                    project,
                    message("unified.repo.manager.test.failed", responseCode) + hint,
                    message("unified.repo.manager.test.title")
                )
            }
        } catch (e: Exception) {
            Messages.showErrorDialog(
                project,
                message("unified.repo.manager.test.error", e.localizedMessage),
                message("unified.repo.manager.test.title")
            )
        }
    }

    private fun saveRepository(repo: RepositoryConfig, target: SaveTarget) {
        when (target) {
            SaveTarget.GRADLE -> {
                val targetFile = configWriter.getRecommendedGradleTarget()
                if (targetFile != null) {
                    val result = configWriter.getGradleRepositoryAddition(repo, targetFile)
                    if (result != null) {
                        val (original, modified) = result
                        val previewDialog = PreviewDiffDialog(
                            project,
                            targetFile.path,
                            original,
                            modified,
                            message("unified.repo.manager.preview.title")
                        )
                        if (previewDialog.showAndGet()) {
                            configWriter.applyGradleChanges(targetFile, modified, "Add Repository: ${repo.name}")
                            // Show info about credentials if saved
                            if (repo.username != null || repo.password != null) {
                                Messages.showInfoMessage(
                                    project,
                                    "Repository added to ${targetFile.name}.\nCredentials saved to ~/.gradle/gradle.properties",
                                    message("unified.repo.manager.add.title")
                                )
                            }
                            loadRepositories()
                        }
                    } else {
                        Messages.showInfoMessage(
                            project,
                            message("unified.repo.manager.already.exists"),
                            message("unified.repo.manager.add.title")
                        )
                    }
                }
            }
            SaveTarget.MAVEN_SETTINGS -> {
                val result = configWriter.getMavenRepositoryAddition(repo)
                if (result != null) {
                    val (original, modified) = result
                    // For Maven, show a simple confirmation since diff might not be meaningful for new file
                    val confirm = if (original.isEmpty()) {
                        Messages.showYesNoDialog(
                            project,
                            message("unified.repo.manager.maven.create.confirm"),
                            message("unified.repo.manager.add.title"),
                            Messages.getQuestionIcon()
                        )
                    } else {
                        Messages.showYesNoDialog(
                            project,
                            message("unified.repo.manager.maven.modify.confirm"),
                            message("unified.repo.manager.add.title"),
                            Messages.getQuestionIcon()
                        )
                    }
                    if (confirm == Messages.YES) {
                        configWriter.applyMavenChanges(modified)
                        loadRepositories()
                    }
                }
            }
            SaveTarget.MAVEN_POM -> {
                val pomFile = configWriter.getRootPomFile()
                if (pomFile != null) {
                    val result = configWriter.getPomRepositoryAddition(repo, pomFile)
                    if (result != null) {
                        val (original, modified) = result
                        val previewDialog = PreviewDiffDialog(
                            project,
                            pomFile.path,
                            original,
                            modified,
                            message("unified.repo.manager.preview.title")
                        )
                        if (previewDialog.showAndGet()) {
                            configWriter.applyPomChanges(pomFile, modified, "Add Repository: ${repo.name}")
                            // Also save credentials to settings.xml if provided
                            if (repo.username != null || repo.password != null) {
                                val settingsResult = configWriter.getMavenRepositoryAddition(
                                    repo.copy(url = "") // Only add credentials, not the repo itself
                                )
                                // Just save credentials to settings.xml silently
                                if (settingsResult != null) {
                                    configWriter.applyMavenChanges(settingsResult.second)
                                }
                                Messages.showInfoMessage(
                                    project,
                                    "Repository added to pom.xml.\nCredentials saved to ~/.m2/settings.xml",
                                    message("unified.repo.manager.add.title")
                                )
                            }
                            loadRepositories()
                        }
                    } else {
                        Messages.showInfoMessage(
                            project,
                            message("unified.repo.manager.already.exists"),
                            message("unified.repo.manager.add.title")
                        )
                    }
                } else {
                    Messages.showErrorDialog(
                        project,
                        "No pom.xml found in project root.",
                        message("unified.repo.manager.add.title")
                    )
                }
            }
            SaveTarget.PLUGIN_ONLY -> {
                // TODO: Store in plugin settings
                Messages.showInfoMessage(
                    project,
                    message("unified.repo.manager.plugin.only.info"),
                    message("unified.repo.manager.add.title")
                )
            }
        }
    }

    private fun removeRepositoryFromConfig(repo: RepositoryConfig) {
        when (repo.source) {
            RepositorySource.GRADLE_SETTINGS, RepositorySource.GRADLE_BUILD -> {
                val basePath = project.basePath ?: return
                val targetFileName = when (repo.source) {
                    RepositorySource.GRADLE_SETTINGS -> "settings.gradle.kts"
                    else -> "build.gradle.kts"
                }
                val targetFile = LocalFileSystem.getInstance().findFileByPath("$basePath/$targetFileName")
                    ?: LocalFileSystem.getInstance().findFileByPath("$basePath/${targetFileName.removeSuffix(".kts")}")

                if (targetFile != null) {
                    val result = configWriter.getGradleRepositoryRemoval(repo, targetFile)
                    if (result != null) {
                        val (original, modified) = result
                        val previewDialog = PreviewDiffDialog(
                            project,
                            targetFile.path,
                            original,
                            modified,
                            message("unified.repo.manager.preview.remove.title")
                        )
                        if (previewDialog.showAndGet()) {
                            configWriter.applyGradleChanges(targetFile, modified, "Remove Repository: ${repo.name}")
                        }
                    }
                }
            }
            RepositorySource.MAVEN_SETTINGS -> {
                Messages.showInfoMessage(
                    project,
                    message("unified.repo.manager.maven.remove.info"),
                    message("unified.repo.manager.remove.title")
                )
            }
            RepositorySource.PLUGIN_SETTINGS -> {
                // TODO: Remove from plugin settings
            }
            RepositorySource.BUILTIN -> {
                // Cannot remove built-in repos
            }
        }
    }
}

/**
 * Save target for new repositories.
 */
enum class SaveTarget {
    GRADLE,         // Save to settings.gradle or build.gradle
    MAVEN_SETTINGS, // Save to ~/.m2/settings.xml
    MAVEN_POM,      // Save to project's pom.xml
    PLUGIN_ONLY     // Save only in plugin settings (won't affect CLI builds)
}

/**
 * Dialog for adding or editing a repository.
 */
internal class AddEditRepositoryDialog(
    private val project: Project,
    private val existingRepo: RepositoryConfig?
) : DialogWrapper(project) {

    private val configWriter = RepositoryConfigWriter.getInstance(project)
    private val isGradleProject = configWriter.isGradleProject()
    private val isMavenProject = configWriter.isMavenProject()

    private val propertyGraph = PropertyGraph()

    private val nameProperty = propertyGraph.property(existingRepo?.name ?: "")
    private var name by nameProperty

    private val urlProperty = propertyGraph.property(existingRepo?.url ?: "")
    private var url by urlProperty

    private val usernameProperty = propertyGraph.property(existingRepo?.username ?: "")
    private var username by usernameProperty

    private val passwordProperty = propertyGraph.property(existingRepo?.password ?: "")
    private var password by passwordProperty

    // Default to the most appropriate target based on project type
    private val defaultTarget = when {
        isGradleProject -> SaveTarget.GRADLE
        isMavenProject -> SaveTarget.MAVEN_POM
        else -> SaveTarget.MAVEN_SETTINGS
    }
    private val saveTargetProperty = propertyGraph.property(defaultTarget)
    var saveTarget by saveTargetProperty

    private val typeProperty = propertyGraph.property(existingRepo?.type ?: RepositoryType.MAVEN)
    private var type by typeProperty

    init {
        title = if (existingRepo == null) {
            message("unified.repo.manager.add.title")
        } else {
            message("unified.repo.manager.edit.title")
        }
        init()
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row(message("unified.repo.dialog.name")) {
                textField()
                    .bindText(nameProperty)
                    .columns(COLUMNS_MEDIUM)
                    .focused()
            }
            row(message("unified.repo.dialog.url")) {
                textField()
                    .bindText(urlProperty)
                    .columns(COLUMNS_LARGE)
                    .validationOnInput {
                        if (it.text.isNotBlank() && !it.text.startsWith("http")) {
                            error(message("unified.repo.dialog.url.invalid"))
                        } else {
                            null
                        }
                    }
            }
            row(message("unified.repo.dialog.type")) {
                comboBox(RepositoryType.entries.toList())
                    .bindItem(typeProperty)
            }

            collapsibleGroup(message("unified.repo.dialog.credentials")) {
                row(message("unified.repo.dialog.username")) {
                    textField()
                        .bindText(usernameProperty)
                        .columns(COLUMNS_MEDIUM)
                }
                row(message("unified.repo.dialog.password")) {
                    passwordField()
                        .bindText(passwordProperty)
                        .columns(COLUMNS_MEDIUM)
                }
                row {
                    comment("For Azure Artifacts, use your PAT as the password. Username can be empty or any value.")
                }
            }

            separator()

            if (existingRepo == null) {
                buttonsGroup(message("unified.repo.dialog.save.to")) {
                    // Show Gradle option if it's a Gradle project
                    if (isGradleProject) {
                        row {
                            radioButton("Gradle (build.gradle)", SaveTarget.GRADLE)
                                .comment("Add to repositories { } block. Credentials saved to ~/.gradle/gradle.properties")
                        }
                    }
                    // Show pom.xml option if it's a Maven project
                    if (isMavenProject) {
                        row {
                            radioButton("Maven (pom.xml)", SaveTarget.MAVEN_POM)
                                .comment("Add to <repositories> in pom.xml. Credentials saved to ~/.m2/settings.xml")
                        }
                    }
                    // Always show settings.xml option
                    row {
                        radioButton("Maven Global (~/.m2/settings.xml)", SaveTarget.MAVEN_SETTINGS)
                            .comment("Add to global Maven settings. Works for both Maven and Gradle projects.")
                    }
                    row {
                        radioButton(message("unified.repo.dialog.save.plugin"), SaveTarget.PLUGIN_ONLY)
                            .comment(message("unified.repo.dialog.save.plugin.comment"))
                    }
                }.bind({ saveTarget }, { saveTarget = it })
            }
        }
    }

    fun getRepository(): RepositoryConfig? {
        if (name.isBlank() || url.isBlank()) return null

        return RepositoryConfig(
            id = name.lowercase().replace(" ", "-").replace(Regex("[^a-z0-9-]"), ""),
            name = name,
            url = url.trimEnd('/'),
            type = type,
            username = username.takeIf { it.isNotBlank() },
            password = password.takeIf { it.isNotBlank() }
        )
    }
}

/**
 * Cell renderer for the repository list.
 */
internal class RepositoryListCellRenderer : DefaultListCellRenderer() {
    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)

        if (value is RepositoryConfig) {
            text = buildString {
                append("<html><b>${value.name}</b>")
                append("<br><font color='gray' size='-1'>${value.url}</font>")
                if (value.source != RepositorySource.BUILTIN) {
                    append("<font color='#666666' size='-2'> [${value.source.name.lowercase().replace("_", " ")}]</font>")
                }
                append("</html>")
            }

            icon = when (value.type) {
                RepositoryType.MAVEN_CENTRAL -> AllIcons.Nodes.PpLib
                RepositoryType.MAVEN -> AllIcons.Nodes.PpLib
                RepositoryType.NEXUS -> AllIcons.Nodes.Deploy
                RepositoryType.ARTIFACTORY -> AllIcons.Nodes.Deploy
                RepositoryType.AZURE_ARTIFACTS -> AllIcons.Providers.Azure
                RepositoryType.JITPACK -> AllIcons.Vcs.Branch
                RepositoryType.GRADLE_PLUGIN_PORTAL -> AllIcons.Nodes.Plugin
                RepositoryType.NPM -> AllIcons.Nodes.Module
                RepositoryType.CUSTOM -> AllIcons.Nodes.Artifact
            }
        }

        return this
    }
}
