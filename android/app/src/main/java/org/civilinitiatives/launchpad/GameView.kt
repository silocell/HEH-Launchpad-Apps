package org.civilinitiatives.launchpad

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Choreographer
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Rocket Rush - hold to fly, let go to fall. The same rules as the web
 * version (heh-arcade/rush.html): zappers, asteroids and gates end the run,
 * stars are 5 points, power-ups are shield, magnet and boost, and the
 * zones change every 1,500 m. Phones get the LIGHT look (fewer particles,
 * no glow) so it stays smooth and easy on the battery; tablets get RICH.
 *
 * World units: the screen is always 1000 units tall.
 */
@SuppressLint("ViewConstructor")
class GameView(ctx: Context, private val q: Quality, private val onExit: () -> Unit) : View(ctx), Choreographer.FrameCallback {

    enum class Quality(val maxParts: Int, val bgStars: Int, val glow: Boolean, val exhaust: Int) {
        LIGHT(120, 70, false, 1), RICH(320, 160, true, 3)
    }

    private class Zone(val name: String, val top: Int, val bot: Int, val zap: Int, val star: Int, val planet: Int)
    private val zones = listOf(
        Zone("Launch Bay", 0xFF0B1030.toInt(), 0xFF1B1F4F.toInt(), 0xFF38BDF8.toInt(), 0xFFC7D2FE.toInt(), 0xFF6366F1.toInt()),
        Zone("Asteroid Belt", 0xFF1A0F0A.toInt(), 0xFF3B2414.toInt(), 0xFFFB923C.toInt(), 0xFFFDE68A.toInt(), 0xFFB45309.toInt()),
        Zone("Pink Nebula", 0xFF2A0A2E.toInt(), 0xFF5B1150.toInt(), 0xFFF472B6.toInt(), 0xFFFBCFE8.toInt(), 0xFFDB2777.toInt()),
        Zone("Ion Storm", 0xFF04201F.toInt(), 0xFF0B3B3A.toInt(), 0xFF2DD4BF.toInt(), 0xFF99F6E4.toInt(), 0xFF0D9488.toInt()),
        Zone("Deep Space", 0xFF020208.toInt(), 0xFF0C0C22.toInt(), 0xFFA78BFA.toInt(), 0xFFFFFFFF.toInt(), 0xFF4C1D95.toInt())
    )

    private enum class Kind { STAR, ZAP, ROCK, GATE, PU }
    private enum class Pu { SHIELD, MAGNET, BOOST }
    private class Thing(val k: Kind, var x: Float, var y: Float) {
        var r = 0f; var t = 0f; var len = 0f; var a = 0f; var spin = 0f; var vy = 0f; var va = 0f
        var w = 70f; var gapY = 0f; var gap = 0f; var pu = Pu.SHIELD; var dead = false; var pts: FloatArray? = null
    }
    private class Part(var x: Float, var y: Float, var vx: Float, var vy: Float, var t: Float, val t0: Float, val c: Int, val s: Float)
    private class Pop(val x: Float, var y: Float, val txt: String, val c: Int, var t: Float)

    private enum class State { MENU, FLY, DEAD }

    private val H = 1000f
    private var W = 1600f
    private var scale = 1f
    private val M = 40f
    private val zoneLen = 1500

    // rocket
    private var rx = 0f; private var ry = 500f; private var vy = 0f; private val rr = 34f; private var tilt = 0f; private var thrust = false
    private var state = State.MENU
    private var paused = false
    private var dist = 0f; private var speed = 0f; private var starsGot = 0; private var zoneIx = 0; private var shake = 0f
    private var deadAt = 0L; private var deadWhy = ""
    private val things = ArrayList<Thing>()
    private val parts = ArrayList<Part>()
    private val pops = ArrayList<Pop>()
    private var nextAt = 0f
    private var shieldOn = false; private var magnetT = 0f; private var boostT = 0f; private var invulnT = 0f; private var puGap = 0f
    private var zoneBanner = 0f
    private var best = 0
    private var newBest = false
    private var pence = ""

    // background stars: x, y, size per layer
    private val layers = listOf(0.08f, 0.22f, 0.5f).mapIndexed { k, sp ->
        sp to Array(q.bgStars / 3 + k * 8) { floatArrayOf(Random.nextFloat() * 3000f, Random.nextFloat() * 1000f, 1f + k * .9f + Random.nextFloat() * 1.2f) }
    }

    // play time (the server keeps count)
    private var left = -1.0; private var leftAt = 0L; private var free = 1; private var playing = false; private var played = 0.0
    private var payOpen = false; private var payMsg = "You've had today's free half hour."; private var buying = false
    private var timeTxt = "⏱️ …"; private var lastTick = 0L; private var tokenSeen = false

    private val prefs = ctx.getSharedPreferences("rush", Context.MODE_PRIVATE)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
    private val path = Path()
    private var lastFrame = 0L
    private var running = true
    private val buttons = HashMap<String, RectF>()

