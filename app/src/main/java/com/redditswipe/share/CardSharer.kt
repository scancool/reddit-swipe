package com.redditswipe.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import com.redditswipe.data.RedditPost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Shares a card as a picture (for Instagram and other apps that want an image) together with its headline
 * and link (for WhatsApp, X, Messages and the like), through Android's share sheet.
 */
object CardSharer {

    // 4:5, the tallest shape Instagram shows in the feed without cropping.
    private const val WIDTH = 1080
    private const val HEIGHT = 1350
    private const val PADDING = 96f

    // A fixed, calm palette so shared cards look the same whatever the phone's theme.
    private const val PAPER = 0xFFF6F1E7.toInt()
    private const val INK = 0xFF1F1B16.toInt()
    private const val BODY_INK = 0xFF4A433B.toInt()
    private const val ACCENT = 0xFF8A5A2B.toInt()
    private const val RULE = 0xFFDCD3C4.toInt()

    suspend fun share(context: Context, post: RedditPost) {
        val image = withContext(Dispatchers.IO) { writeImage(context, post) }
        val text = "${post.title}\n${post.url}"
        val send = Intent(Intent.ACTION_SEND)
            .setType("image/png")
            .putExtra(Intent.EXTRA_STREAM, image)
            .putExtra(Intent.EXTRA_TEXT, text)
            .putExtra(Intent.EXTRA_SUBJECT, post.title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // Also as ClipData, so the share sheet can preview the picture and the chosen app may read it.
        send.clipData = ClipData.newRawUri(null, image)
        context.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun writeImage(context: Context, post: RedditPost): Uri {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap), post)
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val file = File(dir, "calm-read-card.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    private fun draw(canvas: Canvas, post: RedditPost) {
        canvas.drawColor(PAPER)
        val width = (WIDTH - 2 * PADDING).toInt()
        var y = PADDING

        val label = textPaint(36f, ACCENT, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD))
        canvas.drawText(post.sourceLabel.uppercase(), PADDING, y + 36f, label)
        y += 36f + 48f

        val title = layout(post.title, textPaint(68f, INK, Typeface.create(Typeface.SERIF, Typeface.BOLD)), width, 6)
        y = drawLayout(canvas, title, y) + 40f

        // The footer takes the bottom; the story's opening fills whatever room the title leaves.
        val footerTop = HEIGHT - PADDING - 40f
        val bodyPaint = textPaint(40f, BODY_INK, Typeface.SERIF)
        val lineHeight = bodyPaint.fontSpacing * 1.15f
        val lines = ((footerTop - 40f - y) / lineHeight).toInt()
        if (post.body.isNotBlank() && lines > 0) {
            val excerpt = post.body.take(1500).replace(Regex("\n{2,}"), "\n")
            drawLayout(canvas, layout(excerpt, bodyPaint, width, lines), y)
        }

        canvas.drawRect(PADDING, footerTop, WIDTH - PADDING, footerTop + 3f, Paint().apply { color = RULE })
        val footer = textPaint(32f, BODY_INK, Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD))
        canvas.drawText("Calm Read", PADDING, HEIGHT - PADDING + 12f, footer)
        val source = textPaint(32f, BODY_INK, Typeface.SANS_SERIF).apply { textAlign = Paint.Align.RIGHT }
        canvas.drawText(shortLink(post), WIDTH - PADDING, HEIGHT - PADDING + 12f, source)
    }

    /** "bbc.co.uk" for an article, "reddit.com/r/tifu" for a post: where the full story lives. */
    private fun shortLink(post: RedditPost): String =
        if (post.isNews) Uri.parse(post.url).host?.removePrefix("www.").orEmpty()
        else "reddit.com/r/${post.subreddit}"

    private fun textPaint(size: Float, color: Int, typeface: Typeface) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        this.typeface = typeface
    }

    private fun layout(text: String, paint: TextPaint, width: Int, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.15f)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

    /** Draws [layout] with its top at [top] and returns where it ends. */
    private fun drawLayout(canvas: Canvas, layout: StaticLayout, top: Float): Float {
        canvas.save()
        canvas.translate(PADDING, top)
        layout.draw(canvas)
        canvas.restore()
        return top + layout.height
    }
}
