package com.maddrobot.plugins.udm.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.io.HttpRequests
import com.intellij.util.ui.UIUtil
import com.maddrobot.plugins.udm.PackageFinderBundle.message
import com.maddrobot.plugins.udm.maven.Dependency
import com.maddrobot.plugins.udm.util.Icons
import com.maddrobot.plugins.udm.util.showInformationNotification
import java.io.File
import javax.swing.Icon

/**
 * Represents an IntelliJ platform action that downloads a specific type of artifact associated with a Maven-style
 * dependency. Each instance of this class encapsulates the necessary information needed to perform the download,
 * such as the dependency information, URL, and file type suffix.
 *
 * @constructor
 * Constructs a new instance of the action with the provided parameters.
 *
 * @param dependency The Maven dependency for which an artifact will be downloaded. It contains artifact coordinates
 *                   such as `groupId`, `artifactId`, and `version`.
 * @param downloadUrl The source URL from which the artifact file will be downloaded.
 * @param text The text to display for the action in the IntelliJ UI.
 * @param description A description of the action for the IntelliJ UI.
 * @param icon The icon that represents this action in the IntelliJ UI.
 * @param ec A file suffix specific to the type of artifact being downloaded (e.g., `-sources.jar`, `.pom`).
 */
class DownloadArtifactAction(
    private val dependency: Dependency,
    private val downloadUrl: String,
    text: String,
    description: String,
    icon: Icon,
    private val ec: String
) : AnAction(text, description, icon) {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        downloadArtifact(dependency, downloadUrl, ec)
    }

    companion object {
        fun forSource(dependency: Dependency, url: String) =
            DownloadArtifactAction(
                dependency,
                url,
                "Download Source",
                "Download source",
                Icons.JAR_SOURCES.getThemeBasedIcon(),
                "-sources.jar"
            )

        fun forJar(dependency: Dependency, url: String) =
            DownloadArtifactAction(
                dependency,
                url,
                "Download JAR",
                "Download jar",
                Icons.JAR.getThemeBasedIcon(),
                ".jar"
            )

        fun forJavadoc(dependency: Dependency, url: String) =
            DownloadArtifactAction(
                dependency,
                url,
                "Download Javadoc",
                "Download javadoc",
                Icons.JAVADOC.getThemeBasedIcon(),
                "-javadoc.jar"
            )

        fun forPom(dependency: Dependency, url: String) =
            DownloadArtifactAction(
                dependency,
                url,
                "Download Pom",
                "Download pom",
                Icons.JAVADOC.getThemeBasedIcon(),
                ".pom"
            )

        fun forModule(dependency: Dependency, url: String) =
            DownloadArtifactAction(
                dependency,
                url,
                "Download Module",
                "Download module",
                Icons.JAVADOC.getThemeBasedIcon(),
                ".module"
            )
    }

    /**
     * Downloads a specific artifact file for the given dependency. The artifact will be saved
     * to the user's "Downloads" folder with a filename comprised of the artifact ID, version,
     * and a provided suffix.
     *
     * The downloading process is executed as a background task. If the download is successful,
     * a notification will be shown to the user with the location of the downloaded file.
     * If an error occurs, an error dialog will be displayed with the relevant message.
     *
     * @param dependency The dependency containing the group ID, artifact ID, and version
     *                   information of the artifact to download.
     * @param downloadUrl The URL from which the artifact should be downloaded.
     * @param ec A string suffix to append to the artifact filename.
     */
    private fun downloadArtifact(dependency: Dependency, downloadUrl: String, ec: String) {
        val artifactId = dependency.artifactId
        val version = dependency.version
        val savePath = FileUtil.join(System.getProperty("user.home"), "Downloads", "$artifactId-$version$ec")

        object : Task.Backgroundable(null, message("download.task.title"), true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val file = File(savePath)
                    HttpRequests.request(downloadUrl)
                        .productNameAsUserAgent()
                        .saveToFile(file, indicator)
                } catch (e: Exception) {
                    Messages.showErrorDialog(
                        message(
                            "download.error.message",
                            e.localizedMessage ?: "Unknown error"
                        ), message("download.error.title")
                    )
                }
            }

            override fun onSuccess() {
                UIUtil.invokeLaterIfNeeded {
                    showInformationNotification(message("download.success.message", savePath))
                }
            }
        }.queue()
    }
}
