package com.primegame.aimguide

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

class OverlayService : Service() {

    private lateinit var wm: WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var w = 0
    private var h = 0
    private var density = 1f
    private var dpi = 320

    private var lineView: AimLineView? = null
    private var striker: View? = null
    private var aim: View? = null
    private var logo: View? = null
    private var menu: View? = null

    private lateinit var strikerLp: WindowManager.LayoutParams
    private lateinit var aimLp: WindowManager.LayoutParams
    private lateinit var logoLp: WindowManager.LayoutParams
    private lateinit var menuLp: WindowManager.LayoutParams
    private var strikerSize = 0
    private var aimSize = 0

    // board
    private var boardFrac = 0.92f
    private var boardCx = 0f
    private var boardCy = 0f

    // modes
    @Volatile private var assistOn = false
    private var manualOn = false
    @Volatile private var mineWhite = true
    private var ignoreSwitch = false
    private var assistSwitch: Switch? = null
    private var manualSwitch: Switch? = null

    // screen capture
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var vDisplay: VirtualDisplay? = null
    private var scanThread: HandlerThread? = null
    private var scanHandler: Handler? = null
    private var held: Image? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopSelf()
            return START_NOT_STICKY
        }
        if (lineView == null) {
            startAsForeground()
            initMetrics()
            val code = intent?.getIntExtra("code", 0) ?: 0
            @Suppress("DEPRECATION")
            val data = intent?.getParcelableExtra<Intent>("data")
            if (data != null && code == Activity.RESULT_OK) {
                try {
                    setupProjection(code, data)
                } catch (e: Exception) {
                    Toast.makeText(this, "Screen capture chalu hoy ni", Toast.LENGTH_LONG).show()
                }
            }
            createOverlays()
        }
        return START_NOT_STICKY
    }

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("aim", "Aim Guide", NotificationManager.IMPORTANCE_LOW)
        )
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, OverlayService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "aim")
            .setContentTitle("Aim Guide chalche")
            .setContentText("Bondho korte STOP chapo")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .addAction(Notification.Action.Builder(null, "STOP", stopPi).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    @Suppress("DEPRECATION")
    private fun initMetrics() {
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(dm)
        w = dm.widthPixels
        h = dm.heightPixels
        density = dm.density
        dpi = dm.densityDpi
        boardCx = w / 2f
        boardCy = h / 2f
    }

    private fun setupProjection(code: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = mpm.getMediaProjection(code, data)
        proj.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                mainHandler.post {
                    projection = null
                    if (assistOn) Toast.makeText(this@OverlayService, "Screen capture bondho hoye geche", Toast.LENGTH_LONG).show()
                }
            }
        }, mainHandler)
        projection = proj

        val thread = HandlerThread("aim-scan").also { it.start() }
        scanThread = thread
        val sh = Handler(thread.looper)
        scanHandler = sh

        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ rd ->
            val im = try { rd.acquireLatestImage() } catch (e: Exception) { null }
            if (im != null) {
                held?.close()
                held = im
            }
        }, sh)
        reader = r
        vDisplay = proj.createVirtualDisplay(
            "aim-capture", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null
        )
    }

    private fun createOverlays() {
        strikerSize = (64 * density).toInt()
        aimSize = (44 * density).toInt()

        // line layer
        val lv = AimLineView(this)
        wm.addView(lv, baseParams(w, h, touchable = false))
        lineView = lv
        applyBoard()

        // striker handle (manual mode / fallback position)
        strikerLp = baseParams(strikerSize, strikerSize, touchable = true)
        strikerLp.x = w / 2 - strikerSize / 2
        strikerLp.y = (h * 0.75f).toInt() - strikerSize / 2
        val s = Dot(Color.parseColor("#FFD700"), "S", Color.BLACK)
        s.visibility = View.GONE
        wm.addView(s, strikerLp)
        striker = s

        // aim handle
        aimLp = baseParams(aimSize, aimSize, touchable = true)
        aimLp.x = w / 2 - aimSize / 2
        aimLp.y = (h * 0.45f).toInt() - aimSize / 2
        val a = Dot(Color.parseColor("#FF3B30"), "", Color.WHITE)
        a.visibility = View.GONE
        wm.addView(a, aimLp)
        aim = a

        attachDrag(s, strikerLp, onMove = { syncLine() })
        attachDrag(a, aimLp, onMove = { syncLine() })

        // floating M logo
        val logoSize = (52 * density).toInt()
        logoLp = baseParams(logoSize, logoSize, touchable = true)
        logoLp.x = (12 * density).toInt()
        logoLp.y = (h * 0.30f).toInt()
        val lg = Dot(Color.parseColor("#1A1530"), "M", Color.parseColor("#FFD700"))
        wm.addView(lg, logoLp)
        logo = lg

        // menu
        val m = buildMenu()
        menuLp = baseParams((230 * density).toInt(), WindowManager.LayoutParams.WRAP_CONTENT, touchable = true)
        menuLp.x = logoLp.x
        menuLp.y = logoLp.y
        m.visibility = View.GONE
        wm.addView(m, menuLp)
        menu = m

        attachDrag(lg, logoLp, onClick = { openMenu() })
        syncLine()
    }

    private fun baseParams(width: Int, height: Int, touchable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        return WindowManager.LayoutParams(
            width, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
    }

    private fun dp(v: Int) = (v * density).toInt()

    private fun roundBg(color: Int, radius: Float, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            if (stroke != 0) setStroke(dp(2), stroke)
        }

    private fun button(text: String, onClick: (TextView) -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = roundBg(Color.parseColor("#3A3358"), dp(8).toFloat())
            setOnClickListener { onClick(this) }
        }

    private fun buildMenu(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            background = roundBg(Color.parseColor("#F0150F26"), dp(16).toFloat(), Color.parseColor("#FFD700"))
        }

        // header with minimize button
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "AIM LOCK  /  MENU"
            setTextColor(Color.parseColor("#FFD700"))
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val minimize = button("—") { closeMenu() }
        header.addView(title)
        header.addView(minimize)
        root.addView(header)

        val assist = Switch(this).apply {
            text = "Aim Assist"
            setTextColor(Color.WHITE)
            setPadding(0, dp(10), 0, dp(4))
            setOnCheckedChangeListener { btn, on ->
                if (!ignoreSwitch) setAssist(on, btn as Switch)
            }
        }
        val manual = Switch(this).apply {
            text = "Manual Line"
            setTextColor(Color.WHITE)
            setPadding(0, dp(4), 0, dp(4))
            setOnCheckedChangeListener { _, on -> setManual(on) }
        }
        assistSwitch = assist
        manualSwitch = manual
        root.addView(assist)
        root.addView(manual)

        root.addView(button("ALL ON — ENABLE VISUAL GUIDES") {
            if (projection == null || scanHandler == null) {
                Toast.makeText(this, "Screen capture permission is required. Restart AIM LOCK and allow capture.", Toast.LENGTH_LONG).show()
            } else {
                assistSwitch?.isChecked = true
                manualSwitch?.isChecked = true
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        root.addView(button("AUTO HIT — NOT AVAILABLE") {
            Toast.makeText(this, "AIM LOCK provides visual guidance only; it cannot drag or hit game pieces.", Toast.LENGTH_LONG).show()
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        val mine = button("MINE: WHITE") { b ->
            mineWhite = !mineWhite
            b.text = if (mineWhite) "MINE: WHITE" else "MINE: BLACK"
        }
        root.addView(mine, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        root.addView(TextView(this).apply {
            text = "Board (cyan box) adjust:"
            setTextColor(Color.LTGRAY)
            textSize = 12f
            setPadding(0, dp(10), 0, dp(4))
        })
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(button("−") { boardFrac = (boardFrac - 0.03f).coerceAtLeast(0.4f); applyBoard() }, weight())
        row1.addView(button("+") { boardFrac = (boardFrac + 0.03f).coerceAtMost(1.3f); applyBoard() }, weight())
        root.addView(row1)
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val st = dp(8).toFloat()
        row2.addView(button("◀") { boardCx -= st; applyBoard() }, weight())
        row2.addView(button("▲") { boardCy -= st; applyBoard() }, weight())
        row2.addView(button("▼") { boardCy += st; applyBoard() }, weight())
        row2.addView(button("▶") { boardCx += st; applyBoard() }, weight())
        root.addView(row2, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        root.addView(button("STOP APP") { stopSelf() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        menuHeader = title
        return root
    }

    private var menuHeader: View? = null
    private fun weight() = LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(2), 0, dp(2), 0) }

    private fun openMenu() {
        val m = menu ?: return
        val lg = logo ?: return
        menuLp.x = logoLp.x
        menuLp.y = logoLp.y
        wm.updateViewLayout(m, menuLp)
        m.visibility = View.VISIBLE
        lg.visibility = View.GONE
        menuHeader?.let { hd ->
            if (!headerDragAttached) {
                headerDragAttached = true
                attachDrag(hd, menuLp)
            }
        }
    }
    private var headerDragAttached = false

    private fun closeMenu() {
        val m = menu ?: return
        val lg = logo ?: return
        logoLp.x = menuLp.x
        logoLp.y = menuLp.y
        wm.updateViewLayout(lg, logoLp)
        m.visibility = View.GONE
        lg.visibility = View.VISIBLE
    }

    private fun applyBoard() {
        val lv = lineView ?: return
        val side = min(w, h) * boardFrac
        lv.board.set(boardCx - side / 2f, boardCy - side / 2f, boardCx + side / 2f, boardCy + side / 2f)
        lv.invalidate()
    }

    private fun setManual(on: Boolean) {
        manualOn = on
        lineView?.manualOn = on
        striker?.visibility = if (on) View.VISIBLE else View.GONE
        aim?.visibility = if (on) View.VISIBLE else View.GONE
        syncLine()
    }

    private fun setAssist(on: Boolean, sw: Switch) {
        if (on) {
            if (projection == null || scanHandler == null) {
                Toast.makeText(this, "Screen capture permission lagbe. App theke abar START koro", Toast.LENGTH_LONG).show()
                ignoreSwitch = true
                sw.isChecked = false
                ignoreSwitch = false
                return
            }
            assistOn = true
            lineView?.showBoard = true
            val sh = scanHandler!!
            sh.removeCallbacks(scanTick)
            sh.post(scanTick)
        } else {
            assistOn = false
            scanHandler?.removeCallbacks(scanTick)
            lineView?.showBoard = false
            lineView?.polys = emptyList()
            lineView?.rings = emptyList()
        }
        lineView?.invalidate()
    }

    private val scanTick = object : Runnable {
        override fun run() {
            if (!assistOn) return
            try { doScan() } catch (e: Exception) { /* ignore frame errors */ }
            scanHandler?.postDelayed(this, 900)
        }
    }

    private fun doScan() {
        val im = held ?: return
        val lv = lineView ?: return
        val board = RectF(lv.board)
        val fb = PointF(strikerLp.x + strikerSize / 2f, strikerLp.y + strikerSize / 2f)
        val res = AimEngine.scan(im, board, mineWhite, fb)
        mainHandler.post { applyResult(res) }
    }

    private fun applyResult(res: AimEngine.Result) {
        val lv = lineView ?: return
        if (!assistOn) return
        lv.polys = res.polys
        lv.rings = res.rings
        res.striker?.let { s ->
            val nx = (s.x - strikerSize / 2f).toInt()
            val ny = (s.y - strikerSize / 2f).toInt()
            if (abs(nx - strikerLp.x) > 6 || abs(ny - strikerLp.y) > 6) {
                strikerLp.x = nx
                strikerLp.y = ny
                striker?.let { wm.updateViewLayout(it, strikerLp) }
            }
        }
        syncLine()
    }

    private fun attachDrag(v: View, lp: WindowManager.LayoutParams, onClick: (() -> Unit)? = null, onMove: (() -> Unit)? = null) {
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = 0f
        v.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y
                    touchX = e.rawX; touchY = e.rawY
                    moved = 0f
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX
                    val dy = e.rawY - touchY
                    moved = hypot(dx, dy)
                    lp.x = startX + dx.toInt()
                    lp.y = startY + dy.toInt()
                    val target = when (v) {
                        menuHeader -> menu
                        else -> v
                    }
                    if (target != null) wm.updateViewLayout(target, lp)
                    onMove?.invoke()
                }
                MotionEvent.ACTION_UP -> {
                    if (moved < dp(8)) onClick?.invoke()
                }
            }
            true
        }
    }

    private fun syncLine() {
        val lv = lineView ?: return
        if (!::strikerLp.isInitialized || !::aimLp.isInitialized) return
        lv.strikerX = strikerLp.x + strikerSize / 2f
        lv.strikerY = strikerLp.y + strikerSize / 2f
        lv.aimX = aimLp.x + aimSize / 2f
        lv.aimY = aimLp.y + aimSize / 2f
        lv.invalidate()
    }

    override fun onDestroy() {
        assistOn = false
        assistSwitch = null
        manualSwitch = null
        scanHandler?.removeCallbacks(scanTick)
        scanHandler?.post {
            held?.close()
            held = null
            scanThread?.quitSafely()
        }
        try { vDisplay?.release() } catch (_: Exception) {}
        try { reader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        listOf(lineView, striker, aim, logo, menu).forEach { v ->
            if (v != null) try { wm.removeView(v) } catch (_: Exception) {}
        }
        lineView = null; striker = null; aim = null; logo = null; menu = null
        super.onDestroy()
    }

    private inner class Dot(private val fill: Int, private val label: String, private val labelColor: Int) :
        View(this@OverlayService) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val r = min(width, height) / 2f
            p.style = Paint.Style.FILL
            p.color = Color.argb(200, Color.red(fill), Color.green(fill), Color.blue(fill))
            c.drawCircle(width / 2f, height / 2f, r - 3f, p)
            p.style = Paint.Style.STROKE
            p.strokeWidth = 4f
            p.color = Color.parseColor("#FFD700")
            c.drawCircle(width / 2f, height / 2f, r - 3f, p)
            if (label.isNotEmpty()) {
                p.style = Paint.Style.FILL
                p.color = labelColor
                p.textSize = r
                p.textAlign = Paint.Align.CENTER
                p.isFakeBoldText = true
                c.drawText(label, width / 2f, height / 2f + r / 3f, p)
            }
        }
    }
}
