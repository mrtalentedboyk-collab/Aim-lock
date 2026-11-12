package com.primegame.aimguide

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** Full-screen, touch-through view that draws the aim lines. */
class AimLineView(context: Context) : View(context) {

    class Poly(val pts: List<PointF>, val color: Int, val width: Float)
    class Ring(val x: Float, val y: Float, val r: Float, val color: Int)

    var strikerX = 0f
    var strikerY = 0f
    var aimX = 0f
    var aimY = 0f
    val board = RectF()

    var manualOn = false
    var showBoard = false

    @Volatile var polys: List<Poly> = emptyList()
    @Volatile var rings: List<Ring> = emptyList()

    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    override fun onDraw(canvas: Canvas) {
        if (showBoard) {
            p.color = Color.rgb(0, 255, 255)
            p.strokeWidth = 2f
            canvas.drawRect(board, p)
        }

        for (poly in polys) {
            if (poly.pts.size < 2) continue
            val path = Path()
            path.moveTo(poly.pts[0].x, poly.pts[0].y)
            for (i in 1 until poly.pts.size) path.lineTo(poly.pts[i].x, poly.pts[i].y)
            p.color = poly.color
            p.strokeWidth = poly.width
            canvas.drawPath(path, p)
            val last = poly.pts.last()
            fill.color = poly.color
            canvas.drawCircle(last.x, last.y, poly.width * 1.4f, fill)
        }
        for (r in rings) {
            p.color = r.color
            p.strokeWidth = 4f
            canvas.drawCircle(r.x, r.y, r.r, p)
        }

        if (manualOn) drawManual(canvas)
    }

    private fun drawManual(canvas: Canvas) {
        var dx = aimX - strikerX
        var dy = aimY - strikerY
        val len = hypot(dx, dy)
        if (len < 1f) return
        dx /= len
        dy /= len

        var px = strikerX
        var py = strikerY
        val path = Path()
        path.moveTo(px, py)
        for (i in 0 until 4) {
            var t = Float.MAX_VALUE
            if (dx > 0f) t = min(t, (board.right - px) / dx)
            else if (dx < 0f) t = min(t, (board.left - px) / dx)
            if (dy > 0f) t = min(t, (board.bottom - py) / dy)
            else if (dy < 0f) t = min(t, (board.top - py) / dy)
            if (t <= 0f || t == Float.MAX_VALUE) {
                path.lineTo(px + dx * 3000f, py + dy * 3000f)
                break
            }
            val nx = px + dx * t
            val ny = py + dy * t
            path.lineTo(nx, ny)
            if (abs(nx - board.left) < 1f || abs(nx - board.right) < 1f) dx = -dx
            if (abs(ny - board.top) < 1f || abs(ny - board.bottom) < 1f) dy = -dy
            px = nx
            py = ny
        }
        p.color = Color.rgb(255, 200, 0)
        p.strokeWidth = 4f
        canvas.drawPath(path, p)
    }
}

