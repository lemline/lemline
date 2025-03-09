package com.lemline.swruntime.system

import org.jetbrains.annotations.TestOnly

/**
 * Object that provides utility methods to access (and test) environment variables.
 */
object System {

    private val _env by lazy {
        java.lang.System.getenv().toMutableMap()
    }

    /**
     * Retrieves the current environment variables as a map.
     *
     * @return a map of environment variable names to their values.
     */
    fun getEnv() = _env

    /**
     * Retrieves the value of the specified environment variable.
     *
     * @param key the name of the environment variable.
     * @return the value of the environment variable, or null if the variable is not defined.
     */
    fun getEnv(key: String) = _env[key]

    @TestOnly
    fun setEnv(key: String, value: String) {
        _env[key] = value
    }

    @TestOnly
    fun restoreEnv() {
        _env.clear()
        _env.putAll(java.lang.System.getenv())
    }
}