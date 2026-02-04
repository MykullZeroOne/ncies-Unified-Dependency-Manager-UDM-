package com.maddrobot.plugins.udm.gradle.manager.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.observable.properties.PropertyGraph
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.*
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import com.maddrobot.plugins.udm.PackageFinderBundle.message
import com.maddrobot.plugins.udm.gradle.manager.model.UnifiedPackage
import com.maddrobot.plugins.udm.ui.StatusBadge
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * Dialog for bulk upgrading multiple packages at once.
 * Displays a checkbox tree of packages grouped by module or update type.
 */
class BulkUpgradeDialog(
    private val project: Project,
    private val packagesWithUpdates: List<UnifiedPackage>
) : DialogWrapper(project) {

    /**
     * Grouping mode for the package tree.
     */
    enum class GroupingMode {
        BY_MODULE,
        BY_PACKAGE,
        BY_UPDATE_TYPE  // major, minor, patch
    }

    private var groupingMode = GroupingMode.BY_MODULE
    private val selectedPackages = mutableSetOf<String>()

    private lateinit var checkboxTree: CheckboxTree
    private lateinit var rootNode: CheckedTreeNode
    private lateinit var summaryLabel: JBLabel

    init {
        title = "Upgrade All Packages"
        setOKButtonText("Upgrade Selected")
        init()

        // Initially select all packages
        packagesWithUpdates.forEach { selectedPackages.add(it.id) }
        updateTree()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(600, 500)
        panel.border = JBUI.Borders.empty(10)

        // Header with summary and grouping selector
        val headerPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyBottom(10)

            summaryLabel = JBLabel().apply {
                font = font.deriveFont(14f)
            }
            add(summaryLabel, BorderLayout.WEST)

            // Grouping selector
            val groupingPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0)).apply {
                add(JBLabel("Group by:"))
                add(JComboBox(GroupingMode.entries.toTypedArray()).apply {
                    selectedItem = groupingMode
                    addActionListener {
                        groupingMode = selectedItem as GroupingMode
                        updateTree()
                    }
                })
            }
            add(groupingPanel, BorderLayout.EAST)
        }

        // Checkbox tree
        rootNode = CheckedTreeNode("Packages")
        checkboxTree = CheckboxTree(PackageTreeCellRenderer(), rootNode).apply {
            isRootVisible = false
            showsRootHandles = true
        }

        // Selection buttons
        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply {
            border = JBUI.Borders.emptyTop(10)

            add(JButton("Select All").apply {
                addActionListener { selectAll() }
            })
            add(JButton("Deselect All").apply {
                addActionListener { deselectAll() }
            })
            add(JButton("Select Patch Only").apply {
                toolTipText = "Select only patch version updates (e.g., 1.0.0 → 1.0.1)"
                addActionListener { selectByType(UpdateType.PATCH) }
            })
            add(JButton("Select Minor Only").apply {
                toolTipText = "Select only minor version updates (e.g., 1.0.0 → 1.1.0)"
                addActionListener { selectByType(UpdateType.MINOR) }
            })
        }

        panel.add(headerPanel, BorderLayout.NORTH)
        panel.add(JBScrollPane(checkboxTree), BorderLayout.CENTER)
        panel.add(buttonPanel, BorderLayout.SOUTH)

        updateSummary()
        return panel
    }

    private fun updateTree() {
        rootNode.removeAllChildren()

        when (groupingMode) {
            GroupingMode.BY_MODULE -> buildTreeByModule()
            GroupingMode.BY_PACKAGE -> buildTreeByPackage()
            GroupingMode.BY_UPDATE_TYPE -> buildTreeByUpdateType()
        }

        (checkboxTree.model as DefaultTreeModel).reload()
        TreeUtil.expandAll(checkboxTree)
        restoreSelections()
    }

    private fun buildTreeByModule() {
        val byModule = packagesWithUpdates.groupBy { it.modules.firstOrNull() ?: "Unknown" }

        for ((moduleName, packages) in byModule.entries.sortedBy { it.key }) {
            val moduleNode = CheckedTreeNode(ModuleGroupNode(moduleName, packages.size))
            for (pkg in packages.sortedBy { it.name }) {
                val pkgNode = CheckedTreeNode(PackageUpdateNode(pkg))
                moduleNode.add(pkgNode)
            }
            rootNode.add(moduleNode)
        }
    }

    private fun buildTreeByPackage() {
        for (pkg in packagesWithUpdates.sortedBy { it.name }) {
            val pkgNode = CheckedTreeNode(PackageUpdateNode(pkg))
            rootNode.add(pkgNode)
        }
    }

    private fun buildTreeByUpdateType() {
        val byType = packagesWithUpdates.groupBy { categorizeUpdate(it) }

        for (type in listOf(UpdateType.MAJOR, UpdateType.MINOR, UpdateType.PATCH, UpdateType.UNKNOWN)) {
            val packages = byType[type] ?: continue
            val typeNode = CheckedTreeNode(UpdateTypeGroupNode(type, packages.size))
            for (pkg in packages.sortedBy { it.name }) {
                val pkgNode = CheckedTreeNode(PackageUpdateNode(pkg))
                typeNode.add(pkgNode)
            }
            rootNode.add(typeNode)
        }
    }

    private fun categorizeUpdate(pkg: UnifiedPackage): UpdateType {
        val current = parseVersion(pkg.installedVersion ?: return UpdateType.UNKNOWN)
        val latest = parseVersion(pkg.latestVersion ?: return UpdateType.UNKNOWN)

        return when {
            current == null || latest == null -> UpdateType.UNKNOWN
            latest.major > current.major -> UpdateType.MAJOR
            latest.minor > current.minor -> UpdateType.MINOR
            latest.patch > current.patch -> UpdateType.PATCH
            else -> UpdateType.UNKNOWN
        }
    }

    private fun parseVersion(version: String): SemVer? {
        val parts = version.split(".")
        return try {
            SemVer(
                major = parts.getOrNull(0)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0,
                minor = parts.getOrNull(1)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0,
                patch = parts.getOrNull(2)?.takeWhile { it.isDigit() }?.toIntOrNull() ?: 0
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun restoreSelections() {
        fun setChecked(node: DefaultMutableTreeNode) {
            val userObject = node.userObject
            if (node is CheckedTreeNode) {
                when (userObject) {
                    is PackageUpdateNode -> node.isChecked = selectedPackages.contains(userObject.pkg.id)
                    is ModuleGroupNode, is UpdateTypeGroupNode -> {
                        // Parent nodes are checked if all children are checked
                        val allChildrenChecked = (0 until node.childCount).all {
                            val child = node.getChildAt(it) as? CheckedTreeNode
                            val childObj = child?.userObject as? PackageUpdateNode
                            childObj != null && selectedPackages.contains(childObj.pkg.id)
                        }
                        node.isChecked = allChildrenChecked
                    }
                }
            }
            for (i in 0 until node.childCount) {
                setChecked(node.getChildAt(i) as DefaultMutableTreeNode)
            }
        }
        setChecked(rootNode)
        (checkboxTree.model as DefaultTreeModel).reload()
    }

    private fun selectAll() {
        packagesWithUpdates.forEach { selectedPackages.add(it.id) }
        restoreSelections()
        updateSummary()
    }

    private fun deselectAll() {
        selectedPackages.clear()
        restoreSelections()
        updateSummary()
    }

    private fun selectByType(type: UpdateType) {
        selectedPackages.clear()
        packagesWithUpdates
            .filter { categorizeUpdate(it) == type }
            .forEach { selectedPackages.add(it.id) }
        restoreSelections()
        updateSummary()
    }

    private fun updateSummary() {
        val selected = selectedPackages.size
        val total = packagesWithUpdates.size
        summaryLabel.text = "$selected of $total packages selected for upgrade"
    }

    fun getSelectedPackages(): List<UnifiedPackage> {
        return packagesWithUpdates.filter { selectedPackages.contains(it.id) }
    }

    // ========== Data Classes ==========

    data class SemVer(val major: Int, val minor: Int, val patch: Int)

    enum class UpdateType(val displayName: String) {
        MAJOR("Major Updates"),
        MINOR("Minor Updates"),
        PATCH("Patch Updates"),
        UNKNOWN("Other Updates")
    }

    data class ModuleGroupNode(val moduleName: String, val count: Int)
    data class UpdateTypeGroupNode(val type: UpdateType, val count: Int)
    data class PackageUpdateNode(val pkg: UnifiedPackage)

    // ========== Tree Cell Renderer ==========

    private inner class PackageTreeCellRenderer : CheckboxTree.CheckboxTreeCellRenderer() {
        override fun customizeRenderer(
            tree: JTree?,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean
        ) {
            val node = value as? DefaultMutableTreeNode ?: return
            val userObject = node.userObject

            when (userObject) {
                is ModuleGroupNode -> {
                    textRenderer.icon = AllIcons.Nodes.Module
                    textRenderer.append(userObject.moduleName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    textRenderer.append(" (${userObject.count})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is UpdateTypeGroupNode -> {
                    textRenderer.icon = when (userObject.type) {
                        UpdateType.MAJOR -> AllIcons.General.Warning
                        UpdateType.MINOR -> AllIcons.General.Information
                        UpdateType.PATCH -> AllIcons.General.Note
                        UpdateType.UNKNOWN -> AllIcons.Actions.Help
                    }
                    textRenderer.append(userObject.type.displayName, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    textRenderer.append(" (${userObject.count})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is PackageUpdateNode -> {
                    val pkg = userObject.pkg
                    textRenderer.icon = AllIcons.Nodes.PpLib
                    textRenderer.append(pkg.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    textRenderer.append(" ${pkg.installedVersion}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    textRenderer.append(" → ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    textRenderer.append("${pkg.latestVersion}", SimpleTextAttributes(
                        SimpleTextAttributes.STYLE_PLAIN,
                        JBColor(0x4CAF50, 0x81C784)
                    ))
                }
            }
        }
    }
}
