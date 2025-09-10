// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.activities

import com.lemline.core.instances.ActivityInstance
import com.lemline.core.instances.CallAsyncApiInstance
import com.lemline.core.instances.CallGrpcInstance
import com.lemline.core.instances.CallHttpInstance
import com.lemline.core.instances.CallOpenApiInstance
import com.lemline.core.instances.EmitInstance
import com.lemline.core.instances.ListenInstance
import com.lemline.core.instances.RunInstance
import com.lemline.core.instances.WaitInstance
import kotlin.reflect.KClass
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.JsonElement

/**
 * A registry that holds and provides the correct ActivityRunner for a given ActivityInstance.
 *
 * This provider uses a hybrid approach for maximum safety and flexibility:
 * 1.  It first checks a map of custom runners, allowing users to override or extend behavior.
 * 2.  If no custom runner is found, it falls back to a compile-time safe `when` block
 *     that dispatches to the default, built-in runners for all known sealed subtypes
 *     of `ActivityInstance`.
 */
@ExperimentalTime
class ActivityRunnerProvider(
    private val customRunners: Map<KClass<out ActivityInstance<*>>, ActivityRunner<*>> = emptyMap(),
) {
    /**
     * Finds the appropriate runner for the given instance and executes it.
     * It prioritizes custom runners before falling back to the default built-in runners.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun run(instance: ActivityInstance<*>): JsonElement {
        // 1. Prioritize custom/overridden runners from the map.
        val customRunner = customRunners[instance::class]
        if (customRunner != null) {
            return (customRunner as ActivityRunner<ActivityInstance<*>>).run(instance)
        }

        // 2. Fallback to default runners.
        return when (instance) {
            is CallHttpInstance -> defaultHttpCallRunner.run(instance)
            is RunInstance -> defaultRunTaskRunner.run(instance)
            is WaitInstance -> defaultWaitTaskRunner.run(instance)
            is CallGrpcInstance -> notImplementedRunner.run(instance)
            is CallAsyncApiInstance -> notImplementedRunner.run(instance)
            is CallOpenApiInstance -> notImplementedRunner.run(instance)
            is EmitInstance -> notImplementedRunner.run(instance)
            is ListenInstance -> notImplementedRunner.run(instance)
        }
    }


    /**
     * Creates a new ActivityRunnerProvider by adding or replacing a custom runner.
     * This is an immutable operation; it returns a new instance.
     *
     * @param pair A pair of the ActivityInstance class and the runner to register for it.
     * @return A new ActivityRunnerProvider instance with the added runner.
     */
    operator fun <T : ActivityInstance<*>> plus(pair: Pair<KClass<out T>, ActivityRunner<T>>) =
        ActivityRunnerProvider(customRunners + pair)

    /**
     * Merges this provider with another, creating a new provider.
     * Runners from the `other` provider will overwrite any existing runners for the same key.
     *
     * @param other The ActivityRunnerProvider to merge with.
     * @return A new ActivityRunnerProvider instance containing runners from both providers.
     */
    operator fun plus(other: ActivityRunnerProvider) =
        ActivityRunnerProvider(this.customRunners + other.customRunners)


    companion object {
        /**
         * A default provider with no custom runners. It will use the built-in `when` block
         * for all executions. This can be extended via the `plus` operator.
         */
        val default by lazy { ActivityRunnerProvider() }

        // Pre-instantiate the default runners to be used by the 'when' block.
        // These are private to the companion object to avoid direct access.
        private val defaultHttpCallRunner = HttpCallRunner()
        private val defaultRunTaskRunner = RunTaskRunner()
        private val defaultWaitTaskRunner = WaitTaskRunner()
        private val notImplementedRunner = NotImplementedRunner<ActivityInstance<*>>()
    }
}

/**
 * A placeholder runner for activities that are not yet implemented.
 * Throws a NotImplementedError when its run method is called.
 */
class NotImplementedRunner<T : ActivityInstance<*>> : ActivityRunner<T> {
    override suspend fun run(instance: T): JsonElement {
        TODO("Activity runner for ${instance::class.simpleName} is not yet implemented.")
    }
}
