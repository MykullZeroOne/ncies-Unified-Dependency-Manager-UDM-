package com.maddrobot.plugins.udm.ui.maven

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.observable.properties.PropertyGraph
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SideBorder
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.NamedColorUtil
import com.maddrobot.plugins.udm.PackageFinderBundle.message
import com.maddrobot.plugins.udm.listener.SettingsChangedListener
import com.maddrobot.plugins.udm.maven.*
import com.maddrobot.plugins.udm.setting.PackageFinderSetting
import com.maddrobot.plugins.udm.ui.PackageFinderListCellRenderer
import com.maddrobot.plugins.udm.ui.borderPanel
import com.maddrobot.plugins.udm.ui.boxPanel
import com.maddrobot.plugins.udm.ui.scrollPanel
import com.maddrobot.plugins.udm.util.Icons
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.Box
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

/**
 * refer com.jetbrains.python.packaging.toolwindow.packages.table.PyPackagesTable
 *
 * madd robot tech
 * @LastModified: 2025-09-08
 * @since 2025-01-16
 */
class MavenToolWindow(parentDisposable: Disposable) {
    val contentPanel: JPanel
    private val mavenTable = MavenTable()
    private val searchTextField: SearchTextField

    private val propertyGraph: PropertyGraph = PropertyGraph()
    private val repoSourceProperty = propertyGraph.lazyProperty {
        PackageFinderSetting.instance.repoSource
    }
    private var repoSource: MavenRepositorySource by repoSourceProperty
    private val dependencyFormatProperty = propertyGraph.lazyProperty {
        PackageFinderSetting.instance.dependencyFormat
    }
    private val dependencyScopeProperty = propertyGraph.lazyProperty {
        PackageFinderSetting.instance.dependencyScope
    }

    private val dependencyCache: DependencyCache = DependencyCache()

    init {
        searchTextField = createSearchTextField()

        contentPanel = borderPanel {
            val scrollPanel = scrollPanel {
                val dialogPanel = createPanel()
                viewport.view = dialogPanel
            }
            add(scrollPanel, BorderLayout.CENTER)
        }

        ApplicationManager.getApplication().messageBus.connect(parentDisposable)
            .subscribe(SettingsChangedListener.TOPIC, object : SettingsChangedListener {
                override fun onSettingsChanged() {
                    ApplicationManager.getApplication().invokeLater {
                        repoSourceProperty.set(PackageFinderSetting.instance.repoSource)
                        dependencyFormatProperty.set(PackageFinderSetting.instance.dependencyFormat)
                        dependencyScopeProperty.set(PackageFinderSetting.instance.dependencyScope)
                    }
                }
            })

        // Resource cleanup
        Disposer.register(parentDisposable) {
            mavenTable.dispose()
        }
    }

    private fun createPanel(): JPanel {
        return borderPanel {
            val topToolbar = boxPanel {
                border = SideBorder(NamedColorUtil.getBoundsColor(), SideBorder.BOTTOM)
                // Set the height of topToolbar to 50
                preferredSize = Dimension(preferredSize.width, 50)
                minimumSize = Dimension(minimumSize.width, 50)
                maximumSize = Dimension(maximumSize.width, 50)

                add(searchTextField)
                // Spacing
                add(Box.createRigidArea(Dimension(10, 0)))

                add(repositoryAndDependencyFormatPanel())

                val actionToolbar = createToolbar(this)
                add(actionToolbar.component)
            }
            add(topToolbar, BorderLayout.NORTH)
            val component = mavenTable.createTablePanel()
            add(component, BorderLayout.CENTER)
        }
    }

    private fun createSearchTextField(): SearchTextField {
        return SearchTextField().apply {
            // placeholder
            textEditor.emptyText.text = message("maven.table.searchField.emptyText")
            textEditor.putClientProperty(
                "StatusVisibleFunction",
                java.util.function.Function<javax.swing.JTextField, Boolean> { true })

            // Width
            preferredSize = Dimension(450, preferredSize.height)
            minimumSize = Dimension(450, minimumSize.height)
            maximumSize = Dimension(450, maximumSize.height)

            addKeyboardListener(object : KeyAdapter() {
                override fun keyReleased(e: KeyEvent) {
                    // Listen for Enter key to trigger search from the search field
                    if (e.keyCode == KeyEvent.VK_ENTER) {
                        handleSearch(text.trim())
                    }
                }
            })

            addDocumentListener(object : DocumentAdapter() {
                override fun textChanged(p0: DocumentEvent) {
                    // When clicking the clear button on the right side of the SearchTextField
                    if (textEditor.text.isEmpty()) {
                        refreshTable()
                    }
                }
            })
        }
    }

