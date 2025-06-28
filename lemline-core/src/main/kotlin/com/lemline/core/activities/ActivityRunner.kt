// SPDX-License-Identifier: BUSL-1.1
package com.lemline.core.activities

import com.lemline.core.instances.ActivityInstance

/**
 * A generic interface for a component that can execute a specific type of activity.
 *
 * @param T The specific type of ActivityInstance this runner can handle.
 */
interface ActivityRunner<T : ActivityInstance<*>> {
    suspend fun run(instance: T)
}
