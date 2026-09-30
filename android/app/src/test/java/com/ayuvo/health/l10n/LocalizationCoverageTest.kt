package com.ayuvo.health.l10n

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Every translatable string resource must exist in every language of
 * `shared/l10n/l10n_config.json`, with the same format arguments as English
 * (docs/localization.md). `scripts/l10n/l10n_report.py --detail` lists what is missing.
 */
class LocalizationCoverageTest {
    private val res = File("src/main/res")
    private val config = Json.parseToJsonElement(File("../../shared/l10n/l10n_config.json").readText()).jsonObject
    private val languages = config.getValue("languages").jsonArray.map {
        it.jsonObject.getValue("ios").jsonPrimitive.content to it.jsonObject.getValue("android").jsonPrimitive.content
    }
    private val doNotTranslate = config.getValue("do_not_translate").jsonArray.map { it.jsonPrimitive.content }.toSet()
    private val plurals = config.getValue("plural_categories").jsonObject.mapValues { (_, v) ->
        v.jsonArray.map { it.jsonPrimitive.content }
    }
    private val placeholder = Regex("""%(?:(\d+)\$)?[-+#0,]*\d*(?:\.\d+)?(?:ll|l|h|q|z|t|j)?[@dDuUxXoOfeEgGcCsSaA]""")

    private sealed interface Value {
        data class Text(val text: String) : Value
        data class Plural(val forms: Map<String, String>) : Value
        data class Array(val items: List<String>) : Value
    }

    private fun load(folder: String): Map<String, Pair<Value, Boolean>> {
        val dir = File(res, folder)
        val result = mutableMapOf<String, Pair<Value, Boolean>>()
        val files = dir.listFiles { f -> f.name.endsWith(".xml") } ?: return result
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        for (file in files) {
            val root = builder.parse(file).documentElement
            val children = root.childNodes
            for (i in 0 until children.length) {
                val el = children.item(i) as? Element ?: continue
                val translatable = el.getAttribute("translatable") != "false"
                val value: Value = when (el.tagName) {
                    "string" -> Value.Text(el.textContent)
                    "plurals" -> Value.Plural(items(el).associate { it.getAttribute("quantity") to it.textContent })
                    "string-array" -> Value.Array(items(el).map { it.textContent })
                    else -> continue
                }
                result[el.getAttribute("name")] = value to translatable
            }
        }
        return result
    }

    private fun items(el: Element): List<Element> {
        val nodes = el.getElementsByTagName("item")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    /** Specifiers with positions removed; sorted when every argument is positional. */
    private fun signature(text: String): List<String> {
        // "%%" is an escaped percent sign, so "80%%-dən" is not "%-d".
        val matches = placeholder.findAll(text.replace("%%", "\u0000\u0000")).toList()
        val specs = matches.map { it.value.replace(Regex("""\d+\$"""), "") }
        return if (matches.isNotEmpty() && matches.all { it.groupValues[1].isNotEmpty() }) specs.sorted() else specs
    }

    private fun needsTranslation(value: Value): Boolean = when (value) {
        is Value.Text -> placeholder.replace(value.text, "").any(Char::isLetter) && value.text.trim() !in doNotTranslate
        is Value.Plural -> true
        is Value.Array -> value.items.any { it.any(Char::isLetter) }
    }

    @Test
    fun everyStringIsTranslatedInEveryLanguage() {
        val base = load("values").filter { (_, v) -> v.second && needsTranslation(v.first) }
        val problems = mutableListOf<String>()
        for ((ios, qualifier) in languages) {
            val translated = load("values-$qualifier")
            for ((name, pair) in base) {
                val english = pair.first
                val where = "values-$qualifier/$name"
                when (val value = translated[name]?.first) {
                    null -> problems += "missing: $where"
                    is Value.Text -> {
                        if (value.text.isBlank()) problems += "empty: $where"
                        // Strings without arguments are plain text: a "%" there is just a percent sign.
                        if (english is Value.Text && signature(english.text).isNotEmpty() &&
                            signature(value.text) != signature(english.text)
                        ) {
                            problems += "placeholders: $where"
                        }
                    }
                    is Value.Plural -> {
                        val need = plurals[ios] ?: listOf("other")
                        need.filter { it !in value.forms }.forEach { problems += "plural $it missing: $where" }
                    }
                    is Value.Array -> if (english is Value.Array && english.items.size != value.items.size) {
                        problems += "array length: $where"
                    }
                }
            }
        }
        assertTrue("${problems.size} localization problems, e.g. ${problems.sorted().take(20)}", problems.isEmpty())
    }

    @Test
    fun localeConfigListsEveryLanguage() {
        val xml = File(res, "xml/locales_config.xml").readText()
        for ((ios, _) in languages + ("en" to "values")) {
            assertTrue("$ios missing from locales_config.xml", xml.contains("android:name=\"$ios\""))
        }
    }
}
