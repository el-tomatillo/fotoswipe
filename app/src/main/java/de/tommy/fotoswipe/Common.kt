package de.tommy.fotoswipe

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.MediaStore
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets
import android.widget.Toast
import kotlin.math.max

const val ALL_BUCKETS = Long.MIN_VALUE

const val COLOR_BG = 0xFF121218.toInt()
const val COLOR_SURFACE = 0xFF1E1E2A.toInt()
const val COLOR_TEXT = 0xFFEDEDF2.toInt()
const val COLOR_MUTED = 0xFF9A9AAE.toInt()
const val COLOR_DELETE = 0xFFE5484D.toInt()
const val COLOR_KEEP = 0xFF30A46C.toInt()

data class Photo(val id: Long, val bucketId: Long, val bucket: String)

/** Persists swipe decisions so you can stop any time and continue later. */
object Store {
    private fun prefs(c: Context) = c.getSharedPreferences("fotoswipe", Context.MODE_PRIVATE)

    fun kept(c: Context): MutableSet<String> =
        HashSet(prefs(c).getStringSet("kept", emptySet()) ?: emptySet())

    fun pending(c: Context): MutableSet<String> =
        HashSet(prefs(c).getStringSet("pending", emptySet()) ?: emptySet())

    fun saveKept(c: Context, s: Set<String>) =
        prefs(c).edit().putStringSet("kept", HashSet(s)).apply()

    fun savePending(c: Context, s: Set<String>) =
        prefs(c).edit().putStringSet("pending", HashSet(s)).apply()
}

object Media {
    fun uri(id: Long): Uri =
        ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

    /** All images, newest first. Trashed images are excluded by MediaStore automatically. */
    fun all(c: Context): List<Photo> {
        val out = ArrayList<Photo>()
        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )
        c.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj, null, null,
            MediaStore.Images.Media.DATE_TAKEN + " DESC, " + MediaStore.Images.Media.DATE_ADDED + " DESC"
        )?.use { cur ->
            val iId = cur.getColumnIndexOrThrow(proj[0])
            val iB = cur.getColumnIndexOrThrow(proj[1])
            val iN = cur.getColumnIndexOrThrow(proj[2])
            while (cur.moveToNext()) {
                out.add(Photo(cur.getLong(iId), cur.getLong(iB), cur.getString(iN) ?: "Ohne Ordner"))
            }
        }
        return out
    }

    /** Decodes a downscaled bitmap; ImageDecoder applies EXIF rotation automatically. */
    fun load(c: Context, id: Long, maxDim: Int): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(c.contentResolver, uri(id))) { dec, info, _ ->
            val longest = max(info.size.width, info.size.height)
            var sample = 1
            while (longest / (sample * 2) >= maxDim) sample *= 2
            dec.setTargetSampleSize(sample)
        }
    } catch (e: Exception) {
        null
    }
}

fun Context.dp(v: Float): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()

fun rounded(color: Int, radius: Float): GradientDrawable =
    GradientDrawable().apply { setColor(color); cornerRadius = radius }

fun Context.toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

/** Base activity: dark window, edge-to-edge insets and the "move marked photos to trash" flow. */
open class BaseActivity : Activity() {
    private var lastBatch: List<String> = emptyList()

    fun setupRoot(root: View) {
        window.decorView.setBackgroundColor(COLOR_BG)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        root.setBackgroundColor(COLOR_BG)
        root.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        setContentView(root)
    }

    /** Asks Android (system dialog) to move all marked photos to the trash. */
    fun trashPending() {
        Thread {
            val existing = Media.all(this).map { it.id.toString() }.toHashSet()
            val pending = Store.pending(this)
            pending.retainAll(existing)
            Store.savePending(this, pending)
            runOnUiThread {
                if (pending.isEmpty()) {
                    toast("Keine Fotos zum Löschen markiert")
                    onTrashed()
                    return@runOnUiThread
                }
                val batch = pending.take(500)
                lastBatch = batch
                try {
                    val pi = MediaStore.createTrashRequest(
                        contentResolver, batch.map { Media.uri(it.toLong()) }, true
                    )
                    @Suppress("DEPRECATION")
                    startIntentSenderForResult(pi.intentSender, REQ_TRASH, null, 0, 0, 0)
                } catch (e: Exception) {
                    toast("Löschen fehlgeschlagen: ${e.message}")
                }
            }
        }.start()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_TRASH) return
        if (resultCode == RESULT_OK) {
            val pending = Store.pending(this)
            pending.removeAll(lastBatch.toSet())
            Store.savePending(this, pending)
            val rest = if (pending.isEmpty()) "" else " – noch ${pending.size} übrig, bitte erneut tippen"
            toast("${lastBatch.size} Fotos in den Papierkorb verschoben$rest")
        } else {
            toast("Abgebrochen – Markierungen bleiben erhalten")
        }
        lastBatch = emptyList()
        onTrashed()
    }

    open fun onTrashed() {}

    companion object {
        const val REQ_TRASH = 4711
    }
}
