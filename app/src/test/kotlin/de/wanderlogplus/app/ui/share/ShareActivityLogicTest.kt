package de.wanderlogplus.app.ui.share

import android.content.Intent
import android.net.Uri
import de.wanderlogplus.app.ui.attachments.AttachmentSource
import de.wanderlogplus.shared.sync.EntityType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShareActivityLogicTest {
    @Test fun ownFileProviderAndFileUrisAreRejected() {
        val own = Uri.parse("content://de.wanderlogplus.app.files/attachments/abc")
        val other = Uri.parse("content://com.example.docs/doc/1")
        val file = Uri.parse("file:///data/data/de.wanderlogplus.app/files/x")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(own, other, file))
        assertEquals(listOf(other), sharedUris(intent, ownAuthority = "de.wanderlogplus.app.files"))
    }

    @Test fun errorInImportStillSetsResult() = runBlocking {
        val source = object : AttachmentSource {
            override fun fileName() = "a.pdf"
            override fun mimeType() = "application/pdf"
            override fun open(): InputStream = "x".byteInputStream()
        }
        val sink = ShareSink { _, _, _, _ -> throw OutOfMemoryError() }
        val vm = ShareViewModel(sink, listOf(source), CoroutineScope(SupervisorJob() + Dispatchers.Default))
        vm.attach(ShareTarget(EntityType.TRAIN, "tr1", "Zug", null, ShareGroup.TRAIN))
        val result = withTimeout(5_000) { vm.result.filterNotNull().first() }
        assertEquals(ShareResult(0, 0, 1), result)
    }
}
