// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.config

import com.lemline.runner.config.shared.toDuration as toSharedDuration
import kotlin.time.Duration

internal fun String.toDuration(): Duration = this.toSharedDuration()
