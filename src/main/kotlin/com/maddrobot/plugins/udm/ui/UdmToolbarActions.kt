package com.maddrobot.plugins.udm.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ComboBoxAction
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.ui.SearchTextField
import com.maddrobot.plugins.udm.PackageFinderBundle.message
import com.maddrobot.plugins.udm.gradle.manager.service.RepositoryConfig
import com.maddrobot.plugins.udm.licensing.Feature
import com.maddrobot.plugins.udm.licensing.LicenseChecker
import com.maddrobot.plugins.udm.licensing.PremiumFeatureGuard
import javax.swing.*
import java.awt.Dimension
import java.awt.event.KeyEvent

/**
 * Toolbar actions for the Unified Dependency Manager.
 * Provides search, filtering, and batch operations.
 */

// ========== Search Action ==========

/**
 * Embedded search field action for the toolbar.
 * Provides debounced search with 150ms delay.
 */
class SearchPackagesAction(
    private val onSearchChanged: (String) -> Unit
) : AnAction(), CustomComponentAction, DumbAware {

    private var searchField: SearchTextField? = null
    private var searchTimer: Timer? = null
    private val debounceMs = 150

    override fun actionPerformed(e: AnActionEvent) {
        // Focus the search field
        searchField?.requestFocus()
    }

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent {
        return SearchTextField(true).apply {
            textEditor.emptyText.text = message("unified.list.search.placeholder")
            preferredSize = Dimension(200, preferredSize.height)

            addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
                override fun textChanged(e: javax.swing.event.DocumentEvent) {
                    debounceSearch()
                }
            })

            searchField = this
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    private fun debounceSearch() {
        searchTimer?.stop()
        searchTimer = Timer(debounceMs) {
            searchField?.let { field ->
                onSearchChanged(field.text.trim())
            }
        }.apply {
            isRepeats = false
            start()
        }
    }

    fun getText(): String = searchField?.text?.trim() ?: ""

    fun setText(text: String) {
        searchField?.text = text
    }

    fun clearText() {
        searchField?.text = ""
    }
}

// ========== Module Selector Action ==========

/**
 * ComboBox action for selecting which module to filter by.
 */
class ModuleSelectorAction(
    private val onModuleSelected: (String?) -> Unit
) : ComboBoxAction(), DumbAware {

    private var modules: List<String> = emptyList()
    private var selectedModule: String? = null
    private val allModulesText = message("unified.panel.module.all")

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun createPopupActionGroup(button: JComponent, context: DataContext): DefaultActionGroup {
        val group = DefaultActionGroup()

        // Add "All Modules" option
        group.add(object : AnAction(allModulesText) {
            override fun actionPerformed(e: AnActionEvent) {
                selectedModule = null
                onModuleSelected(null)
            }
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        })

        group.addSeparator()

        // Add individual modules
        for (module in modules) {
            group.add(object : AnAction(module) {
                override fun actionPerformed(e: AnActionEvent) {
                    selectedModule = module
                    onModuleSelected(module)
                }
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            })
        }

        return group
    }

    override fun update(e: AnActionEvent) {
        e.presentation.text = selectedModule ?: allModulesText
    }

    fun setModules(newModules: List<String>) {
        modules = newModules
    }

    fun getSelectedModule(): String? = selectedModule
}

// ========== Feed/Repository Selector Action ==========

/**
 * ComboBox action for selecting which repository feed to search.
 */
class FeedSelectorAction(
    private val onFeedSelected: (RepositoryConfig?) -> Unit
) : ComboBoxAction(), DumbAware {

    private var feeds: List<RepositoryConfig> = emptyList()
    private var selectedFeed: RepositoryConfig? = null
    private val allFeedsText = "All Repositories"

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun createPopupActionGroup(button: JComponent, context: DataContext): DefaultActionGroup {
        val group = DefaultActionGroup()

        // Add "All Repositories" option
        group.add(object : AnAction(allFeedsText, null, AllIcons.Actions.Search) {
            override fun actionPerformed(e: AnActionEvent) {
                selectedFeed = null
                onFeedSelected(null)
            }
            override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
        })

        group.addSeparator()

        // Add individual feeds
        for (feed in feeds) {
            group.add(object : AnAction(feed.name) {
                override fun actionPerformed(e: AnActionEvent) {
                    selectedFeed = feed
                    onFeedSelected(feed)
                }
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            })
        }

        return group
    }

    override fun update(e: AnActionEvent) {
        e.presentation.text = selectedFeed?.name ?: allFeedsText
    }

    fun setFeeds(newFeeds: List<RepositoryConfig>) {
        feeds = newFeeds
    }

    fun getSelectedFeed(): RepositoryConfig? = selectedFeed
}

