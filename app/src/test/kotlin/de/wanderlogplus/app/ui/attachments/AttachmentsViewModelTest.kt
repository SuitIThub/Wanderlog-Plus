package de.wanderlogplus.app.ui.attachments

import de.wanderlogplus.app.files.AttachmentTooLargeException
import de.wanderlogplus.shared.sync.EntityType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class AttachmentsViewModelTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val calls = mutableListOf<Triple<EntityType, String, String>>()

    private fun source(name: String = "Ticket.pdf") = object : AttachmentSource {
        override fun fileName() = name
        override fun mimeType() = "application/pdf"
        override fun open(): InputStream? = ByteArrayInputStream(ByteArray(3))
    }

    private fun vm(add: suspend (EntityType, String, InputStream, String, String) -> String = { t, id, _, n, _ -> calls += Triple(t, id, n); "x" }) =
        AttachmentsViewModel(edit = {}, addAttachment = add, ensureDay = { "day-$it" }, writeScope = scope)

    @Test fun addToDayCreatesDayFirstAndTargetsIt() = runBlocking {
        vm().add(AttachmentTarget.Day("2027-05-20"), source()).join()
        assertEquals(listOf(Triple(EntityType.DAY, "day-2027-05-20", "Ticket.pdf")), calls)
    }

    @Test fun tooLargeShowsFixedMessage() = runBlocking {
        val v = vm { _, _, _, _, _ -> throw AttachmentTooLargeException() }
        v.add(AttachmentTarget.Entity(EntityType.TRAIN, "t"), source()).join()
        assertEquals("Datei zu groß (max. 25 MB)", v.message.value)
        v.consumeMessage()
        assertNull(v.message.value)
    }

    @Test fun otherFailureDoesNotLeakDetails() = runBlocking {
        val v = vm { _, _, _, name, _ -> throw IllegalStateException("secret $name") }
        v.add(AttachmentTarget.Entity(EntityType.TRAIN, "t"), source("Geheim.pdf")).join()
        assertEquals(MSG_ADD_FAILED, v.message.value)
    }
}
