package star.intellijplugin.pkgfinder.gradle.manager.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import star.intellijplugin.pkgfinder.PackageFinderBundle.message
import java.awt.*
import javax.swing.*

/**
 * Main tool window panel with tab bar (Packages, Repositories, Caches, Log).
 * Provides NuGet-style interface for the Unified Dependency Manager.
 */
class MainToolWindowPanel(
    private val project: Project,
    private val parentDisposable: Disposable
) : SimpleToolWindowPanel(true, true) {

    private val tabbedPane = JBTabbedPane()

    // Tab panels
    private val packagesPanel: UnifiedDependencyPanel
    private val repositoriesPanel: JPanel
    private val cachesPanel: JPanel
    private val logPanel: JPanel

    val contentPanel: JPanel

    // Tab button management - must be initialized before init block
    private val tabButtons = mutableListOf<JToggleButton>()
    private val tabButtonGroup = ButtonGroup()

    init {
        // Create tab content panels
        packagesPanel = UnifiedDependencyPanel(project, parentDisposable)
        repositoriesPanel = createRepositoriesPanel()
        cachesPanel = createCachesPanel()
        logPanel = createLogPanel()

        // Build the main panel
        contentPanel = JPanel(BorderLayout()).apply {
            add(createHeaderPanel(), BorderLayout.NORTH)
            add(createMainContentPanel(), BorderLayout.CENTER)
        }

        setContent(contentPanel)
    }

    private fun createHeaderPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 8, 0, 8)
            background = JBColor.PanelBackground

            // Left side: UDP logo/title and tabs
            val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false

                // UDP logo/title
                add(JBLabel(message("unified.main.title")).apply {
                    font = font.deriveFont(Font.BOLD, 14f)
                    icon = AllIcons.Nodes.PpLib
                    border = JBUI.Borders.emptyRight(16)
                })

                // Tab buttons
                add(createTabButton(message("unified.tab.packages"), 0, true))
                add(createTabButton(message("unified.tab.repositories"), 1, false))
                add(createTabButton(message("unified.tab.caches"), 2, false))
                add(createTabButton(message("unified.tab.log"), 3, false))
            }

            // Right side: Project selector and menu
            val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                isOpaque = false

                add(JBLabel(message("unified.panel.module")))
                add(packagesPanel.getModuleSelector().apply {
                    preferredSize = Dimension(120, preferredSize.height)
                })
                add(createMenuButton())
            }

            add(leftPanel, BorderLayout.WEST)
            add(rightPanel, BorderLayout.EAST)
        }
    }

    private fun createTabButton(text: String, tabIndex: Int, selected: Boolean): JToggleButton {
        return JToggleButton(text).apply {
            isFocusPainted = false
            isBorderPainted = false
            isContentAreaFilled = false
            isOpaque = false
            font = font.deriveFont(13f)
            foreground = if (selected) JBColor(0x4A90D9, 0x589DF6) else JBColor.foreground()
            border = JBUI.Borders.empty(8, 16)
            isSelected = selected

            // Underline for selected tab
            if (selected) {
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 2, 0, JBColor(0x4A90D9, 0x589DF6)),
                    JBUI.Borders.empty(8, 16, 6, 16)
                )
            }

            addActionListener {
                selectTab(tabIndex)
            }

            tabButtons.add(this)
            tabButtonGroup.add(this)
        }
    }

    private fun selectTab(index: Int) {
        // Update button styles
        tabButtons.forEachIndexed { i, button ->
            val isSelected = i == index
            button.foreground = if (isSelected) JBColor(0x4A90D9, 0x589DF6) else JBColor.foreground()
            button.border = if (isSelected) {
                BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 2, 0, JBColor(0x4A90D9, 0x589DF6)),
                    JBUI.Borders.empty(8, 16, 6, 16)
                )
            } else {
                JBUI.Borders.empty(8, 16)
            }
        }

        // Switch tab content
        tabbedPane.selectedIndex = index
    }

    private fun createMenuButton(): JButton {
        return JButton(AllIcons.Actions.More).apply {
            isBorderPainted = false
            isContentAreaFilled = false
            toolTipText = message("unified.main.menu")
            preferredSize = Dimension(28, 28)
            addActionListener {
                showPopupMenu(this)
            }
        }
    }

    private fun showPopupMenu(component: Component) {
        val menu = JPopupMenu()
        menu.add(JMenuItem(message("unified.panel.settings")).apply {
            icon = AllIcons.General.Settings
            addActionListener {
                RepositoryManagerDialog(project).show()
            }
        })
        menu.addSeparator()
        menu.add(JMenuItem(message("unified.main.refresh")).apply {
            icon = AllIcons.Actions.Refresh
            addActionListener {
                packagesPanel.refresh()
            }
        })
        menu.show(component, 0, component.height)
    }

    private fun createMainContentPanel(): JPanel {
        return JPanel(CardLayout()).apply {
            // Use CardLayout for tab switching
            add(packagesPanel.getContentWithoutHeader(), "packages")
            add(repositoriesPanel, "repositories")
            add(cachesPanel, "caches")
            add(logPanel, "log")

            // Create a wrapper that switches cards
            tabbedPane.addChangeListener {
                val layout = this.layout as CardLayout
                when (tabbedPane.selectedIndex) {
                    0 -> layout.show(this, "packages")
                    1 -> layout.show(this, "repositories")
                    2 -> layout.show(this, "caches")
                    3 -> layout.show(this, "log")
                }
            }
        }.also { cardPanel ->
            // Initialize with packages tab
            tabbedPane.addTab("Packages", JPanel())
            tabbedPane.addTab("Repositories", JPanel())
            tabbedPane.addTab("Caches", JPanel())
            tabbedPane.addTab("Log", JPanel())
            tabbedPane.selectedIndex = 0
        }
    }

    private fun createRepositoriesPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            val label = JBLabel(message("unified.tab.repositories.coming")).apply {
                horizontalAlignment = SwingConstants.CENTER
                foreground = JBColor.GRAY
            }
            add(label, BorderLayout.CENTER)
        }
    }

    private fun createCachesPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            val label = JBLabel(message("unified.tab.caches.coming")).apply {
                horizontalAlignment = SwingConstants.CENTER
                foreground = JBColor.GRAY
            }
            add(label, BorderLayout.CENTER)
        }
    }

    private fun createLogPanel(): JPanel {
        return JPanel(BorderLayout()).apply {
            val label = JBLabel(message("unified.tab.log.coming")).apply {
                horizontalAlignment = SwingConstants.CENTER
                foreground = JBColor.GRAY
            }
            add(label, BorderLayout.CENTER)
        }
    }
}