// ========== Prerelease Toggle Action ==========

/**
 * Toggle action for including/excluding prerelease versions.
 */
class PrereleaseToggleAction(
    private val onToggled: (Boolean) -> Unit
) : ToggleAction(
    message("unified.filter.prerelease"),
    "Include prerelease versions (alpha, beta, RC, SNAPSHOT)",
    AllIcons.General.Filter
), DumbAware {

    private var isIncluded = false

    override fun isSelected(e: AnActionEvent): Boolean = isIncluded

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        isIncluded = state
        onToggled(state)
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    fun isPrereleasesIncluded(): Boolean = isIncluded
}

// ========== Refresh Action ==========

/**
 * Action to refresh all dependency data.
 */
class RefreshAction(
    private val onRefresh: () -> Unit
) : DumbAwareAction(
    message("gradle.manager.button.refresh"),
    "Refresh dependency data from build files and repositories",
    AllIcons.Actions.Refresh
) {
    override fun actionPerformed(e: AnActionEvent) {
        onRefresh()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

// ========== Upgrade All Action ==========

/**
 * Action to open bulk upgrade dialog for all packages with updates.
 * PREMIUM FEATURE: Requires Premium license.
 */
class UpgradeAllAction(
    private val project: Project,
    private val onUpgradeAll: () -> Unit
) : DumbAwareAction(
    "Upgrade All",
    "Open bulk upgrade dialog to update all packages with available updates",
    AllIcons.Actions.Upload
) {
    private var hasUpdates = false
    private val isPremium: Boolean get() = LicenseChecker.getInstance().isPremium()

    override fun actionPerformed(e: AnActionEvent) {
        // Gate behind Premium license
        if (!PremiumFeatureGuard.checkOrPrompt(project, Feature.BULK_UPGRADE)) {
            return
        }
        onUpgradeAll()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = hasUpdates
        // Show premium badge in tooltip if not licensed
        if (!isPremium) {
            e.presentation.text = "Upgrade All (Premium)"
            e.presentation.description = "Bulk upgrade requires a Premium license"
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    fun setHasUpdates(value: Boolean) {
        hasUpdates = value
    }
}

// ========== Consolidate Action ==========

/**
 * Action to consolidate package versions across modules.
 * PREMIUM FEATURE: Requires Premium license.
 */
class ConsolidateAction(
    private val project: Project,
    private val onConsolidate: () -> Unit
) : DumbAwareAction(
    "Consolidate Versions",
    "Consolidate inconsistent package versions across modules",
    AllIcons.Actions.GroupBy
) {
    private var hasInconsistentVersions = false
    private val isPremium: Boolean get() = LicenseChecker.getInstance().isPremium()

    override fun actionPerformed(e: AnActionEvent) {
        // Gate behind Premium license
        if (!PremiumFeatureGuard.checkOrPrompt(project, Feature.VERSION_CONSOLIDATION)) {
            return
        }
        onConsolidate()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = hasInconsistentVersions
        // Show premium badge in tooltip if not licensed
        if (!isPremium) {
            e.presentation.text = "Consolidate Versions (Premium)"
            e.presentation.description = "Version consolidation requires a Premium license"
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    fun setHasInconsistentVersions(value: Boolean) {
        hasInconsistentVersions = value
    }
}

// ========== Exclusion Suggestion Action ==========

/**
 * Action to analyze transitive dependencies and suggest exclusions.
 * PREMIUM FEATURE: Requires Premium license.
 */
class ExclusionSuggestionAction(
    private val project: Project,
    private val onSuggestExclusions: () -> Unit
) : DumbAwareAction(
    message("unified.exclusion.suggestion.button"),
    message("unified.exclusion.suggestion.description"),
    AllIcons.General.InspectionsEye
) {
    private val isPremium: Boolean get() = LicenseChecker.getInstance().isPremium()

    override fun actionPerformed(e: AnActionEvent) {
        if (!PremiumFeatureGuard.checkOrPrompt(project, Feature.EXCLUSION_SUGGESTIONS)) {
            return
        }
        onSuggestExclusions()
    }

    override fun update(e: AnActionEvent) {
        if (!isPremium) {
            e.presentation.text = message("unified.exclusion.suggestion.button") + " (Premium)"
            e.presentation.description = "Exclusion suggestions require a Premium license"
        }
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

// ========== Collapse/Expand All Actions ==========

/**
 * Action to collapse all sections in the package list.
 */
class CollapseAllSectionsAction(
    private val onCollapseAll: () -> Unit
) : DumbAwareAction(
    "Collapse All",
    "Collapse all list sections",
    AllIcons.Actions.Collapseall
) {
    override fun actionPerformed(e: AnActionEvent) {
        onCollapseAll()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

/**
 * Action to expand all sections in the package list.
 */
class ExpandAllSectionsAction(
    private val onExpandAll: () -> Unit
) : DumbAwareAction(
    "Expand All",
    "Expand all list sections",
    AllIcons.Actions.Expandall
) {
    override fun actionPerformed(e: AnActionEvent) {
        onExpandAll()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

// ========== Settings Action ==========

/**
 * Action to open repository settings.
 */
class RepositorySettingsAction(
    private val onOpenSettings: () -> Unit
) : DumbAwareAction(
    message("unified.panel.settings"),
    "Manage package repositories",
    AllIcons.General.Settings
) {
    override fun actionPerformed(e: AnActionEvent) {
        onOpenSettings()
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}

// ========== Toolbar Action Group ==========

/**
 * Factory for creating the main UDM toolbar action group.
 */
object UdmToolbarActionGroup {

    /**
     * Create the main toolbar action group with all actions.
     */
    fun create(
        project: Project,
        searchAction: SearchPackagesAction,
        moduleSelectorAction: ModuleSelectorAction,
        feedSelectorAction: FeedSelectorAction,
        prereleaseToggleAction: PrereleaseToggleAction,
        refreshAction: RefreshAction,
        exclusionSuggestionAction: ExclusionSuggestionAction,
        upgradeAllAction: UpgradeAllAction,
        consolidateAction: ConsolidateAction,
        settingsAction: RepositorySettingsAction
    ): DefaultActionGroup {
        return DefaultActionGroup().apply {
            // Search field
            add(searchAction)
            addSeparator()

            // Module selector
            add(moduleSelectorAction)
            addSeparator()

            // Feed selector
            add(feedSelectorAction)
            addSeparator()

            // Prerelease toggle
            add(prereleaseToggleAction)
            addSeparator()

            // Refresh + Exclusion suggestions (next to each other)
            add(refreshAction)
            add(exclusionSuggestionAction)
            addSeparator()

            // Batch operations
            add(upgradeAllAction)
            add(consolidateAction)
            addSeparator()

            // Settings
            add(settingsAction)
        }
    }

    /**
     * Create a simplified toolbar with just essential actions.
     */
    fun createSimple(
        refreshAction: RefreshAction,
        settingsAction: RepositorySettingsAction
    ): DefaultActionGroup {
        return DefaultActionGroup().apply {
            add(refreshAction)
            addSeparator()
            add(settingsAction)
        }
    }
}

// ========== Keyboard Shortcut Registration ==========

/**
 * Keyboard shortcut constants for UDM actions.
 */
object UdmKeyboardShortcuts {
    const val SEARCH = "ctrl F"
    const val REFRESH = "F5"
    const val UPGRADE_ALL = "ctrl shift U"
    const val PRERELEASE_TOGGLE = "ctrl P"
    const val DELETE_PACKAGE = "DELETE"
    const val COPY_COORDINATE = "ctrl C"
    const val COPY_WITH_FORMAT = "ctrl shift C"
    const val SHOW_DEPENDENCY_TREE = "ctrl D"
    const val INSTALL_SELECTED = "ctrl I"
    const val UPDATE_SELECTED = "ctrl U"
    const val ESCAPE = "ESCAPE"

    /**
     * Parse a keyboard shortcut string to a KeyStroke.
     */
    fun parseShortcut(shortcut: String): KeyStroke? {
        return KeyStroke.getKeyStroke(shortcut)
    }
}
