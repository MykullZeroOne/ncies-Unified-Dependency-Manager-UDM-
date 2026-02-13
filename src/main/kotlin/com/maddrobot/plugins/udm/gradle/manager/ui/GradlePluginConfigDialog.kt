package com.maddrobot.plugins.udm.gradle.manager.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.maddrobot.plugins.udm.PackageFinderBundle.message
import com.maddrobot.plugins.udm.gradle.manager.ConfigProperty
import com.maddrobot.plugins.udm.gradle.manager.GradlePluginConfig
import java.awt.*
import javax.swing.*

/**
 * Dialog for editing Gradle plugin extension block configuration.
 * Provides a visual property editor and a raw text editor mode.
 */
class GradlePluginConfigDialog(
    project: Project,
    private val pluginId: String,
    private val initialExtensionName: String?,
    private val existingConfig: GradlePluginConfig?,
    private val isKotlinDsl: Boolean
) : DialogWrapper(project) {

    private val extensionNameField = JBTextField(initialExtensionName ?: "", 20)
    private val propertyRows = mutableListOf<PropertyRow>()
    private val propertyListPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val rawEditorArea = JBTextArea(10, 40).apply {
        lineWrap = false
        border = JBUI.Borders.empty(4)
    }
    private val previewArea = JBTextArea(5, 40).apply {
        isEditable = false
        lineWrap = false
        border = JBUI.Borders.empty(4)
        foreground = JBColor.GRAY
    }

    private var isRawMode = false
    private val cardLayout = CardLayout()
    private val editorCards = JPanel(cardLayout)
    private val toggleButton = JButton(message("unified.plugin.configure.gradle.raw.editor"))

    private data class PropertyRow(
        val nameField: JBTextField,
        val valueField: JBTextField,
        val removeButton: JButton,
        val panel: JPanel
    )

    /**
     * The extension block name entered by the user.
     */
    val extensionName: String get() = extensionNameField.text.trim()

    /**
     * The resulting properties from the visual editor.
     */
    val resultProperties: List<ConfigProperty>
        get() = propertyRows
            .filter { it.nameField.text.trim().isNotBlank() }
            .map { ConfigProperty(name = it.nameField.text.trim(), value = it.valueField.text.trim()) }

    /**
     * The raw block content from the raw editor (inner content without braces).
     */
    val rawBlockContent: String get() = rawEditorArea.text

    /**
     * Whether the user used raw editor mode for their final input.
     */
    val isRawEditorMode: Boolean get() = isRawMode

    init {
        title = message("unified.plugin.configure.gradle.title")
        setOKButtonText(message("unified.plugin.configure.apply"))
        init()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(8)
            preferredSize = Dimension(550, 500)
        }

        // Top: Extension name
        val topPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        topPanel.add(JBLabel(message("unified.plugin.configure.gradle.extension.label")))
        topPanel.add(extensionNameField)
        if (initialExtensionName == null) {
            topPanel.add(JBLabel(message("unified.plugin.configure.gradle.no.known.extension")).apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(10f)
            })
        }
        extensionNameField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = updatePreview()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = updatePreview()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = updatePreview()
        })
        mainPanel.add(topPanel, BorderLayout.NORTH)

        // Center: Editor area (card layout for visual/raw toggle)
        val centerPanel = JPanel(BorderLayout(0, 4))

        // Visual editor
        val visualPanel = JPanel(BorderLayout(0, 4))
        visualPanel.add(JBLabel(message("unified.plugin.configure.gradle.properties.label")).apply {
            font = font.deriveFont(Font.BOLD, 12f)
            border = JBUI.Borders.emptyBottom(4)
        }, BorderLayout.NORTH)
        visualPanel.add(JBScrollPane(propertyListPanel).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }, BorderLayout.CENTER)

        // Add property button
        val addButton = JButton(message("unified.plugin.configure.gradle.add.property"))
        addButton.addActionListener { addPropertyRow("", "") }
        val addPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        addPanel.add(addButton)
        visualPanel.add(addPanel, BorderLayout.SOUTH)

        // Raw editor
        val rawPanel = JPanel(BorderLayout())
        rawPanel.add(JBScrollPane(rawEditorArea), BorderLayout.CENTER)

        editorCards.add(visualPanel, "visual")
        editorCards.add(rawPanel, "raw")
        cardLayout.show(editorCards, "visual")

        centerPanel.add(editorCards, BorderLayout.CENTER)

        // Toggle button
        toggleButton.addActionListener {
            isRawMode = !isRawMode
            if (isRawMode) {
                // Sync visual -> raw
                rawEditorArea.text = generateRawContent()
                cardLayout.show(editorCards, "raw")
                toggleButton.text = message("unified.plugin.configure.gradle.visual.editor")
            } else {
                cardLayout.show(editorCards, "visual")
                toggleButton.text = message("unified.plugin.configure.gradle.raw.editor")
            }
            updatePreview()
        }
        val togglePanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        togglePanel.add(toggleButton)
        centerPanel.add(togglePanel, BorderLayout.SOUTH)

        mainPanel.add(centerPanel, BorderLayout.CENTER)

        // Bottom: Preview
        val previewPanel = JPanel(BorderLayout(0, 4)).apply {
            border = JBUI.Borders.emptyTop(8)
        }
        previewPanel.add(JBLabel(message("unified.plugin.configure.gradle.preview")).apply {
            font = font.deriveFont(Font.BOLD, 11f)
        }, BorderLayout.NORTH)
        previewPanel.add(JBScrollPane(previewArea).apply {
            preferredSize = Dimension(0, 100)
        }, BorderLayout.CENTER)
        mainPanel.add(previewPanel, BorderLayout.SOUTH)

        // Initialize with existing properties
        initializeFromExistingConfig()
        updatePreview()

        return mainPanel
    }

    private fun initializeFromExistingConfig() {
        val props = existingConfig?.properties ?: emptyList()
        if (props.isNotEmpty()) {
            for (prop in props) {
                addPropertyRow(prop.name, prop.value)
            }
        } else {
            // Start with one empty row
            addPropertyRow("", "")
        }

        // Initialize raw editor with existing raw text
        if (existingConfig?.rawText != null) {
            // Extract inner content between braces
            val raw = existingConfig.rawText
            val openBrace = raw.indexOf('{')
            val closeBrace = raw.lastIndexOf('}')
            if (openBrace != -1 && closeBrace != -1 && closeBrace > openBrace) {
                rawEditorArea.text = raw.substring(openBrace + 1, closeBrace).trimIndent()
            }
        }
    }

    private fun addPropertyRow(name: String, value: String) {
        val nameField = JBTextField(name, 15).apply {
            emptyText.text = message("unified.plugin.configure.gradle.property.name.placeholder")
        }
        val valueField = JBTextField(value, 20).apply {
            emptyText.text = message("unified.plugin.configure.gradle.property.value.placeholder")
        }
        val removeButton = JButton(message("unified.plugin.configure.gradle.remove.property")).apply {
            preferredSize = Dimension(80, 28)
        }

        val rowPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 36)
        }
        rowPanel.add(nameField)
        rowPanel.add(JBLabel("="))
        rowPanel.add(valueField)
        rowPanel.add(removeButton)

        val row = PropertyRow(nameField, valueField, removeButton, rowPanel)
        propertyRows.add(row)
        propertyListPanel.add(rowPanel)

        removeButton.addActionListener {
            propertyRows.remove(row)
            propertyListPanel.remove(rowPanel)
            propertyListPanel.revalidate()
            propertyListPanel.repaint()
            updatePreview()
        }

        // Update preview on changes
        val docListener = object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = updatePreview()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = updatePreview()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = updatePreview()
        }
        nameField.document.addDocumentListener(docListener)
        valueField.document.addDocumentListener(docListener)

        propertyListPanel.revalidate()
        propertyListPanel.repaint()
    }

    private fun generateRawContent(): String {
        return propertyRows
            .filter { it.nameField.text.trim().isNotBlank() }
            .joinToString("\n") { row ->
                val name = row.nameField.text.trim()
                val value = row.valueField.text.trim()
                val formattedValue = formatValue(value)
                "    $name = $formattedValue"
            }
    }

    private fun formatValue(value: String): String {
        if (value == "true" || value == "false") return value
        if (value.toLongOrNull() != null || value.toDoubleOrNull() != null) return value
        if (value.startsWith("\"") || value.startsWith("'") || value.contains("(")) return value
        return if (isKotlinDsl) "\"$value\"" else "'$value'"
    }

    private fun updatePreview() {
        val extName = extensionNameField.text.trim().ifBlank { "extension" }
        val content = if (isRawMode) {
            "$extName {\n${rawEditorArea.text}\n}"
        } else {
            val props = generateRawContent()
            "$extName {\n$props\n}"
        }
        previewArea.text = content
    }

    override fun doOKAction() {
        if (extensionNameField.text.trim().isBlank()) {
            extensionNameField.requestFocusInWindow()
            return
        }
        super.doOKAction()
    }
}
