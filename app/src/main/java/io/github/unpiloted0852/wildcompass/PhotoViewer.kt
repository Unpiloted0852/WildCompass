package io.github.unpiloted0852.wildcompass

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
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
        dialog.show()
    }

    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    private class Pages(
        private val photos: List<Photo>,
        private val onTap: () -> Unit,
    ) : RecyclerView.Adapter<Pages.Holder>() {

        class Holder(val image: ZoomImageView) : RecyclerView.ViewHolder(image)

        override fun getItemCount() = photos.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val image = ZoomImageView(parent.context)
            image.layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            image.contentDescription = "Photo. Pinch or double-tap to zoom, tap to close."
            image.onTap = onTap
            return Holder(image)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            loadFirstThatWorks(holder.image, photos[position], 0)
        }

        private fun loadFirstThatWorks(image: ZoomImageView, photo: Photo, index: Int) {
            val urls = photo.fullUrls
            image.load(urls[index]) {
                placeholderMemoryCacheKey(cardCopies[photo.url])
                size(MAX_PIXELS)
                scale(Scale.FIT)
                // Never enlarge: a smaller original is shown with exactly the pixels it has.
                precision(Precision.INEXACT)
                // A zoomed picture is moved with a matrix, which hardware bitmaps handle too,
                // but very large ones can exceed the texture limit on older phones.
                allowHardware(false)
                listener(onError = { _, _ ->
                    if (index + 1 < urls.size) loadFirstThatWorks(image, photo, index + 1)
                })
            }
        }
    }
}
