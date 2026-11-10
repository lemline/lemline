// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.gateway.grpc

import com.google.protobuf.Duration as ProtoDuration
import com.google.protobuf.Timestamp as ProtoTimestamp
import java.time.Instant

internal fun toProtoTimestamp(value: Instant): ProtoTimestamp =
    ProtoTimestamp.newBuilder()
        .setSeconds(value.epochSecond)
        .setNanos(value.nano)
        .build()

internal fun toProtoDuration(valueMillis: Long): ProtoDuration {
    val seconds = valueMillis / 1_000
    val nanos = ((valueMillis % 1_000) * 1_000_000).toInt()
    return ProtoDuration.newBuilder()
        .setSeconds(seconds)
        .setNanos(nanos)
        .build()
}
