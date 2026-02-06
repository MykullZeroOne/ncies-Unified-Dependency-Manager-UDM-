package com.maddrobot.plugins.udm.gradle.manager.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.maddrobot.plugins.udm.PackageFinderBundle.message
import com.maddrobot.plugins.udm.gradle.manager.model.DependencyExclusion
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Dialog for adding a dependency exclusion.
 * Allows specifying group ID and optional artifact ID.
 */
class ExclusionDialog(
    project: Project?,
    private val parentPackageName: String,
    prefilledGroupId: String? = null,
    prefilledArtifactId: String? = null
) : DialogWrapper(project) {

    private val groupIdField = JBTextField(prefilledGroupId ?: "", 30)
    private val artifactIdField = JBTextField(prefilledArtifactId ?: "", 30)

    init {
        title = message("unified.exclusion.dialog.title")
        setOKButtonText(message("unified.exclusion.add.button"))
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            insets = JBUI.insets(4)
            anchor = GridBagConstraints.WEST
            fill = GridBagConstraints.HORIZONTAL
        }

        // Parent label
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 2
        panel.add(JBLabel(message("unified.exclusion.dialog.parent.label") + " " + parentPackageName), gbc)

        // Group ID
        gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 1; gbc.weightx = 0.0
        panel.add(JBLabel(message("unified.exclusion.dialog.group.label")), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(groupIdField, gbc)

        // Artifact ID
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0
        panel.add(JBLabel(message("unified.exclusion.dialog.artifact.label")), gbc)
        gbc.gridx = 1; gbc.weightx = 1.0
        panel.add(artifactIdField, gbc)

        return panel
    }

    override fun doValidate(): ValidationInfo? {
        if (groupIdField.text.isBlank()) {
            return ValidationInfo(message("unified.exclusion.dialog.group.required"), groupIdField)
        }
        return null
    }

    override fun getPreferredFocusedComponent(): JComponent = groupIdField

    fun getExclusion(): DependencyExclusion {
        return DependencyExclusion(
            groupId = groupIdField.text.trim(),
            artifactId = artifactIdField.text.trim().ifBlank { null }
        )
    }
}
