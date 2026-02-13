package com.maddrobot.plugins.udm.npm

/**
 * Enum class representing supported NPM package managers.
 *
 * This enum provides a set of pre-defined package manager options
 * commonly used in JavaScript/Node.js environments for managing dependencies.
 *
 * Each enum constant is associated with a display name, which corresponds
 * to the actual command name of the package manager.
 *
 * The enum includes the following package managers:
 * - NPM: Represents the "npm" package manager, the default for Node.js.
 * - YARN: Represents the "yarn" package manager, known for its speed and efficiency.
 * - PNPM: Represents the "pnpm" package manager, featuring disk space optimization.
 *
 * @property displayName The string representation of the package manager's command.
 */
enum class NpmPackageManager(val displayName: String) {
    /**
     * Represents the NPM package manager within the context of the application.
     *
     * This enum constant is part of the `NpmPackageManager` enumeration, which defines
     * supported package managers for operations such as package installation and management.
     *
     * The `NPM` constant corresponds to the default package manager "npm".
     *
     * @property displayName A string representation of the package manager's name, used for display or commands.
     */
    NPM("npm"),

    /**
     * Represents the Yarn package manager in the context of NPM-based dependency management.
     *
     * Yarn is an alternative to npm for managing JavaScript project dependencies. It enables users
     * to install, manage, and share packages with enhanced performance and reliability.
     *
     * Commonly used as one of the available options for developers managing NPM packages.
     *
     * @property displayName The human-readable name of the package manager.
     */
    YARN("yarn"),

    /**
     * Represents the PNPM package manager.
     *
     * PNPM is an alternative to NPM and Yarn for managing JavaScript packages in a project. It
     * provides fast installation and efficient disk space usage by creating a single content-addressable
     * storage for all files.
     *
     * This enum constant can be used to reference PNPM as a package manager within the context
     * of the `NpmPackageManager` enumeration.
     *
     * @property displayName The display name for the package manager, which in this case is "pnpm".
     */
    PNPM("pnpm"),
}
