package dev.jtiisto.wellnesssync.core.sync

data class SyncConfig(
    val batchSize: Int = DEFAULT_BATCH_SIZE,
    val maxQuarantinePerRun: Int = DEFAULT_MAX_QUARANTINE_PER_RUN,
    val maxQuarantineWithoutSuccess: Int = DEFAULT_MAX_QUARANTINE_WITHOUT_SUCCESS,
) {
    companion object {
        const val DEFAULT_BATCH_SIZE = 500

        // Hard cap on rows quarantined in a single sync run. A genuine poison
        // row needs 1; blowing through this many means the failure is
        // systemic (contract drift) and the run aborts loudly instead of
        // quarantining the backlog.
        const val DEFAULT_MAX_QUARANTINE_PER_RUN = 10

        // Circuit breaker for systemic 422s: if NO request has succeeded yet
        // this run, only this many rows may be quarantined before aborting —
        // a success elsewhere is the evidence that rejections are row-specific
        const val DEFAULT_MAX_QUARANTINE_WITHOUT_SUCCESS = 3
    }
}
