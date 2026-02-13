package com.maddrobot.plugins.udm.gradle.manager.service

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.maddrobot.plugins.udm.gradle.manager.InstalledDependency
import com.maddrobot.plugins.udm.gradle.manager.model.DependencyExclusion
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Service for analyzing transitive dependencies and suggesting exclusions.
 * Combines two strategies:
 * 1. Dynamic conflict detection - finds version mismatches in transitive deps
 * 2. Rules-based matching - matches against a bundled curated exclusion rules file
 */
@Service(Service.Level.PROJECT)
class ExclusionSuggestionService(private val project: Project) : Disposable {
    private val log = Logger.getInstance(javaClass)
    private val transitiveDependencyService by lazy { TransitiveDependencyService.getInstance(project) }
    private val json = Json { ignoreUnknownKeys = true }

    // Cached rules loaded from bundled JSON
    private val exclusionRules: List<ExclusionRule> by lazy { loadExclusionRules() }

    // Analysis results cache (accessed from pooled threads + EDT)
    @Volatile
    private var cachedResults: List<ExclusionSuggestion>? = null
    @Volatile
    private var cacheKey: String? = null

    companion object {
        fun getInstance(project: Project): ExclusionSuggestionService =
            project.getService(ExclusionSuggestionService::class.java)

        private const val RULES_RESOURCE = "/exclusion-rules.json"
    }

    /**
     * Analyze installed dependencies for exclusion suggestions.
     * Fetches transitive deps in parallel with timeouts. Supports cancellation.
     *
     * @param dependencies The installed dependencies to analyze
     * @param moduleFilter Optional module name to filter by (null = all modules)
     * @param buildSystem The build system these dependencies come from
     * @param cancelled Set to true externally to cancel the analysis
     * @param onProgress Called on EDT with (completed, total) counts during fetching
     * @param onStatusChange Called on EDT with a status message (e.g. "Reading local caches...")
     * @param callback Called on EDT with the analysis result including cache miss stats
     */
    fun analyzeDependencies(
        dependencies: List<InstalledDependency>,
        moduleFilter: String?,
        buildSystem: BuildSystem = BuildSystem.GRADLE,
        cancelled: AtomicBoolean = AtomicBoolean(false),
        onProgress: ((completed: Int, total: Int) -> Unit)? = null,
        onStatusChange: ((status: String) -> Unit)? = null,
        callback: (AnalysisResult) -> Unit
    ) {
        val filtered = if (moduleFilter != null) {
            dependencies.filter { it.moduleName == moduleFilter }
        } else {
            dependencies
        }

        if (filtered.isEmpty()) {
            ApplicationManager.getApplication().invokeLater({ callback(AnalysisResult(emptyList(), 0, 0)) }, ModalityState.any())
            return
        }

        // Check cache
        val key = filtered.map { it.fullName }.sorted().joinToString(",") + "|" + (moduleFilter ?: "all")
        val cached = cachedResults
        if (key == cacheKey && cached != null) {
            ApplicationManager.getApplication().invokeLater({ callback(AnalysisResult(cached, filtered.size, 0)) }, ModalityState.any())
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                if (cancelled.get()) return@executeOnPooledThread

                val suggestions = mutableListOf<ExclusionSuggestion>()
                val directDependencyIds = filtered.map { "${it.groupId}:${it.artifactId}" }.toSet()
                val totalCount = filtered.size

                if (onStatusChange != null) {
                    ApplicationManager.getApplication().invokeLater({ onStatusChange("Reading local dependency caches...") }, ModalityState.any())
                }

                // Phase 1: Read transitive deps from LOCAL caches only (~/.m2 and ~/.gradle).
                // No network calls — this completes in milliseconds.
                val transitiveMap = mutableMapOf<String, MutableList<TransitiveOccurrence>>()
                var localCacheMisses = 0

                for ((index, dep) in filtered.withIndex()) {
                    if (cancelled.get()) return@executeOnPooledThread

                    try {
                        val transitiveDeps = transitiveDependencyService.getTransitiveDependenciesLocalSync(
                            dep.groupId, dep.artifactId, dep.version
                        )

                        if (transitiveDeps == null) {
                            // POM file not found in local cache
                            localCacheMisses++
                        }

                        for (transitive in (transitiveDeps ?: emptyList())) {
                            val transitiveId = "${transitive.groupId}:${transitive.artifactId}"
                            transitiveMap.getOrPut(transitiveId) { mutableListOf() }.add(
                                TransitiveOccurrence(
                                    parent = dep,
                                    groupId = transitive.groupId,
                                    artifactId = transitive.artifactId,
                                    version = transitive.version ?: "managed"
                                )
                            )
                        }
                    } catch (e: Exception) {
                        localCacheMisses++
                        log.debug("Failed to read transitive deps for ${dep.groupId}:${dep.artifactId}: ${e.message}")
                    }

                    if (onProgress != null) {
                        val done = index + 1
                        ApplicationManager.getApplication().invokeLater({ onProgress(done, totalCount) }, ModalityState.any())
                    }
                }

                if (cancelled.get()) return@executeOnPooledThread

                // Phase 2: Dynamic conflict detection - same artifact at different versions
                for ((transitiveId, occurrences) in transitiveMap) {
                    val distinctVersions = occurrences.map { it.version }.distinct()
                    if (distinctVersions.size > 1) {
                        val parts = transitiveId.split(":")
                        val groupId = parts[0]
                        val artifactId = parts[1]

                        val sortedOccurrences = occurrences.sortedByDescending { it.version }
                        for (occurrence in sortedOccurrences.drop(1)) {
                            if (transitiveId in directDependencyIds) continue

                            suggestions.add(
                                ExclusionSuggestion(
                                    parentDependency = occurrence.parent,
                                    exclusion = DependencyExclusion(groupId, artifactId),
                                    reason = "Version conflict: ${distinctVersions.joinToString(" vs ")} across ${occurrences.map { "${it.parent.groupId}:${it.parent.artifactId}" }.distinct().joinToString(", ")}",
                                    severity = SuggestionSeverity.WARNING,
                                    source = SuggestionSource.CONFLICT_DETECTION,
                                    buildSystem = buildSystem,
                                    conflictingVersions = distinctVersions
                                )
                            )
                        }
                    }
                }

                // Phase 3: Rules-based matching
                val allTransitiveIds = transitiveMap.keys
                val allDependencyIds = directDependencyIds + allTransitiveIds

                for (rule in exclusionRules) {
                    val ruleId = "${rule.groupId}:${rule.artifactId}"

                    if (ruleId !in allTransitiveIds) continue

                    if (rule.conditions != null) {
                        val conditionsMet = rule.conditions.whenPresent.all { required ->
                            required in allDependencyIds
                        }
                        if (!conditionsMet) continue
                    }

                    val occurrences = transitiveMap[ruleId] ?: continue
                    for (occurrence in occurrences) {
                        if (occurrence.parent.exclusions.any { it.groupId == rule.groupId && (it.artifactId == null || it.artifactId == rule.artifactId) }) {
                            continue
                        }

                        suggestions.add(
                            ExclusionSuggestion(
                                parentDependency = occurrence.parent,
                                exclusion = DependencyExclusion(rule.groupId, rule.artifactId),
                                reason = rule.reason,
                                severity = SuggestionSeverity.fromString(rule.severity),
                                source = SuggestionSource.KNOWN_RULES,
                                buildSystem = buildSystem
                            )
                        )
                    }
                }

                if (cancelled.get()) return@executeOnPooledThread

                val deduped = suggestions.distinctBy {
                    "${it.parentDependency.id}|${it.exclusion.id}"
                }.sortedWith(compareBy<ExclusionSuggestion> { it.severity.ordinal }.thenBy { it.exclusion.id })

                cachedResults = deduped
                cacheKey = key

                val result = AnalysisResult(deduped, totalCount, localCacheMisses)
                ApplicationManager.getApplication().invokeLater({ callback(result) }, ModalityState.any())
            } catch (e: Exception) {
                log.warn("Failed to analyze dependencies for exclusion suggestions", e)
                ApplicationManager.getApplication().invokeLater({ callback(AnalysisResult(emptyList(), filtered.size, filtered.size)) }, ModalityState.any())
            }
        }
    }

    /**
     * Clear the analysis cache.
     */
    fun clearCache() {
        cachedResults = null
        cacheKey = null
    }

    override fun dispose() {
        clearCache()
    }

    private fun loadExclusionRules(): List<ExclusionRule> {
        return try {
            val resourceStream = javaClass.getResourceAsStream(RULES_RESOURCE)
            if (resourceStream == null) {
                log.warn("Exclusion rules resource not found: $RULES_RESOURCE")
                return emptyList()
            }

            val content = resourceStream.bufferedReader().use { it.readText() }
            val config = json.decodeFromString<ExclusionRulesConfig>(content)
            log.info("Loaded ${config.knownProblematic.size} exclusion rules")
            config.knownProblematic
        } catch (e: Exception) {
            log.warn("Failed to load exclusion rules", e)
            emptyList()
        }
    }
}

