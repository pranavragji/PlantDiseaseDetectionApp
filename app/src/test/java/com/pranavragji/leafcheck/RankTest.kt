package com.pranavragji.leafcheck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class RankTest {
    private val labels = listOf("apple__healthy", "none__no_leaf", "tomato__early_blight", "tomato__healthy")

    @Test
    fun ranksAndRestrictsToPickedPlant() {
        val probs = floatArrayOf(0.5f, 0.1f, 0.3f, 0.1f)
        assertEquals("apple__healthy", rank(probs, labels, null).first().label)

        // user said "tomato": apple drops out, the rest is rescaled to sum to 1
        val tomato = rank(probs, labels, "tomato")
        assertEquals(listOf("tomato__early_blight", "none__no_leaf", "tomato__healthy"), tomato.map { it.label })
        assertEquals(0.6f, tomato.first().confidence, 1e-5f)
        assertEquals(1f, tomato.sumOf { it.confidence.toDouble() }.toFloat(), 1e-5f)
    }

    // Every model label must have a name, explanation and care tips in every language.
    @Test
    fun everyLabelIsTranslated() {
        val res = File("src/main/res")
        val shipped = File("src/main/assets/labels.json").readText()
            .removeSurrounding("[", "]").split(",").map { it.trim().trim('"') }.filter { it.isNotEmpty() }
        for (dir in listOf("values", "values-hi", "values-es", "values-zh", "values-ar")) {
            val names = mutableSetOf<String>()
            for (file in listOf("strings.xml", "plants.xml")) {
                val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "$dir/$file"))
                val nodes = doc.getElementsByTagName("string")
                for (i in 0 until nodes.length) names += nodes.item(i).attributes.getNamedItem("name").nodeValue
            }
            for (label in shipped) {
                val c = conditionOf(label)
                for (key in listOf("crop_" + cropOf(label), "cond_$c", "about_$c", "care_$c"))
                    assertTrue("$dir is missing $key", key in names)
            }
        }
    }
}