    init {
        best = prefs.getInt("best", 0)
        isHapticFeedbackEnabled = true
        reset(); state = State.MENU
        Choreographer.getInstance().postFrameCallback(this)
    }

    // ---------------- server hooks ----------------
    fun tokenReady() {
        if (tokenSeen) return
        tokenSeen = true
        Server.call(JSONObject().put("action", "playTime").put("sec", 0)) { j, e -> if (j != null) apply(j) else timeTxt = "⏱️ ?" }
    }
    fun signedOut() { timeTxt = "⏱️ sign in" }
    private fun apply(j: JSONObject) {
        left = j.optDouble("left", 1800.0); free = j.optInt("free", 0); leftAt = System.currentTimeMillis()
        if (left <= 0 && playing) outOfTime()
    }
    private fun tick() {
        val sec = Math.round(played).toInt(); played = 0.0
        Server.call(JSONObject().put("action", "playTime").put("sec", sec)) { j, _ -> if (j != null) apply(j) }
    }
    private fun outOfTime() {
        if (payOpen) return
        playing = false; paused = true; tick(); payOpen = true; thrust = false
    }
    private fun buyTime() {
        if (buying) return
        buying = true
        Server.call(JSONObject().put("action", "playBuy")) { j, e ->
            buying = false
            if (j != null) { apply(j); payOpen = false; paused = false; if (state == State.FLY) { playing = true; lastFrame = 0 } }
            else payMsg = e ?: "Couldn't do that."
        }
    }

    // ---------------- lifecycle ----------------
    fun pauseGame() { thrust = false; if (playing) { playing = false; tick() }; paused = true }
    fun resumeGame() { if (state == State.FLY && !payOpen) { paused = false; playing = true }; lastFrame = 0 }
    fun finishUp() { running = false; if (played > 1) tick() }
    /** Back button: on the menu/dead screen it leaves; while flying it pauses to the menu. */
    fun back(): Boolean {
        if (state == State.FLY) { die("Paused"); return true }
        return false
    }

    // ---------------- the game ----------------
    private fun rnd(a: Float, b: Float) = a + Random.nextFloat() * (b - a)
    private fun meters() = floor(dist / M).toInt()
    private fun score() = meters() + starsGot * 5
    private fun diff() = min(1f, meters() / 4000f)

    private fun reset() {
        rx = max(220f, W * .24f); ry = 520f; vy = 0f; tilt = 0f; thrust = false
        dist = 0f; speed = 430f; starsGot = 0; zoneIx = 0; shake = 0f
        things.clear(); parts.clear(); pops.clear(); nextAt = W + 300
        shieldOn = false; magnetT = 0f; boostT = 0f; invulnT = 1.2f; puGap = 2200f
    }

    private fun addStar(x: Float, y: Float) { things.add(Thing(Kind.STAR, x, y).apply { r = 22f; t = Random.nextFloat() * 6 }) }
    private fun starLine(x: Float, y: Float, n: Int): Float { for (i in 0 until n) addStar(x + i * 60, y); return n * 60f }
    private fun starWave(x: Float, y: Float, n: Int): Float { for (i in 0 until n) addStar(x + i * 58, y + sin(i / 2f) * 120); return n * 58f }
    private fun zapper(x: Float, y: Float, len: Float, ang: Float, spin: Float) { things.add(Thing(Kind.ZAP, x, y).apply { this.len = len; a = ang; this.spin = spin }) }
    private fun rock(x: Float, y: Float, r: Float, vy: Float) {
        things.add(Thing(Kind.ROCK, x, y).apply { this.r = r; this.vy = vy; a = Random.nextFloat() * 6; va = rnd(-1f, 1f); pts = FloatArray(11) { r * rnd(.78f, 1.08f) } })
    }
    private fun gate(x: Float, gy: Float, gap: Float) { things.add(Thing(Kind.GATE, x, 0f).apply { gapY = gy; this.gap = gap }) }
    private fun power(x: Float, y: Float) { things.add(Thing(Kind.PU, x, y).apply { r = 34f; pu = Pu.values()[Random.nextInt(3)] }) }

