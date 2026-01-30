package star.intellijplugin.pkgfinder

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import star.intellijplugin.pkgfinder.gradle.manager.ui.MainToolWindowPanel

/**
 * Factory for creating the Unified Dependency Manager tool window.
 * Provides a NuGet-style interface with tabs for Packages, Repositories, Caches, and Log.
 *
 * @author drawsta
 * @LastModified: 2026-01-30
 * @since 2025-01-16
 */
class PackageFinderToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val contentManager = toolWindow.contentManager
        val contentFactory = contentManager.factory

        val mainPanel = MainToolWindowPanel(project, toolWindow.disposable)
        val content = contentFactory.createContent(
            mainPanel.contentPanel,
            null,
            false
        )

        contentManager.addContent(content)
    }
}
