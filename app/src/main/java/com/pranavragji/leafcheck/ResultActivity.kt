package com.pranavragji.leafcheck

import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.io.File
import kotlin.concurrent.thread

// Shows what LeafCheck thinks is wrong and what to do. Opened with a fresh photo
// (which gets saved to history) or with a saved scan from the dashboard.
class ResultActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)
        findViewById<View>(R.id.again).setOnClickListener { finish() }

        val time = intent.getLongExtra("time", 0)
        val saved = Scans.find(this, time)
        val crop = saved?.crop ?: intent.getStringExtra("crop").orEmpty()
        val photo = "scans/$time.jpg"
        val bitmap = BitmapFactory.decodeFile(File(filesDir, photo).path) ?: return finish()
        findViewById<ImageView>(R.id.photo).apply { setImageBitmap(bitmap); clipToOutline = true }

        thread {
            val guesses = Classifier.get(this).classify(bitmap, crop.ifEmpty { null })
            val best = guesses.first()
            if (saved == null) Scans.add(this, Scan(time, best.label, best.confidence, photo, crop))
            runOnUiThread { show(guesses) }
        }
    }

    private fun show(guesses: List<Guess>) {
        val best = guesses.first()
        val condition = conditionOf(best.label)
        val noLeaf = best.label == NO_LEAF
        val healthy = condition == "healthy"

        findViewById<TextView>(R.id.chip).apply {
            visibility = View.VISIBLE
            setText(if (noLeaf) R.string.unknown_title else if (healthy) R.string.healthy_title else R.string.care_title)
            background.mutate().setTint(getColor(if (healthy) R.color.green else R.color.care))
        }
        findViewById<TextView>(R.id.crop).text = if (noLeaf) "" else cropName(best.label)
        findViewById<TextView>(R.id.condition).text = conditionName(best.label)
        findViewById<View>(R.id.details).visibility = View.VISIBLE

        val pct = (best.confidence * 100).toInt()
        findViewById<ProgressBar>(R.id.bar).progress = pct
        findViewById<TextView>(R.id.confidence).text = getString(R.string.confidence, pct)
        // ponytail: one fixed threshold for every class; per-class calibration if users report noisy results
        findViewById<View>(R.id.warning).visibility = if (best.confidence < 0.6f && !noLeaf) View.VISIBLE else View.GONE

        findViewById<TextView>(R.id.about).text = text("about_$condition")
        val care = findViewById<LinearLayout>(R.id.care)
        for (line in text("care_$condition").split("\n")) care.addView(bullet("•  $line"))

        val others = findViewById<LinearLayout>(R.id.others)
        val rest = guesses.drop(1).take(3).filter { it.confidence >= 0.02f }
        others.visibility = if (rest.isEmpty()) View.GONE else View.VISIBLE
        for (g in rest) {
            val name = if (g.label == NO_LEAF) conditionName(g.label) else "${cropName(g.label)}: ${conditionName(g.label)}"
            others.addView(bullet(name + "  " + getString(R.string.confidence, (g.confidence * 100).toInt())))
        }
    }

    private fun bullet(s: String) = TextView(this).apply {
        text = s
        textSize = 15f
        setTextColor(getColor(R.color.ink))
        setLineSpacing(0f, 1.25f)
        setPadding(0, 6, 0, 6)
    }
}
