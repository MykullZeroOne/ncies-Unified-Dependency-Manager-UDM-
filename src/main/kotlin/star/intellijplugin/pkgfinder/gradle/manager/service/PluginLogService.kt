package star.intellijplugin.pkgfinder.gradle.manager.service

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Service for capturing and displaying plugin log messages in the UI.
 * This allows users to see what's happening during search operations and other activities.
 */
@Service(Service.Level.PROJECT)
class PluginLogService(private val project: Project) {

    companion object {
        fun getInstance(project: Project): PluginLogService =
            project.getService(PluginLogService::class.java)

        private const val MAX_LOG_ENTRIES = 1000
    }

    enum class LogLevel {
        DEBUG,
        INFO,
        WARN,
        ERROR
    }

    data class LogEntry(
        val timestamp: Long,
        val level: LogLevel,
        val message: String,
        val source: String? = null
    ) {
        private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS")

        fun formatted(): String {
            val time = dateFormat.format(Date(timestamp))
            val levelStr = level.name.padEnd(5)
            val sourceStr = if (source != null) "[$source] " else ""
            return "$time $levelStr $sourceStr$message"
        }
    }

    private val logEntries = CopyOnWriteArrayList<LogEntry>()
    private val listeners = CopyOnWriteArrayList<(LogEntry) -> Unit>()

    /**
     * Add a log entry.
     */
    fun log(level: LogLevel, message: String, source: String? = null) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            message = message,
            source = source
        )

        logEntries.add(entry)

        // Trim old entries if we exceed the max
        while (logEntries.size > MAX_LOG_ENTRIES) {
            logEntries.removeAt(0)
        }

        // Notify listeners
        listeners.forEach { it(entry) }
    }

    fun debug(message: String, source: String? = null) = log(LogLevel.DEBUG, message, source)
    fun info(message: String, source: String? = null) = log(LogLevel.INFO, message, source)
    fun warn(message: String, source: String? = null) = log(LogLevel.WARN, message, source)
    fun error(message: String, source: String? = null) = log(LogLevel.ERROR, message, source)

    /**
     * Get all log entries.
     */
    fun getEntries(): List<LogEntry> = logEntries.toList()

    /**
     * Get entries as formatted text.
     */
    fun getFormattedLog(): String {
        return logEntries.joinToString("\n") { it.formatted() }
    }

    /**
     * Clear all log entries.
     */
    fun clear() {
        logEntries.clear()
        listeners.forEach { it(LogEntry(System.currentTimeMillis(), LogLevel.INFO, "Log cleared", "System")) }
    }

    /**
     * Add a listener for new log entries.
     */
    fun addListener(listener: (LogEntry) -> Unit) {
        listeners.add(listener)
    }

    /**
     * Remove a listener.
     */
    fun removeListener(listener: (LogEntry) -> Unit) {
        listeners.remove(listener)
    }
}