// ── Data Classes ──

/**
 * Result of an exclusion suggestion analysis.
 */
data class AnalysisResult(
    val suggestions: List<ExclusionSuggestion>,
    val totalAnalyzed: Int,
    val localCacheMisses: Int
) {
    val hasMissingPoms: Boolean get() = localCacheMisses > 0
    val allMissing: Boolean get() = localCacheMisses == totalAnalyzed && totalAnalyzed > 0
}

/**
 * A suggestion to add an exclusion to a specific dependency.
 */
data class ExclusionSuggestion(
    val parentDependency: InstalledDependency,
    val exclusion: DependencyExclusion,
    val reason: String,
    val severity: SuggestionSeverity,
    val source: SuggestionSource,
    val buildSystem: BuildSystem = BuildSystem.GRADLE,
    val conflictingVersions: List<String> = emptyList()
) {
    val displayName: String
        get() = "Exclude ${exclusion.displayName} from ${parentDependency.id}"
}

enum class BuildSystem {
    GRADLE, MAVEN
}

enum class SuggestionSeverity {
    CRITICAL, WARNING, INFO;

    companion object {
        fun fromString(value: String): SuggestionSeverity = when (value.lowercase()) {
            "critical" -> CRITICAL
            "warning" -> WARNING
            else -> INFO
        }
    }
}

enum class SuggestionSource {
    CONFLICT_DETECTION,
    KNOWN_RULES
}

/**
 * Internal tracking of where a transitive dependency comes from.
 */
private data class TransitiveOccurrence(
    val parent: InstalledDependency,
    val groupId: String,
    val artifactId: String,
    val version: String
)

// ── Serializable JSON model for exclusion-rules.json ──

@Serializable
data class ExclusionRulesConfig(
    val knownProblematic: List<ExclusionRule>
)

@Serializable
data class ExclusionRule(
    val groupId: String,
    val artifactId: String,
    val reason: String,
    val severity: String,
    val conditions: ExclusionRuleConditions? = null
)

@Serializable
data class ExclusionRuleConditions(
    val whenPresent: List<String> = emptyList()
)
