package com.pranavragji.leafcheck

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONArray
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread

// Home screen: pick or take a photo, plus the "Your garden" dashboard built from scan history.
class MainActivity : BaseActivity() {
    private lateinit var crops: List<String>
    private val prefs by lazy { getSharedPreferences("prefs", 0) }
    private val cameraFile by lazy { File(cacheDir, "camera.jpg") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val labels = JSONArray(assets.open("labels.json").reader().readText()).let { a -> List(a.length()) { a.getString(it) } }
        crops = labels.map(::cropOf).distinct().filter { it != "none" }.sortedBy { text("crop_$it") }

        val lang = findViewById<TextView>(R.id.lang)
        lang.text = Lang.names[Lang.tags.indexOf(Lang.current(this)).coerceAtLeast(0)]
        lang.setOnClickListener { pickLanguage() }

        val plant = findViewById<Spinner>(R.id.plant)
        plant.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf(getString(R.string.plant_auto)) + crops.map { text("crop_$it") })
        plant.setSelection(crops.indexOf(prefs.getString("crop", "")) + 1)
        plant.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.edit().putString("crop", if (pos == 0) "" else crops[pos - 1]).apply()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        findViewById<View>(R.id.camera).setOnClickListener { takePhoto() }
        findViewById<View>(R.id.gallery).setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), PICK)
        }
        findViewById<TextView>(R.id.supported).apply {
            text = getString(R.string.supported, crops.size)
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle(R.string.supported_title)
                    .setMessage(crops.joinToString("\n") { text("crop_$it") })
                    .setPositiveButton(R.string.ok, null).show()
            }
        }
        findViewById<View>(R.id.clear).setOnClickListener {
            AlertDialog.Builder(this).setMessage(R.string.clear_confirm)
                .setPositiveButton(R.string.delete) { _, _ -> Scans.clear(this); render() }
                .setNegativeButton(R.string.cancel, null).show()
        }

        // a photo shared from another app (gallery, files, WhatsApp...)
        if (savedInstanceState == null && intent.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let(::open)
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun pickLanguage() {
        val current = Lang.tags.indexOf(Lang.current(this))
        AlertDialog.Builder(this).setTitle(R.string.language)
            .setSingleChoiceItems(Lang.names.toTypedArray(), current) { d, i ->
                d.dismiss()
                if (i != current) {
                    Lang.set(this, Lang.tags[i])
                    recreate()
                }
            }.show()
    }

    private fun takePhoto() {
        val uri = FileProvider.getUriForFile(this, "$packageName.files", cameraFile)
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri)
            .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        try {
            startActivityForResult(i, CAMERA)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_camera, Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("plain Activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            CAMERA -> open(Uri.fromFile(cameraFile))
            PICK -> data?.data?.let(::open)
        }
    }

    // Saves a 512px square copy for history, then shows the result screen.
    private fun open(uri: Uri) = thread {
        val time = System.currentTimeMillis()
        val ok = runCatching {
            val square = loadSquare(this, uri, 512) ?: error("unreadable")
            val f = File(filesDir, "scans/$time.jpg").apply { parentFile?.mkdirs() }
            f.outputStream().use { square.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
        }.isSuccess
        runOnUiThread {
            if (ok) startActivity(Intent(this, ResultActivity::class.java)
                .putExtra("time", time).putExtra("crop", prefs.getString("crop", "")))
            else Toast.makeText(this, R.string.error_image, Toast.LENGTH_LONG).show()
        }
    }

    private fun render() {
        val scans = Scans.all(this).filter { it.label != NO_LEAF }
        val sick = scans.filter { !it.healthy }
        findViewById<TextView>(R.id.stat_scans).text = "%d".format(scans.size)
        findViewById<TextView>(R.id.stat_healthy).text = "%d".format(scans.size - sick.size)
        findViewById<TextView>(R.id.stat_care).text = "%d".format(sick.size)
        findViewById<View>(R.id.empty).visibility = if (scans.isEmpty()) View.VISIBLE else View.GONE
        findViewById<View>(R.id.dashboard).visibility = if (scans.isEmpty()) View.GONE else View.VISIBLE

        val top = findViewById<TextView>(R.id.top_issue)
        val worst = sick.groupingBy { it.label }.eachCount().maxByOrNull { it.value }?.key
        top.visibility = if (worst == null) View.GONE else View.VISIBLE
        if (worst != null) top.text = getString(R.string.top_issue, "${cropName(worst)}: ${conditionName(worst)}")

        val byPlant = findViewById<LinearLayout>(R.id.by_plant)
        byPlant.removeViews(1, byPlant.childCount - 1)
        for ((crop, list) in scans.groupBy { cropOf(it.label) }.entries.sortedByDescending { it.value.size }) {
            val row = layoutInflater.inflate(R.layout.item_scan, byPlant, false)
            row.findViewById<View>(R.id.thumb).visibility = View.GONE
            row.findViewById<TextView>(R.id.title).text = text("crop_$crop")
            row.findViewById<TextView>(R.id.subtitle).text =
                getString(R.string.plant_row, list.size, list.count { !it.healthy })
            dot(row, list.all { it.healthy })
            byPlant.addView(row)
        }

        val recent = findViewById<LinearLayout>(R.id.recent)
        recent.removeViews(1, recent.childCount - 1)
        val when_ = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        for (s in Scans.all(this).take(10)) {
            val row = layoutInflater.inflate(R.layout.item_scan, recent, false)
            val thumb = BitmapFactory.decodeFile(File(filesDir, s.photo).path, BitmapFactory.Options().apply { inSampleSize = 4 })
            row.findViewById<ImageView>(R.id.thumb).apply { setImageBitmap(thumb); clipToOutline = true }
            row.findViewById<TextView>(R.id.title).text =
                if (s.label == NO_LEAF) conditionName(s.label) else "${cropName(s.label)}: ${conditionName(s.label)}"
            row.findViewById<TextView>(R.id.subtitle).text =
                when_.format(Date(s.time)) + "  ·  " + getString(R.string.confidence, (s.confidence * 100).toInt())
            dot(row, s.healthy)
            row.setOnClickListener { startActivity(Intent(this, ResultActivity::class.java).putExtra("time", s.time)) }
            recent.addView(row)
        }
    }

    private fun dot(row: View, healthy: Boolean) {
        row.findViewById<View>(R.id.dot).background.mutate().setTint(getColor(if (healthy) R.color.green else R.color.care))
    }

    companion object {
        const val CAMERA = 1
        const val PICK = 2
    }
}
