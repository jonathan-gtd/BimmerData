/*
 * BimmerData - Copyright (C) 2026 Jonathan GATARD - GPL-3.0-or-later, see LICENSE.
 */
package io.github.jonathangtd.bimmerdata.live.vehicle

import io.github.jonathangtd.bimmerdata.live.CableExecutor
import java.util.Locale

/**
 * [JobRunner] on the shared cable: every job is one [CableExecutor.exclusive] operation, so the
 * live reading goes on between the jobs of a vehicle scan or a fault read. Same job whitelist as
 * [EdiabasJobRunner]. Cancel by cancelling the calling coroutine.
 */
class SharedJobRunner(private val cable: CableExecutor, private val label: String = "Faults") : JobRunner {

    override suspend fun run(sgbd: String, job: String, args: String): JobOutcome {
        require(job.uppercase(Locale.ROOT) in VehicleJobs.Read) { "job $job is not allowed here" }
        return execute(sgbd, job, args)
    }

    override suspend fun clear(sgbd: String, job: String, confirmation: UserConfirmation): JobOutcome {
        require(job.uppercase(Locale.ROOT) in VehicleJobs.Clear) { "job $job is not a fault memory clear" }
        return execute(sgbd, job, "")
    }

    private suspend fun execute(sgbd: String, job: String, args: String): JobOutcome =
        cable.exclusive("$label: ${EdFilesName.base(sgbd).uppercase(Locale.ROOT)} $job") { a ->
            try {
                JobOutcome(a.executeJob(sgbd, job, args).drop(1))
            } catch (ex: Exception) {
                val partial = a.ediabas.resultSets?.drop(1) ?: emptyList()
                JobOutcome(
                    partial, error = if (a.aborted) "Cancelled" else EdiabasJobRunner.describe(ex),
                    noResponse = EdiabasJobRunner.isNoResponse(ex), cancelled = a.aborted,
                )
            }
        }

    override suspend fun resolveGroup(group: String): GroupResolution =
        cable.exclusive("$label: identify ${EdFilesName.base(group).uppercase(Locale.ROOT)}") { a ->
            try {
                GroupResolution(EdFilesName.base(a.resolveGroup(group.lowercase(Locale.ROOT))))
            } catch (ex: Exception) {
                GroupResolution(null, if (a.aborted) "Cancelled" else EdiabasJobRunner.describe(ex), EdiabasJobRunner.isNoResponse(ex))
            }
        }
}
