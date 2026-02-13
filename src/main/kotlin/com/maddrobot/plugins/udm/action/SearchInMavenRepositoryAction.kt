package com.maddrobot.plugins.udm.action

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.maddrobot.plugins.udm.maven.Dependency

/**
 * Action for searching a specific dependency in the Maven Repository.
 *
 * @constructor Initializes the action with the specified dependency to be searched.
 * @param dependency The Maven dependency containing the group ID, artifact ID, and version.
 *
 * This action opens a browser window directed to the corresponding Maven Repository page
 * of the dependency using its group ID and artifact ID.
 *
 * Inherits functionality from the `AnAction` class and overrides relevant methods for defining
 * the action's behavior and update thread.
 */
class SearchInMavenRepositoryAction(
    private val dependency: Dependency
) : AnAction("Search in Maven Repository") {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        BrowserUtil.browse("https://mvnrepository.com/artifact/${dependency.groupId}/${dependency.artifactId}")
    }
}
