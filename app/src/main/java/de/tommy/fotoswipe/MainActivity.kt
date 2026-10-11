package de.tommy.fotoswipe

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Folder (album) picker. */
class MainActivity : BaseActivity() {

    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var trashBtn: Button
    private lateinit var permBtn: Button
    private var askedOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16f)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        content.addView(TextView(this).apply {
            text = "FotoSwipe"
            textSize = 28f
            setTextColor(COLOR_TEXT)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        status = TextView(this).apply {
            textSize = 14f
            setTextColor(COLOR_MUTED)
            setPadding(0, dp(4f), 0, dp(12f))
            text = "Ordner wählen – links wischen = löschen, rechts = behalten."
        }
        content.addView(status)

        permBtn = Button(this).apply {
            text = "Zugriff auf Fotos erlauben"
            visibility = View.GONE
            setOnClickListener { requestPerms() }
        }
        content.addView(permBtn)

        trashBtn = Button(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            background = rounded(COLOR_DELETE, dp(12f).toFloat())
            visibility = View.GONE
            setOnClickListener { trashPending() }
        }
        content.addView(trashBtn, LinearLayout.LayoutParams(-1, dp(52f)).apply { bottomMargin = dp(12f) })

        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)

        content.addView(TextView(this).apply {
            text = "Tipp: Ordner lange drücken, um den Fortschritt dort zurückzusetzen."
            textSize = 12f
            setTextColor(COLOR_MUTED)
            setPadding(0, dp(16f), 0, 0)
        })

        setupRoot(ScrollView(this).apply { addView(content) })
    }

    override fun onResume() {
        super.onResume()
        if (hasAccess()) {
            permBtn.visibility = if (hasFullAccess()) View.GONE else View.VISIBLE
            if (!hasFullAccess()) permBtn.text = "Zugriff auf ALLE Fotos erlauben"
            refresh()
        } else {
            permBtn.visibility = View.VISIBLE
            status.text = "FotoSwipe braucht Zugriff auf deine Fotos."
            if (!askedOnce) {
                askedOnce = true
                requestPerms()
            }
        }
    }

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun hasFullAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.READ_MEDIA_IMAGES)
        else granted(Manifest.permission.READ_EXTERNAL_STORAGE)

    private fun hasAccess(): Boolean =
        hasFullAccess() || (Build.VERSION.SDK_INT >= 34 &&
                granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))

    private fun requestPerms() {
        val perms = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        requestPermissions(perms, 1)
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(rc, perms, results)
        // onResume runs afterwards and refreshes the screen
    }

    override fun onTrashed() = refresh()

    private class Bucket(val id: Long, val name: String) {
        var total = 0
        var open = 0
    }

    private fun refresh() {
        Thread {
            val photos = Media.all(this)
            val kept = Store.kept(this)
            val pending = Store.pending(this)
            val existing = photos.map { it.id.toString() }.toHashSet()
            if (pending.retainAll(existing)) Store.savePending(this, pending)

            val all = Bucket(ALL_BUCKETS, "Alle Fotos")
            val buckets = LinkedHashMap<Long, Bucket>()
            for (p in photos) {
                val b = buckets.getOrPut(p.bucketId) { Bucket(p.bucketId, p.bucket) }
                val key = p.id.toString()
                val open = key !in kept && key !in pending
                b.total++; all.total++
                if (open) { b.open++; all.open++ }
            }
            val sorted = buckets.values.sortedByDescending { it.total }
            runOnUiThread { render(listOf(all) + sorted, pending.size) }
        }.start()
    }

    private fun render(buckets: List<Bucket>, pendingCount: Int) {
        if (pendingCount > 0) {
            trashBtn.visibility = View.VISIBLE
            trashBtn.text = "🗑  $pendingCount markierte Fotos löschen"
        } else trashBtn.visibility = View.GONE

        status.text = "Ordner wählen – links wischen = löschen, rechts = behalten."
        list.removeAllViews()
        for (b in buckets) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rounded(COLOR_SURFACE, dp(12f).toFloat())
                setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
                isClickable = true
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, SwipeActivity::class.java)
                        .putExtra("bucket", b.id).putExtra("name", b.name))
                }
                setOnLongClickListener { confirmReset(b); true }
            }
            row.addView(TextView(this).apply {
                text = b.name
                textSize = 17f
                setTextColor(COLOR_TEXT)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(TextView(this).apply {
                text = if (b.open == 0) "✓ fertig · ${b.total}" else "${b.open} offen · ${b.total}"
                textSize = 13f
                setTextColor(if (b.open == 0) COLOR_KEEP else COLOR_MUTED)
            })
            list.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8f) })
        }
    }

    private fun confirmReset(b: Bucket) {
        AlertDialog.Builder(this)
            .setTitle("Fortschritt zurücksetzen?")
            .setMessage("Alle als „behalten“ markierten Fotos in „${b.name}“ werden wieder angezeigt.")
            .setNegativeButton("Abbrechen", null)
            .setPositiveButton("Zurücksetzen") { _, _ ->
                Thread {
                    val ids = Media.all(this)
                        .filter { b.id == ALL_BUCKETS || it.bucketId == b.id }
                        .map { it.id.toString() }.toSet()
                    val kept = Store.kept(this)
                    kept.removeAll(ids)
                    Store.saveKept(this, kept)
                    runOnUiThread { refresh() }
                }.start()
            }
            .show()
    }
}
