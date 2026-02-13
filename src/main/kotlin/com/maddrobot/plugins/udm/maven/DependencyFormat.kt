package com.maddrobot.plugins.udm.maven

/**
 * Represents the format in which a dependency can be declared in build scripts.
 *
 * This enum is used to specify different styles of dependency declarations supported by build tools like Maven or Gradle.
 * Each enum constant provides a display name that signifies its specific declaration format.
 */
enum class DependencyFormat(val displayName: String) {
    /**
     * Enumeration representing different formats for declaring dependencies within build systems.
     *
     * Each format corresponds to the syntax or specific declaration method required by a particular
     * build tool or configuration system. For example, Maven declarations use XML, while Gradle
     * uses Groovy or Kotlin-based DSLs.
     *
     * @property displayName The human-readable name of the dependency format.
     */
    MavenDeclaration("Maven"),

    /**
     * Represents the declaration of a dependency in a Groovy-based Gradle DSL format.
     *
     * This format is commonly used in `build.gradle` files to declare dependencies with a
     * string notation that includes the group ID, artifact ID, and version.
     *
     * Example of the Gradle Groovy declaration format:
     * `implementation 'group:artifact:version'`
     *
     * The `displayName` property is used to describe the declaration format succinctly
     * as "Gradle (short)".
     */
    GradleGroovyDeclaration("Gradle (short)"),

    /**
     * Represents a dependency format declaration for Gradle using Kotlin DSL.
     *
     * This declaration specifically formats dependencies in the Kotlin-based syntax
     * used in Gradle build scripts. It is part of the [DependencyFormat] enumeration
     * that includes various formats, such as Maven and Gradle Groovy.
     *
     * Use this format for Gradle build files that leverage Kotlin's DSL for dependency
     * management, where dependencies are typically expressed with Kotlin methods
     * and string interpolation.
     */
    GradleKotlinDeclaration("Gradle (Kotlin)")
}
