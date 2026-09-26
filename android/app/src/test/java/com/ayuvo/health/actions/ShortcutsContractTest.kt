package com.ayuvo.health.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * `res/xml/shortcuts.xml` (main, debug, debug2) and the manifest wiring for actions: every static
 * shortcut and App Actions capability must land on a real catalog action (docs/actions.md).
 */
class ShortcutsContractTest {
    private val catalog = ActionsTestFiles.catalog
    private val validator = ActionValidator(catalog)
    private val android = "http://schemas.android.com/apk/res/android"

    private fun file(rel: String): File =
        listOf("src/$rel", "app/src/$rel").map(::File).first { it.exists() }

    private fun parse(f: File): Element {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(f).documentElement
    }

    private fun Element.children(tag: String): List<Element> {
        val nodes = getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }.filter { it.parentNode == this }
    }

    private fun Element.a(name: String): String = getAttributeNS(android, name)

    @Test
    fun buildTypeCopiesDifferOnlyInPackage() {
        val main = file("main/res/xml/shortcuts.xml").readText()
        assertEquals(main.replace("\"com.ayuvo.health\"", "\"com.ayuvo.health.debug\""), file("debug/res/xml/shortcuts.xml").readText())
        assertEquals(main.replace("\"com.ayuvo.health\"", "\"com.ayuvo.health.debug2\""), file("debug2/res/xml/shortcuts.xml").readText())
    }

    @Test
    fun staticShortcutsMapToCatalogActions() {
        val root = parse(file("main/res/xml/shortcuts.xml"))
        val enabled = root.children("shortcut").filter { it.a("enabled") == "true" }
        assertEquals(listOf("log_water", "start_fast", "today_summary", "log_weight"), enabled.map { it.a("shortcutId") })
        for (s in enabled) {
            val intent = s.children("intent").single()
            assertEquals(ActionIntents.ACTION, intent.a("action"))
            assertEquals("com.ayuvo.health.MainActivity", intent.a("targetClass"))
            val extras = intent.children("extra").associate { it.a("name") to it.a("value") }
            val parsed = ActionIntents.parse(ActionIntents.ACTION, null, extras) as ActionIntents.Parsed.Request
            val v = validator.validate(parsed.request.id, parsed.request.params, ActionSource.ANDROID, mapOf("volume_unit" to "ml", "mass_unit" to "kg"))
            // "Log water" / "Log weight" carry no value on purpose: they open the in-app logger.
            val ok = v is ValidationResult.Ok ||
                (v is ValidationResult.Failed && v.code == ActionErrorCode.MISSING_PARAM && parsed.request.id in setOf("water.log", "weight.log"))
            assertTrue("${s.a("shortcutId")}: $v", ok)
        }
    }

    @Test
    fun capabilitiesMapToAssistantIntents() {
        val root = parse(file("main/res/xml/shortcuts.xml"))
        val caps = root.children("capability")
        val names = caps.map { it.a("name").removePrefix("actions.intent.").lowercase() }
        assertEquals(AssistantIntents.ALL.sorted(), names.sorted())
        for (c in caps) {
            val intent = c.children("intent").single()
            assertEquals("ayuvo://assistant/" + c.a("name").removePrefix("actions.intent.").lowercase(), intent.a("data"))
            assertEquals("com.ayuvo.health.MainActivity", intent.a("targetClass"))
        }
        val features = root.children("shortcut").filter { it.a("enabled") == "false" }.map { s ->
            s.children("capability-binding").single().children("parameter-binding").single().a("value")
        }
        assertEquals(catalog.enums.getValue("section").filter { it != "browse" }.sorted(), features.sorted())
        for (f in features) {
            val req = AssistantIntents.map(AssistantIntents.OPEN_APP_FEATURE, mapOf("feature" to f))!!
            assertTrue(f, validator.validate(req.id, req.params, ActionSource.ANDROID) is ValidationResult.Ok)
        }
    }

    @Test
    fun manifestRoutesActionLinksAndPublishesShortcuts() {
        val manifest = file("main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("<data android:scheme=\"ayuvo\" />"))
        for (host in listOf("action", "open", "assistant")) assertTrue(host, manifest.contains("<data android:host=\"$host\" />"))
        val aliases = Regex("<activity-alias(.*?)</activity-alias>", RegexOption.DOT_MATCHES_ALL).findAll(manifest).map { it.groupValues[1] }
            .filter { it.contains("android.intent.category.LAUNCHER") }.toList()
        assertTrue(aliases.isNotEmpty())
        assertTrue(aliases.all { it.contains("android:name=\"android.app.shortcuts\"") && it.contains("@xml/shortcuts") })
    }
}
