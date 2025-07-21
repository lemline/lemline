// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.exceptions

import kotlin.time.Duration


class WaitStartedException(val delay: Duration) : RuntimeException()