    private fun spawnChunk(x: Float): Float {
        val d = diff()
        var w: Float
        if (puGap <= 0) { power(x + 120, rnd(220f, 780f)); puGap = rnd(6000f, 10000f); return 360f }
        val roll = Random.nextFloat()
        when {
            roll < .2f -> w = if (Random.nextBoolean()) starLine(x, rnd(200f, 800f), 8) else starWave(x, rnd(300f, 700f), 10)
            roll < .5f -> {
                val vert = Random.nextFloat() < .45f
                val len = rnd(240f, 360 + d * 140)
                val y = if (vert) rnd(len / 2 + 60, H - len / 2 - 60) else rnd(160f, 840f)
                val ang = if (vert) (PI / 2).toFloat() else listOf(0f, .7f, -.7f)[Random.nextInt(3)]
                val spin = if (d > .35f && Random.nextFloat() < d * .5f) rnd(.6f, 1.4f) * (if (Random.nextBoolean()) -1 else 1) else 0f
                zapper(x + 120, y, len, ang, spin)
                if (Random.nextFloat() < .7f) starLine(x + 40, if (y < 500) min(900f, y + len / 2 + 110) else max(100f, y - len / 2 - 110), 5)
                w = 520 - d * 120
            }
            roll < .7f -> {
                val gap = 330 - d * 90
                val gy = rnd(gap / 2 + 90, H - gap / 2 - 90)
                gate(x + 140, gy, gap); starLine(x + 70, gy, 4)
                w = 560 - d * 120
            }
            roll < .88f -> {
                val n = 2 + floor(d * 2.5f + Random.nextFloat() * 1.5f).toInt()
                for (i in 0 until n) rock(x + 100 + i * rnd(150f, 230f), rnd(120f, 880f), rnd(38f, 64 + d * 20), rnd(-90f, 90f) * (.4f + d))
                w = 160f + n * 200
            }
            else -> {
                val gp = 320 - d * 70
                val cy = rnd(gp / 2 + 120, H - gp / 2 - 120)
                zapper(x + 120, (cy - gp / 2) / 2, cy - gp / 2 - 40, (PI / 2).toFloat(), 0f)
                zapper(x + 120, cy + gp / 2 + (H - cy - gp / 2) / 2, H - cy - gp / 2 - 40, (PI / 2).toFloat(), 0f)
                addStar(x + 120, cy)
                w = 520f
            }
        }
        return w + rnd(140f, 260f) * (1.15f - d * .5f)
    }

    private fun segDist(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
        var t = if (l2 > 0) ((px - ax) * dx + (py - ay) * dy) / l2 else 0f
        t = max(0f, min(1f, t))
        val qx = ax + t * dx - px; val qy = ay + t * dy - py
        return sqrt(qx * qx + qy * qy)
    }
    private fun hits(t: Thing): Boolean {
        val r = rr * .82f
        return when (t.k) {
            Kind.ZAP -> { val c = cos(t.a) * t.len / 2; val s = sin(t.a) * t.len / 2; segDist(rx, ry, t.x - c, t.y - s, t.x + c, t.y + s) < r + 14 }
            Kind.ROCK -> { val dx = t.x - rx; val dy = t.y - ry; dx * dx + dy * dy < (t.r * .85f + r) * (t.r * .85f + r) }
            Kind.GATE -> if (rx + r < t.x - t.w / 2 || rx - r > t.x + t.w / 2) false else (ry - r < t.gapY - t.gap / 2 || ry + r > t.gapY + t.gap / 2)
            else -> false
        }
    }

    private fun burst(x: Float, y: Float, c: Int, n: Int, sp: Float) {
        val count = if (q == Quality.LIGHT) n / 2 else n
        for (i in 0 until count) {
            if (parts.size >= q.maxParts) parts.removeAt(0)
            val a = Random.nextFloat() * 6.283f; val v = rnd(.3f, 1f) * sp
            val life = rnd(.4f, .9f)
            parts.add(Part(x, y, cos(a) * v, sin(a) * v, life, 1f, c, rnd(3f, 7f)))
        }
    }

    private fun hit(t: Thing) {
        if (boostT > 0 || invulnT > 0) return
        if (shieldOn) {
            shieldOn = false; invulnT = 1.2f; shake = 14f; buzz()
            burst(rx, ry, 0xFF60A5FA.toInt(), 30, 520f)
            if (t.k == Kind.ROCK) { t.dead = true; burst(t.x, t.y, 0xFF9CA3AF.toInt(), 24, 380f) }
            if (t.k == Kind.ZAP) t.dead = true
            pops.add(Pop(rx, ry - 60, "Shield saved you!", 0xFF93C5FD.toInt(), .9f))
            return
        }
        die(when (t.k) { Kind.ZAP -> "Zapped!"; Kind.ROCK -> "Hit an asteroid!"; else -> "Hit a gate!" })
    }

    private fun die(why: String) {
        if (state != State.FLY) return
        state = State.DEAD; deadAt = System.currentTimeMillis(); deadWhy = why; playing = false; tick(); thrust = false
        shake = 26f; buzz()
        burst(rx, ry, 0xFFFBBF24.toInt(), 60, 700f); burst(rx, ry, 0xFFF43F5E.toInt(), 40, 500f)
        val sc = score()
        newBest = sc > best
        if (newBest) { best = sc; prefs.edit().putInt("best", sc).apply() }
        pence = ""
        if (sc > 0) Server.call(JSONObject().put("action", "played").put("game", "rush").put("score", sc)) { j, _ ->
            val g = j?.optInt("gained", 0) ?: 0
            pence = if (g > 0) "+${g}p for that run" else ""
        }
    }

