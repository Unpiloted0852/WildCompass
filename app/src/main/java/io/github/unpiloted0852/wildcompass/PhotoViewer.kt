package io.github.unpiloted0852.wildcompass

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import coil.imageLoader
import coil.load
import coil.memory.MemoryCache
import coil.size.Precision
import coil.size.Scale

/**
 * Full-screen view of a record's photos: swipe between them, pinch or double-tap to zoom,
 * tap or press back to close.
 */
object PhotoViewer {

    /**
     * The full-screen view loads the original file, not a reduced rendition, so that zooming
     * in shows real detail. iNaturalist keeps originals up to 2048 px on the long side; other
     * publishers' originals can be far larger, and those are scaled to at most this many
     * pixels a side so that one photo cannot use up the phone's memory.
     */
    private const val MAX_PIXELS = 4096

    /** Where Coil keeps each card-sized photo, to show it at once while the original loads. */
    private val cardCopies = HashMap<String, MemoryCache.Key>()

    fun rememberCardCopy(url: String, key: MemoryCache.Key?) {
        if (key != null) cardCopies[url] = key
    }

    fun show(activity: Activity, photos: List<Photo>, title: String) {
        if (photos.isEmpty()) return
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val density = activity.resources.displayMetrics.density
        val pad = (12 * density).toInt()

        val caption = TextView(activity).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#99000000"))
            setPadding(pad, pad, pad, pad)
        }
        fun describe(position: Int) {
            caption.text = buildString {
                append(title)
                if (photos.size > 1) append("  ·  ${position + 1} of ${photos.size}")
                photos[position].credit?.let { append("\nPhoto $it") }
            }
        }

        val pager = ViewPager2(activity).apply {
            adapter = Pages(photos) { dialog.dismiss() }
            registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) = describe(position)
            })
        }
        describe(0)

        val root = FrameLayout(activity).apply {
            setBackgroundColor(Color.BLACK)
            addView(pager, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(caption, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        }
        // Keep the caption clear of the navigation bar.
        ViewCompat.setOnApplyWindowInsetsListener(caption) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(pad + bars.left, pad, pad + bars.right, pad + bars.bottom)
            insets
        }
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        // The viewer is a window of its own, so it has to ask to keep the screen on too.
        dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        dialog.show()
    }

    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private class Pages(
        private val photos: List<Photo>,
        private val onTap: () -> Unit,
    ) : RecyclerView.Adapter<Pages.Holder>() {

        class Holder(
            root: FrameLayout,
            val image: ZoomImageView,
            /** Shown in the middle while there is nothing to look at yet. */
            val spinner: ProgressBar,
            /** Shown at the top while a sharper copy replaces the one on screen. */
            val sharpening: View,
            val message: TextView,
        ) : RecyclerView.ViewHolder(root) {
            var photo: Photo? = null
        }

        override fun getItemCount() = photos.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val context = parent.context
            val density = context.resources.displayMetrics.density
            val image = ZoomImageView(context).apply {
                contentDescription = "Photo. Pinch or double-tap to zoom, tap to close."
                onTap = this@Pages.onTap
            }
            val spinner = ProgressBar(context)
            val small = (18 * density).toInt()
            val sharpening = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_badge)
                setPadding((12 * density).toInt(), (6 * density).toInt(), (14 * density).toInt(), (6 * density).toInt())
                addView(ProgressBar(context), LinearLayout.LayoutParams(small, small))
                addView(
                    TextView(context).apply {
                        setTextColor(Color.WHITE)
                        textSize = 13f
                        text = "Loading full resolution…"
                        setPadding((8 * density).toInt(), 0, 0, 0)
                    }
                )
            }
            val message = TextView(context).apply {
                setTextColor(Color.WHITE)
                textSize = 15f
                gravity = Gravity.CENTER
                text = "This photo could not be loaded."
            }
            val root = FrameLayout(context).apply {
                layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
                addView(image, FrameLayout.LayoutParams(MATCH, MATCH))
                addView(spinner, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
                addView(
                    sharpening,
                    FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                        topMargin = (56 * density).toInt()
                    }
                )
                addView(message, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
            }
            return Holder(root, image, spinner, sharpening, message)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val photo = photos[position]
            holder.photo = photo
            holder.image.setImageDrawable(null)
            holder.image.resetZoom()
            holder.message.visibility = View.GONE

            // The card's copy, if it is still in memory, goes up at once; either way a
            // spinner shows that the original is on its way.
            val cardCopy = cardCopies[photo.url]
                ?.takeIf { holder.image.context.imageLoader.memoryCache?.get(it) != null }
            holder.spinner.visibility = if (cardCopy == null) View.VISIBLE else View.GONE
            holder.sharpening.visibility = if (cardCopy == null) View.GONE else View.VISIBLE
            loadFirstThatWorks(holder, photo, cardCopy, 0)
        }

        private fun loadFirstThatWorks(holder: Holder, photo: Photo, cardCopy: MemoryCache.Key?, index: Int) {
            val urls = photo.fullUrls
            holder.image.load(urls[index]) {
                placeholderMemoryCacheKey(cardCopy)
                size(MAX_PIXELS)
                scale(Scale.FIT)
                // Never enlarge: a smaller original is shown with exactly the pixels it has.
                precision(Precision.INEXACT)
                // A zoomed picture is moved with a matrix, which hardware bitmaps handle too,
                // but very large ones can exceed the texture limit on older phones.
                allowHardware(false)
                listener(
                    onSuccess = { _, _ ->
                        if (holder.photo === photo) {
                            holder.spinner.visibility = View.GONE
                            holder.sharpening.visibility = View.GONE
                        }
                    },
                    onError = { _, _ ->
                        if (holder.photo === photo) {
                            if (index + 1 < urls.size) {
                                loadFirstThatWorks(holder, photo, cardCopy, index + 1)
                            } else {
                                holder.spinner.visibility = View.GONE
                                holder.sharpening.visibility = View.GONE
                                // With the card's copy on screen there is still a photo to look at.
                                if (cardCopy == null) holder.message.visibility = View.VISIBLE
                            }
                        }
                    }
                )
            }
        }
    }
}
