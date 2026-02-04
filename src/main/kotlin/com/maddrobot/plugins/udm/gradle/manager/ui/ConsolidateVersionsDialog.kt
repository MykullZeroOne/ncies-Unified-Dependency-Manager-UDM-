package com.maddrobot.plugins.udm.gradle.manager.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.maddrobot.plugins.udm.gradle.manager.model.UnifiedPackage
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * Dialog for consolidating inconsistent package versions across modules.
 * Shows packages that have different versions in different modules and allows
 * selecting a target version to consolidate to.
 */
class ConsolidateVersionsDialog(
    private val project: Project,
    private val inconsistentPackages: List<InconsistentPackage>
) : DialogWrapper(project) {

    /**
     * Represents a package with inconsistent versions across modules.
     */
    data class InconsistentPackage(
        val packageId: String,
        val name: String,
        val publisher: String,
        val moduleVersions: Map<String, String>,  // module -> version
        val latestVersion: String?,
        var targetVersion: String? = null
    ) {
        val versions: Set<String> get() = moduleVersions.values.toSet()
        val versionCount: Int get() = versions.size
    }

    private val tableModel = ConsolidateTableModel(inconsistentPackages)
    private lateinit var table: JBTable
    private lateinit var summaryLabel: JBLabel
    private lateinit var previewPanel: JPanel

    init {
        title = "Consolidate Package Versions"
        setOKButtonText("Apply Consolidation")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = Dimension(800, 500)
        panel.border = JBUI.Borders.empty(10)

        // Header
        val headerPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyBottom(10)

            add(JBLabel("<html><b>Packages with Inconsistent Versions</b><br>" +
                "<font color='gray'>These packages have different versions across modules. " +
                "Select a target version for each to consolidate.</font></html>"), BorderLayout.WEST)

            summaryLabel = JBLabel().apply {
                font = font.deriveFont(12f)
            }
            add(summaryLabel, BorderLayout.EAST)
        }

        // Table
        table = JBTable(tableModel).apply {
            setShowGrid(true)
            gridColor = JBColor.border()
            rowHeight = 32

            // Column renderers
            columnModel.getColumn(0).cellRenderer = PackageNameRenderer()
            columnModel.getColumn(1).cellRenderer = VersionsRenderer()
            columnModel.getColumn(2).cellEditor = VersionComboBoxEditor()
            columnModel.getColumn(2).cellRenderer = TargetVersionRenderer()
            columnModel.getColumn(3).cellRenderer = AffectedModulesRenderer()

            // Column widths
            columnModel.getColumn(0).preferredWidth = 200
            columnModel.getColumn(1).preferredWidth = 200
            columnModel.getColumn(2).preferredWidth = 150
            columnModel.getColumn(3).preferredWidth = 250
        }

        // Action buttons
        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply {
            border = JBUI.Borders.emptyTop(10)

            add(JButton("Use Latest for All").apply {
                toolTipText = "Set target version to latest available for all packages"
                addActionListener { setAllToLatest() }
            })
            add(JButton("Use Newest Installed for All").apply {
                toolTipText = "Set target version to newest installed version for all packages"
                addActionListener { setAllToNewestInstalled() }
            })
            add(JButton("Preview Changes").apply {
                icon = AllIcons.Actions.Preview
                addActionListener { showPreview() }
            })
        }

        // Preview panel (initially hidden)
        previewPanel = JPanel(BorderLayout()).apply {
            isVisible = false
            border = BorderFactory.createTitledBorder(
                JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0),
                "Preview"
            )
        }

        val mainPanel = JPanel(BorderLayout()).apply {
            add(JBScrollPane(table), BorderLayout.CENTER)
            add(previewPanel, BorderLayout.SOUTH)
        }

        panel.add(headerPanel, BorderLayout.NORTH)
        panel.add(mainPanel, BorderLayout.CENTER)
        panel.add(buttonPanel, BorderLayout.SOUTH)

        updateSummary()
        return panel
    }

    private fun setAllToLatest() {
        for (pkg in inconsistentPackages) {
            pkg.targetVersion = pkg.latestVersion ?: pkg.versions.maxOrNull()
        }
        tableModel.fireTableDataChanged()
        updateSummary()
    }

    private fun setAllToNewestInstalled() {
        for (pkg in inconsistentPackages) {
            pkg.targetVersion = pkg.versions.maxWithOrNull(VersionComparator()) ?: pkg.versions.first()
        }
        tableModel.fireTableDataChanged()
        updateSummary()
    }

    private fun showPreview() {
        val previewText = StringBuilder("<html><body style='font-family: monospace;'>")
        previewText.append("<h3>Changes to Apply:</h3>")
        previewText.append("<table border='0' cellpadding='4'>")

        for (pkg in inconsistentPackages.filter { it.targetVersion != null }) {
            previewText.append("<tr><td colspan='3'><b>${pkg.name}</b></td></tr>")
            for ((module, version) in pkg.moduleVersions) {
                if (version != pkg.targetVersion) {
                    previewText.append("<tr>")
                    previewText.append("<td>&nbsp;&nbsp;$module</td>")
                    previewText.append("<td><font color='red'>$version</font></td>")
                    previewText.append("<td>→ <font color='green'>${pkg.targetVersion}</font></td>")
                    previewText.append("</tr>")
                }
            }
        }

        previewText.append("</table></body></html>")

        previewPanel.removeAll()
        previewPanel.add(JBScrollPane(JBLabel(previewText.toString())).apply {
            preferredSize = Dimension(0, 150)
        }, BorderLayout.CENTER)
        previewPanel.isVisible = true
        previewPanel.revalidate()
    }

    private fun updateSummary() {
        val configured = inconsistentPackages.count { it.targetVersion != null }
        val total = inconsistentPackages.size
        summaryLabel.text = "$configured of $total packages configured"
    }

    fun getConsolidationResults(): List<ConsolidationResult> {
        return inconsistentPackages
            .filter { it.targetVersion != null }
            .flatMap { pkg ->
                pkg.moduleVersions
                    .filter { (_, version) -> version != pkg.targetVersion }
                    .map { (module, oldVersion) ->
                        ConsolidationResult(
                            packageId = pkg.packageId,
                            moduleName = module,
                            oldVersion = oldVersion,
                            newVersion = pkg.targetVersion!!
                        )
                    }
            }
    }

    data class ConsolidationResult(
        val packageId: String,
        val moduleName: String,
        val oldVersion: String,
        val newVersion: String
    )

    // ========== Table Model ==========

    private inner class ConsolidateTableModel(
        private val packages: List<InconsistentPackage>
    ) : AbstractTableModel() {

        private val columns = arrayOf("Package", "Current Versions", "Target Version", "Affected Modules")

        override fun getRowCount(): Int = packages.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any? {
            val pkg = packages[rowIndex]
            return when (columnIndex) {
                0 -> pkg
                1 -> pkg.versions
                2 -> pkg.targetVersion
                3 -> pkg.moduleVersions.keys
                else -> null
            }
        }

        override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
            if (columnIndex == 2) {
                packages[rowIndex].targetVersion = aValue as? String
                updateSummary()
            }
        }

        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean = columnIndex == 2
    }

    // ========== Cell Renderers ==========

    private class PackageNameRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            if (value is InconsistentPackage) {
                icon = AllIcons.Nodes.PpLib
                text = "<html><b>${value.name}</b><br><font color='gray' size='-2'>${value.publisher}</font></html>"
            }
            return this
        }
    }

    private class VersionsRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            if (value is Set<*>) {
                @Suppress("UNCHECKED_CAST")
                val versions = value as Set<String>
                val coloredVersions = versions.joinToString(", ") { v ->
                    "<font color='orange'>$v</font>"
                }
                text = "<html>$coloredVersions</html>"
                icon = AllIcons.General.Warning
            }
            return this
        }
    }

    private class TargetVersionRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            text = value?.toString() ?: "Select..."
            foreground = if (value != null) JBColor(0x4CAF50, 0x81C784) else JBColor.GRAY
            return this
        }
    }

    private class AffectedModulesRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable?, value: Any?, isSelected: Boolean, hasFocus: Boolean, row: Int, column: Int
        ): Component {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            if (value is Set<*>) {
                @Suppress("UNCHECKED_CAST")
                val modules = value as Set<String>
                text = modules.joinToString(", ")
                icon = AllIcons.Nodes.Module
            }
            return this
        }
    }

    // ========== Cell Editor ==========

    private inner class VersionComboBoxEditor : DefaultCellEditor(JComboBox<String>()) {
        override fun getTableCellEditorComponent(
            table: JTable?, value: Any?, isSelected: Boolean, row: Int, column: Int
        ): Component {
            val comboBox = component as JComboBox<*>
            @Suppress("UNCHECKED_CAST")
            val typedComboBox = comboBox as JComboBox<String>
            typedComboBox.removeAllItems()

            val pkg = inconsistentPackages[row]

            // Add latest version first if available
            pkg.latestVersion?.let { typedComboBox.addItem("$it (latest)") }

            // Add installed versions sorted newest first
            pkg.versions.sortedWith(VersionComparator().reversed()).forEach {
                if (it != pkg.latestVersion) {
                    typedComboBox.addItem(it)
                }
            }

            return comboBox
        }

        override fun getCellEditorValue(): Any? {
            val value = super.getCellEditorValue() as? String
            return value?.replace(" (latest)", "")
        }
    }

    // ========== Version Comparator ==========

    private class VersionComparator : Comparator<String> {
        override fun compare(v1: String, v2: String): Int {
            val parts1 = v1.split("[.\\-_]".toRegex()).mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }
            val parts2 = v2.split("[.\\-_]".toRegex()).mapNotNull { it.takeWhile { c -> c.isDigit() }.toIntOrNull() }

            for (i in 0 until maxOf(parts1.size, parts2.size)) {
                val p1 = parts1.getOrElse(i) { 0 }
                val p2 = parts2.getOrElse(i) { 0 }
                if (p1 != p2) return p1.compareTo(p2)
            }
            return 0
        }
    }

    companion object {
        /**
         * Build a list of inconsistent packages from a list of unified packages.
         */
        fun findInconsistentPackages(packages: List<UnifiedPackage>): List<InconsistentPackage> {
            return packages
                .filter { it.isInstalled && it.installedVersion != null }
                .groupBy { it.id }
                .filter { (_, pkgs) ->
                    // Has multiple versions across modules
                    pkgs.map { it.installedVersion }.distinct().size > 1
                }
                .map { (id, pkgs) ->
                    val first = pkgs.first()
                    val moduleVersions = pkgs
                        .flatMap { pkg -> pkg.modules.map { module -> module to pkg.installedVersion!! } }
                        .toMap()

                    InconsistentPackage(
                        packageId = id,
                        name = first.name,
                        publisher = first.publisher,
                        moduleVersions = moduleVersions,
                        latestVersion = pkgs.mapNotNull { it.latestVersion }.maxWithOrNull(VersionComparator())
                    )
                }
                .sortedBy { it.name }
        }
    }
}
