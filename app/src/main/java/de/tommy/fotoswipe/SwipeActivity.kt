package de.tommy.fotoswipe

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min

class SwipeActivity : BaseActivity() {

    private var queue: List<Long> = emptyList()
    private var idx = 0
    private lateinit var kept: MutableSet<String>
    private lateinit var pending: MutableSet<String>
    private val history = ArrayDeque<Pair<Long, Boolean>>() // id to wasDelete

    private val cache = HashMap<Long, Bitmap?>()
    private val loading = HashSet<Long>()
    private val pool = Executors.newFixedThreadPool(2)
    private var maxDim = 1440
    private var animating = false

    private lateinit var progress: TextView
    private lateinit var trashBtn: Button
    private lateinit var area: FrameLayout
    private lateinit var front: Card
    private lateinit var back: Card
    private lateinit var done: LinearLayout
    private lateinit var doneText: TextView
    private lateinit var buttons: LinearLayout

    /** A photo card with "BEHALTEN" / "LÖSCHEN" stamps. */
    private inner class Card {
        val root = FrameLayout(this@SwipeActivity)
        val image = ImageView(this@SwipeActivity)
        val keepLabel = stamp("BEHALTEN", COLOR_KEEP)
        val delLabel = stamp("LÖSCHEN", COLOR_DELETE)
        val hint = TextView(this@SwipeActivity).apply {
            setTextColor(COLOR_MUTED); textSize = 14f; gravity = Gravity.CENTER
        }

        init {
            root.background = rounded(COLOR_SURFACE, dp(20f).toFloat())
            root.clipToOutline = true
            root.elevation = dp(6f).toFloat()
            image.scaleType = ImageView.ScaleType.FIT_CENTER
            root.addView(hint, FrameLayout.LayoutParams(-1, -1))
            root.addView(image, FrameLayout.LayoutParams(-1, -1))
            val m = dp(20f)
            root.addView(keepLabel, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { setMargins(m, m, m, m) })
            root.addView(delLabel, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(m, m, m, m) })
            keepLabel.rotation = -12f
            delLabel.rotation = 12f
        }

        fun show(id: Long?) {
            if (id == null) { root.visibility = View.INVISIBLE; return }
            root.visibility = View.VISIBLE
            val bmp = cache[id]
            image.setImageBitmap(bmp)
            hint.text = when {
                bmp != null -> ""
                id in cache -> "Foto kann nicht geladen werden"
                else -> "Lädt …"
            }
        }

        fun reset() {
            root.translationX = 0f; root.translationY = 0f; root.rotation = 0f
            root.scaleX = 1f; root.scaleY = 1f
            keepLabel.alpha = 0f; delLabel.alpha = 0f
        }

