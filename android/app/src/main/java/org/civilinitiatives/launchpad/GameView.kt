package org.civilinitiatives.launchpad

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * Rocket Rush 3D for Android: the 3D world (RushGL on a GLSurfaceView) with
 * the score, timer, cards and touch steering drawn on top. The same rules
 * as the web and iPhone/iPad/Mac versions; 30 free minutes a day, then 5p
 * for each half hour (the server keeps count).
 *
 * Steering: put a finger anywhere and slide it - the rocket follows the
 * slide, like a trackpad. Phones get LIGHT, tablets RICH.
 */
@SuppressLint("ViewConstructor")
class GameView(ctx: Context, private val q: Quality, private val onExit: () -> Unit) : FrameLayout(ctx), RushHost {

    enum class Quality { LIGHT, RICH }

    private val game = RushGL(q == Quality.RICH, this)
    private val surface = RushSurface(ctx, q == Quality.RICH, game)
    private val hud = Hud(ctx)

    private enum class State { MENU, FLY, DEAD }
    @Volatile private var state = State.MENU

    // results of the last run
    private var deadWhy = ""; private var lastScore = 0; private var lastMeters = 0; private var lastStars = 0; private var lastZone = 1
    private var best = 0; private var newBest = false; private var pence = ""
    // banners
    private var zoneName = ""; private var zoneIx = 0; private var zoneCol = Color.WHITE; private var zoneT = 0f
    private var popTxt = ""; private var popCol = Color.WHITE; private var popT = 0f
    private var steered = false

    // play time (the server keeps count)
    private var timeLeft = -1.0; private var leftAt = 0L; private var free = 1; private var playing = false; private var played = 0.0
    private var payOpen = false; private var payMsg = "You've had today's free half hour."; private var buying = false
    private var timeTxt = "⏱️ …"; private var lastTick = 0L; private var tokenSeen = false
    private val prefs = ctx.getSharedPreferences("rush", Context.MODE_PRIVATE)

