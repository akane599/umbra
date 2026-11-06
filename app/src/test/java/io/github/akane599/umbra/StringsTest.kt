package io.github.akane599.umbra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** Checks on the string resources themselves (unit tests run with the module directory as working directory). */
class StringsTest {

    private fun strings(dir: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/$dir/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) }
            .filter { it.attributes.getNamedItem("translatable")?.nodeValue != "false" }
            .associate { it.attributes.getNamedItem("name").nodeValue to it.textContent }
    }

    @Test
    fun everyStringIsTranslated() {
        val en = strings("values")
        val tr = strings("values-tr")
        assertEquals("missing in values-tr", emptySet<String>(), en.keys - tr.keys)
        assertEquals("missing in values", emptySet<String>(), tr.keys - en.keys)
    }

    /** The "Commands from other apps" text must name every action that is still accepted when it is off. */
    @Test
    fun externalOffTextNamesEverySafeAction() {
        for (dir in listOf("values", "values-tr")) {
            val text = strings(dir).getValue("pref_external_off")
            for (action in PanelService.SAFE_ACTIONS) {
                val name = action.substringAfterLast('.')
                assertTrue("$dir/pref_external_off doesn't mention $name: $text", name in text)
            }
        }
    }
}
