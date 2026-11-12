package com.primegame.aimguide

import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.media.Image
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Finds coins on a screenshot by colour and plans pocket shots.
 * Everything is a heuristic: it works best when the board outline (cyan rectangle)
 * matches the real playing surface.
 */
object AimEngine {

    private const val STEP = 3

    class Result(val striker: PointF?, val polys: List<AimLineView.Poly>, val rings: List<AimLineView.Ring>)

    private class Blob(val x: Float, val y: Float, val area: Int, val type: Int)
    private class Plan(val coin: PointF, val ghost: PointF, val path: List<PointF>, val score: Float, val bank: Boolean)

    private val GREEN = Color.rgb(0, 255, 0)
    private val CYAN = Color.rgb(0, 255, 255)
    private val GOLD = Color.rgb(255, 200, 0)
    private val GRAY = Color.rgb(130, 130, 130)

    // 1 = white, 2 = black, 3 = red (queen)
    private fun cls(r: Int, g: Int, b: Int): Int {
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        if (r > 150 && g < 95 && b < 95) return 3
        if (mx < 70) return 2
        if (mn > 200 && mx - mn < 45) return 1
        return 0
    }

    fun scan(img: Image, board: RectF, mineWhite: Boolean, fallback: PointF): Result {
        val side = board.width()
        val rc = side * 0.0215f
        val rs = side * 0.0275f

        val plane = img.planes[0]
        val buf = plane.buffer
        val rowStride = plane.rowStride
        val pixStride = plane.pixelStride
        val gw = img.width / STEP
        val gh = img.height / STEP
        val grid = ByteArray(gw * gh)

        val gx0 = max(0, (board.left / STEP).toInt())
        val gx1 = min(gw - 1, (board.right / STEP).toInt())
        val gy0 = max(0, (board.top / STEP).toInt())
        val gy1 = min(gh - 1, (board.bottom / STEP).toInt())

        val corners = arrayOf(
            PointF(board.left, board.top), PointF(board.right, board.top),
            PointF(board.left, board.bottom), PointF(board.right, board.bottom)
        )
        val excl = side * 0.085f

        for (gy in gy0..gy1) {
            for (gx in gx0..gx1) {
                val sx = gx * STEP
                val sy = gy * STEP
                var near = false
                for (c in corners) if (hypot(sx - c.x, sy - c.y) < excl) near = true
                if (near) continue
                val o = sy * rowStride + sx * pixStride
                if (o + 2 >= buf.limit()) continue
                val r = buf.get(o).toInt() and 0xFF
                val g = buf.get(o + 1).toInt() and 0xFF
                val b = buf.get(o + 2).toInt() and 0xFF
                grid[gy * gw + gx] = cls(r, g, b).toByte()
            }
        }

        // connected components
        val visited = BooleanArray(gw * gh)
        val stack = IntArray(gw * gh)
        val blobs = ArrayList<Blob>()
        val rcg = rc / STEP
        val exp = (PI * rcg * rcg).toFloat()

        for (gy in gy0..gy1) {
            for (gx in gx0..gx1) {
                val idx = gy * gw + gx
                val t = grid[idx].toInt()
                if (t == 0 || visited[idx]) continue
                var sp = 0
                stack[sp++] = idx
                visited[idx] = true
                var cnt = 0
                var sumX = 0f
                var sumY = 0f
                var minX = gx
                var maxX = gx
                var minY = gy
                var maxY = gy
                while (sp > 0) {
                    val cur = stack[--sp]
                    val cx = cur % gw
                    val cy = cur / gw
                    cnt++
                    sumX += cx
                    sumY += cy
                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy
                    if (cx + 1 <= gx1) { val n = cur + 1; if (!visited[n] && grid[n].toInt() == t) { visited[n] = true; stack[sp++] = n } }
                    if (cx - 1 >= gx0) { val n = cur - 1; if (!visited[n] && grid[n].toInt() == t) { visited[n] = true; stack[sp++] = n } }
                    if (cy + 1 <= gy1) { val n = cur + gw; if (!visited[n] && grid[n].toInt() == t) { visited[n] = true; stack[sp++] = n } }
                    if (cy - 1 >= gy0) { val n = cur - gw; if (!visited[n] && grid[n].toInt() == t) { visited[n] = true; stack[sp++] = n } }
                }
                val bw = maxX - minX + 1
                val bh = maxY - minY + 1
                val ratio = bw.toFloat() / bh
                if (cnt < 0.25f * exp || cnt > 3.5f * exp) continue
                if (ratio < 0.6f || ratio > 1.7f) continue
                if (cnt.toFloat() / (bw * bh) < 0.5f) continue
                blobs.add(Blob((sumX / cnt) * STEP + STEP / 2f, (sumY / cnt) * STEP + STEP / 2f, cnt, t))
            }
        }

        // striker: a clearly bigger blob in the bottom band of the board
        var striker: PointF? = null
        var bestArea = 0
        for (b in blobs) {
            if (b.area >= 1.35f * exp && b.y > board.bottom - 0.25f * side && b.area > bestArea) {
                bestArea = b.area
                striker = PointF(b.x, b.y)
            }
        }
        val s = striker ?: fallback

        val myType = if (mineWhite) 1 else 2
        val all = ArrayList<PointF>()
        val mine = ArrayList<PointF>()
        for (b in blobs) {
            if (b.area >= 1.35f * exp) continue
            val pt = PointF(b.x, b.y)
            all.add(pt)
            if (b.type == myType || b.type == 3) mine.add(pt)
        }

        val k = side * 0.02f
        val pockets = listOf(
            PointF(board.left + k, board.top + k), PointF(board.right - k, board.top + k),
            PointF(board.left + k, board.bottom - k), PointF(board.right - k, board.bottom - k)
        )

        val polys = ArrayList<AimLineView.Poly>()
        val rings = ArrayList<AimLineView.Ring>()
        var best: Plan? = null

        for (c in mine) {
            val others = all.filter { it !== c }
            var plan: Plan? = null
            for (pk in pockets) {
                val direct = evaluate(s, c, pk, listOf(c, pk), false, others, rc, rs, board)
                if (direct != null && (plan == null || direct.score < plan.score)) plan = direct

                val walls = listOf(
                    Triple(0, board.left, PointF(2 * board.left - pk.x, pk.y)),
                    Triple(0, board.right, PointF(2 * board.right - pk.x, pk.y)),
                    Triple(1, board.top, PointF(pk.x, 2 * board.top - pk.y)),
                    Triple(1, board.bottom, PointF(pk.x, 2 * board.bottom - pk.y))
                )
                for ((axis, wv, pm) in walls) {
                    val t = if (axis == 0) (wv - c.x) / (pm.x - c.x) else (wv - c.y) / (pm.y - c.y)
                    if (t.isNaN() || t <= 0f || t >= 1f) continue
                    val bx = c.x + t * (pm.x - c.x)
                    val by = c.y + t * (pm.y - c.y)
                    if (bx < board.left - 1 || bx > board.right + 1 || by < board.top - 1 || by > board.bottom + 1) continue
                    val bank = evaluate(s, c, pm, listOf(c, PointF(bx, by), pk), true, others, rc, rs, board)
                    if (bank != null && (plan == null || bank.score < plan.score)) plan = bank
                }
            }
            if (plan != null) {
                polys.add(AimLineView.Poly(plan.path, if (plan.bank) CYAN else GREEN, 4f))
                if (best == null || plan.score < best.score) best = plan
            } else {
                var near = pockets[0]
                var nd = Float.MAX_VALUE
                for (pk in pockets) {
                    val d = hypot(pk.x - c.x, pk.y - c.y)
                    if (d < nd) { nd = d; near = pk }
                }
                polys.add(AimLineView.Poly(listOf(c, near), GRAY, 3f))
            }
        }

        if (best != null) {
            polys.add(AimLineView.Poly(listOf(s, best.ghost), GOLD, 6f))
            polys.add(AimLineView.Poly(best.path, GOLD, 6f))
            rings.add(AimLineView.Ring(best.ghost.x, best.ghost.y, rs, GOLD))
        }
        return Result(striker, polys, rings)
    }

