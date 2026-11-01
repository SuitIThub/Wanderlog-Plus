package de.wanderlogplus.app.sync

import androidx.work.ListenableWorker.Result
import de.wanderlogplus.client.ApiErrorKind
import de.wanderlogplus.client.SyncResult
import org.junit.Assert.assertEquals
import org.junit.Test

class SyncWorkerTest {
    private fun failed(kind: ApiErrorKind) = SyncWorker.resultFor(SyncResult.Failed(kind, "x"))

    @Test fun retriesRecoverableErrors() {
        for (k in listOf(ApiErrorKind.NETWORK, ApiErrorKind.SERVER, ApiErrorKind.UNAUTHORIZED, ApiErrorKind.PROTOCOL)) {
            assertEquals(k.name, Result.retry(), failed(k))
        }
    }

    @Test fun clockDriftAndOthersSucceedWithoutRetry() {
        for (k in listOf(ApiErrorKind.CLOCK_DRIFT, ApiErrorKind.BAD_REQUEST, ApiErrorKind.CURSOR_AHEAD, ApiErrorKind.NOT_CONFIGURED)) {
            assertEquals(k.name, Result.success(), failed(k))
        }
        assertEquals(Result.success(), SyncWorker.resultFor(SyncResult.Ok(1, 2)))
        assertEquals(Result.success(), SyncWorker.resultFor(null))
    }
}
