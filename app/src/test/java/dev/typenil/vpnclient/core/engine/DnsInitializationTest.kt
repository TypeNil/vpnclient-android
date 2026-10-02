package dev.typenil.vpnclient.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Isolate the superclass and all nested objects; never reuse warmed DNS classes. */
class DnsInitializationTest {
    @Test fun cloudflareFirst() = checkFirst("Cloudflare")
    @Test fun googleFirst() = checkFirst("Google")
    @Test fun quad9First() = checkFirst("Quad9")
    @Test fun adGuardFirst() = checkFirst("AdGuard")

    private fun checkFirst(preset: String) {
        val name = "dev.typenil.vpnclient.core.engine.DnsUpstream"
        val parent = javaClass.classLoader!!
        val loader = object : ClassLoader(parent) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name != "dev.typenil.vpnclient.core.engine.DnsUpstream" &&
                    !name.startsWith("dev.typenil.vpnclient.core.engine.DnsUpstream\$")) {
                    return super.loadClass(name, resolve)
                }
                synchronized(this) {
                    val type = findLoadedClass(name) ?: run {
                        val bytes = parent.getResourceAsStream(name.replace('.', '/') + ".class")!!
                            .use { it.readBytes() }
                        defineClass(name, bytes, 0, bytes.size)
                    }
                    if (resolve) resolveClass(type)
                    return type
                }
            }
        }
        // Initializing a subclass first triggers the original superclass cycle.
        Class.forName("$name\$$preset", true, loader).getField("INSTANCE").get(null)
        val type = Class.forName(name, true, loader)
        val companion = type.getField("Companion").get(null)
        val methods = companion.javaClass
        val presets = methods.getMethod("getPresets").invoke(companion) as List<*>
        assertEquals(4, presets.size)
        presets.forEach { assertNotNull("null preset after $preset first", it) }
        val cloudflare = Class.forName("$name\$Cloudflare", true, loader).getField("INSTANCE").get(null)
        assertSame(cloudflare, methods.getMethod("getDefault").invoke(companion))
        val fromKey = methods.getMethod("fromKey", String::class.java)
        assertSame(cloudflare, fromKey.invoke(companion, null))
        val key = type.getMethod("getKey")
        presets.forEach { assertSame(it, fromKey.invoke(companion, key.invoke(it))) }
    }
}