    private fun buzz() { try { performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) } catch (_: Exception) { } }

    private fun grab(t: Thing) {
        burst(t.x, t.y, 0xFFA78BFA.toInt(), 26, 420f)
        try { performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) } catch (_: Exception) { }
        when (t.pu) {
            Pu.SHIELD -> { shieldOn = true; pops.add(Pop(t.x, t.y - 50, "🛡 Shield!", 0xFFFDE68A.toInt(), .9f)) }
            Pu.MAGNET -> { magnetT = 8f; pops.add(Pop(t.x, t.y - 50, "🧲 Magnet!", 0xFFFDE68A.toInt(), .9f)) }
            Pu.BOOST -> { boostT = 3f; pops.add(Pop(t.x, t.y - 50, "🔥 Boost!", 0xFFFDE68A.toInt(), .9f)) }
        }
    }

    private fun start() {
        if (payOpen) return
        reset(); state = State.FLY; paused = false; playing = true; leftAt = System.currentTimeMillis()
        zoneBanner = 1.8f
        if (left >= 0 && left <= 0.0) outOfTime()
    }

    private fun update(dt: Float) {
        if (invulnT > 0) invulnT -= dt
        if (magnetT > 0) magnetT -= dt
        if (boostT > 0) { boostT -= dt; if (boostT <= 0) invulnT = .8f }
        if (zoneBanner > 0) zoneBanner -= dt

        val target = 430 + min(520f, meters() * .13f)
        speed += (target - speed) * min(1f, dt * .5f)
        val v = speed * (if (boostT > 0) 2.1f else 1f)
        val mv = v * dt
        dist += mv; nextAt -= mv; puGap -= mv
        while (nextAt < W + 400) nextAt += spawnChunk(nextAt)

        val z = meters() / zoneLen
        if (z != zoneIx) { zoneIx = z; zoneBanner = 1.8f }

        val g = 2500f; val up = 5600f
        if (boostT > 0) vy += ((500 - ry) * 6 - vy) * min(1f, dt * 4) else vy += (if (thrust) -up + g else g) * dt
        vy = max(-950f, min(1050f, vy))
        ry += vy * dt
        if (ry < 50) { ry = 50f; vy = max(0f, vy) }
        if (ry > H - 50) { ry = H - 50; vy = min(0f, vy) }
        tilt += ((vy / 1000) * .5f - tilt) * min(1f, dt * 10)

        if (thrust || boostT > 0) for (e in 0 until (if (boostT > 0) q.exhaust + 1 else q.exhaust)) {
            if (parts.size >= q.maxParts) parts.removeAt(0)
            val life = rnd(.2f, .45f)
            val c = if (boostT > 0) 0xFFF97316.toInt() else listOf(0xFFFBBF24.toInt(), 0xFFF97316.toInt(), 0xFFFDE68A.toInt())[Random.nextInt(3)]
            parts.add(Part(rx - 52, ry + rnd(-8f, 8f), -rnd(300f, 600f) - v * .3f, rnd(-60f, 60f) + 200, life, .45f, c, rnd(5f, 10f)))
        }

        var i = things.size - 1
        while (i >= 0) {
            val t = things[i]
            t.x -= mv
            when (t.k) {
                Kind.ROCK -> { t.y += t.vy * dt; t.a += t.va * dt; if (t.y < t.r || t.y > H - t.r) t.vy = -t.vy; if (!t.dead && hits(t)) hit(t) }
                Kind.ZAP -> { if (t.spin != 0f) t.a += t.spin * dt; if (!t.dead && hits(t)) hit(t) }
                Kind.GATE -> if (hits(t)) hit(t)
                Kind.STAR -> {
                    t.t += dt * 4
                    if (magnetT > 0) {
                        val dx = rx - t.x; val dy = ry - t.y; val dd = sqrt(dx * dx + dy * dy)
                        if (dd < 360 && dd > 0) { t.x += dx / dd * 900 * dt; t.y += dy / dd * 900 * dt }
                    }
                    val cx = t.x - rx; val cy = t.y - ry
                    if (cx * cx + cy * cy < (t.r + rr + 6) * (t.r + rr + 6)) { starsGot++; burst(t.x, t.y, 0xFFFDE047.toInt(), 10, 260f); t.dead = true }
                }
                Kind.PU -> {
                    t.t += dt
                    val px = t.x - rx; val py = t.y - ry
                    if (px * px + py * py < (t.r + rr) * (t.r + rr)) { grab(t); t.dead = true }
                }
            }
            if (state != State.FLY) return
            if (t.dead || t.x < -500) things.removeAt(i)
            i--
        }
    }

    private fun updateFx(dt: Float) {
        var i = parts.size - 1
        while (i >= 0) { val p = parts[i]; p.x += p.vx * dt; p.y += p.vy * dt; p.vy += 400 * dt; p.t -= dt; if (p.t <= 0) parts.removeAt(i); i-- }
        var j = pops.size - 1
        while (j >= 0) { pops[j].y -= 60 * dt; pops[j].t -= dt; if (pops[j].t <= 0) pops.removeAt(j); j-- }
        if (shake > 0) shake = max(0f, shake - 60 * dt)
    }

    // ---------------- frame loop ----------------
    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val now = frameTimeNanos / 1_000_000L
        val dt = if (lastFrame == 0L) 0.016f else min(.05f, (now - lastFrame) / 1000f)
        lastFrame = now
        if (state == State.FLY && !paused) {
            if (playing) played += dt
            update(dt)
        } else if (state == State.MENU) {
            dist += 120 * dt; ry = 500 + sin(now / 500.0).toFloat() * 40; tilt = sin(now / 700.0).toFloat() * .1f
        }
        updateFx(dt)
        if (playing && System.currentTimeMillis() - lastTick > 30000) { lastTick = System.currentTimeMillis(); tick() }
        invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        scale = h / H; W = w / scale
        if (state != State.FLY) rx = max(220f, W * .24f)
    }

    // ---------------- drawing ----------------
    override fun onDraw(c: Canvas) {
        val z = zones[zoneIx % zones.size]
        p.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), z.top, z.bot, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p); p.shader = null
        c.save()
        val sx = if (shake > 0) rnd(-shake, shake) else 0f
        val sy = if (shake > 0) rnd(-shake, shake) else 0f
        c.scale(scale, scale); c.translate(sx, sy)

        drawPlanet(c, z)
        p.color = z.star
        for ((sp, stars) in layers) {
            p.alpha = ((.25f + sp) * 255).toInt().coerceAtMost(255)
            for (s in stars) {
                val x = ((s[0] - dist * sp) % 3000f + 3000f) % 3000f
                if (x < W + 10) c.drawRect(x, s[1], x + s[2], s[1] + s[2], p)
            }
        }
        p.alpha = 255
        for (t in things) {
            if (t.x < -200 || t.x > W + 300) continue
            when (t.k) {
                Kind.STAR -> drawStar(c, t)
                Kind.ZAP -> drawZap(c, t, z.zap)
                Kind.ROCK -> drawRock(c, t)
                Kind.GATE -> drawGate(c, t, z.zap)
                Kind.PU -> drawPu(c, t)
            }
        }
        if (state != State.DEAD) drawRocket(c)
        for (pt in parts) {
            p.color = pt.c; p.alpha = (max(0f, pt.t / pt.t0) * 255).toInt().coerceIn(0, 255)
            c.drawCircle(pt.x, pt.y, pt.s * (pt.t / pt.t0 + .3f), p)
        }
        p.alpha = 255
        txt.textAlign = Paint.Align.CENTER; txt.textSize = 30f
        for (po in pops) { txt.color = po.c; txt.alpha = (min(1f, po.t * 2) * 255).toInt(); c.drawText(po.txt, po.x, po.y, txt) }
        txt.alpha = 255
        c.restore()

        drawHud(c, z)
        buttons.clear()
        when {
            payOpen -> drawPay(c)
            state == State.MENU -> drawMenu(c)
            state == State.DEAD && System.currentTimeMillis() - deadAt > 900 -> drawDead(c)
        }
    }

    private fun drawPlanet(c: Canvas, z: Zone) {
        var px = W * .78f - (dist * .02f) % (W + 600)
        val pr = 140f
        if (px < -pr) px += W + 600
        if (q.glow) {
            p.shader = RadialGradient(px, 300f, pr * 1.6f, intArrayOf(withAlpha(z.planet, 90), withAlpha(z.planet, 0)), null, Shader.TileMode.CLAMP)
            c.drawCircle(px, 300f, pr * 1.6f, p)
        }
        p.shader = RadialGradient(px - pr * .45f, 300 - pr * .45f, pr * 1.3f, intArrayOf(0xAAFFFFFF.toInt(), z.planet, 0xFF05060F.toInt()), floatArrayOf(0f, .2f, 1f), Shader.TileMode.CLAMP)
        p.alpha = 140
        c.drawCircle(px, 300f, pr, p)
        p.shader = null; p.alpha = 255
        p.style = Paint.Style.STROKE; p.strokeWidth = 6f; p.color = withAlpha(z.star, 90)
        c.save(); c.rotate(-20f, px, 300f); c.drawOval(px - pr * 1.7f, 300 - pr * .32f, px + pr * 1.7f, 300 + pr * .32f, p); c.restore()
        p.style = Paint.Style.FILL
    }

    private fun withAlpha(col: Int, a: Int) = (col and 0x00FFFFFF) or (a shl 24)

    private fun drawRocket(c: Canvas) {
        c.save(); c.translate(rx, ry); c.rotate(Math.toDegrees(tilt.toDouble()).toFloat()); c.scale(1.2f, 1.2f)
        val blink = invulnT > 0 && floor(invulnT * 12).toInt() % 2 == 0
        val alpha = if (blink) 115 else 255
        if (thrust || boostT > 0 || state == State.MENU) {
            val fl = (if (boostT > 0) 70f else 40f) + Random.nextFloat() * 18
            p.shader = LinearGradient(-36f, 0f, -36 - fl, 0f, intArrayOf(0xFFFFF7ED.toInt(), 0xFFFBBF24.toInt(), 0x00F43F5E), floatArrayOf(0f, .35f, 1f), Shader.TileMode.CLAMP)
            p.alpha = alpha
            path.reset(); path.moveTo(-34f, -13f); path.quadTo(-36 - fl, 0f, -34f, 13f); path.close(); c.drawPath(path, p)
            p.shader = null
        }
        p.color = 0xFFE11D48.toInt(); p.alpha = alpha
        path.reset(); path.moveTo(-18f, -14f); path.lineTo(-40f, -34f); path.lineTo(-34f, -10f); path.close(); c.drawPath(path, p)
        path.reset(); path.moveTo(-18f, 14f); path.lineTo(-40f, 34f); path.lineTo(-34f, 10f); path.close(); c.drawPath(path, p)
        p.shader = LinearGradient(0f, -20f, 0f, 20f, 0xFFFFFFFF.toInt(), 0xFFC7D2FE.toInt(), Shader.TileMode.CLAMP); p.alpha = alpha
        path.reset(); path.moveTo(48f, 0f); path.cubicTo(30f, -24f, -10f, -22f, -36f, -14f); path.lineTo(-36f, 14f); path.cubicTo(-10f, 22f, 30f, 24f, 48f, 0f); path.close()
        c.drawPath(path, p); p.shader = null
        p.color = 0xFFE11D48.toInt(); p.alpha = alpha
        path.reset(); path.moveTo(48f, 0f); path.cubicTo(40f, -10f, 30f, -15f, 24f, -16f); path.lineTo(24f, 16f); path.cubicTo(30f, 15f, 40f, 10f, 48f, 0f); path.close(); c.drawPath(path, p)
        p.color = 0xFF1E3A8A.toInt(); p.alpha = alpha; c.drawCircle(4f, 0f, 10f, p)
        p.color = 0xFF38BDF8.toInt(); p.alpha = alpha; c.drawCircle(4f, 0f, 7f, p)
        p.color = 0xCCFFFFFF.toInt(); c.drawCircle(1.5f, -2.5f, 2.5f, p)
        p.alpha = 255
        if (shieldOn) {
            val r = 58 + sin(System.currentTimeMillis() / 120.0).toFloat() * 3
            p.color = 0x2460A5FA; c.drawCircle(4f, 0f, r, p)
            p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.color = 0xE660A5FA.toInt(); c.drawCircle(4f, 0f, r, p); p.style = Paint.Style.FILL
        }
        if (magnetT > 0) {
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = 0x59F472B6
            c.drawCircle(0f, 0f, 90 + (System.currentTimeMillis() / 8 % 40).toFloat(), p); p.style = Paint.Style.FILL
        }
        c.restore()
    }

    private fun drawStar(c: Canvas, t: Thing) {
        val s = 1 + sin(t.t) * .12f
        c.save(); c.translate(t.x, t.y); c.rotate(Math.toDegrees((t.t * .3f).toDouble()).toFloat()); c.scale(s, s)
        if (q.glow) { p.color = 0x40FDE047; c.drawCircle(0f, 0f, 30f, p) }
        p.color = 0xFFFDE047.toInt()
        path.reset()
        for (i in 0 until 10) {
            val r = if (i % 2 == 1) 9f else 21f; val a = i * PI / 5 - PI / 2
            if (i == 0) path.moveTo((cos(a) * r).toFloat(), (sin(a) * r).toFloat()) else path.lineTo((cos(a) * r).toFloat(), (sin(a) * r).toFloat())
        }
        path.close(); c.drawPath(path, p)
        p.color = 0xFFFFFBEB.toInt(); c.drawCircle(-3f, -4f, 4f, p)
        c.restore()
    }

    private fun drawZap(c: Canvas, t: Thing, col: Int) {
        val cc = cos(t.a) * t.len / 2; val ss = sin(t.a) * t.len / 2
        val ax = t.x - cc; val ay = t.y - ss; val bx = t.x + cc; val by = t.y + ss
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
        if (q.glow) { p.color = withAlpha(col, 60); p.strokeWidth = 22f; c.drawLine(ax, ay, bx, by, p) }
        p.color = withAlpha(col, 140); p.strokeWidth = 6f; c.drawLine(ax, ay, bx, by, p)
        p.color = Color.WHITE; p.strokeWidth = 3f
        path.reset(); path.moveTo(ax, ay)
        val n = max(4, (t.len / 34).toInt()); val nx = -ss / (t.len / 2); val ny = cc / (t.len / 2)
        for (i in 1 until n) { val f = i.toFloat() / n; val j = rnd(-12f, 12f); path.lineTo(ax + (bx - ax) * f + nx * j, ay + (by - ay) * f + ny * j) }
        path.lineTo(bx, by); c.drawPath(path, p)
        p.style = Paint.Style.FILL
        for ((x, y) in listOf(ax to ay, bx to by)) {
            p.shader = RadialGradient(x - 5, y - 5, 20f, intArrayOf(Color.WHITE, col, 0xFF111827.toInt()), floatArrayOf(0f, .5f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(x, y, 19f, p); p.shader = null
        }
    }

    private fun drawRock(c: Canvas, t: Thing) {
        c.save(); c.translate(t.x, t.y); c.rotate(Math.toDegrees(t.a.toDouble()).toFloat())
        p.shader = RadialGradient(-t.r * .3f, -t.r * .3f, t.r * 1.2f, 0xFFA8A29E.toInt(), 0xFF44403C.toInt(), Shader.TileMode.CLAMP)
        val pts = t.pts!!
        path.reset()
        for (i in pts.indices) {
            val a = i.toDouble() / pts.size * 2 * PI
            val x = (cos(a) * pts[i]).toFloat(); val y = (sin(a) * pts[i]).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close(); c.drawPath(path, p); p.shader = null
        p.color = 0x38000000
        c.drawCircle(t.r * .25f, -t.r * .2f, t.r * .22f, p); c.drawCircle(-t.r * .3f, t.r * .3f, t.r * .15f, p)
        c.restore()
    }

    private fun drawGate(c: Canvas, t: Thing, col: Int) {
        val top = t.gapY - t.gap / 2; val bot = t.gapY + t.gap / 2; val x0 = t.x - t.w / 2
        p.shader = LinearGradient(x0, 0f, x0 + t.w, 0f, intArrayOf(0xFF1F2937.toInt(), 0xFF4B5563.toInt(), 0xFF111827.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRect(x0, -20f, x0 + t.w, top, p); c.drawRect(x0, bot, x0 + t.w, H + 20, p); p.shader = null
        if (q.glow) { p.color = withAlpha(col, 70); c.drawRect(x0 - 12, top - 20, x0 + t.w + 12, top + 6, p); c.drawRect(x0 - 12, bot - 6, x0 + t.w + 12, bot + 20, p) }
        p.color = col
        c.drawRect(x0 - 6, top - 14, x0 + t.w + 6, top, p); c.drawRect(x0 - 6, bot, x0 + t.w + 6, bot + 14, p)
        p.color = 0x14FFFFFF
        var y = top - 70; while (y > -20) { c.drawRect(x0 + 8, y, x0 + t.w - 8, y + 4, p); y -= 70 }
        var y2 = bot + 70; while (y2 < H) { c.drawRect(x0 + 8, y2, x0 + t.w - 8, y2 + 4, p); y2 += 70 }
    }

    private fun drawPu(c: Canvas, t: Thing) {
        val b = sin(t.t * 4) * 6
        val col = when (t.pu) { Pu.SHIELD -> 0xFF60A5FA.toInt(); Pu.MAGNET -> 0xFFF472B6.toInt(); Pu.BOOST -> 0xFFF97316.toInt() }
        if (q.glow) { p.color = withAlpha(col, 60); c.drawCircle(t.x, t.y + b, 46f, p) }
        p.color = 0x1AFFFFFF; c.drawCircle(t.x, t.y + b, 34f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.color = col; c.drawCircle(t.x, t.y + b, 34f, p); p.style = Paint.Style.FILL
        txt.textAlign = Paint.Align.CENTER; txt.textSize = 34f; txt.color = Color.WHITE
        c.drawText(when (t.pu) { Pu.SHIELD -> "🛡"; Pu.MAGNET -> "🧲"; Pu.BOOST -> "🔥" }, t.x, t.y + b + 12, txt)
    }

    // ---------------- HUD and screens (in screen pixels) ----------------
    private val dpx = resources.displayMetrics.density

    private fun chip(c: Canvas, text: String, x: Float, y: Float, alignRight: Boolean, bg: Int = 0x8C0A0C1E.toInt()): Float {
        txt.textSize = 15 * dpx; txt.textAlign = Paint.Align.LEFT; txt.color = Color.WHITE
        val w = txt.measureText(text) + 24 * dpx; val h = 32 * dpx
        val left = if (alignRight) x - w else x
        p.color = bg; c.drawRoundRect(left, y, left + w, y + h, h / 2, h / 2, p)
        c.drawText(text, left + 12 * dpx, y + h / 2 + 5.5f * dpx, txt)
        return if (alignRight) left - 8 * dpx else left + w + 8 * dpx
    }

    private fun drawHud(c: Canvas, z: Zone) {
        val top = 14 * dpx
        var x = 14 * dpx
        x = chip(c, "%,d m".format(meters()), x, top, false)
        x = chip(c, "⭐ $starsGot", x, top, false)
        val pu = buildList {
            if (shieldOn) add("🛡")
            if (magnetT > 0) add("🧲 ${kotlin.math.ceil(magnetT).toInt()}")
            if (boostT > 0) add("🔥 ${kotlin.math.ceil(boostT).toInt()}")
        }
        if (pu.isNotEmpty()) chip(c, pu.joinToString("  "), x, top, false)
        // time left
        if (left >= 0) {
            val l = max(0.0, left - (if (playing) (System.currentTimeMillis() - leftAt) / 1000.0 else 0.0))
            timeTxt = "⏱️ %d:%02d %s".format((l / 60).toInt(), (l % 60).toInt(), if (free > 0) "free" else "left")
            if (playing && l <= 0) outOfTime()
        }
        val rx0 = width - 14 * dpx
        // close button
        val bw = 32 * dpx
        p.color = 0x8C0A0C1E.toInt(); c.drawCircle(rx0 - bw / 2, top + bw / 2, bw / 2, p)
        txt.textAlign = Paint.Align.CENTER; txt.color = Color.WHITE; txt.textSize = 16 * dpx
        c.drawText("✕", rx0 - bw / 2, top + bw / 2 + 6 * dpx, txt)
        buttons["close"] = RectF(rx0 - bw - 6 * dpx, top - 6 * dpx, rx0 + 6 * dpx, top + bw + 6 * dpx)
        chip(c, timeTxt, rx0 - bw - 8 * dpx, top, true, if (left in 0.0..120.0) 0xFFB45309.toInt() else 0x8C0A0C1E.toInt())

        if (zoneBanner > 0 && state == State.FLY) {
            val a = (min(1f, zoneBanner) * 255).toInt()
            txt.textAlign = Paint.Align.CENTER
            txt.textSize = 13 * dpx; txt.color = 0xFFA5ADCF.toInt(); txt.alpha = a
            c.drawText("ZONE ${zoneIx + 1}", width / 2f, height * .32f, txt)
            txt.textSize = 30 * dpx; txt.color = z.zap; txt.alpha = a
            c.drawText(z.name, width / 2f, height * .32f + 36 * dpx, txt)
            txt.alpha = 255
        }
        if (state == State.FLY && dist < 40 * M && !thrust) {
            txt.textAlign = Paint.Align.CENTER; txt.textSize = 15 * dpx; txt.color = 0xFFA5ADCF.toInt()
            c.drawText("Hold to fly · let go to drop", width / 2f, height - 30 * dpx, txt)
        }
    }

    private fun card(c: Canvas, h: Float): RectF {
        p.color = 0x8C05060F.toInt(); c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        val w = min(width - 40 * dpx, 420 * dpx)
        val r = RectF((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        p.color = 0xF0101230.toInt(); c.drawRoundRect(r, 24 * dpx, 24 * dpx, p)
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
        line(c, "Hold anywhere to fly up. Let go to drop.", r.top + 92 * dpx, 15f, 0xFFA5ADCF.toInt())
        line(c, "⭐ stars = 5 points · ⚡🪨🧱 end your run", r.top + 120 * dpx, 15f, 0xFFA5ADCF.toInt())
        line(c, "Power-ups: 🛡 shield · 🧲 magnet · 🔥 boost", r.top + 148 * dpx, 15f, 0xFFA5ADCF.toInt())
        if (best > 0) line(c, "Your best: %,d".format(best), r.top + 182 * dpx, 16f, Color.WHITE)
        button(c, "play", "Play", width / 2f, r.top + 200 * dpx)
        line(c, "30 minutes free a day, then 5p per half hour", r.top + 290 * dpx, 12.5f, 0xFFA5ADCF.toInt())
    }

    private fun drawDead(c: Canvas) {
        val r = card(c, 340 * dpx)
        line(c, deadWhy, r.top + 40 * dpx, 16f, 0xFFA5ADCF.toInt())
        line(c, "%,d".format(score()), r.top + 96 * dpx, 46f, Color.WHITE)
        line(c, "%,d m  ·  ⭐ %d  ·  zone %d".format(meters(), starsGot, zoneIx + 1), r.top + 130 * dpx, 15f, 0xFFA5ADCF.toInt())
        line(c, if (newBest && score() > 0) "🏆 New best!" else "Your best: %,d".format(best), r.top + 164 * dpx, 16f, if (newBest) 0xFFFBBF24.toInt() else Color.WHITE)
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

    // ---------------- touch ----------------
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = e.actionIndex
                val x = e.getX(idx); val y = e.getY(idx)
                val hitId = buttons.entries.firstOrNull { it.value.contains(x, y) }?.key
                when (hitId) {
                    "close", "close2", "stop" -> { onExit(); return true }
                    "play", "again" -> { start(); return true }
                    "buy" -> { buyTime(); return true }
                }
                if (state == State.FLY && !payOpen) thrust = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> thrust = false
            MotionEvent.ACTION_POINTER_UP -> if (e.pointerCount <= 1) thrust = false
        }
        return true
    }
}
