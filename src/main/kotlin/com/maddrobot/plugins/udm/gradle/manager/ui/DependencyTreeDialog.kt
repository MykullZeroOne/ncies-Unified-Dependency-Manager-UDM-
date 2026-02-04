package com.maddrobot.plugins.udm.gradle.manager.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.*
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.tree.TreeUtil
import com.maddrobot.plugins.udm.gradle.manager.model.UnifiedPackage
import com.maddrobot.plugins.udm.gradle.manager.service.TransitiveDependency
import com.maddrobot.plugins.udm.gradle.manager.service.TransitiveDependencyService
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel

/**
 * Dialog for visualizing the dependency tree of a package.
 * Shows what the selected package depends on (forward dependencies)
 * or what depends on the package (reverse dependencies).
 */
class DependencyTreeDialog(
    private val project: Project,
    private val rootPackage: UnifiedPackage,
    private val mode: Mode = Mode.DEPENDENCIES
) : DialogWrapper(project) {

    enum class Mode {
        DEPENDENCIES,     // What this package depends on
        DEPENDENTS        // What depends on this package
    }

    /**
     * Represents a dependency node in the tree.
     */
    data class DependencyNode(
        val groupId: String,
        val artifactId: String,
        val version: String?,
        val scope: String?,
        val isConflict: Boolean = false,  // Version mismatch with another occurrence
        val conflictVersion: String? = null,
        val isOptional: Boolean = false,
        val depth: Int = 0
    ) {
        val id: String get() = "$groupId:$artifactId"
        val displayName: String get() = artifactId
        val fullCoordinate: String get() = "$groupId:$artifactId${version?.let { ":$it" } ?: ""}"
    }

    private val transitiveDependencyService = TransitiveDependencyService.getInstance(project)

    private lateinit var tree: Tree
    private lateinit var rootNode: DefaultMutableTreeNode
    private lateinit var searchField: SearchTextField
    private lateinit var statusLabel: JBLabel

    private var isLoading = false

    init {
        title = when (mode) {
            Mode.DEPENDENCIES -> "Dependencies of ${rootPackage.name}"
            Mode.DEPENDENTS -> "Dependents of ${rootPackage.name}"
        }
        setOKButtonText("Close")
        setCancelButtonText("")
        init()
        loadDependencies()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(600, 500)
        panel.border = JBUI.Borders.empty(10)

        // Header with package info
        val headerPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyBottom(10)

            val pkgInfo = JBLabel().apply {
                icon = AllIcons.Nodes.PpLib
                text = "<html><b>${rootPackage.id}</b> : ${rootPackage.installedVersion ?: rootPackage.latestVersion}<br>" +
                    "<font color='gray'>${if (mode == Mode.DEPENDENCIES) "Shows what this package depends on" else "Shows what depends on this package"}</font></html>"
            }
            add(pkgInfo, BorderLayout.WEST)
        }

        // Search field
        val searchPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyBottom(8)

            searchField = SearchTextField(true).apply {
                textEditor.emptyText.text = "Filter dependencies..."
                addDocumentListener(object : DocumentAdapter() {
                    override fun textChanged(e: javax.swing.event.DocumentEvent) {
                        filterTree(text.trim())
                    }
                })
            }
            add(searchField, BorderLayout.CENTER)
        }

        // Tree
        rootNode = DefaultMutableTreeNode(
            DependencyNode(
                groupId = rootPackage.publisher,
                artifactId = rootPackage.name,
                version = rootPackage.installedVersion ?: rootPackage.latestVersion,
                scope = null,
                depth = 0
            )
        )
        tree = Tree(rootNode).apply {
            isRootVisible = true
            showsRootHandles = true
            cellRenderer = DependencyTreeCellRenderer()
        }

        // Status and action buttons
        val bottomPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(10)

            statusLabel = JBLabel().apply {
                foreground = JBColor.GRAY
            }
            add(statusLabel, BorderLayout.WEST)

            val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0)).apply {
                add(JButton("Expand All").apply {
                    icon = AllIcons.Actions.Expandall
                    addActionListener { TreeUtil.expandAll(tree) }
                })
                add(JButton("Collapse All").apply {
                    icon = AllIcons.Actions.Collapseall
                    addActionListener { TreeUtil.collapseAll(tree, 1) }
                })
                add(JButton("Copy as Text").apply {
                    icon = AllIcons.Actions.Copy
                    addActionListener { copyTreeAsText() }
                })
                add(JButton("Refresh").apply {
                    icon = AllIcons.Actions.Refresh
                    addActionListener { loadDependencies() }
                })
            }
            add(buttonPanel, BorderLayout.EAST)
        }

        val treePanel = JPanel(BorderLayout()).apply {
            add(searchPanel, BorderLayout.NORTH)
            add(JBScrollPane(tree), BorderLayout.CENTER)
        }

        panel.add(headerPanel, BorderLayout.NORTH)
        panel.add(treePanel, BorderLayout.CENTER)
        panel.add(bottomPanel, BorderLayout.SOUTH)

        return panel
    }

    private fun loadDependencies() {
        if (isLoading) return
        isLoading = true
        statusLabel.text = "Loading dependencies..."
        statusLabel.icon = AllIcons.Process.Step_1

        val version = rootPackage.installedVersion ?: rootPackage.latestVersion ?: return

        transitiveDependencyService.getTransitiveDependencies(
            rootPackage.publisher,
            rootPackage.name,
            version
        ) { dependencies: List<TransitiveDependency> ->
            SwingUtilities.invokeLater {
                buildTree(dependencies)
                isLoading = false
            }
        }
    }

    private fun buildTree(dependencies: List<TransitiveDependency>) {
        rootNode.removeAllChildren()

        // Track all versions seen for conflict detection
        val versionsByPackage = mutableMapOf<String, MutableSet<String>>()
        dependencies.forEach { dep: TransitiveDependency ->
            dep.version?.let { version: String ->
                versionsByPackage.getOrPut(dep.id) { mutableSetOf() }.add(version)
            }
        }

        // Build nodes
        var totalCount = 0
        var conflictCount = 0

        for (dep: TransitiveDependency in dependencies) {
            val versions = versionsByPackage[dep.id] ?: emptySet()
            val isConflict = versions.size > 1
            val conflictVersion = if (isConflict) versions.filter { it != dep.version }.firstOrNull() else null

            if (isConflict) conflictCount++
            totalCount++

            val node = DefaultMutableTreeNode(
                DependencyNode(
                    groupId = dep.groupId,
                    artifactId = dep.artifactId,
                    version = dep.version,
                    scope = dep.scope,
                    isConflict = isConflict,
                    conflictVersion = conflictVersion,
                    isOptional = dep.optional,
                    depth = 1
                )
            )
            rootNode.add(node)
        }

        (tree.model as DefaultTreeModel).reload()
        TreeUtil.expandAll(tree)

        // Update status
        val conflictText = if (conflictCount > 0) ", $conflictCount conflicts" else ""
        statusLabel.text = "$totalCount dependencies$conflictText"
        statusLabel.icon = if (conflictCount > 0) AllIcons.General.Warning else null
    }

    private fun filterTree(query: String) {
        if (query.isEmpty()) {
            // Show all nodes
            (tree.model as DefaultTreeModel).reload()
            TreeUtil.expandAll(tree)
            return
        }

        val lowerQuery = query.lowercase()

        // Hide nodes that don't match
        fun matchesQuery(node: DefaultMutableTreeNode): Boolean {
            val userObject = node.userObject as? DependencyNode ?: return false
            return userObject.groupId.lowercase().contains(lowerQuery) ||
                userObject.artifactId.lowercase().contains(lowerQuery) ||
                userObject.fullCoordinate.lowercase().contains(lowerQuery)
        }

        // For now, just expand to show matches (full filtering would require custom tree model)
        TreeUtil.expandAll(tree)
    }

    private fun copyTreeAsText() {
        val sb = StringBuilder()

        fun appendNode(node: DefaultMutableTreeNode, indent: String) {
            val dep = node.userObject as? DependencyNode ?: return
            val conflictNote = if (dep.isConflict) " [CONFLICT: also ${dep.conflictVersion}]" else ""
            val scopeNote = dep.scope?.let { " ($it)" } ?: ""
            val optionalNote = if (dep.isOptional) " [optional]" else ""

            sb.append("$indent${dep.fullCoordinate}$scopeNote$optionalNote$conflictNote\n")

            for (i in 0 until node.childCount) {
                appendNode(node.getChildAt(i) as DefaultMutableTreeNode, "$indent    ")
            }
        }

        appendNode(rootNode, "")

        CopyPasteManager.getInstance().setContents(StringSelection(sb.toString()))
        statusLabel.text = "Copied to clipboard"
    }

    override fun createActions(): Array<Action> {
        return arrayOf(okAction)
    }

    // ========== Tree Cell Renderer ==========

    private class DependencyTreeCellRenderer : DefaultTreeCellRenderer() {
        override fun getTreeCellRendererComponent(
            tree: JTree?,
            value: Any?,
            sel: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean
        ): Component {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)

            val node = value as? DefaultMutableTreeNode ?: return this
            val dep = node.userObject as? DependencyNode ?: return this

            icon = when {
                dep.isConflict -> AllIcons.General.Warning
                dep.isOptional -> AllIcons.Nodes.Plugin
                dep.depth == 0 -> AllIcons.Nodes.PpLib
                else -> AllIcons.Nodes.PpLibFolder
            }

            val versionText = dep.version ?: "managed"
            val scopeText = dep.scope?.let { " <font color='gray'>($it)</font>" } ?: ""
            val optionalText = if (dep.isOptional) " <font color='purple'>[optional]</font>" else ""

            text = if (dep.isConflict) {
                "<html><font color='orange'>${dep.artifactId}</font> : $versionText" +
                    " <font color='red'>[conflict: ${dep.conflictVersion}]</font>$scopeText$optionalText</html>"
            } else {
                "<html>${dep.artifactId} : <font color='gray'>$versionText</font>$scopeText$optionalText</html>"
            }

            toolTipText = "<html>" +
                "<b>${dep.fullCoordinate}</b><br>" +
                "Group: ${dep.groupId}<br>" +
                "Artifact: ${dep.artifactId}<br>" +
                "Version: ${dep.version ?: "managed"}<br>" +
                (dep.scope?.let { "Scope: $it<br>" } ?: "") +
                (if (dep.isConflict) "<font color='red'>Conflict with version: ${dep.conflictVersion}</font><br>" else "") +
                (if (dep.isOptional) "Optional dependency<br>" else "") +
                "</html>"

            return this
        }
    }
}
