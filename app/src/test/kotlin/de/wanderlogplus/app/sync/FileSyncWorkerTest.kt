package de.wanderlogplus.app.sync

import androidx.work.ListenableWorker.Result
import de.wanderlogplus.app.files.FileSyncResult
import org.junit.Assert.assertEquals
import org.junit.Test

class FileSyncWorkerTest {
    @Test fun mapsResults() {
        assertEquals(Result.success(), FileSyncWorker.resultFor(FileSyncResult(1, 1, 0)))
        assertEquals(Result.success(), FileSyncWorker.resultFor(FileSyncResult(0, 0, 1, retryable = false)))
        assertEquals(Result.retry(), FileSyncWorker.resultFor(FileSyncResult(0, 0, 1, retryable = true)))
    }
}
