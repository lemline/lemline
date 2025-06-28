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

/**
 * A registry that holds and provides the correct ActivityRunner for a given ActivityInstance.
 * This uses a map to look up the runner at runtime based on the instance's class.
 * This class is immutable; modification methods return a new instance.
 */
class ActivityRunnerProvider(
    private val runners: Map<KClass<out ActivityInstance<*>>, ActivityRunner<*>>,
) {
    /**
     * Finds the appropriate runner for the given instance and executes it.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun run(instance: ActivityInstance<*>) {
        // Find the runner registered for this specific instance class (e.g., CallHttpInstance::class)
        val runner = runners[instance::class]
            ?: throw IllegalStateException("No ActivityRunner registered for type ${instance::class.simpleName}")

        // We need to cast because the map stores Runner<*>, but we know it's the correct one.
        (runner as ActivityRunner<ActivityInstance<*>>).run(instance)
    }

    /**
     * Creates a new ActivityRunnerProvider by adding or replacing a runner for a given activity type.
     * This is an immutable operation; it returns a new instance.
     *
     * @param pair A pair of the ActivityInstance class and the runner to register for it.
     * @return A new ActivityRunnerProvider instance with the added runner.
     */
    operator fun plus(pair: Pair<KClass<out ActivityInstance<*>>, ActivityRunner<*>>): ActivityRunnerProvider {
        return ActivityRunnerProvider(runners + pair)
    }

    /**
     * Merges this provider with another, creating a new provider.
     * Runners from the `other` provider will overwrite any existing runners for the same key.
     *
     * @param other The ActivityRunnerProvider to merge with.
     * @return A new ActivityRunnerProvider instance containing runners from both providers.
     */
    operator fun plus(other: ActivityRunnerProvider): ActivityRunnerProvider {
        return ActivityRunnerProvider(this.runners + other.runners)
    }

    companion object {
        /**
         * A default provider containing runners for all standard, built-in activities.
         * This can be used as a base and extended with custom runners.
         */
        val default by lazy {
            ActivityRunnerProvider(
                mapOf(
                    CallHttpInstance::class to HttpCallRunner(),
                    RunInstance::class to RunTaskRunner(),
                    WaitInstance::class to WaitTaskRunner(),
                    CallGrpcInstance::class to NotImplementedRunner(),
                    CallAsyncApiInstance::class to NotImplementedRunner(),
                    CallOpenApiInstance::class to NotImplementedRunner(),
                    EmitInstance::class to NotImplementedRunner(),
                    ListenInstance::class to NotImplementedRunner(),
                )
            )
        }
    }
}

/**
 * A placeholder runner for activities that are not yet implemented.
 * Throws a NotImplementedError when its run method is called.
 */
class NotImplementedRunner<T : ActivityInstance<*>> : ActivityRunner<T> {
    override suspend fun run(instance: T) {
        TODO("Activity runner for ${instance::class.simpleName} is not yet implemented.")
    }
}
