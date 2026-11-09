// SPDX-License-Identifier: BUSL-1.1
package com.lemline.runner.cli.failures

import com.lemline.common.values.IDV7
import com.lemline.core.states.WorkflowCommand
import com.lemline.core.states.WorkflowEvent
import com.lemline.core.states.WorkflowState
import com.lemline.runner.cli.GlobalMixin
import com.lemline.runner.cli.exceptions.CliException
import com.lemline.runner.common.messaging.CommandEmitter
import com.lemline.runner.common.messaging.EventEmitter
import com.lemline.runner.common.messaging.FailureReasons
import com.lemline.runner.common.messaging.InstanceMessage
import com.lemline.runner.failures.FailureModel
import com.lemline.runner.failures.FailureRepository
import io.quarkus.arc.Unremovable
import jakarta.inject.Inject
import kotlinx.coroutines.runBlocking
import picocli.CommandLine.Command
import picocli.CommandLine.Mixin
import picocli.CommandLine.Option

/**
 * Re-emits parked failure payloads to the broker.
 *
 * Workflow state travels only in messages, so a message parked in the failures table
 * (e.g. because its namespace was not followed by the receiving runner) is the only
 * copy of that instance's state. Once the operator has fixed the broker bindings,
 * this command decodes each parked payload and re-emits it. Routing is a pure
 * function of the message's namespace, so re-emission is safe.
 *
 * Commands are re-emitted on the commands channel, events on the events channel;
 * the decoded payload's workflow state kind determines the channel.
 */
@Unremovable
@Command(
    name = "replay",
    description = [
        "Re-emits parked failure payloads to their per-namespace broker destination.",
        "- Selects a single failure with --id, or all failures with the given --reason.",
        "- Successfully re-emitted rows are deleted from the failures table.",
        "- Rows whose payload cannot be decoded are reported and kept.",
        "- Requires confirmation unless --yes is used."
    ],
)
open class FailuresReplayCommand : Runnable {

    @Mixin
    lateinit var mixin: GlobalMixin

    @Inject
    lateinit var failureRepository: FailureRepository

    @Inject
    internal lateinit var commandEmitter: CommandEmitter

    @Inject
    internal lateinit var eventEmitter: EventEmitter

    @Option(
        names = ["--id"],
        description = ["Replay the single failure row with this id (UUID)."],
    )
    var id: String? = null

    @Option(
        names = ["--reason"],
        description = ["Replay all failure rows with this reason (default: foreign_namespace)."],
    )
    var reason: String = FailureReasons.FOREIGN_NAMESPACE

    @Option(
        names = ["--dry-run"],
        description = ["List the failures that would be replayed without emitting anything."],
        defaultValue = "false"
    )
    var dryRun: Boolean = false

    @Option(
        names = ["--yes", "-y"],
        description = ["Replay without asking for confirmation."],
        defaultValue = "false"
    )
    var yes: Boolean = false

    override fun run(): Unit = runBlocking {
        val failures = selectFailures()
        if (failures.isEmpty()) {
            cliPrint(id?.let { "No failure found with id '$it'." } ?: "No failures found with reason '$reason'.")
            return@runBlocking
        }

        if (dryRun) {
            cliPrint("Dry run: ${failures.size} failure(s) would be replayed:")
            failures.forEach { cliPrint("  ${describe(it)}") }
            return@runBlocking
        }

        if (!confirmReplay(failures.size)) return@runBlocking

        var replayed = 0
        var failed = 0
        for (failure in failures) {
            val message = try {
                decode(failure)
            } catch (e: Exception) {
                cliPrintError("Skipping failure ${failure.id}: cannot decode payload (${e.message})")
                failed++
                continue
            }
            try {
                val kind = emit(message)
                failureRepository.deleteById(failure.id)
                cliPrint("Replayed failure ${failure.id} (namespace: ${message.workflowInfo.namespace}, kind: $kind)")
                replayed++
            } catch (e: Exception) {
                cliPrintError("Failed to replay failure ${failure.id}: ${e.message}")
                failed++
            }
        }

        cliPrint("Replay complete: $replayed replayed, $failed failed.")
    }

    /**
     * Selects the failure rows to replay: a single row by id, or all rows with the given reason.
     */
    private suspend fun selectFailures(): List<FailureModel> = when (val failureId = id) {
        null -> failureRepository.findByReason(reason)
        else -> {
            val idv7 = try {
                IDV7.from(failureId)
            } catch (e: Exception) {
                cliError("Invalid failure id '$failureId': ${e.message}")
            }
            listOfNotNull(failureRepository.findById(idv7))
        }
    }

    /**
     * Decodes the instance message carried by a failure row.
     *
     * Foreign-namespace failures keep the raw transport payload (the protobuf envelope);
     * decoding it yields the workflow state, whose kind (command vs event) selects the channel.
     * Rows without a raw payload fall back to the already-decoded instance message, if any.
     */
    private fun decode(failure: FailureModel): InstanceMessage<out WorkflowState> =
        failure.payload?.let { InstanceMessage.fromTransportPayload<WorkflowState>(it) }
            ?: failure.instanceMessage
            ?: throw IllegalStateException("row has neither a payload nor a decoded instance message")

    /**
     * Routes the message to the channel matching its workflow state kind.
     * The emitters derive the per-namespace destination from the message's workflowInfo.
     *
     * @return "command" or "event", for logging
     */
    private suspend fun emit(message: InstanceMessage<out WorkflowState>): String =
        when (val state = message.workflowState) {
            is WorkflowCommand -> {
                commandEmitter.send(InstanceMessage(message.workflowInfo, state))
                "command"
            }

            is WorkflowEvent -> {
                eventEmitter.send(InstanceMessage(message.workflowInfo, state))
                "event"
            }
        }

    private fun describe(failure: FailureModel): String {
        val decoded = runCatching { decode(failure) }.getOrNull()
        return when (decoded) {
            null -> "${failure.id} (reason: ${failure.errorReason}, payload cannot be decoded)"
            else -> "${failure.id} (namespace: ${decoded.workflowInfo.namespace}, " +
                "kind: ${decoded.kind}, reason: ${failure.errorReason})"
        }
    }

    private val InstanceMessage<out WorkflowState>.kind: String
        get() = when (workflowState) {
            is WorkflowCommand -> "command"
            is WorkflowEvent -> "event"
        }

    // Made protected to enable testing through inheritance
    protected fun confirmReplay(count: Int): Boolean {
        // This check makes confirmation a no-op if --yes is used
        if (yes) return true

        print("Replay $count failure(s) to the broker? [y/N]: ")
        val confirmation = readUserInput()?.trim()?.lowercase()
        if (confirmation != "y" && confirmation != "yes") {
            cliPrint("Replay cancelled.")
            return false
        }
        return true
    }

    // Made protected and extracted to support testing through override
    protected open fun readUserInput(): String? = readlnOrNull()

    fun cliPrint(msg: String) = println(msg)

    fun cliPrintError(msg: String) = System.err.println(msg)

    internal fun cliError(msg: String): Nothing = throw CliException(msg)
}
