package com.example.faceframe.collage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.example.faceframe.model.Person
import kotlin.math.ceil
import kotlin.math.max

/**
 * Draws the list of people as a poster worth sharing.
 *
 * 1080x1920, the Instagram Story shape the assignment points at and the right
 * one for a phone. No Context anywhere in here, just bitmaps and a Canvas, so
 * it can be tested without a device and has no idea where the file ends up.
 * That is MediaSaver's problem.
 */
object CollageRenderer {

    private const val WIDTH = 1080
    private const val HEIGHT = 1920

    private const val MARGIN = 56f
    private const val GAP = 22f
    private const val TILE_RADIUS = 34f
    private const val HEADER_HEIGHT = 250f
    private const val FOOTER_HEIGHT = 92f

    private const val BACKGROUND_TOP = 0xFF1B1231.toInt()
    private const val BACKGROUND_BOTTOM = 0xFF0A0A11.toInt()
    private const val ACCENT = 0xFF9B7BFF.toInt()
    private const val MUTED = 0xFF9A94AD.toInt()
    private const val TILE_EMPTY = 0xFF241F33.toInt()

    fun render(people: List<Person>): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        drawBackground(canvas)
        drawHeader(canvas, people)
        drawTiles(canvas, people)
        drawFooter(canvas)

