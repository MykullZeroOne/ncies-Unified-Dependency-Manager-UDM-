package com.maddrobot.plugins.udm.gradle.manager.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CheckBoxList
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.maddrobot.plugins.udm.PackageFinderBundle.message
import com.maddrobot.plugins.udm.gradle.manager.model.DependencyExclusion
import com.maddrobot.plugins.udm.gradle.manager.model.UnifiedPackage
import com.maddrobot.plugins.udm.gradle.manager.service.TransitiveDependency
import com.maddrobot.plugins.udm.gradle.manager.service.TransitiveDependencyService
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

/**
 * Dialog for managing dependency exclusions with multi-select list of transitive dependencies.
 * Shows all transitive dependencies of a package and allows selecting multiple to exclude.
 */
class ManageExclusionsDialog(
    private val project: Project,
    private val pkg: UnifiedPackage
) : DialogWrapper(project) {

    private val transitiveDependencyService = TransitiveDependencyService.getInstance(project)
    private val checkBoxList = CheckBoxList<DependencyItem>()
    private val loadingLabel = JBLabel(message("unified.exclusion.manage.loading")).apply {
        icon = AllIcons.Process.Step_1
        foreground = JBColor.GRAY
    }
    private val contentPanel = JPanel(BorderLayout())
    private var isLoading = true
    private var dependencies: List<TransitiveDependency> = emptyList()

    // Track which dependencies are already excluded
    private val existingExclusions: Set<String> = pkg.exclusions.map { it.id }.toSet()

    init {
        title = message("unified.exclusion.manage.title", pkg.id)
        setOKButtonText(message("unified.exclusion.manage.button"))
        init()
        loadTransitiveDependencies()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout()).apply {
            preferredSize = Dimension(500, 400)
            border = JBUI.Borders.empty(8)
        }

        // Header with instructions
        val headerPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyBottom(12)
            add(JBLabel(message("unified.exclusion.manage.header", pkg.id)), BorderLayout.CENTER)
        }
        mainPanel.add(headerPanel, BorderLayout.NORTH)

        // Content panel - shows loading or list
        contentPanel.add(loadingLabel, BorderLayout.CENTER)
        mainPanel.add(contentPanel, BorderLayout.CENTER)

        // Button panel for select all / deselect all
        val buttonPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = JBUI.Borders.emptyTop(8)

            add(JButton(message("unified.exclusion.manage.select.all")).apply {
                addActionListener { selectAll(true) }
            })
            add(Box.createHorizontalStrut(8))
            add(JButton(message("unified.exclusion.manage.deselect.all")).apply {
                addActionListener { selectAll(false) }
            })
            add(Box.createHorizontalGlue())
        }
        mainPanel.add(buttonPanel, BorderLayout.SOUTH)

        return mainPanel
    }

    private fun loadTransitiveDependencies() {
        val version = pkg.installedVersion ?: pkg.latestVersion ?: return

        transitiveDependencyService.getTransitiveDependencies(pkg.publisher, pkg.name, version) { deps ->
            SwingUtilities.invokeLater {
                dependencies = deps
                isLoading = false
                updateList()
            }
        }
    }

    private fun updateList() {
        contentPanel.removeAll()

        if (dependencies.isEmpty()) {
            contentPanel.add(JBLabel(message("unified.exclusion.manage.empty")).apply {
                horizontalAlignment = SwingConstants.CENTER
                foreground = JBColor.GRAY
            }, BorderLayout.CENTER)
        } else {
            // Populate checkbox list
            val items = dependencies.map { dep ->
                val coordinate = "${dep.groupId}:${dep.artifactId}"
                val versionStr = dep.version ?: message("unified.tree.dialog.version.managed")
                val displayText = "$coordinate:$versionStr"
                val isAlreadyExcluded = existingExclusions.contains(coordinate) ||
                    existingExclusions.contains(dep.groupId)
                DependencyItem(
                    groupId = dep.groupId,
                    artifactId = dep.artifactId,
                    version = dep.version,
                    displayText = displayText,
                    isAlreadyExcluded = isAlreadyExcluded
                )
            }.sortedBy { it.displayText }

            checkBoxList.clear()
            for (item in items) {
                checkBoxList.addItem(item, item.displayText, !item.isAlreadyExcluded && false)
            }

            // Disable already excluded items
            for (i in 0 until checkBoxList.itemsCount) {
                val item = checkBoxList.getItemAt(i)
                if (item?.isAlreadyExcluded == true) {
                    checkBoxList.setItemSelected(item, true)
                }
            }

            val scrollPane = JBScrollPane(checkBoxList).apply {
                preferredSize = Dimension(0, 300)
            }
            contentPanel.add(scrollPane, BorderLayout.CENTER)
        }

        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun selectAll(selected: Boolean) {
        for (i in 0 until checkBoxList.itemsCount) {
            val item = checkBoxList.getItemAt(i)
            if (item != null && !item.isAlreadyExcluded) {
                checkBoxList.setItemSelected(item, selected)
            }
        }
    }

    override fun doOKAction() {
        if (getSelectedExclusions().isEmpty()) {
            // No new exclusions selected
            super.doOKAction()
            return
        }
        super.doOKAction()
    }

    /**
     * Get the list of newly selected exclusions (not including already excluded ones).
     */
    fun getSelectedExclusions(): List<DependencyExclusion> {
        val selected = mutableListOf<DependencyExclusion>()
        for (i in 0 until checkBoxList.itemsCount) {
            val item = checkBoxList.getItemAt(i)
            if (item != null && !item.isAlreadyExcluded && checkBoxList.isItemSelected(item)) {
                selected.add(DependencyExclusion(
                    groupId = item.groupId,
                    artifactId = item.artifactId
                ))
            }
        }
        return selected
    }

    /**
     * Data class for items in the checkbox list.
     */
    data class DependencyItem(
        val groupId: String,
        val artifactId: String,
        val version: String?,
        val displayText: String,
        val isAlreadyExcluded: Boolean
    ) {
        override fun toString(): String = if (isAlreadyExcluded) "$displayText ${message("unified.exclusion.manage.already.excluded")}" else displayText
    }
}
