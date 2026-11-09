// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.messaging.cloudevents

import com.lemline.common.values.WorkflowNamespace
import io.cloudevents.CloudEvent

/**
 * An incoming CloudEvent together with the namespace of the destination it arrived on.
 *
 * A runner following several namespaces consumes all their `<prefix>-<namespace>-cloudevents`
 * destinations on the single `cloudevents-in` channel. [arrivalNamespace] identifies which
 * destination delivered the event and scopes listener correlation to that namespace only.
 *
 * Deliberately does NOT implement [com.lemline.common.values.WithOptionalWorkflowInfo]:
 * external CloudEvents carry no workflow identity, so the generic foreign-namespace guard in
 * [com.lemline.runner.common.messaging.MessageHandler] cannot apply. [CloudEventHandler.handle]
 * enforces the equivalent guard against [arrivalNamespace] instead.
 */
internal data class ReceivedCloudEvent(
    val event: CloudEvent,
    val arrivalNamespace: WorkflowNamespace,
)