    init {
        best = prefs.getInt("best", 0)
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(hud, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        isHapticFeedbackEnabled = true
    }

    // ---------------- from the 3D game (GL thread) ----------------
    override fun died(why: String, score: Int, meters: Int, stars: Int, zone: Int) {
        post {
            if (state != State.FLY) return@post
            state = State.DEAD; deadWhy = why; lastScore = score; lastMeters = meters; lastStars = stars; lastZone = zone
            playing = false; tick()
            newBest = score > best
            if (newBest) { best = score; prefs.edit().putInt("best", score).apply() }
            pence = ""
            if (score > 0 && why != "Paused") Server.call(JSONObject().put("action", "played").put("game", "rush").put("score", score)) { j, _ ->
                val g = j?.optInt("gained", 0) ?: 0
                pence = if (g > 0) "+${g}p for that run" else ""
            }
            hud.deadAt = System.currentTimeMillis()
        }
    }
    override fun pop(text: String, color: Int) { post { popTxt = text; popCol = color; popT = 1.1f } }
    override fun zone(ix: Int, name: String, color: Int) { post { zoneIx = ix; zoneName = name; zoneCol = color; zoneT = 1.9f } }
    override fun buzz(strong: Boolean) {
        post { try { performHapticFeedback(if (strong) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.KEYBOARD_TAP) } catch (_: Exception) { } }
    }

    // ---------------- server hooks ----------------
    fun tokenReady() {
        if (tokenSeen) return
        tokenSeen = true
        Server.call(JSONObject().put("action", "playTime").put("sec", 0)) { j, _ -> if (j != null) apply(j) else timeTxt = "⏱️ ?" }
    }
    fun signedOut() { timeTxt = "⏱️ sign in" }
    private fun apply(j: JSONObject) {
        timeLeft = j.optDouble("left", 1800.0); free = j.optInt("free", 0); leftAt = System.currentTimeMillis()
        if (timeLeft <= 0 && playing) outOfTime()
    }
    private fun tick() {
        val sec = Math.round(played).toInt(); played = 0.0
        Server.call(JSONObject().put("action", "playTime").put("sec", sec)) { j, _ -> if (j != null) apply(j) }
    }
    private fun outOfTime() {
        if (payOpen) return
        playing = false; game.paused = true; tick(); payOpen = true
    }
    private fun buyTime() {
        if (buying) return
        buying = true
        Server.call(JSONObject().put("action", "playBuy")) { j, e ->
            buying = false
            if (j != null) { apply(j); payOpen = false; if (state == State.FLY) { game.paused = false; playing = true } }
            else payMsg = e ?: "Couldn't do that."
        }
    }

    // ---------------- lifecycle ----------------
    fun pauseGame() { surface.onPause(); if (playing) { playing = false; tick() }; game.paused = true }
    fun resumeGame() { surface.onResume(); if (state == State.FLY && !payOpen) { game.paused = false; playing = true } }
    fun finishUp() { hud.running = false; if (played > 1) tick() }
    /** Back button: while flying it ends the run; otherwise it leaves. */
    fun back(): Boolean {
        if (state == State.FLY) { surface.queueEvent { game.stopRun("Paused") }; return true }
        return false
    }

    private fun start() {
        if (payOpen) return
        state = State.FLY; playing = true; leftAt = System.currentTimeMillis(); steered = false
        surface.queueEvent { game.startRun() }
        if (timeLeft >= 0 && timeLeft <= 0.0) outOfTime()
    }

    // ---------------- the layer on top: numbers, cards, touch ----------------
    private inner class Hud(ctx: Context) : View(ctx), Choreographer.FrameCallback {
        var running = true
        var deadAt = 0L
        private var lastFrame = 0L
        private val dpx = resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        private val buttons = HashMap<String, RectF>()
        // trackpad-style steering
        private var dragId = -1; private var dx0 = 0f; private var dy0 = 0f; private var tx0 = 0f; private var ty0 = 0f

        init { Choreographer.getInstance().postFrameCallback(this) }

        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val now = frameTimeNanos / 1_000_000L
            val dt = if (lastFrame == 0L) .016f else min(.05f, (now - lastFrame) / 1000f)
            lastFrame = now
            if (state == State.FLY && playing && !game.paused) played += dt
            if (zoneT > 0) zoneT -= dt
            if (popT > 0) popT -= dt
            if (playing && System.currentTimeMillis() - lastTick > 30000) { lastTick = System.currentTimeMillis(); tick() }
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }

        override fun onDraw(c: Canvas) {
            buttons.clear()
            drawHud(c)
            when {
                payOpen -> drawPay(c)
                state == State.MENU -> drawMenu(c)
                state == State.DEAD && System.currentTimeMillis() - deadAt > 900 -> drawDead(c)
            }
        }

        private fun chip(c: Canvas, text: String, x: Float, y: Float, alignRight: Boolean, bg: Int = 0x8C0A0C1E.toInt()): Float {
            txt.textSize = 15 * dpx; txt.textAlign = Paint.Align.LEFT; txt.color = Color.WHITE
            val w = txt.measureText(text) + 24 * dpx; val h = 32 * dpx
            val l = if (alignRight) x - w else x
            p.color = bg; c.drawRoundRect(l, y, l + w, y + h, h / 2, h / 2, p)
            c.drawText(text, l + 12 * dpx, y + h / 2 + 5.5f * dpx, txt)
            return if (alignRight) l - 8 * dpx else l + w + 8 * dpx
        }

        private fun drawHud(c: Canvas) {
            val top = 14 * dpx
            var x = 14 * dpx
            val m = if (state == State.FLY) game.meters else lastMeters
            val s = if (state == State.FLY) game.stars else lastStars
            if (state != State.MENU) {
                x = chip(c, "%,d m".format(m), x, top, false)
                x = chip(c, "⭐ $s", x, top, false)
                val pu = game.puText
                if (state == State.FLY && pu.isNotEmpty()) chip(c, pu, x, top, false)
            }
            if (timeLeft >= 0) {
                val l = max(0.0, timeLeft - (if (playing) (System.currentTimeMillis() - leftAt) / 1000.0 else 0.0))
                timeTxt = "⏱️ %d:%02d %s".format((l / 60).toInt(), (l % 60).toInt(), if (free > 0) "free" else "left")
                if (playing && l <= 0) outOfTime()
            }
            val rx0 = width - 14 * dpx
            val bw = 32 * dpx
            p.color = 0x8C0A0C1E.toInt(); c.drawCircle(rx0 - bw / 2, top + bw / 2, bw / 2, p)
            txt.textAlign = Paint.Align.CENTER; txt.color = Color.WHITE; txt.textSize = 16 * dpx
            c.drawText("✕", rx0 - bw / 2, top + bw / 2 + 6 * dpx, txt)
            buttons["close"] = RectF(rx0 - bw - 6 * dpx, top - 6 * dpx, rx0 + 6 * dpx, top + bw + 6 * dpx)
            chip(c, timeTxt, rx0 - bw - 8 * dpx, top, true, if (timeLeft in 0.0..120.0) 0xFFB45309.toInt() else 0x8C0A0C1E.toInt())

            if (zoneT > 0 && state == State.FLY) {
                val a = (min(1f, zoneT) * 255).toInt()
                txt.textAlign = Paint.Align.CENTER
                txt.textSize = 13 * dpx; txt.color = 0xFFA5ADCF.toInt(); txt.alpha = a
                c.drawText("ZONE ${zoneIx + 1}", width / 2f, height * .26f, txt)
                txt.textSize = 30 * dpx; txt.color = zoneCol; txt.alpha = a
                c.drawText(zoneName, width / 2f, height * .26f + 36 * dpx, txt)
                txt.alpha = 255
            }
            if (popT > 0 && state == State.FLY) {
                txt.textAlign = Paint.Align.CENTER; txt.textSize = 22 * dpx; txt.color = popCol
                txt.alpha = (min(1f, popT * 2) * 255).toInt()
                c.drawText(popTxt, width / 2f, height * .42f - (1.1f - popT) * 30 * dpx, txt)
                txt.alpha = 255
            }
            if (state == State.FLY && !steered) {
                txt.textAlign = Paint.Align.CENTER; txt.textSize = 15 * dpx; txt.color = 0xFFC7D2FE.toInt()
                c.drawText("Slide your finger to steer", width / 2f, height - 30 * dpx, txt)
            }
        }

        private fun card(c: Canvas, h: Float): RectF {
            p.color = 0x7305060F; c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
            val w = min(width - 40 * dpx, 420 * dpx)
            val r = RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
            p.color = 0xEB101230.toInt(); c.drawRoundRect(r, 24 * dpx, 24 * dpx, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = dpx; p.color = 0x1FFFFFFF; c.drawRoundRect(r, 24 * dpx, 24 * dpx, p); p.style = Paint.Style.FILL
            return r
        }
        private fun button(c: Canvas, id: String, label: String, cx: Float, y: Float, ghost: Boolean = false) {
            txt.textSize = 18 * dpx; txt.textAlign = Paint.Align.CENTER; txt.color = Color.WHITE
            val w = txt.measureText(label) + 48 * dpx; val h = 50 * dpx
            val r = RectF(cx - w / 2, y, cx + w / 2, y + h)
            if (ghost) p.color = 0x1FFFFFFF else p.shader = LinearGradient(r.left, 0f, r.right, 0f, 0xFFF59E0B.toInt(), 0xFFEC4899.toInt(), Shader.TileMode.CLAMP)
            c.drawRoundRect(r, 16 * dpx, 16 * dpx, p); p.shader = null
            c.drawText(label, cx, y + h / 2 + 6.5f * dpx, txt)
            buttons[id] = r
        }
        private fun line(c: Canvas, s: String, y: Float, size: Float, col: Int) {
            txt.textAlign = Paint.Align.CENTER; txt.textSize = size * dpx; txt.color = col
            c.drawText(s, width / 2f, y, txt)
        }

        private fun drawMenu(c: Canvas) {
            val r = card(c, 330 * dpx)
            line(c, "🚀 Rocket Rush", r.top + 54 * dpx, 32f, 0xFFFBBF24.toInt())
            line(c, "Fly through space. Slide your finger to steer.", r.top + 92 * dpx, 15f, 0xFFA5ADCF.toInt())
            line(c, "⭐ stars = 5 points · ⚡🪨🧱 end your run", r.top + 120 * dpx, 15f, 0xFFA5ADCF.toInt())
            line(c, "Power-ups: 🛡 shield · 🧲 magnet · 🔥 boost", r.top + 148 * dpx, 15f, 0xFFA5ADCF.toInt())
            if (best > 0) line(c, "Your best: %,d".format(best), r.top + 182 * dpx, 16f, Color.WHITE)
            button(c, "play", "Play", width / 2f, r.top + 200 * dpx)
            line(c, "30 minutes free a day, then 5p per half hour", r.top + 290 * dpx, 12.5f, 0xFFA5ADCF.toInt())
        }
        private fun drawDead(c: Canvas) {
            val r = card(c, 340 * dpx)
            line(c, deadWhy, r.top + 40 * dpx, 16f, 0xFFA5ADCF.toInt())
            line(c, "%,d".format(lastScore), r.top + 96 * dpx, 46f, Color.WHITE)
            line(c, "%,d m  ·  ⭐ %d  ·  zone %d".format(lastMeters, lastStars, lastZone), r.top + 130 * dpx, 15f, 0xFFA5ADCF.toInt())
            line(c, if (newBest && lastScore > 0) "🏆 New best!" else "Your best: %,d".format(best), r.top + 164 * dpx, 16f, if (newBest) 0xFFFBBF24.toInt() else Color.WHITE)
            button(c, "again", "Fly again", width / 2f, r.top + 186 * dpx)
            button(c, "close2", "Back to the Launchpad", width / 2f, r.top + 246 * dpx, true)
            if (pence.isNotEmpty()) line(c, pence, r.top + 322 * dpx, 13f, 0xFFFDE68A.toInt())
        }
        private fun drawPay(c: Canvas) {
            val r = card(c, 310 * dpx)
            line(c, "Time's up for now", r.top + 50 * dpx, 26f, 0xFFFBBF24.toInt())
            line(c, payMsg, r.top + 86 * dpx, 15f, 0xFFA5ADCF.toInt())
            line(c, "Keep playing: 5p for 30 more minutes.", r.top + 112 * dpx, 15f, Color.WHITE)
            button(c, "buy", if (buying) "…" else "Pay 5p", width / 2f, r.top + 134 * dpx)
            button(c, "stop", "Stop for today", width / 2f, r.top + 194 * dpx, true)
            line(c, "Earn pence in the learning games.", r.top + 278 * dpx, 12.5f, 0xFFA5ADCF.toInt())
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val idx = e.actionIndex
                    val x = e.getX(idx); val y = e.getY(idx)
                    when (buttons.entries.firstOrNull { it.value.contains(x, y) }?.key) {
                        "close", "close2", "stop" -> { onExit(); return true }
                        "play", "again" -> { start(); return true }
                        "buy" -> { buyTime(); return true }
                    }
                    if (state == State.FLY && !payOpen && dragId == -1) {
                        dragId = e.getPointerId(idx); dx0 = x; dy0 = y; tx0 = game.tx; ty0 = game.ty
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val idx = e.findPointerIndex(dragId)
                    if (idx >= 0 && state == State.FLY) {
                        /* a slide across the shorter side of the screen moves the rocket across most of the space */
                        val gain = 12f * 1.7f / min(width.toFloat(), height * 1.6f)
                        game.tx = (tx0 + (e.getX(idx) - dx0) * gain).coerceIn(-6f, 6f)
                        game.ty = (ty0 - (e.getY(idx) - dy0) * gain).coerceIn(-3.5f, 3.5f)
                        steered = true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragId = -1
                MotionEvent.ACTION_POINTER_UP -> if (e.getPointerId(e.actionIndex) == dragId) dragId = -1
            }
            return true
        }
    }
}
