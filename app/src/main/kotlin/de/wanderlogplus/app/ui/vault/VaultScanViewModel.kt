package de.wanderlogplus.app.ui.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.wanderlogplus.app.data.VaultScanStateEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** State for the Sensitiv-Scan screen; the scan itself runs in a WorkManager worker (survives process death). */
class VaultScanViewModel(
    scanState: () -> Flow<VaultScanStateEntity?>,
    foundCount: () -> Flow<Int>,
    pendingCount: () -> Flow<Int>,
    private val startScan: () -> Unit,
    private val resumeUploads: () -> Unit,
) : ViewModel() {
    val state: StateFlow<VaultScanStateEntity?> = scanState().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val found: StateFlow<Int> = foundCount().stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val pending: StateFlow<Int> = pendingCount().stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    fun start() = startScan()

    /** Enqueues the upload worker chain for the queued-but-not-yet-transferred finds. */
    fun resume() = resumeUploads()
}
