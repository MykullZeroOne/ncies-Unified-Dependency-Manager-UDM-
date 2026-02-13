package com.maddrobot.plugins.udm.gradle.manager.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import com.maddrobot.plugins.udm.PackageFinderBundle.message
import java.awt.BorderLayout
import java.io.File
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * A dialog that provides a side-by-side diff view of two versions of content, typically used to preview and compare
 * changes before they are applied. This dialog displays the original content and the new modified content, allowing
 * users to review the differences in a user-friendly interface.
 *
 * @constructor Creates a new instance of the dialog.
 * @param project The IntelliJ IDEA project instance associated with this dialog.
 * @param filePath The path of the file being previewed.
 * @param originalContent The original content of the file before any modifications.
 * @param newContent The modified content to compare against the original content.
 * @param title The title of the dialog, defaults to a localized preview title string.
 */
class PreviewDiffDialog(
    private val project: Project,
    private val filePath: String,
    private val originalContent: String,
    private val newContent: String,
    title: String = message("gradle.manager.preview.title")
) : DialogWrapper(project) {

    init {
        this.title = title
        setOKButtonText(message("gradle.manager.button.apply"))
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        panel.preferredSize = com.intellij.util.ui.JBUI.size(800, 600)

        val factory = DiffContentFactory.getInstance()
        val file = LocalFileSystem.getInstance().findFileByPath(filePath)

        val content1 = if (file != null) factory.create(project, file) else factory.create(originalContent)
        val content2 = factory.create(project, newContent)

        val request = SimpleDiffRequest(
            message("gradle.manager.preview.diff.title"),
            content1,
            content2,
            message("gradle.manager.preview.original"),
            message("gradle.manager.preview.modified")
        )

        val diffPanel = DiffManager.getInstance().createRequestPanel(project, disposable, null)
        diffPanel.setRequest(request)

        panel.add(diffPanel.component, BorderLayout.CENTER)
        return panel
    }
}