    private fun evaluate(
        s: PointF, c: PointF, target: PointF, path: List<PointF>, bank: Boolean,
        others: List<PointF>, rc: Float, rs: Float, board: RectF
    ): Plan? {
        val dx = target.x - c.x
        val dy = target.y - c.y
        val len = hypot(dx, dy)
        if (len < 1f) return null
        val ux = dx / len
        val uy = dy / len
        val g = PointF(c.x - ux * (rc + rs), c.y - uy * (rc + rs))
        if (g.x < board.left + rs || g.x > board.right - rs || g.y < board.top + rs || g.y > board.bottom - rs) return null

        val sx = g.x - s.x
        val sy = g.y - s.y
        val sl = hypot(sx, sy)
        if (sl < 1f) return null
        val cos = (sx * ux + sy * uy) / sl
        if (cos < (if (bank) 0.5f else 0.3f)) return null

        for (o in others) if (segDist(o, s, g) < rs + rc + 2f) return null

        var plen = 0f
        for (i in 0 until path.size - 1) {
            val a = path[i]
            val b = path[i + 1]
            plen += hypot(b.x - a.x, b.y - a.y)
            for (o in others) if (segDist(o, a, b) < 2 * rc + 2f) return null
        }
        val score = sl + 1.5f * plen + 600f * (1f - cos) + (if (bank) 400f else 0f)
        return Plan(c, g, path, score, bank)
    }

    private fun segDist(p: PointF, a: PointF, b: PointF): Float {
        val abx = b.x - a.x
        val aby = b.y - a.y
        val l2 = abx * abx + aby * aby
        if (l2 < 1e-6f) return hypot(p.x - a.x, p.y - a.y)
        var t = ((p.x - a.x) * abx + (p.y - a.y) * aby) / l2
        t = max(0f, min(1f, t))
        return hypot(p.x - (a.x + t * abx), p.y - (a.y + t * aby))
    }
}

