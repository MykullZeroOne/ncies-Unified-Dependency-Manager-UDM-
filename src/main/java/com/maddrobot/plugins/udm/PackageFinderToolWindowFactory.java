package com.maddrobot.plugins.udm;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.maddrobot.plugins.udm.gradle.manager.ui.MainToolWindowPanel;
import org.jetbrains.annotations.NotNull;

/**
 * Factory for creating the Unified Dependency Manager tool window.
 * Provides a NuGet-style interface with tabs for Packages, Repositories, Caches, and Log.
 */
public final class PackageFinderToolWindowFactory implements ToolWindowFactory {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        var contentManager = toolWindow.getContentManager();
        var contentFactory = contentManager.getFactory();

        var mainPanel = new MainToolWindowPanel(project, toolWindow.getDisposable());
        var content = contentFactory.createContent(mainPanel.getContentPanel(), null, false);
        contentManager.addContent(content);
    }
}