        fun labels(dx: Float) {
            val t = min(1f, abs(dx) / (area.width * 0.28f).coerceAtLeast(1f))
            keepLabel.alpha = if (dx > 0) t else 0f
            delLabel.alpha = if (dx < 0) t else 0f
        }
    }

    private fun stamp(text: String, color: Int) = TextView(this).apply {
        this.text = text
        textSize = 26f
        setTextColor(color)
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(dp(12f), dp(4f), dp(12f), dp(4f))
        background = android.graphics.drawable.GradientDrawable().apply {
            setStroke(dp(3f), color); cornerRadius = dp(8f).toFloat(); setColor(0x66000000)
        }
        alpha = 0f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        maxDim = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
            .coerceIn(800, 2000)
        val bucket = intent.getLongExtra("bucket", ALL_BUCKETS)
        val name = intent.getStringExtra("name") ?: "Fotos"

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // Top bar
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(8f), dp(12f), dp(8f))
        }
        val titleBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(TextView(this).apply {
            text = name; textSize = 18f; setTextColor(COLOR_TEXT); maxLines = 1
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        progress = TextView(this).apply { textSize = 13f; setTextColor(COLOR_MUTED) }
        titleBox.addView(progress)
        top.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))
        trashBtn = Button(this).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            isAllCaps = false
            background = rounded(COLOR_DELETE, dp(10f).toFloat())
            setPadding(dp(14f), 0, dp(14f), 0)
            setOnClickListener { trashPending() }
        }
        top.addView(trashBtn, LinearLayout.LayoutParams(-2, dp(40f)))
        root.addView(top)

        // Card area
        area = FrameLayout(this).apply { setPadding(dp(16f), dp(8f), dp(16f), dp(8f)); clipChildren = false }
        back = Card()
        front = Card()
        area.addView(back.root, FrameLayout.LayoutParams(-1, -1))
        area.addView(front.root, FrameLayout.LayoutParams(-1, -1))
        back.root.scaleX = 0.94f; back.root.scaleY = 0.94f

        done = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        doneText = TextView(this).apply {
            textSize = 18f; setTextColor(COLOR_TEXT); gravity = Gravity.CENTER
            setPadding(dp(24f), 0, dp(24f), dp(20f))
        }
        done.addView(doneText)
        done.addView(Button(this).apply {
            text = "Zurück zur Ordnerliste"
            setOnClickListener { finish() }
        })
        area.addView(done, FrameLayout.LayoutParams(-1, -1))
        root.addView(area, LinearLayout.LayoutParams(-1, 0, 1f))

        // Bottom buttons
        buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16f), dp(8f), dp(16f), dp(16f))
        }
        buttons.addView(roundButton("✕", COLOR_DELETE, 64f) { decide(true) })
        buttons.addView(roundButton("↶", COLOR_MUTED, 48f) { undo() })
        buttons.addView(roundButton("♥", COLOR_KEEP, 64f) { decide(false) })
        root.addView(buttons)

        setupRoot(root)
        setupTouch()

        kept = Store.kept(this)
        pending = Store.pending(this)
        progress.text = "Lädt …"
        Thread {
            val list = Media.all(this)
                .filter { bucket == ALL_BUCKETS || it.bucketId == bucket }
                .map { it.id }
                .filter { it.toString() !in kept && it.toString() !in pending }
            runOnUiThread { queue = list; idx = 0; bind() }
        }.start()
    }

    private fun roundButton(label: String, color: Int, sizeDp: Float, onClick: () -> Unit) =
        TextView(this).apply {
            text = label
            textSize = sizeDp / 2.6f
            gravity = Gravity.CENTER
            setTextColor(color)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(COLOR_SURFACE)
                setStroke(dp(2f), color)
            }
            isClickable = true
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply {
                setMargins(dp(18f), 0, dp(18f), 0)
            }
        }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch() {
        var downX = 0f
        var downY = 0f
        front.root.setOnTouchListener { v, e ->
            if (animating || idx >= queue.size) return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; true }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    v.translationX = dx
                    v.translationY = dy * 0.25f
                    v.rotation = dx / 22f
                    front.labels(dx)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = e.rawX - downX
                    if (e.actionMasked == MotionEvent.ACTION_UP && abs(dx) > area.width * 0.28f) {
                        decide(dx < 0)
                    } else {
                        v.animate().translationX(0f).translationY(0f).rotation(0f)
                            .setDuration(180).start()
                        front.labels(0f)
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun decide(delete: Boolean) {
        if (animating || idx >= queue.size) return
        animating = true
        front.root.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        val dir = if (delete) -1f else 1f
        front.labels(dir * area.width)
        front.root.animate()
            .translationX(dir * area.width * 1.5f)
            .rotation(dir * 25f)
            .setDuration(220)
            .withEndAction {
                val id = queue[idx]
                val key = id.toString()
                if (delete) { pending.add(key); Store.savePending(this, pending) }
                else { kept.add(key); Store.saveKept(this, kept) }
                history.addLast(id to delete)
                if (history.size > 200) history.removeFirst()
                idx++
                animating = false
                bind()
            }
            .start()
    }

    private fun undo() {
        if (animating) return
        val last = history.removeLastOrNull() ?: run { toast("Nichts zum Rückgängigmachen"); return }
        val key = last.first.toString()
        if (last.second) { pending.remove(key); Store.savePending(this, pending) }
        else { kept.remove(key); Store.saveKept(this, kept) }
        idx--
        bind()
        // slide the card back in from the side it left
        val dir = if (last.second) -1f else 1f
        front.root.translationX = dir * area.width
        front.root.rotation = dir * 15f
        front.root.animate().translationX(0f).rotation(0f).setDuration(200).start()
    }

    override fun onTrashed() {
        pending = Store.pending(this)
        history.clear() // trashed photos can't be un-marked any more
        bind()
    }

    private fun bind() {
        front.reset()
        val cur = queue.getOrNull(idx)
        val next = queue.getOrNull(idx + 1)
        front.show(cur)
        back.show(next)
        back.reset(); back.root.scaleX = 0.94f; back.root.scaleY = 0.94f

        val finished = idx >= queue.size
        done.visibility = if (finished) View.VISIBLE else View.GONE
        buttons.alpha = if (finished) 0.3f else 1f
        progress.text = if (queue.isEmpty()) "Keine offenen Fotos"
        else "${min(idx + 1, queue.size)} / ${queue.size}"

        trashBtn.text = "🗑 ${pending.size}"
        trashBtn.visibility = if (pending.isEmpty()) View.GONE else View.VISIBLE

        if (finished) {
            doneText.text = if (pending.isEmpty()) "Alles gesichtet! 🎉"
            else "Alles gesichtet! 🎉\n\n${pending.size} Fotos sind zum Löschen markiert.\nTippe oben auf 🗑, um sie in den Papierkorb zu verschieben."
        }

        // prefetch and evict
        for (i in idx..idx + 3) queue.getOrNull(i)?.let { load(it) }
        val keep = (idx - 1..idx + 3).mapNotNull { queue.getOrNull(it) }.toSet()
        cache.keys.retainAll(keep + loading)
    }

    private fun load(id: Long) {
        if (id in cache || id in loading) return
        loading.add(id)
        pool.execute {
            val bmp = Media.load(this, id, maxDim)
            runOnUiThread {
                loading.remove(id)
                cache[id] = bmp
                if (queue.getOrNull(idx) == id) front.show(id)
                if (queue.getOrNull(idx + 1) == id) back.show(id)
            }
        }
    }

    override fun onDestroy() {
        pool.shutdownNow()
        super.onDestroy()
    }
}
