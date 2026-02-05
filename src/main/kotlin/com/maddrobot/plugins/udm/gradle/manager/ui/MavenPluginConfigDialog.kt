package com.maddrobot.plugins.udm.gradle.manager.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.HyperlinkLabel
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.maddrobot.plugins.udm.PackageFinderBundle.message
import com.maddrobot.plugins.udm.maven.manager.MojoDescriptor
import com.maddrobot.plugins.udm.maven.manager.MojoParameter
import com.maddrobot.plugins.udm.maven.manager.PluginDescriptor
import java.awt.*
import javax.swing.*

/**
 * Dialog for editing Maven plugin configuration.
 * Displays mojo parameters from the plugin descriptor and allows editing values.
 */
class MavenPluginConfigDialog(
    project: Project,
    private val descriptor: PluginDescriptor?,
    private val currentConfiguration: Map<String, String>,
    private val pluginId: String
) : DialogWrapper(project) {

    private val goalComboBox = JComboBox<String>()
    private val parameterPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val parameterInputs = mutableMapOf<String, JComponent>()
    private var selectedMojo: MojoDescriptor? = null

    /**
     * The resulting configuration map after the user clicks Apply.
     */
    val resultConfiguration: Map<String, String>
        get() {
            val config = mutableMapOf<String, String>()
            for ((name, component) in parameterInputs) {
                val value = when (component) {
                    is JCheckBox -> if (component.isSelected) "true" else "false"
                    is JTextField -> component.text.trim()
                    else -> ""
                }
                if (value.isNotBlank()) {
                    config[name] = value
                }
            }
            return config
        }

    init {
        title = message("unified.plugin.configure.maven.title")
        setOKButtonText(message("unified.plugin.configure.apply"))
        init()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(8)
            preferredSize = Dimension(600, 500)
        }

        if (descriptor == null) {
            // Show fallback message when descriptor is not available
            mainPanel.add(createFallbackPanel(), BorderLayout.CENTER)
            return mainPanel
        }

        // Top: Goal selector
        val goalPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        goalPanel.add(JBLabel(message("unified.plugin.configure.maven.goal.label")))

        for (mojo in descriptor.mojos) {
            goalComboBox.addItem(mojo.goal)
        }
        goalComboBox.addActionListener {
            val selectedGoal = goalComboBox.selectedItem as? String
            selectedMojo = descriptor.mojos.find { it.goal == selectedGoal }
            refreshParameterPanel()
        }
        goalPanel.add(goalComboBox)
        mainPanel.add(goalPanel, BorderLayout.NORTH)

        // Center: Parameters (scrollable)
        val scrollPane = JBScrollPane(parameterPanel).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }
        mainPanel.add(scrollPane, BorderLayout.CENTER)

        // Bottom: Documentation link
        val bottomPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            border = JBUI.Borders.emptyTop(8)
        }
        val docsUrl = descriptor.url ?: buildFallbackDocsUrl()
        if (docsUrl != null) {
            val docsLink = HyperlinkLabel(message("unified.plugin.configure.maven.docs"))
            docsLink.setHyperlinkTarget(docsUrl)
            bottomPanel.add(docsLink)
        }
        mainPanel.add(bottomPanel, BorderLayout.SOUTH)

        // Select first mojo by default
        if (descriptor.mojos.isNotEmpty()) {
            goalComboBox.selectedIndex = 0
        }

        return mainPanel
    }

    private fun createFallbackPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 12)).apply {
            border = JBUI.Borders.empty(16)
        }

        panel.add(JBLabel(message("unified.plugin.configure.maven.load.failed")).apply {
            foreground = JBColor.GRAY
        }, BorderLayout.NORTH)

        // Provide a generic key-value editor as fallback
        val kvPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        for ((key, value) in currentConfiguration) {
            addParameterRow(kvPanel, key, "java.lang.String", false, value, null, null)
        }

        // Add empty row for new entries
        addParameterRow(kvPanel, "", "java.lang.String", false, "", null, null)

        panel.add(JBScrollPane(kvPanel), BorderLayout.CENTER)
        return panel
    }

    private fun refreshParameterPanel() {
        parameterPanel.removeAll()
        parameterInputs.clear()

        val mojo = selectedMojo ?: return

        // Header
        if (mojo.description != null) {
            parameterPanel.add(JBLabel(mojo.description).apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(11f)
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.emptyBottom(8)
            })
        }

        // Section label
        parameterPanel.add(JBLabel(message("unified.plugin.configure.maven.parameters.label")).apply {
            font = font.deriveFont(Font.BOLD, 12f)
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(4)
        })

        // Sort parameters: required first, then alphabetically
        val editableParams = mojo.parameters.filter { it.editable }
        val sortedParams = editableParams.sortedWith(compareByDescending<MojoParameter> { it.required }.thenBy { it.name })

        for (param in sortedParams) {
            val currentValue = currentConfiguration[param.name]
            addParameterRow(
                parameterPanel,
                param.name,
                param.type,
                param.required,
                currentValue ?: param.defaultValue ?: "",
                param.description,
                param.expression
            )
        }

        parameterPanel.add(Box.createVerticalGlue())
        parameterPanel.revalidate()
        parameterPanel.repaint()
    }

    private fun addParameterRow(
        container: JPanel,
        name: String,
        type: String,
        required: Boolean,
        initialValue: String,
        description: String?,
        expression: String?
    ) {
        val rowPanel = JPanel(BorderLayout(8, 2)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 60)
            border = JBUI.Borders.emptyBottom(4)
        }

        // Label with required marker
        val labelText = if (required) "*$name" else name
        val label = JBLabel(labelText).apply {
            preferredSize = Dimension(160, 24)
            if (required) {
                font = font.deriveFont(Font.BOLD)
            }
        }
        rowPanel.add(label, BorderLayout.WEST)

        // Input component
        val inputComponent: JComponent = if (isBooleanType(type)) {
            JCheckBox().apply {
                isSelected = initialValue.equals("true", ignoreCase = true)
            }
        } else {
            JTextField(initialValue, 20)
        }
        if (name.isNotBlank()) {
            parameterInputs[name] = inputComponent
        }
        rowPanel.add(inputComponent, BorderLayout.CENTER)

        // Type/default info
        val infoText = buildString {
            if (required) append(message("unified.plugin.configure.maven.parameter.required"))
            else if (expression != null) append(expression)
            else append("($type)")
        }
        val infoLabel = JBLabel(infoText).apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(10f)
            preferredSize = Dimension(160, 24)
        }
        rowPanel.add(infoLabel, BorderLayout.EAST)

        container.add(rowPanel)

        // Tooltip description
        if (description != null) {
            inputComponent.toolTipText = description
            label.toolTipText = description
        }
    }

    private fun isBooleanType(type: String): Boolean {
        return type in listOf("boolean", "java.lang.Boolean", "Boolean")
    }

    private fun buildFallbackDocsUrl(): String? {
        val parts = pluginId.split(":")
        if (parts.size < 2) return null
        val groupId = parts[0]
        val artifactId = parts[1]
        return when {
            groupId.startsWith("org.apache.maven.plugins") ->
                "https://maven.apache.org/plugins/$artifactId/"
            else ->
                "https://search.maven.org/artifact/$groupId/$artifactId"
        }
    }
}
