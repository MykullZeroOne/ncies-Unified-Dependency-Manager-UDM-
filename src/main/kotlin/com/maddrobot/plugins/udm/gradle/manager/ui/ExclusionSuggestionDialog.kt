package com.maddrobot.plugins.udm.gradle.manager.ui

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.OSProcessHandler
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CheckBoxList
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.maddrobot.plugins.udm.PackageFinderBundle.message
import com.maddrobot.plugins.udm.gradle.manager.InstalledDependency
import com.maddrobot.plugins.udm.gradle.manager.service.BuildSystem
import com.maddrobot.plugins.udm.gradle.manager.service.ExclusionSuggestion
import com.maddrobot.plugins.udm.gradle.manager.service.ExclusionSuggestionService
import com.maddrobot.plugins.udm.gradle.manager.service.SuggestionSeverity
import com.maddrobot.plugins.udm.gradle.manager.service.SuggestionSource
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.*

/**
 * Dialog for showing exclusion suggestions based on transitive dependency analysis
 * and bundled exclusion rules. Supports async loading with progress indicator.
 * Handles both Gradle and Maven dependencies.
 */
class ExclusionSuggestionDialog(
    private val project: Project,
    private val dependencySets: List<DependencySet>,
    private val moduleFilter: String?
) : DialogWrapper(project) {

    /**
     * A set of dependencies from a specific build system.
     */
    data class DependencySet(
        val dependencies: List<InstalledDependency>,
        val buildSystem: BuildSystem
    )

    private val suggestionService = ExclusionSuggestionService.getInstance(project)
    private val checkBoxList = CheckBoxList<SuggestionItem>()
    private val progressBar = JProgressBar().apply {
        isIndeterminate = true
        isStringPainted = true
        string = message("unified.exclusion.suggestion.loading")
    }
    private val progressLabel = JBLabel(message("unified.exclusion.suggestion.loading")).apply {
        icon = AllIcons.Process.Step_1
        foreground = JBColor.GRAY
    }
    private val contentPanel = JPanel(BorderLayout())
    private var suggestions: List<ExclusionSuggestion> = emptyList()
    private var totalCacheMisses = 0
    private var totalAnalyzed = 0
    private val cancelled = AtomicBoolean(false)
    private var currentProcessHandler: OSProcessHandler? = null

    init {
        title = message("unified.exclusion.suggestion.dialog.title")
        setOKButtonText(message("unified.exclusion.add.button"))
        init()
        loadSuggestions()
    }

    override fun doCancelAction() {
        cancelled.set(true)
        currentProcessHandler?.destroyProcess()
        currentProcessHandler = null
        super.doCancelAction()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout()).apply {
            preferredSize = Dimension(600, 450)
            border = JBUI.Borders.empty(8)
        }

        // Header
        val headerPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyBottom(12)
            add(JBLabel(message("unified.exclusion.suggestion.dialog.header")), BorderLayout.CENTER)
        }
        mainPanel.add(headerPanel, BorderLayout.NORTH)

        // Content - shows loading initially
        val loadingPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(20)
            add(progressLabel, BorderLayout.NORTH)
            add(progressBar, BorderLayout.CENTER)
        }
        contentPanel.add(loadingPanel, BorderLayout.CENTER)
        mainPanel.add(contentPanel, BorderLayout.CENTER)

        // Button panel
        val buttonPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            border = JBUI.Borders.emptyTop(8)

            add(JButton(message("unified.exclusion.suggestion.select.all")).apply {
                addActionListener { selectAll(true) }
            })
            add(Box.createHorizontalStrut(8))
            add(JButton(message("unified.exclusion.suggestion.deselect.all")).apply {
                addActionListener { selectAll(false) }
            })
            add(Box.createHorizontalGlue())
        }
        mainPanel.add(buttonPanel, BorderLayout.SOUTH)

        return mainPanel
    }

    private fun loadSuggestions() {
        val nonEmptySets = dependencySets.filter { it.dependencies.isNotEmpty() }

        if (nonEmptySets.isEmpty()) {
            suggestions = emptyList()
            updateList()
            return
        }

        val totalDeps = nonEmptySets.sumOf { it.dependencies.size }
        val globalCompleted = AtomicInteger(0)

        progressBar.minimum = 0
        progressBar.maximum = totalDeps
        progressBar.value = 0
        progressBar.string = "0 / $totalDeps"

        // Track how many analyses are pending
        val pending = AtomicInteger(nonEmptySets.size)
        val allResults = java.util.Collections.synchronizedList(mutableListOf<ExclusionSuggestion>())
        val accumulatedMisses = AtomicInteger(0)
        val accumulatedTotal = AtomicInteger(0)

        for (set in nonEmptySets) {
            suggestionService.analyzeDependencies(
                dependencies = set.dependencies,
                moduleFilter = moduleFilter,
                buildSystem = set.buildSystem,
                cancelled = cancelled,
                onProgress = { _, _ ->
                    val global = globalCompleted.incrementAndGet()
                    if (!cancelled.get()) {
                        progressBar.isIndeterminate = false
                        progressBar.value = global
                        progressBar.string = "$global / $totalDeps"
                        progressLabel.text = message("unified.exclusion.suggestion.analyzing", global, totalDeps)
                    }
                },
                onStatusChange = { status ->
                    if (!cancelled.get()) {
                        progressLabel.text = status
                    }
                }
            ) { result ->
                if (cancelled.get()) return@analyzeDependencies
                allResults.addAll(result.suggestions)
                accumulatedMisses.addAndGet(result.localCacheMisses)
                accumulatedTotal.addAndGet(result.totalAnalyzed)
                if (pending.decrementAndGet() == 0) {
                    ApplicationManager.getApplication().invokeLater({
                        if (cancelled.get()) return@invokeLater
                        suggestions = allResults
                            .distinctBy { "${it.parentDependency.id}|${it.exclusion.id}" }
                            .sortedWith(compareBy<ExclusionSuggestion> { it.severity.ordinal }.thenBy { it.exclusion.id })
                        totalCacheMisses = accumulatedMisses.get()
                        totalAnalyzed = accumulatedTotal.get()
                        updateList()
                    }, ModalityState.any())
                }
            }
        }
    }

    private fun updateList() {
        contentPanel.removeAll()

        // Show warning banner with resolve button if local POM cache is incomplete
        if (totalCacheMisses > 0) {
            val hasMaven = dependencySets.any { it.buildSystem == BuildSystem.MAVEN }
            val hasGradle = dependencySets.any { it.buildSystem == BuildSystem.GRADLE }
            val warningColor = if (totalCacheMisses == totalAnalyzed) JBColor.ORANGE else JBColor.GRAY
            val warningHex = String.format("#%06x", warningColor.rgb and 0xFFFFFF)
            val warningText = if (totalCacheMisses == totalAnalyzed) {
                message("unified.exclusion.suggestion.cache.missing.all", totalCacheMisses, totalAnalyzed)
            } else {
                message("unified.exclusion.suggestion.cache.missing.partial", totalCacheMisses, totalAnalyzed)
            }

            val warningPanel = JPanel(BorderLayout()).apply {
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, JBColor.border()),
                    JBUI.Borders.empty(8)
                )
                add(JBLabel("<html><font color='$warningHex'>$warningText</font></html>").apply {
                    icon = AllIcons.General.Warning
                }, BorderLayout.CENTER)

                val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
                if (hasMaven) {
                    buttonPanel.add(JButton(message("unified.exclusion.suggestion.resolve.maven")).apply {
                        addActionListener { resolveDependencies(BuildSystem.MAVEN) }
                    })
                }
                if (hasGradle) {
                    buttonPanel.add(JButton(message("unified.exclusion.suggestion.resolve.gradle")).apply {
                        addActionListener { resolveDependencies(BuildSystem.GRADLE) }
                    })
                }
                add(buttonPanel, BorderLayout.SOUTH)
            }
            contentPanel.add(warningPanel, BorderLayout.NORTH)
        }

        if (suggestions.isEmpty() && totalCacheMisses == 0) {
            contentPanel.add(JBLabel(message("unified.exclusion.suggestion.empty")).apply {
                horizontalAlignment = SwingConstants.CENTER
                foreground = JBColor.GRAY
            }, BorderLayout.CENTER)
        } else if (suggestions.isEmpty()) {
            // All POMs missing - warning banner is already shown, just add empty center
            contentPanel.add(JBLabel("").apply {
                horizontalAlignment = SwingConstants.CENTER
            }, BorderLayout.CENTER)
        } else {
            val items = suggestions.map { suggestion ->
                val severityLabel = when (suggestion.severity) {
                    SuggestionSeverity.CRITICAL -> message("unified.exclusion.suggestion.severity.critical")
                    SuggestionSeverity.WARNING -> message("unified.exclusion.suggestion.severity.warning")
                    SuggestionSeverity.INFO -> message("unified.exclusion.suggestion.severity.info")
                }
                val sourceLabel = when (suggestion.source) {
                    SuggestionSource.CONFLICT_DETECTION -> message("unified.exclusion.suggestion.source.conflict")
                    SuggestionSource.KNOWN_RULES -> message("unified.exclusion.suggestion.source.rule")
                }
                val displayText = "$severityLabel Exclude ${suggestion.exclusion.displayName} from ${suggestion.parentDependency.id} ($sourceLabel)"

                SuggestionItem(
                    suggestion = suggestion,
                    displayText = displayText
                )
            }

            checkBoxList.clear()
            for (item in items) {
                val preSelected = item.suggestion.severity == SuggestionSeverity.CRITICAL
                checkBoxList.addItem(item, item.displayText, preSelected)
            }

            val scrollPane = JBScrollPane(checkBoxList).apply {
                preferredSize = Dimension(0, 350)
            }
            contentPanel.add(scrollPane, BorderLayout.CENTER)

            // Summary label
            val criticalCount = suggestions.count { it.severity == SuggestionSeverity.CRITICAL }
            val warningCount = suggestions.count { it.severity == SuggestionSeverity.WARNING }
            val infoCount = suggestions.count { it.severity == SuggestionSeverity.INFO }
            val details = buildString {
                if (criticalCount > 0) append("$criticalCount critical, ")
                if (warningCount > 0) append("$warningCount warnings, ")
                if (infoCount > 0) append("$infoCount info")
                if (endsWith(", ")) setLength(length - 2)
            }
            val summaryText = message("unified.exclusion.suggestion.summary", suggestions.size, details)
            contentPanel.add(JBLabel(summaryText).apply {
                border = JBUI.Borders.emptyTop(8)
                foreground = JBColor.GRAY
            }, BorderLayout.SOUTH)
        }

        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun selectAll(selected: Boolean) {
        for (i in 0 until checkBoxList.itemsCount) {
            val item = checkBoxList.getItemAt(i)
            if (item != null) {
                checkBoxList.setItemSelected(item, selected)
            }
        }
    }

    /**
     * Run mvn dependency:resolve or ./gradlew dependencies to populate the local cache,
     * then re-run the exclusion analysis. Uses executeOnPooledThread + ModalityState.any()
     * to ensure callbacks fire even while this modal dialog is open.
     */
    private fun resolveDependencies(buildSystem: BuildSystem) {
        val basePath = project.basePath ?: return
        val workDir = File(basePath)

        val (executable, args) = when (buildSystem) {
            BuildSystem.MAVEN -> {
                val mvn = when {
                    File(workDir, "mvnw").exists() -> File(workDir, "mvnw").absolutePath
                    File(workDir, "mvnw.cmd").exists() -> File(workDir, "mvnw.cmd").absolutePath
                    else -> "mvn"
                }
                mvn to listOf("dependency:resolve", "-U")
            }
            BuildSystem.GRADLE -> {
                val gradle = when {
                    File(workDir, "gradlew").exists() -> File(workDir, "gradlew").absolutePath
                    File(workDir, "gradlew.bat").exists() -> File(workDir, "gradlew.bat").absolutePath
                    else -> "gradle"
                }
                gradle to listOf("dependencies", "--refresh-dependencies")
            }
        }

        // Show resolving state in the dialog
        contentPanel.removeAll()
        progressBar.isIndeterminate = true
        progressBar.string = message("unified.exclusion.suggestion.resolving")
        progressLabel.text = message("unified.exclusion.suggestion.resolve.running", "${File(executable).name} ${args.joinToString(" ")}")
        progressLabel.icon = AllIcons.Process.Step_1

        val loadingPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(20)
            add(progressLabel, BorderLayout.NORTH)
            add(progressBar, BorderLayout.CENTER)
        }
        contentPanel.add(loadingPanel, BorderLayout.CENTER)
        contentPanel.revalidate()
        contentPanel.repaint()

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val commandLine = GeneralCommandLine()
                    .withExePath(executable)
                    .withParameters(args)
                    .withWorkDirectory(workDir)
                    .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)

                val handler = OSProcessHandler(commandLine)
                currentProcessHandler = handler
                val output = StringBuilder()

                handler.addProcessListener(object : com.intellij.execution.process.ProcessAdapter() {
                    override fun onTextAvailable(event: com.intellij.execution.process.ProcessEvent, outputType: com.intellij.openapi.util.Key<*>) {
                        output.append(event.text)
                        val line = event.text.trim()
                        if (line.isNotEmpty()) {
                            ApplicationManager.getApplication().invokeLater({
                                if (!cancelled.get()) {
                                    progressLabel.text = line.take(80)
                                }
                            }, ModalityState.any())
                        }
                    }
                })
                handler.startNotify()

                while (!handler.isProcessTerminated) {
                    if (cancelled.get()) {
                        handler.destroyProcess()
                        currentProcessHandler = null
                        return@executeOnPooledThread
                    }
                    Thread.sleep(200)
                }
                currentProcessHandler = null

                val exitCode = handler.exitCode ?: -1
                if (exitCode != 0) {
                    val lastLines = output.toString().lines().takeLast(10).joinToString("\n")
                    ApplicationManager.getApplication().invokeLater({
                        Messages.showErrorDialog(
                            project,
                            message("unified.exclusion.suggestion.resolve.failed.exit", exitCode, lastLines),
                            message("unified.exclusion.suggestion.resolve.failed.title")
                        )
                    }, ModalityState.any())
                    return@executeOnPooledThread
                }

                // Success - clear cache and re-analyze
                ApplicationManager.getApplication().invokeLater({
                    if (!cancelled.get()) {
                        suggestionService.clearCache()
                        rerunAnalysis()
                    }
                }, ModalityState.any())

            } catch (e: Exception) {
                currentProcessHandler = null
                ApplicationManager.getApplication().invokeLater({
                    Messages.showErrorDialog(
                        project,
                        message("unified.exclusion.suggestion.resolve.failed", e.message ?: "Unknown error"),
                        message("unified.exclusion.suggestion.resolve.failed.title")
                    )
                }, ModalityState.any())
            }
        }
    }

    /**
     * Reset dialog state to loading and re-run the analysis.
     */
    private fun rerunAnalysis() {
        suggestions = emptyList()
        totalCacheMisses = 0
        totalAnalyzed = 0
        cancelled.set(false)

        contentPanel.removeAll()
        progressBar.isIndeterminate = true
        progressBar.string = message("unified.exclusion.suggestion.loading")
        progressLabel.text = message("unified.exclusion.suggestion.loading")
        progressLabel.icon = AllIcons.Process.Step_1

        val loadingPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(20)
            add(progressLabel, BorderLayout.NORTH)
            add(progressBar, BorderLayout.CENTER)
        }
        contentPanel.add(loadingPanel, BorderLayout.CENTER)
        contentPanel.revalidate()
        contentPanel.repaint()

        loadSuggestions()
    }

    /**
     * Get the list of selected exclusion suggestions.
     */
    fun getSelectedSuggestions(): List<ExclusionSuggestion> {
        val selected = mutableListOf<ExclusionSuggestion>()
        for (i in 0 until checkBoxList.itemsCount) {
            val item = checkBoxList.getItemAt(i)
            if (item != null && checkBoxList.isItemSelected(item)) {
                selected.add(item.suggestion)
            }
        }
        return selected
    }

    /**
     * Data class for items in the checkbox list.
     */
    data class SuggestionItem(
        val suggestion: ExclusionSuggestion,
        val displayText: String
    ) {
        override fun toString(): String = displayText
    }
}
