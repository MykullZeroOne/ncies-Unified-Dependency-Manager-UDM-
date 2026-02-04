package com.maddrobot.plugins.udm.action

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.maddrobot.plugins.udm.maven.Dependency

/**
 * @author drawsta
 * @LastModified: 2025-09-08
 * @since 2025-09-08
 */
class SearchInMavenRepositoryAction(
    private val dependency: Dependency
) : AnAction("Search in Maven Repository") {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        BrowserUtil.browse("https://mvnrepository.com/artifact/${dependency.groupId}/${dependency.artifactId}")
    }
}
