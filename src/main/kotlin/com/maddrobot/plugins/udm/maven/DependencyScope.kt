package com.maddrobot.plugins.udm.maven

/**
 * Represents the scope of a dependency in a build system.
 *
 * The `DependencyScope` enum class provides various scopes commonly used in build tools
 * such as Gradle and Maven. Each scope is characterized by how the dependency is applied
 * during the build lifecycle (e.g., during compilation, testing, or runtime).
 *
 * @property displayName The human-readable name of the scope.
 * @property gradleScopeConfig The corresponding configuration name for the Gradle build system.
 * @property mavenScopeConfig The corresponding scope name for the Maven build system, if applicable.
 */
enum class DependencyScope(val displayName: String, val gradleScopeConfig: String, val mavenScopeConfig: String?) {
    /**
     * Represents the "Compile" dependency scope in a Gradle or Maven build environment.
     *
     * This scope is used to define dependencies that are required during the compilation phase
     * of a project. These dependencies are bundled into the final deliverable.
     *
     * @property displayName A user-friendly name for the dependency scope ("Compile").
     * @property gradleScopeConfig The corresponding Gradle configuration for this scope ("implementation").
     * @property mavenScopeConfig The corresponding Maven scope for this dependency, or null if no specific scope is required.
     */
    COMPILE("Compile", "implementation", null),

    /**
     * Represents the "Test" dependency scope in a build system.
     *
     * The `TEST` enum constant is one of the predefined dependency scopes, offering a specific
     * configuration for dependencies that are required during the testing phase of the build process.
     *
     * @property displayName The human-readable name of the scope, used for display purposes.
     * @property gradleScopeConfig The scope configuration to be used in a Gradle build script.
     * @property mavenScopeConfig The corresponding scope configuration for Maven builds, if applicable.
     */
    TEST("Test", "testImplementation", "test"),

    /**
     * Represents the "Provided" dependency scope in a Maven or Gradle build system.
     *
     * The "Provided" scope indicates that the dependency is required at compile-time but will
     * not be bundled with the application. It is typically used for dependencies that are
     * provided by the runtime environment, such as application servers or containers.
     *
     * @property displayName The human-readable name of the dependency scope.
     * @property gradleScopeConfig The corresponding configuration name for Gradle build scripts.
     * @property mavenScopeConfig The corresponding scope name for Maven configurations.
     */
    PROVIDED("Provided", "compileOnly", "provided"),

    /**
     * Represents the runtime dependency scope in a project.
     *
     * The runtime scope includes dependencies that are needed for the application to run but are not
     * required at compile-time. This scope is typically used for libraries or resources that are loaded
     * dynamically or used during the runtime of the application.
     *
     * @property displayName A user-friendly name for the dependency scope, displayed as "Runtime".
     * @property gradleScopeConfig The corresponding Gradle configuration keyword for this scope: "runtimeOnly".
     * @property mavenScopeConfig The equivalent Maven scope name for this scope: "runtime".
     */
    RUNTIME("Runtime", "runtimeOnly", "runtime")
}
