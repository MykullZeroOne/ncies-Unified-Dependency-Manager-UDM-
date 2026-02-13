package com.maddrobot.plugins.udm.action

import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.vfs.LocalFileSystem
import com.maddrobot.plugins.udm.util.Icons
import java.io.File

/**
 * An action to open the containing folder of a specified file path in the system's file explorer.
 *
 * @constructor Initializes the action with a file path to open.
 *
 * @param path The absolute file path of the folder or file to reveal in the file explorer.
 *
 * This action retrieves the virtual file for the specified path using the local file system. If the file exists,
 * it uses the `RevealFileAction` to open the corresponding folder in the system file explorer.
 *
 * The action is set to execute on the background thread (ActionUpdateThread.BGT).
 */
class OpenContainingFolderAction(
    private val path: String
) : AnAction("Open Containing Folder", "Open containing folder", Icons.FOLDER.getThemeBasedIcon()) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
    override fun actionPerformed(e: AnActionEvent) {
        val virtualFile = LocalFileSystem.getInstance().findFileByPath(path)
        if (virtualFile == null || !virtualFile.exists()) {
            return
        }

        RevealFileAction.openFile(File(path))
    }
}
