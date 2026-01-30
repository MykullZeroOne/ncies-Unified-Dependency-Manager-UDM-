package star.intellijplugin.pkgfinder.gradle.manager.ui

import com.intellij.openapi.observable.properties.PropertyGraph
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.panel
import star.intellijplugin.pkgfinder.PackageFinderBundle.message
import javax.swing.JComponent

class AddDependencyDialog(
    private val project: Project,
    private val groupId: String,
    private val artifactId: String,
    private val versions: List<String>,
    private val modules: List<String>
) : DialogWrapper(project) {

    private val propertyGraph = PropertyGraph()
    val selectedModuleProperty = propertyGraph.property(modules.firstOrNull() ?: "")
    var selectedModule by selectedModuleProperty
    
    val selectedConfigurationProperty = propertyGraph.property("implementation")
    var selectedConfiguration by selectedConfigurationProperty
    
    val selectedVersionProperty = propertyGraph.property(versions.firstOrNull() ?: "")
    var selectedVersion by selectedVersionProperty

    private val configurations = listOf(
        "implementation", "api", "compileOnly", "runtimeOnly",
        "testImplementation", "testRuntimeOnly", "testCompileOnly",
        "annotationProcessor"
    )

    init {
        title = message("gradle.manager.add.title")
        init()
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row(message("gradle.manager.column.groupId")) {
                label(groupId)
            }
            row(message("gradle.manager.column.artifactId")) {
                label(artifactId)
            }
            row(message("gradle.manager.column.module")) {
                comboBox(modules).bindItem(selectedModuleProperty)
            }
            row(message("gradle.manager.column.configuration")) {
                comboBox(configurations).bindItem(selectedConfigurationProperty)
            }
            row(message("gradle.manager.column.version")) {
                comboBox(versions).bindItem(selectedVersionProperty)
            }
        }
    }
}
