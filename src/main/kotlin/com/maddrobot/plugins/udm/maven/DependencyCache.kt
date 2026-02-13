package com.maddrobot.plugins.udm.maven

/**
 * Represents a cache for holding Maven dependencies from various sources.
 *
 * This class acts as a centralized storage for dependencies retrieved from different repositories.
 * Dependencies are categorized into three lists: local, central, and Nexus-based repositories.
 */
class DependencyCache {

    /**
     * Represents a collection of local Maven dependencies available in the system.
     *
     * This list is populated with instances of the `Dependency` class, which describes the core
     * components of a Maven dependency, including `groupId`, `artifactId`, and `version`. These dependencies
     * are typically resolved or loaded from the local `.m2` repository or other similar sources.
     *
     * This variable is initialized with an empty list by default and can be updated with relevant
     * dependencies based on the project configuration or scanning logic.
     */
    var localDependencies: List<Dependency> = emptyList()

    /**
     * A list representing dependencies retrieved from the central Maven repository.
     *
     * This variable holds the dependencies that are obtained from the central repository
     * for build or dependency resolution purposes. Each dependency is represented as an
     * instance of the [Dependency] class, encompassing core Maven GAV (GroupId, ArtifactId,
     * Version) information.
     *
     * This property is initialized to an empty list and can be dynamically populated based
     * on the resolution or retrieval logic implemented in the context of the application.
     */
    var centralDependencies: List<Dependency> = emptyList()

    /**
     * A list of dependencies sourced from the Nexus repository.
     *
     * This property stores Maven dependency metadata retrieved from the Nexus repository, which typically includes
     * information such as group ID, artifact ID, and version. It is part of the [DependencyCache] class and complements
     * other dependency lists, such as local and central dependencies.
     *
     * This variable is initialized to an empty list and can be updated with dependency data fetched or processed
     * from Nexus.
     */
    var nexusDependencies: List<Dependency> = emptyList()
}
