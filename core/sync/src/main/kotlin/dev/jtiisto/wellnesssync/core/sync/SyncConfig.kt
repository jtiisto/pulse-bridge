package dev.jtiisto.wellnesssync.core.sync

data class SyncConfig(
    val batchSize: Int = DEFAULT_BATCH_SIZE,
) {
    companion object {
        const val DEFAULT_BATCH_SIZE = 500
    }
}