    private fun createToolbar(panel: JPanel): ActionToolbar {
        val group = DefaultActionGroup()

        // Reload package data for the selected Maven repository source
        // On hover: 'text' is the tooltip; 'description' is shown in the IDE status bar
        val reloadIconButtonAction = object : DumbAwareAction(
            message("maven.table.ReloadButton.text"),
            message("maven.table.ReloadButton.description"),
            Icons.REFRESH.getThemeBasedIcon()
        ) {
            override fun actionPerformed(e: AnActionEvent) {
                // Clear the search field
                searchTextField.text = ""

                lodadAndCacheLocalDependencies()
                refreshTable()
            }

            override fun update(e: AnActionEvent) {
                // The reload button is only visible when the Local repository is selected
                e.presentation.isEnabledAndVisible = repoSource == MavenRepositorySource.LOCAL
            }

            override fun getActionUpdateThread(): ActionUpdateThread {
                return ActionUpdateThread.EDT
            }
        }

        group.add(reloadIconButtonAction)

        val actionToolbar =
            ActionManager.getInstance().createActionToolbar(ActionPlaces.TOOLWINDOW_CONTENT, group, true)
        actionToolbar.targetComponent = panel
        actionToolbar.component.maximumSize = Dimension(70, actionToolbar.component.maximumSize.height)
        // actionToolbar.layoutStrategy = ToolbarLayoutStrategy.NOWRAP_STRATEGY
        return actionToolbar
    }

    private fun repositoryAndDependencyFormatPanel(): DialogPanel {
        return panel {
            row {
                // Maven repository source https://mvnrepository.com/repos
                comboBox(MavenRepositorySource.entries, PackageFinderListCellRenderer)
                    .label(message("maven.RepositorySource.label"))
                    .bindItem(repoSourceProperty)
                    .onChanged {
                        // After switching repository source, restore the table and pagination to their previous state
                        refreshTable(it.item)
                    }
                // Dependency scope
                comboBox(DependencyScope.entries, PackageFinderListCellRenderer)
                    .label(message("maven.DependencyScope.label"))
                    .bindItem(dependencyScopeProperty)
                    .onChanged {
                        // After switching dependency scope, update the table model's dependency scope
                        mavenTable.dependencyScope = it.item
                    }
                // Dependency declaration format
                comboBox(DependencyFormat.entries, PackageFinderListCellRenderer)
                    .label(message("maven.DependencyFormat.label"))
                    .bindItem(dependencyFormatProperty)
                    .onChanged {
                        // After switching dependency format, update the table model's dependency declaration
                        mavenTable.dependencyFormat = it.item
                    }
            }
        }
    }

    private fun handleSearch(text: String) {
        mavenTable.showLoading(true)

        ApplicationManager.getApplication().executeOnPooledThread {
            val searchResult: List<Dependency> = when (repoSource) {
                MavenRepositorySource.CENTRAL -> {
                    val dependencies = DependencyService.searchFromMavenCentral(text)
                    cacheCentralDependencies(dependencies)
                    dependencies
                }

                MavenRepositorySource.LOCAL -> {
                    if (dependencyCache.localDependencies.isEmpty()) {
                        lodadAndCacheLocalDependencies()
                    }
                    dependencyCache.localDependencies.filter {
                        val localDependency = it as LocalDependency
                        localDependency.artifactId.contains(text) || localDependency.groupId.contains(text)
                            || localDependency.name.contains(text) || localDependency.description.contains(text)
                    }
                }

                MavenRepositorySource.NEXUS -> {
                    val dependencies = DependencyService.searchFromNexus(text)
                    cacheNexusDependencies(dependencies)
                    dependencies
                }
            }
            ApplicationManager.getApplication().invokeLater {
                // Refresh the table and update all rows with the search results
                mavenTable.refreshTable(searchResult, repoSource)
                mavenTable.showLoading(false)
            }
        }
    }

    private fun lodadAndCacheLocalDependencies() {
        val dependencies: List<Dependency> = LocalDependency.loadData()
        dependencyCache.localDependencies = dependencies
    }

    private fun cacheCentralDependencies(data: List<Dependency>) {
        dependencyCache.centralDependencies = data
    }

    private fun cacheNexusDependencies(data: List<Dependency>) {
        dependencyCache.nexusDependencies = data
    }

    private fun refreshTable(selectedRepoSource: MavenRepositorySource = repoSource) {
        // Restore all table data when search is cleared, or update all data when searching/loading local Maven dependencies
        // Updating all data also updates the current page data
        when (selectedRepoSource) {
            MavenRepositorySource.CENTRAL -> {
                searchTextField.textEditor.emptyText.text = message("maven.table.searchField.emptyText")
                mavenTable.refreshTable(dependencyCache.centralDependencies, selectedRepoSource)
            }

            MavenRepositorySource.LOCAL -> {
                searchTextField.textEditor.emptyText.text =
                    message("maven.table.local.searchField.emptyText")
                mavenTable.refreshTable(dependencyCache.localDependencies, selectedRepoSource)
            }

            MavenRepositorySource.NEXUS -> {
                searchTextField.textEditor.emptyText.text =
                    message("maven.table.nexus.searchField.emptyText")
                mavenTable.refreshTable(dependencyCache.nexusDependencies, selectedRepoSource)
            }
        }
    }
}
