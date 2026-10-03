package dev.typenil.vpnclient.core.common.log

import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticProviderTest {
    @Test fun memoryPipeIsReadableButNotWritable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val text = "Core warnings\nWARN synthetic timeout\n"
        val id = DiagnosticProvider.store.publish(text)
        val uri = Uri.parse("content://${context.packageName}.diagnostics/$id")
        val resolver = context.contentResolver
        assertEquals(text, resolver.openInputStream(uri)!!.bufferedReader().use { it.readText() })
        resolver.query(uri, null, null, null, null)!!.use {
            assertTrue(it.moveToFirst())
            assertEquals(text.toByteArray().size, it.getInt(it.getColumnIndexOrThrow(OpenableColumns.SIZE)))
        }
        assertThrows(java.io.FileNotFoundException::class.java) { resolver.openFileDescriptor(uri, "w") }
        DiagnosticProvider.store.publish("new snapshot")
        assertThrows(java.io.FileNotFoundException::class.java) { resolver.openInputStream(uri) }
    }
}
