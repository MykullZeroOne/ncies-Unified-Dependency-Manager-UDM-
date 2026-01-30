package star.intellijplugin.pkgfinder.gradle.manager.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.vfs.LocalFileSystem
import star.intellijplugin.pkgfinder.PackageFinderBundle.message
import java.awt.BorderLayout
import java.io.File
import javax.swing.JComponent
import javax.swing.JPanel

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
