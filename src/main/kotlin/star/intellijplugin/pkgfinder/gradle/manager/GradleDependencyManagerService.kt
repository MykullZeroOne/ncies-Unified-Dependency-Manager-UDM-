package star.intellijplugin.pkgfinder.gradle.manager

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.util.messages.Topic
import org.jetbrains.plugins.gradle.util.GradleConstants

@Service(Service.Level.PROJECT)
class GradleDependencyManagerService(private val project: Project) {

    private val scanner = GradleDependencyScanner(project)
    private val updateService = GradleUpdateService()

    var installedDependencies: List<InstalledDependency> = emptyList()
        private set

    var dependencyUpdates: List<DependencyUpdate> = emptyList()
        private set

    interface DependencyChangeListener {
        fun onDependenciesChanged()
    }

    companion object {
        val DEPENDENCY_CHANGE_TOPIC = Topic.create("Dependency Change", DependencyChangeListener::class.java)

        fun getInstance(project: Project): GradleDependencyManagerService = project.getService(GradleDependencyManagerService::class.java)
    }

    init {
        project.messageBus.connect().subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                val relevantChange = events.any { event ->
                    val name = event.file?.name ?: ""
                    name == GradleConstants.DEFAULT_SCRIPT_NAME || name == GradleConstants.KOTLIN_DSL_SCRIPT_NAME
                }
                if (relevantChange) {
                    refresh()
                }
            }
        })
    }

    fun refresh() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val installed = scanner.scanInstalledDependencies()
            installedDependencies = installed

            // Notify partial refresh
            ApplicationManager.getApplication().invokeLater {
                project.messageBus.syncPublisher(DEPENDENCY_CHANGE_TOPIC).onDependenciesChanged()
            }

            // Check for updates
            val updates = mutableListOf<DependencyUpdate>()
            for (dep in installed) {
                val latest = updateService.getLatestVersion(dep.groupId, dep.artifactId)
                if (latest != null && latest != dep.version) {
                    updates.add(DependencyUpdate(dep, latest))
                }
            }
            dependencyUpdates = updates

            ApplicationManager.getApplication().invokeLater {
                project.messageBus.syncPublisher(DEPENDENCY_CHANGE_TOPIC).onDependenciesChanged()
            }
        }
    }
}

data class DependencyUpdate(val installed: InstalledDependency, val latestVersion: String)