        return bitmap
    }

    /**
     * Development aid: one tile per tracklet, labelled with its timestamps.
     *
     * The point is being able to see which tracklets are really the same
     * person, instead of inferring it from cluster counts and hoping.
     */
    fun renderDebugSheet(tiles: List<Pair<Bitmap?, String>>): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFF101018.toInt())

        val header = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 46f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        canvas.drawText("DEBUG - ${tiles.size} tracklets", MARGIN, 74f, header)

        if (tiles.isEmpty()) return bitmap

        val columns = 5
        val rows = ceil(tiles.size / columns.toFloat()).toInt()
        val top = 110f
        val areaWidth = WIDTH - 2 * MARGIN
        val areaHeight = HEIGHT - top - 40f
        val tileWidth = (areaWidth - GAP * (columns - 1)) / columns
        val tileHeight = (areaHeight - GAP * (rows - 1)) / rows

        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = minOf(24f, tileWidth * 0.16f)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }

        tiles.forEachIndexed { index, (shot, caption) ->
            val left = MARGIN + (index % columns) * (tileWidth + GAP)
            val tileTop = top + (index / columns) * (tileHeight + GAP)
            val rect = RectF(left, tileTop, left + tileWidth, tileTop + tileHeight)

            canvas.save()
            canvas.clipRect(rect)
            if (shot != null && !shot.isRecycled) drawCover(canvas, shot, rect)
            else canvas.drawColor(TILE_EMPTY)
            canvas.drawRect(
                rect.left, rect.bottom - label.textSize * 1.7f, rect.right, rect.bottom,
                Paint().apply { color = 0xCC000000.toInt() }
            )
            canvas.restore()

            canvas.drawText("#${index + 1} $caption", rect.left + 6f, rect.bottom - 8f, label)
        }
        return bitmap
    }

    private fun drawBackground(canvas: Canvas) {
        val base = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, HEIGHT.toFloat(),
                BACKGROUND_TOP, BACKGROUND_BOTTOM, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), base)

        // A soft glow at the top; a flat gradient looks dead by comparison.
        val glow = Paint().apply {
            shader = RadialGradient(
                WIDTH * 0.5f, 0f, WIDTH * 0.9f,
                0x40A98BFF, Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT * 0.5f, glow)
    }

    private fun drawHeader(canvas: Canvas, people: List<Person>) {
        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 78f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = -0.02f
        }
        canvas.drawText("People in this video", MARGIN, 118f, title)

        val appearances = people.sumOf { it.appearanceCount }
        val subtitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ACCENT
            textSize = 38f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        canvas.drawText(
            "${people.size} unique  ·  $appearances appearances",
            MARGIN, 178f, subtitle
        )

        val rule = Paint().apply { color = 0x22FFFFFF }
        canvas.drawRect(MARGIN, 208f, WIDTH - MARGIN, 210f, rule)
    }

    private fun drawTiles(canvas: Canvas, people: List<Person>) {
        if (people.isEmpty()) return

        // The grid follows the headcount. Two columns suits five people; a
        // fixed grid looks wrong the moment a video has a different number.
        val columns = when {
            people.size == 1 -> 1
            people.size <= 6 -> 2
            else -> 3
        }
        val rows = ceil(people.size / columns.toFloat()).toInt()

        val top = HEADER_HEIGHT
        val bottom = HEIGHT - FOOTER_HEIGHT
        val areaWidth = WIDTH - 2 * MARGIN
        val areaHeight = bottom - top

        val tileWidth = (areaWidth - GAP * (columns - 1)) / columns
        val tileHeight = (areaHeight - GAP * (rows - 1)) / rows

        people.forEachIndexed { index, person ->
            val row = index / columns
            val column = index % columns

            // Centre a short last row, otherwise the poster leans left.
            val inThisRow = minOf(columns, people.size - row * columns)
            val rowWidth = inThisRow * tileWidth + (inThisRow - 1) * GAP
            val rowLeft = (WIDTH - rowWidth) / 2f

            val left = rowLeft + column * (tileWidth + GAP)
            val tileTop = top + row * (tileHeight + GAP)

            drawTile(canvas, person, RectF(left, tileTop, left + tileWidth, tileTop + tileHeight))
        }
    }

    private fun drawTile(canvas: Canvas, person: Person, rect: RectF) {
        val shape = Path().apply {
            addRoundRect(rect, TILE_RADIUS, TILE_RADIUS, Path.Direction.CW)
        }

        canvas.save()
        canvas.clipPath(shape)

        val shot = person.representativeShot
        if (shot != null && !shot.isRecycled) {
            drawCover(canvas, shot, rect)
        } else {
            canvas.drawColor(TILE_EMPTY)
        }

        // Dark fade at the bottom, or the label is unreadable over the photo.
        val scrimTop = rect.bottom - rect.height() * 0.44f
        val scrim = Paint().apply {
            shader = LinearGradient(
                0f, scrimTop, 0f, rect.bottom,
                Color.TRANSPARENT, 0xE8000000.toInt(), Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(rect.left, scrimTop, rect.right, rect.bottom, scrim)
        canvas.restore()

        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = 0x1FFFFFFF
        }
        canvas.drawPath(shape, border)

        // Text scales with the tile, so it is not tiny in a two-person collage
        // and enormous in a nine-person one.
        val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = rect.width() * 0.088f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ACCENT
            textSize = rect.width() * 0.066f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }

        val padding = rect.width() * 0.06f
        canvas.drawText(
            person.label,
            rect.left + padding,
            rect.bottom - padding - countPaint.textSize * 1.35f,
            namePaint
        )
        canvas.drawText(
            appearanceLabel(person.appearanceCount),
            rect.left + padding,
            rect.bottom - padding,
            countPaint
        )
    }

    /**
     * Fills the tile without distorting the photo.
     *
     * Anchored at 35% rather than centred: in a generous crop the face sits
     * high, and centring lops off the top of the head.
     */
    private fun drawCover(canvas: Canvas, bitmap: Bitmap, dst: RectF) {
        val scale = max(dst.width() / bitmap.width, dst.height() / bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale

        val left = dst.centerX() - width / 2f
        val top = dst.top - (height - dst.height()) * 0.35f

        canvas.drawBitmap(
            bitmap,
            null,
            RectF(left, top, left + width, top + height),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        )
    }

    private fun drawFooter(canvas: Canvas) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = MUTED
            textSize = 30f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        canvas.drawText("Made with FaceFrame · on-device", MARGIN, HEIGHT - 38f, paint)
    }

    private fun appearanceLabel(count: Int): String =
        if (count == 1) "1 appearance" else "$count appearances"
}
