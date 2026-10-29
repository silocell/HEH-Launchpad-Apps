package org.civilinitiatives.launchpad

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/**
 * Rocket Rush 3D for Android, drawn with OpenGL ES 2.0 - the same rules as
 * the web (heh-arcade/rush.html) and iPhone/iPad/Mac (RushGame.swift)
 * versions. Fly forward through space and steer round zappers, walls and
 * asteroids; stars are 5 points; power-ups are shield, magnet and boost;
 * the zone changes every 1,500 m.
 *
 * The flying space is 12 wide and 7 tall (HW, HH are half of that). The
 * rocket stays at z = 0 and everything else comes towards it, drawn at
 * z = -(thing.z - dist). 1.5 units = 1 metre on the distance counter.
 *
 * Tablets get RICH (4x anti-aliasing, glows, more dust and sparks); phones
 * get LIGHT so it stays smooth and easy on the battery.
 *
 * The game runs on the GL thread (onDrawFrame). The HUD and cards on top
 * (GameView) read the numbers here and set where to steer.
 */
interface RushHost {
    fun died(why: String, score: Int, meters: Int, stars: Int, zone: Int)
    fun pop(text: String, color: Int)
    fun zone(ix: Int, name: String, color: Int)
    fun buzz(strong: Boolean)
}

/** A shape uploaded to the graphics card: position + normal per corner. */
class Mesh(private val data: FloatArray, val mode: Int = GLES20.GL_TRIANGLES) {
    val count = data.size / 6
    var vbo = 0
    fun upload() {
        val buf = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(data).position(0)
        val ids = IntArray(1); GLES20.glGenBuffers(1, ids, 0); vbo = ids[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, vbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, buf, GLES20.GL_STATIC_DRAW)
    }
}

/** Builds simple shapes. */
object Shapes {
    private class B { val f = ArrayList<Float>(4096)
        fun v(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float) { f.add(x); f.add(y); f.add(z); f.add(nx); f.add(ny); f.add(nz) }
        fun flat(a: FloatArray, b: FloatArray, c: FloatArray) {
            val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]
            val wx = c[0] - a[0]; val wy = c[1] - a[1]; val wz = c[2] - a[2]
            var nx = uy * wz - uz * wy; var ny = uz * wx - ux * wz; var nz = ux * wy - uy * wx
            val l = sqrt(nx * nx + ny * ny + nz * nz).takeIf { it > 0f } ?: 1f
            nx /= l; ny /= l; nz /= l
            for (p in arrayOf(a, b, c)) v(p[0], p[1], p[2], nx, ny, nz)
        }
        fun mesh(mode: Int = GLES20.GL_TRIANGLES) = Mesh(f.toFloatArray(), mode)
    }
    private fun p(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)

    fun box(): Mesh {
        val b = B(); val h = .5f
        val c = arrayOf(p(-h, -h, -h), p(h, -h, -h), p(h, h, -h), p(-h, h, -h), p(-h, -h, h), p(h, -h, h), p(h, h, h), p(-h, h, h))
        val faces = arrayOf(intArrayOf(4, 5, 6, 7), intArrayOf(1, 0, 3, 2), intArrayOf(5, 1, 2, 6), intArrayOf(0, 4, 7, 3), intArrayOf(7, 6, 2, 3), intArrayOf(0, 1, 5, 4))
        for (q in faces) { b.flat(c[q[0]], c[q[1]], c[q[2]]); b.flat(c[q[0]], c[q[2]], c[q[3]]) }
        return b.mesh()
    }
    /** along y, centred; r0 at the bottom, r1 at the top */
    fun cylinder(r0: Float, r1: Float, h: Float, seg: Int, caps: Boolean = true): Mesh {
        val b = B(); val y0 = -h / 2; val y1 = h / 2
        val slope = (r0 - r1) / h
        for (i in 0 until seg) {
            val a0 = (i * 2 * PI / seg).toFloat(); val a1 = ((i + 1) * 2 * PI / seg).toFloat()
            val c0 = cos(a0); val s0 = sin(a0); val c1 = cos(a1); val s1 = sin(a1)
            val n0 = norm3(c0, slope, s0); val n1 = norm3(c1, slope, s1)
            b.v(r0 * c0, y0, r0 * s0, n0[0], n0[1], n0[2]); b.v(r1 * c1, y1, r1 * s1, n1[0], n1[1], n1[2]); b.v(r0 * c1, y0, r0 * s1, n1[0], n1[1], n1[2])
            b.v(r0 * c0, y0, r0 * s0, n0[0], n0[1], n0[2]); b.v(r1 * c0, y1, r1 * s0, n0[0], n0[1], n0[2]); b.v(r1 * c1, y1, r1 * s1, n1[0], n1[1], n1[2])
            if (caps) {
                if (r1 > 0f) { b.v(0f, y1, 0f, 0f, 1f, 0f); b.v(r1 * c1, y1, r1 * s1, 0f, 1f, 0f); b.v(r1 * c0, y1, r1 * s0, 0f, 1f, 0f) }
                if (r0 > 0f) { b.v(0f, y0, 0f, 0f, -1f, 0f); b.v(r0 * c0, y0, r0 * s0, 0f, -1f, 0f); b.v(r0 * c1, y0, r0 * s1, 0f, -1f, 0f) }
            }
        }
        return b.mesh()
    }
    fun sphere(r: Float, seg: Int, rings: Int): Mesh {
        val b = B()
        fun pt(i: Int, j: Int): FloatArray {
            val th = (j * PI / rings).toFloat(); val ph = (i * 2 * PI / seg).toFloat()
            return floatArrayOf(sin(th) * cos(ph), cos(th), sin(th) * sin(ph))
        }
        for (j in 0 until rings) for (i in 0 until seg) {
            val a = pt(i, j); val bb = pt(i + 1, j); val c = pt(i + 1, j + 1); val d = pt(i, j + 1)
            for (q in arrayOf(a, c, bb, a, d, c)) b.v(q[0] * r, q[1] * r, q[2] * r, q[0], q[1], q[2])
        }
        return b.mesh()
    }
    fun torus(R: Float, r: Float, seg: Int, tube: Int, arc: Float = (2 * PI).toFloat()): Mesh {
        val b = B()
        fun pt(i: Int, j: Int): FloatArray {
            val u = i * arc / seg; val w = (j * 2 * PI / tube).toFloat()
            val nx = cos(w) * cos(u); val ny = cos(w) * sin(u); val nz = sin(w)
            return floatArrayOf((R + r * cos(w)) * cos(u), (R + r * cos(w)) * sin(u), r * sin(w), nx, ny, nz)
        }
        for (i in 0 until seg) for (j in 0 until tube) {
            val a = pt(i, j); val bb = pt(i + 1, j); val c = pt(i + 1, j + 1); val d = pt(i, j + 1)
            for (q in arrayOf(a, bb, c, a, c, d)) b.v(q[0], q[1], q[2], q[3], q[4], q[5])
        }
        return b.mesh()
    }
    /** a flat ring in the x-y plane */
    fun ring(r0: Float, r1: Float, seg: Int): Mesh {
        val b = B()
        for (i in 0 until seg) {
            val a0 = (i * 2 * PI / seg).toFloat(); val a1 = ((i + 1) * 2 * PI / seg).toFloat()
            val p0 = p(cos(a0) * r0, sin(a0) * r0, 0f); val p1 = p(cos(a0) * r1, sin(a0) * r1, 0f)
            val p2 = p(cos(a1) * r1, sin(a1) * r1, 0f); val p3 = p(cos(a1) * r0, sin(a1) * r0, 0f)
            b.flat(p0, p1, p2); b.flat(p0, p2, p3)
        }
        return b.mesh()
    }
    /** a five-pointed star, puffed out to a little depth */
    fun star(): Mesh {
        val b = B(); val d = .1f
        val pts = Array(10) { i -> val r = if (i % 2 == 1) .22f else .55f; val a = (i * PI / 5 + PI / 2).toFloat(); floatArrayOf(cos(a) * r, sin(a) * r) }
        for (i in 0 until 10) {
            val a = pts[i]; val c = pts[(i + 1) % 10]
            b.flat(p(0f, 0f, d * 1.8f), p(a[0], a[1], d), p(c[0], c[1], d))
            b.flat(p(0f, 0f, -d * 1.8f), p(c[0], c[1], -d), p(a[0], a[1], -d))
            b.flat(p(a[0], a[1], d), p(a[0], a[1], -d), p(c[0], c[1], -d)); b.flat(p(a[0], a[1], d), p(c[0], c[1], -d), p(c[0], c[1], d))
        }
        return b.mesh()
    }
    private val icoV: Array<FloatArray> by lazy {
        val t = ((1 + sqrt(5.0)) / 2).toFloat()
        arrayOf(p(-1f, t, 0f), p(1f, t, 0f), p(-1f, -t, 0f), p(1f, -t, 0f), p(0f, -1f, t), p(0f, 1f, t), p(0f, -1f, -t), p(0f, 1f, -t), p(t, 0f, -1f), p(t, 0f, 1f), p(-t, 0f, -1f), p(-t, 0f, 1f)).map { norm3(it[0], it[1], it[2]) }.toTypedArray()
    }
    private val icoF = arrayOf(intArrayOf(0, 11, 5), intArrayOf(0, 5, 1), intArrayOf(0, 1, 7), intArrayOf(0, 7, 10), intArrayOf(0, 10, 11), intArrayOf(1, 5, 9), intArrayOf(5, 11, 4), intArrayOf(11, 10, 2), intArrayOf(10, 7, 6), intArrayOf(7, 1, 8),
        intArrayOf(3, 9, 4), intArrayOf(3, 4, 2), intArrayOf(3, 2, 6), intArrayOf(3, 6, 8), intArrayOf(3, 8, 9), intArrayOf(4, 9, 5), intArrayOf(2, 4, 11), intArrayOf(6, 2, 10), intArrayOf(8, 6, 7), intArrayOf(9, 8, 1))
    /** a lumpy asteroid with flat faces */
    fun rock(detail: Boolean): Mesh {
        val v = ArrayList(icoV.map { it.clone() })
        var f = icoF.map { it.clone() }
        if (detail) {
            val cache = HashMap<Int, Int>()
            fun mid(a: Int, c: Int): Int {
                val key = min(a, c) * 1000 + max(a, c)
                return cache.getOrPut(key) { val m = norm3((v[a][0] + v[c][0]) / 2, (v[a][1] + v[c][1]) / 2, (v[a][2] + v[c][2]) / 2); v.add(m); v.size - 1 }
            }
            f = f.flatMap { (a, bb, c) -> val ab = mid(a, bb); val bc = mid(bb, c); val ca = mid(c, a)
                listOf(intArrayOf(a, ab, ca), intArrayOf(bb, bc, ab), intArrayOf(c, ca, bc), intArrayOf(ab, bc, ca)) }
        }
        for (q in v) { val k = .75f + Random.nextFloat() * .38f; q[0] *= k; q[1] *= k; q[2] *= k }
        val b = B()
        for (q in f) b.flat(v[q[0]], v[q[1]], v[q[2]])
        return b.mesh()
    }
    /** the edges of an icosahedron, as lines (the power-up cage) */
    fun cage(r: Float): Mesh {
        val b = B(); val seen = HashSet<Int>()
        for (q in icoF) for (k in 0 until 3) {
            val a = q[k]; val c = q[(k + 1) % 3]; val key = min(a, c) * 100 + max(a, c)
            if (seen.add(key)) { val pa = icoV[a]; val pc = icoV[c]; b.v(pa[0] * r, pa[1] * r, pa[2] * r, 0f, 1f, 0f); b.v(pc[0] * r, pc[1] * r, pc[2] * r, 0f, 1f, 0f) }
        }
        return b.mesh(GLES20.GL_LINES)
    }
    fun norm3(x: Float, y: Float, z: Float): FloatArray { val l = sqrt(x * x + y * y + z * z).takeIf { it > 0f } ?: 1f; return floatArrayOf(x / l, y / l, z / l) }
}

/** Asks for 4x anti-aliasing on tablets, and falls back if the phone can't. */
class MsaaChooser(private val samples: Int) : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        val tries = mutableListOf<IntArray>()
        if (samples > 0) tries += intArrayOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_DEPTH_SIZE, 16,
            EGL10.EGL_RENDERABLE_TYPE, 4, EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, samples, EGL10.EGL_NONE)
        tries += intArrayOf(EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_RENDERABLE_TYPE, 4, EGL10.EGL_NONE)
        tries += intArrayOf(EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_RENDERABLE_TYPE, 4, EGL10.EGL_NONE)
        for (a in tries) {
            val out = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
            if (egl.eglChooseConfig(display, a, out, 1, n) && n[0] > 0 && out[0] != null) return out[0]!!
        }
        throw IllegalStateException("No OpenGL ES 2 config")
    }
}

class RushGL(private val rich: Boolean, private val host: RushHost) : GLSurfaceView.Renderer {
    private class Zone(val name: String, val bg: Int, val zap: Int, val dust: Int, val planet: Int)
    private val zones = listOf(
        Zone("Launch Bay", 0x0B1030, 0x38BDF8, 0xC7D2FE, 0x6366F1),
        Zone("Asteroid Belt", 0x1F120A, 0xFB923C, 0xFDE68A, 0xB45309),
        Zone("Pink Nebula", 0x2A0A2E, 0xF472B6, 0xFBCFE8, 0xDB2777),
        Zone("Ion Storm", 0x04201F, 0x2DD4BF, 0x99F6E4, 0x0D9488),
        Zone("Deep Space", 0x03030C, 0xA78BFA, 0xFFFFFF, 0x7C3AED)
    )
    private enum class Kind { STAR, ZAP, ROCK, WALL, PU }
    private enum class Pu { SHIELD, MAGNET, BOOST }
    private class Thing(val k: Kind, var x: Float, var y: Float, var z: Float) {
        var len = 0f; var a = 0f; var spin = 0f; var r = 0f; var vx = 0f; var vy = 0f; var rx = 0f; var ry = 0f
        var rotX = 0f; var rotY = 0f; var hx = 0f; var hy = 0f; var hs = 0f; var t = 0f; var pu = Pu.SHIELD; var dead = false; var mesh = 0
    }

    // the rules
    private val HW = 6f; private val HH = 3.5f; private val RR = .55f; private val U = 1.5f; private val VIEW = 170f; private val ZONE_LEN = 1500

    // ---- read by the HUD (GameView) ----
    @Volatile var meters = 0; private set
    @Volatile var stars = 0; private set
    @Volatile var puText = ""; private set
    @Volatile var flying = false; private set
    @Volatile var paused = false
    // ---- set by touch ----
    @Volatile var tx = 0f
    @Volatile var ty = 0f
    @Volatile var keyX = 0f
    @Volatile var keyY = 0f

    private var rx = 0f; private var ry = 0f; private var vx = 0f; private var vy = 0f
    private var dead = false
    private var dist = 0f; private var speed = 16.1f; private var zoneIx = 0; private var shake = 0f
    private val things = ArrayList<Thing>()
    private var nextZ = 60f
    private var shieldOn = false; private var magnetT = 0f; private var boostT = 0f; private var invulnT = 0f; private var puGap = 300f
    private var bank = 0f; private var pitch = 0f
    private var shockAge = -1f; private var shockX = 0f; private var shockY = 0f
    private var lastNs = 0L
    private var clock = 0f
    private val bg = floatArrayOf(.04f, .06f, .19f); private val bgFrom = FloatArray(3); private val bgTo = FloatArray(3); private var bgMix = 1f
    private var zapCol = rgb(0x38BDF8); private var dustCol = rgb(0xC7D2FE); private var planetCol = rgb(0x6366F1)

    // particles (pool)
    private val PN = if (rich) 900 else 350
    private val pX = FloatArray(PN); private val pY = FloatArray(PN); private val pZ = FloatArray(PN)
    private val pVX = FloatArray(PN); private val pVY = FloatArray(PN); private val pVZ = FloatArray(PN)
    private val pT = FloatArray(PN); private val pT0 = FloatArray(PN); private val pC = FloatArray(PN * 3); private var pAlive = 0
    // dust
    private val DN = if (rich) 1600 else 600
    private val dust = FloatArray(DN * 3)
    private val SKY = if (rich) 500 else 250
    private val sky = FloatArray(SKY * 7)
    // the points drawn each frame: x y z r g b size
    private val MAXPTS = PN + DN + 200
    private val pts = FloatArray(MAXPTS * 7)
    private val ptsBuf: FloatBuffer = ByteBuffer.allocateDirect(MAXPTS * 7 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val skyBuf: FloatBuffer = ByteBuffer.allocateDirect(SKY * 7 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    // shapes
    private val mBox = Shapes.box()
    private val mCyl = Shapes.cylinder(1f, 1f, 1f, if (rich) 12 else 8, caps = false)
    private val mBody = Shapes.cylinder(.5f, .42f, 1.5f, if (rich) 24 else 14)
    private val mNose = Shapes.cylinder(.42f, 0f, .9f, if (rich) 24 else 14)
    private val mTail = Shapes.cylinder(.3f, .36f, .28f, 14)
    private val mFlame = Shapes.cylinder(.3f, 0f, 1f, 12, caps = false)
    private val mSphere = Shapes.sphere(1f, if (rich) 20 else 12, if (rich) 14 else 8)
    private val mStar = Shapes.star()
    private val mRocks = List(4) { Shapes.rock(rich) }
    private val mCage = Shapes.cage(.75f)
    private val mTorus = Shapes.torus(1f, .035f, 40, 6)
    private val mMagnetIcon = Shapes.torus(.36f, .12f, 16, 8, (PI * 1.3).toFloat())
    private val mRing = Shapes.ring(.9f, 1f, 48)
    private val mPlanetRing = Shapes.ring(1.3f, 1.85f, 64)
    private val meshes get() = listOf(mBox, mCyl, mBody, mNose, mTail, mFlame, mSphere, mStar, mCage, mTorus, mMagnetIcon, mRing, mPlanetRing) + mRocks

    // GL bits
    private var litProg = 0; private var ptProg = 0
    private var aPos = 0; private var aNor = 0; private var uP = 0; private var uMV = 0; private var uN = 0
    private var uCol = 0; private var uEm = 0; private var uLit = 0; private var uAlpha = 0; private var uFog = 0; private var uFogR = 0; private var uAdd = 0; private var uL1 = 0; private var uL2 = 0
    private var pPos = 0; private var pCol = 0; private var pP = 0; private var pV = 0; private var pSize = 0; private var pFogR = 0
    private val proj = FloatArray(16); private val view = FloatArray(16); private val model = FloatArray(16); private val mv = FloatArray(16)
    private val tmp = FloatArray(16); private val inv = FloatArray(16); private val nrm = FloatArray(9); private val stack = FloatArray(16 * 8)
    private var vw = 1; private var vh = 1; private var pxPerUnit = 500f
    private val l1 = Shapes.norm3(.45f, .75f, .5f); private val l2 = Shapes.norm3(-.5f, -.2f, -.8f)

    init {
        for (i in 0 until DN) { dust[i * 3] = rnd(-80f, 80f); dust[i * 3 + 1] = rnd(-50f, 50f); dust[i * 3 + 2] = -rnd(0f, 420f) + 20f }
        for (i in 0 until SKY) {
            val th = rnd(0f, 6.283f); val ph = rnd(-1f, 1f); val r = 330f
            val c = sqrt(1 - ph * ph)
            sky[i * 7] = cos(th) * c * r; sky[i * 7 + 1] = ph * r * .7f; sky[i * 7 + 2] = -abs(sin(th) * c * r) - 40f
            val b = rnd(.35f, 1f); sky[i * 7 + 3] = b; sky[i * 7 + 4] = b; sky[i * 7 + 5] = b; sky[i * 7 + 6] = rnd(.8f, 2.2f)
        }
        skyBuf.put(sky).position(0)
        reset(); setZone(0, false)
    }

    // ---------------- helpers ----------------
    private fun rnd(a: Float, b: Float) = a + Random.nextFloat() * (b - a)
    private fun rgb(c: Int) = floatArrayOf(((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f)
    private fun diff() = min(1f, meters / 4000f)
    private fun speedNow() = speed * (if (boostT > 0) 2.1f else 1f)
    private fun zoneOf(i: Int) = zones[i % zones.size]

    // ---------------- run control (called on the GL thread) ----------------
    fun reset() {
        rx = 0f; ry = 0f; tx = 0f; ty = 0f; vx = 0f; vy = 0f
        dist = 0f; speed = 16.1f; stars = 0; meters = 0; shake = 0f
        things.clear(); nextZ = 60f; pAlive = 0
        shieldOn = false; magnetT = 0f; boostT = 0f; invulnT = 1.2f; puGap = rnd(225f, 375f)
        shockAge = -1f; dead = false; puText = ""
        if (zoneIx != 0) setZone(0, false)
    }
    fun startRun() { reset(); flying = true; paused = false; setZone(0, true) }
    fun stopRun(why: String) { if (flying) die(why) }

    private fun setZone(ix: Int, banner: Boolean) {
        zoneIx = ix
        val z = zoneOf(ix)
        bgFrom[0] = bg[0]; bgFrom[1] = bg[1]; bgFrom[2] = bg[2]
        val t = rgb(z.bg); bgTo[0] = t[0]; bgTo[1] = t[1]; bgTo[2] = t[2]
        bgMix = if (banner) 0f else 1f
        if (!banner) { bg[0] = t[0]; bg[1] = t[1]; bg[2] = t[2] }
        zapCol = rgb(z.zap); dustCol = rgb(z.dust); planetCol = rgb(z.planet)
        if (banner) host.zone(ix, z.name, z.zap or 0xFF000000.toInt())
    }

    // ---------------- making things ----------------
    private fun add(t: Thing) = t.also { things.add(it) }
    private fun addStar(x: Float, y: Float, z: Float) { add(Thing(Kind.STAR, x, y, z).apply { t = rnd(0f, 6f) }) }
    private fun starLine(z: Float, x: Float, y: Float, n: Int): Float { for (i in 0 until n) addStar(x, y, z + i * 3); return n * 3f }
    private fun starWave(z: Float, n: Int): Float { val cx = rnd(-HW + 3, HW - 3); val cy = rnd(-HH + 1, HH - 1); for (i in 0 until n) addStar(cx + sin(i / 1.6f) * 3, cy, z + i * 3); return n * 3f }
    private fun starSpiral(z: Float, n: Int): Float { val cx = rnd(-HW + 3, HW - 3); val cy = rnd(-1f, 1f); for (i in 0 until n) addStar(cx + cos(i * .7f) * 2.2f, cy + sin(i * .7f) * 2.2f, z + i * 2.6f); return n * 2.6f }
    private fun zapper(z: Float, x: Float, y: Float, len: Float, ang: Float, spin: Float) { add(Thing(Kind.ZAP, x, y, z).apply { this.len = len; a = ang; this.spin = spin }) }
    private fun rock(z: Float, x: Float, y: Float, r: Float) {
        val dd = .4f + diff()
        add(Thing(Kind.ROCK, x, y, z).apply { this.r = r; vx = rnd(-2f, 2f) * dd; vy = rnd(-1.4f, 1.4f) * dd; rx = rnd(-1f, 1f); ry = rnd(-1f, 1f); rotX = rnd(0f, 6f); rotY = rnd(0f, 6f); mesh = Random.nextInt(4) })
    }
    private fun wall(z: Float, hx: Float, hy: Float, hs: Float) { add(Thing(Kind.WALL, 0f, 0f, z).apply { this.hx = hx; this.hy = hy; this.hs = hs }) }
    private fun power(z: Float, x: Float, y: Float) { add(Thing(Kind.PU, x, y, z).apply { pu = Pu.values()[Random.nextInt(3)] }) }

    /** one "chunk" of space at a time, out ahead of the rocket */
    private fun spawnChunk(z: Float): Float {
        val d = diff(); val roll = Random.nextFloat(); var w: Float
        if (puGap <= 0) { power(z + 6, rnd(-HW + 2, HW - 2), rnd(-HH + 1, HH - 1)); puGap = rnd(225f, 375f); return 22f }
        if (roll < .18f) {
            val k = Random.nextFloat()
            w = if (k < .4f) starLine(z, rnd(-HW + 1.5f, HW - 1.5f), rnd(-HH + 1, HH - 1), 8) else if (k < .75f) starWave(z, 9) else starSpiral(z, 10)
        } else if (roll < .46f) {
            val kind = Random.nextFloat(); val len: Float; val x: Float; val y: Float; val a: Float
            if (kind < .35f) { len = rnd(5f, 7 + d * 3); a = 0f; x = rnd(-HW + len / 3, HW - len / 3); y = rnd(-HH + .6f, HH - .6f) }
            else if (kind < .7f) { len = rnd(4.5f, 6 + d * 1.5f); a = (PI / 2).toFloat(); x = rnd(-HW + 1, HW - 1); y = rnd(-1f, 1f) }
            else { len = rnd(6f, 8 + d * 3); a = floatArrayOf(.6f, -.6f, .9f, -.9f)[Random.nextInt(4)]; x = rnd(-3f, 3f); y = rnd(-1.2f, 1.2f) }
            val spin = if (d > .3f && Random.nextFloat() < d * .55f) rnd(.5f, 1.2f) * (if (Random.nextBoolean()) -1 else 1) else 0f
            zapper(z + 8, x, y, len, a, spin)
            if (Random.nextFloat() < .7f) starLine(z + 2, if (x > 0) x - 4 else x + 4, if (y > 0) y - 2 else y + 2, 5)
            w = 24 - d * 5
        } else if (roll < .66f) {
            val hs = 2.2f - d * .65f; val hx = rnd(-HW + hs + .3f, HW - hs - .3f); val hy = rnd(-HH + hs + .2f, HH - hs - .2f)
            wall(z + 10, hx, hy, hs); starLine(z + 1, hx, hy, 3)
            w = 26 - d * 5
        } else if (roll < .86f) {
            val n = 3 + (d * 3 + Random.nextFloat() * 2).toInt()
            var zz = z + 6
            for (i in 0 until n) { rock(zz, rnd(-HW + 1, HW - 1), rnd(-HH + .8f, HH - .8f), rnd(.85f, 1.35f + d * .5f)); zz += rnd(5f, 8f) }
            w = 10 + n * 7f
        } else {
            val gap = 2.7f - d * .6f
            if (Random.nextBoolean()) {
                val cy = rnd(-HH + gap / 2 + .5f, HH - gap / 2 - .5f)
                zapper(z + 8, 0f, cy + gap / 2 + .2f, HW * 2 + 1, 0f, 0f); zapper(z + 8, 0f, cy - gap / 2 - .2f, HW * 2 + 1, 0f, 0f)
                addStar(rnd(-3f, 3f), cy, z + 8)
            } else {
                val cx = rnd(-HW + gap / 2 + 1, HW - gap / 2 - 1); val v = (PI / 2).toFloat()
                zapper(z + 8, cx + gap / 2 + .2f, 0f, HH * 2 + 1, v, 0f); zapper(z + 8, cx - gap / 2 - .2f, 0f, HH * 2 + 1, v, 0f)
                addStar(cx, rnd(-1.5f, 1.5f), z + 8)
            }
            w = 24f
        }
        return w + rnd(6f, 11f) * (1.15f - d * .5f)
    }

    // ---------------- collisions ----------------
    private fun segDist(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
        val t = (if (l2 > 0) ((px - ax) * dx + (py - ay) * dy) / l2 else 0f).coerceIn(0f, 1f)
        val qx = ax + t * dx - px; val qy = ay + t * dy - py
        return sqrt(qx * qx + qy * qy)
    }
    private fun hits(t: Thing): Boolean {
        val rel = t.z - dist
        return when (t.k) {
            Kind.ZAP -> { if (rel < -1.1f || rel > 1.2f) false else { val c = cos(t.a) * t.len / 2; val s = sin(t.a) * t.len / 2; segDist(rx, ry, t.x - c, t.y - s, t.x + c, t.y + s) < RR + .2f } }
            Kind.WALL -> { if (rel < -1.1f || rel > 1.2f) false else { val m = t.hs - RR * .8f; abs(rx - t.hx) > m || abs(ry - t.hy) > m } }
            Kind.ROCK -> { val dz = rel.coerceIn(-.9f, .9f); val ex = t.x - rx; val ey = t.y - ry; val ez = rel - dz; val k = t.r * .82f + RR * .8f; ex * ex + ey * ey + ez * ez < k * k }
            else -> false
        }
    }
    private fun spark(x: Float, y: Float, z: Float, vx: Float, vy: Float, vz: Float, life: Float, c: Int) {
        if (pAlive >= PN) return
        val i = pAlive++
        pX[i] = x; pY[i] = y; pZ[i] = z; pVX[i] = vx; pVY[i] = vy; pVZ[i] = vz; pT[i] = life; pT0[i] = life
        pC[i * 3] = ((c shr 16) and 255) / 255f; pC[i * 3 + 1] = ((c shr 8) and 255) / 255f; pC[i * 3 + 2] = (c and 255) / 255f
    }
    private fun burst(x: Float, y: Float, z: Float, c: Int, n: Int, sp: Float) {
        val count = (n * PN / 600f).toInt()
        for (i in 0 until count) {
            val u = Random.nextFloat() * 2 - 1; val a = Random.nextFloat() * 6.283f; val s = sqrt(1 - u * u); val v = sp * (.3f + Random.nextFloat() * .7f)
            spark(x, y, z, cos(a) * s * v, sin(a) * s * v, u * v + speedNow() * .7f, .5f + Random.nextFloat() * .5f, c)
        }
    }
    private fun hit(t: Thing) {
        if (boostT > 0 || invulnT > 0) {
            if (boostT > 0 && t.k == Kind.ROCK) { t.dead = true; burst(t.x, t.y, t.z, 0xD6D3D1, 30, 9f); shake = .25f }
            return
        }
        if (shieldOn) {
            shieldOn = false; invulnT = 1.2f; shake = .45f; host.buzz(true)
            burst(rx, ry, dist, 0x60A5FA, 40, 10f)
            if (t.k == Kind.ROCK || t.k == Kind.ZAP) { t.dead = true; burst(t.x, t.y, t.z, 0x9CA3AF, 30, 8f) }
            host.pop("🛡 Shield saved you!", 0xFF93C5FD.toInt())
            return
        }
        die(when (t.k) { Kind.ZAP -> "Zapped!"; Kind.ROCK -> "Hit an asteroid!"; else -> "Hit a wall!" })
    }
    private fun die(why: String) {
        if (!flying) return
        flying = false; dead = true; shake = .9f; host.buzz(true)
        burst(rx, ry, dist, 0xFBBF24, 90, 14f); burst(rx, ry, dist, 0xF43F5E, 60, 10f); burst(rx, ry, dist, 0xE5E7EB, 40, 6f)
        if (rich) { shockAge = 0f; shockX = rx; shockY = ry }
        host.died(why, meters + stars * 5, meters, stars, zoneIx + 1)
    }
    private fun grab(t: Thing) {
        val c = when (t.pu) { Pu.SHIELD -> 0x60A5FA; Pu.MAGNET -> 0xF472B6; Pu.BOOST -> 0xF97316 }
        burst(t.x, t.y, t.z, c, 40, 8f); host.buzz(false)
        when (t.pu) {
            Pu.SHIELD -> { shieldOn = true; host.pop("🛡 Shield!", 0xFF93C5FD.toInt()) }
            Pu.MAGNET -> { magnetT = 8f; host.pop("🧲 Magnet!", 0xFFF9A8D4.toInt()) }
            Pu.BOOST -> { boostT = 3f; host.pop("🔥 Boost!", 0xFFFDBA74.toInt()) }
        }
    }

    // ---------------- one step of the world ----------------
    private fun step(dt: Float) {
        if (invulnT > 0) invulnT -= dt
        if (magnetT > 0) magnetT -= dt
        if (boostT > 0) { boostT -= dt; if (boostT <= 0) invulnT = .8f }
        val target = (430 + min(520f, meters * .13f)) / 40 * U           // the same pace as every version
        speed += (target - speed) * min(1f, dt * .5f)
        val mvd = speedNow() * dt
        dist += mvd; puGap -= mvd
        meters = (dist / U).toInt()
        while (nextZ < dist + VIEW) nextZ += spawnChunk(nextZ)
        val z = meters / ZONE_LEN
        if (z != zoneIx) setZone(z, true)

        if (keyX != 0f || keyY != 0f) { tx = (tx + keyX * 13 * dt).coerceIn(-HW, HW); ty = (ty + keyY * 11 * dt).coerceIn(-HH, HH) }
        var ax = (tx - rx) * 9; var ay = (ty - ry) * 9
        val l = sqrt(ax * ax + ay * ay); val maxV = 17f
        if (l > maxV) { ax *= maxV / l; ay *= maxV / l }
        vx += (ax - vx) * min(1f, dt * 12); vy += (ay - vy) * min(1f, dt * 12)
        rx = (rx + vx * dt).coerceIn(-HW, HW); ry = (ry + vy * dt).coerceIn(-HH, HH)

        var i = things.size - 1
        while (i >= 0) {
            val t = things[i]
            when (t.k) {
                Kind.ROCK -> {
                    t.x += t.vx * dt; t.y += t.vy * dt; t.rotX += t.rx * dt; t.rotY += t.ry * dt
                    if (abs(t.x) > HW + .5f) t.vx = -t.vx
                    if (abs(t.y) > HH + .3f) t.vy = -t.vy
                    if (!t.dead && hits(t)) hit(t)
                }
                Kind.ZAP -> { if (t.spin != 0f) t.a += t.spin * dt; if (!t.dead && hits(t)) hit(t) }
                Kind.WALL -> if (hits(t)) hit(t)
                Kind.STAR -> {
                    t.t += dt * 3
                    val rel = t.z - dist
                    if (magnetT > 0 && rel < 26 && rel > -2) {
                        val dx = rx - t.x; val dy = ry - t.y; val dz = -rel; val dd = max(.001f, sqrt(dx * dx + dy * dy + dz * dz))
                        t.x += dx / dd * 30 * dt; t.y += dy / dd * 30 * dt; t.z += dz / dd * 30 * dt
                    }
                    val cx = t.x - rx; val cy = t.y - ry; val cz = t.z - dist
                    val extra = if (cz < 0 && cz > -mvd - 1) 1f else 0f
                    if (cx * cx + cy * cy + cz * cz < 1.45f * 1.45f + extra) { stars++; burst(t.x, t.y, t.z, 0xFDE047, 14, 5f); t.dead = true }
                }
                Kind.PU -> {
                    t.t += dt
                    val px = t.x - rx; val py = t.y - ry; val pz = t.z - dist
                    if (px * px + py * py + pz * pz < 2.1f * 2.1f) { grab(t); t.dead = true }
                }
            }
            if (!flying) return
            if (t.dead || t.z < dist - 12) things.removeAt(i)
            i--
        }
        // HUD text
        val parts = ArrayList<String>(3)
        if (shieldOn) parts.add("🛡")
        if (magnetT > 0) parts.add("🧲 ${kotlin.math.ceil(magnetT).toInt()}")
        if (boostT > 0) parts.add("🔥 ${kotlin.math.ceil(boostT).toInt()}")
        puText = parts.joinToString("  ")
    }

    private fun stepFx(dt: Float) {
        var i = pAlive - 1
        while (i >= 0) {
            pT[i] -= dt
            if (pT[i] <= 0) {
                val j = --pAlive
                pX[i] = pX[j]; pY[i] = pY[j]; pZ[i] = pZ[j]; pVX[i] = pVX[j]; pVY[i] = pVY[j]; pVZ[i] = pVZ[j]; pT[i] = pT[j]; pT0[i] = pT0[j]
                pC[i * 3] = pC[j * 3]; pC[i * 3 + 1] = pC[j * 3 + 1]; pC[i * 3 + 2] = pC[j * 3 + 2]
            } else {
                pX[i] += pVX[i] * dt; pY[i] += pVY[i] * dt; pZ[i] += pVZ[i] * dt; pVX[i] *= .985f; pVY[i] *= .985f
            }
            i--
        }
        if (shake > 0) shake = max(0f, shake - 1.6f * dt)
        if (bgMix < 1) { bgMix = min(1f, bgMix + dt * .6f); for (k in 0..2) bg[k] = bgFrom[k] + (bgTo[k] - bgFrom[k]) * bgMix }
        if (shockAge >= 0) { shockAge += dt; if (shockAge > .9f) shockAge = -1f }
    }

    // ---------------- GL ----------------
    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        litProg = program(LIT_VS, LIT_FS); ptProg = program(PT_VS, PT_FS)
        aPos = GLES20.glGetAttribLocation(litProg, "aPos"); aNor = GLES20.glGetAttribLocation(litProg, "aNor")
        uP = GLES20.glGetUniformLocation(litProg, "uP"); uMV = GLES20.glGetUniformLocation(litProg, "uMV"); uN = GLES20.glGetUniformLocation(litProg, "uN")
        uCol = GLES20.glGetUniformLocation(litProg, "uCol"); uEm = GLES20.glGetUniformLocation(litProg, "uEm"); uLit = GLES20.glGetUniformLocation(litProg, "uLit")
        uAlpha = GLES20.glGetUniformLocation(litProg, "uAlpha"); uFog = GLES20.glGetUniformLocation(litProg, "uFog"); uFogR = GLES20.glGetUniformLocation(litProg, "uFogR")
        uAdd = GLES20.glGetUniformLocation(litProg, "uAdd"); uL1 = GLES20.glGetUniformLocation(litProg, "uL1"); uL2 = GLES20.glGetUniformLocation(litProg, "uL2")
        pPos = GLES20.glGetAttribLocation(ptProg, "aPos"); pCol = GLES20.glGetAttribLocation(ptProg, "aCol")
        pP = GLES20.glGetUniformLocation(ptProg, "uP"); pV = GLES20.glGetUniformLocation(ptProg, "uV"); pSize = GLES20.glGetUniformLocation(ptProg, "uSize"); pFogR = GLES20.glGetUniformLocation(ptProg, "uFogR")
        for (m in meshes) m.upload()
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        lastNs = 0L
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        vw = max(1, w); vh = max(1, h)
        GLES20.glViewport(0, 0, vw, vh)
        val aspect = vw.toFloat() / vh
        val fov = min(100.0, Math.toDegrees(2 * atan(tan(Math.toRadians(35.0)) * max(1.0, 1.45 / aspect)))).toFloat()
        Matrix.perspectiveM(proj, 0, fov, aspect, .1f, 420f)
        pxPerUnit = vh / (2 * tan(Math.toRadians(fov / 2.0)).toFloat())
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 1 / 60f else min(.05f, (now - lastNs) / 1e9f)
        lastNs = now
        clock = (clock + dt) % 3600f
        val before = dist
        if (flying && !paused) step(dt)
        else if (!flying && !dead) {
            dist += 14 * dt
            rx = sin(clock / .9f) * 1.2f; ry = sin(clock / .65f) * .6f; vx = cos(clock / .9f) * 1.3f; vy = 0f
        }
        if (!paused) {
            if (!dead && (flying || !paused)) {
                val ne = max(1, ((if (boostT > 0) 5 else 2) * PN / 600f).toInt())
                for (e in 0 until ne) spark(rx + rnd(-.15f, .15f), ry + rnd(-.15f, .15f), dist - 1.3f, rnd(-1f, 1f) - vx * .3f, rnd(-1f, 1f) - vy * .3f,
                    speedNow() - rnd(10f, 16f), rnd(.2f, .4f), if (boostT > 0) 0xF97316 else intArrayOf(0xFBBF24, 0xF97316, 0xFDE68A)[Random.nextInt(3)])
            }
            stepFx(dt)
        }
        render(dt, dist - before)
    }

    // ---- drawing helpers ----
    private var sp = 0
    private fun push() { System.arraycopy(model, 0, stack, sp * 16, 16); sp++ }
    private fun pop() { sp--; System.arraycopy(stack, sp * 16, model, 0, 16) }
    private fun draw(m: Mesh, c: FloatArray, em: FloatArray? = null, lit: Boolean = true, alpha: Float = 1f, add: Boolean = false) {
        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        GLES20.glUniformMatrix4fv(uMV, 1, false, mv, 0)
        if (lit) {
            Matrix.invertM(inv, 0, model, 0); Matrix.transposeM(tmp, 0, inv, 0)
            nrm[0] = tmp[0]; nrm[1] = tmp[1]; nrm[2] = tmp[2]; nrm[3] = tmp[4]; nrm[4] = tmp[5]; nrm[5] = tmp[6]; nrm[6] = tmp[8]; nrm[7] = tmp[9]; nrm[8] = tmp[10]
            GLES20.glUniformMatrix3fv(uN, 1, false, nrm, 0)
        }
        GLES20.glUniform3f(uCol, c[0], c[1], c[2])
        if (em != null) GLES20.glUniform3f(uEm, em[0], em[1], em[2]) else GLES20.glUniform3f(uEm, 0f, 0f, 0f)
        GLES20.glUniform1f(uLit, if (lit) 1f else 0f)
        GLES20.glUniform1f(uAlpha, alpha)
        GLES20.glUniform1f(uAdd, if (add) 1f else 0f)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, m.vbo)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 24, 0)
        GLES20.glVertexAttribPointer(aNor, 3, GLES20.GL_FLOAT, false, 24, 12)
        GLES20.glDrawArrays(m.mode, 0, m.count)
    }
    private fun T(x: Float, y: Float, z: Float) = Matrix.translateM(model, 0, x, y, z)
    private fun Rx(a: Float) = Matrix.rotateM(model, 0, Math.toDegrees(a.toDouble()).toFloat(), 1f, 0f, 0f)
    private fun Ry(a: Float) = Matrix.rotateM(model, 0, Math.toDegrees(a.toDouble()).toFloat(), 0f, 1f, 0f)
    private fun Rz(a: Float) = Matrix.rotateM(model, 0, Math.toDegrees(a.toDouble()).toFloat(), 0f, 0f, 1f)
    private fun S(x: Float, y: Float, z: Float) = Matrix.scaleM(model, 0, x, y, z)
    private fun blend(add: Boolean) {
        if (add) { GLES20.glEnable(GLES20.GL_BLEND); GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE); GLES20.glDepthMask(false) }
        else { GLES20.glDisable(GLES20.GL_BLEND); GLES20.glDepthMask(true) }
    }

    private val WHITE = floatArrayOf(.97f, .98f, 1f); private val RED = rgb(0xE11D48); private val STEEL = rgb(0x475569)
    private val GLASS = rgb(0x38BDF8); private val GLASS_EM = floatArrayOf(.05f, .15f, .45f)
    private val GOLD = rgb(0xFDE047); private val GOLD_EM = floatArrayOf(.45f, .3f, 0f)
    private val ROCK = rgb(0x8B8178); private val WALL = rgb(0x5B6573); private val WALL_EM = floatArrayOf(.04f, .05f, .08f)
    private val POD = rgb(0x1F2937); private val FLAME = rgb(0xFBBF24); private val FLAME_IN = rgb(0xFFF7ED); private val BOOST = rgb(0xF97316)
    private val SHIELD = rgb(0x60A5FA); private val SHIELD_W = rgb(0x93C5FD); private val PINK = rgb(0xF472B6)
    private fun puCol(p: Pu) = when (p) { Pu.SHIELD -> SHIELD; Pu.MAGNET -> PINK; Pu.BOOST -> BOOST }

    private fun render(dt: Float, mvd: Float) {
        GLES20.glClearColor(bg[0], bg[1], bg[2], 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        // camera
        val sx = if (shake > 0) rnd(-shake, shake) else 0f; val sy = if (shake > 0) rnd(-shake, shake) else 0f
        if (!flying && !dead) {
            val a = clock / 4
            Matrix.setLookAtM(view, 0, sin(a) * 4.5f, 1.6f + sin(a * 1.3f), 6.5f + cos(a) * 1.5f, 0f, 0f, -2f, 0f, 1f, 0f)
        } else {
            Matrix.setLookAtM(view, 0, rx * .55f + sx, ry * .55f + 2.3f + sy, 7.2f - (if (boostT > 0) 1f else 0f), rx * .75f, ry * .75f - .2f, -14f, 0f, 1f, 0f)
        }

        // far stars (no fog)
        GLES20.glUseProgram(ptProg)
        GLES20.glUniformMatrix4fv(pP, 1, false, proj, 0)
        GLES20.glUniformMatrix4fv(pV, 1, false, view, 0)
        GLES20.glUniform1f(pSize, pxPerUnit * .9f)
        GLES20.glUniform2f(pFogR, 0f, 0f)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        blend(true); GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        drawPoints(skyBuf, SKY)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        GLES20.glUseProgram(litProg)
        GLES20.glUniformMatrix4fv(uP, 1, false, proj, 0)
        GLES20.glUniform3f(uL1, l1[0], l1[1], l1[2]); GLES20.glUniform3f(uL2, l2[0], l2[1], l2[2])
        GLES20.glEnableVertexAttribArray(aPos); GLES20.glEnableVertexAttribArray(aNor)
        blend(false)

        // the planet, far away (no fog)
        GLES20.glUniform2f(uFogR, 0f, 0f)
        Matrix.setIdentityM(model, 0); T(150f, 85f, -330f); Ry(clock * .02f); S(42f, 42f, 42f)
        draw(mSphere, planetCol, floatArrayOf(planetCol[0] * .12f, planetCol[1] * .12f, planetCol[2] * .12f))
        GLES20.glUniform3f(uFog, bg[0], bg[1], bg[2]); GLES20.glUniform2f(uFogR, 70f, VIEW + 5)

        // things (solid parts)
        for (t in things) {
            val rz = -(t.z - dist)
            Matrix.setIdentityM(model, 0); T(t.x, t.y, rz)
            when (t.k) {
                Kind.STAR -> { Ry(t.t); draw(mStar, GOLD, GOLD_EM) }
                Kind.ROCK -> { Rx(t.rotX); Ry(t.rotY); S(t.r, t.r, t.r); draw(mRocks[t.mesh], ROCK) }
                Kind.ZAP -> {
                    Rz(t.a)
                    for (s in intArrayOf(-1, 1)) { push(); T(s * t.len / 2, 0f, 0f); S(.42f, .42f, .42f); draw(mSphere, POD, floatArrayOf(zapCol[0] * .5f, zapCol[1] * .5f, zapCol[2] * .5f)); pop() }
                    push(); S(t.len, .05f, .05f); Rz((PI / 2).toFloat()); draw(mCyl, floatArrayOf(1f, 1f, 1f), lit = false); pop()
                }
                Kind.WALL -> {
                    val D = .7f; val X = HW + 4; val Y = HH + 4; val hx = t.hx; val hy = t.hy; val hs = t.hs
                    fun slab(x0: Float, x1: Float, y0: Float, y1: Float) { push(); T((x0 + x1) / 2, (y0 + y1) / 2, 0f); S(x1 - x0, y1 - y0, D); draw(mBox, WALL, WALL_EM); pop() }
                    slab(-X, X, hy + hs, Y); slab(-X, X, -Y, hy - hs); slab(-X, hx - hs, hy - hs, hy + hs); slab(hx + hs, X, hy - hs, hy + hs)
                    val tt = .14f
                    for (b in arrayOf(floatArrayOf(hx, hy + hs, hs * 2 + tt, tt), floatArrayOf(hx, hy - hs, hs * 2 + tt, tt), floatArrayOf(hx + hs, hy, tt, hs * 2), floatArrayOf(hx - hs, hy, tt, hs * 2))) {
                        push(); T(b[0], b[1], D / 2 + .02f); S(b[2], b[3], .08f); draw(mBox, zapCol, lit = false); pop()
                    }
                }
                Kind.PU -> {
                    T(0f, sin(t.t * 4) * .2f, 0f); Ry(t.t * 2)
                    val c = puCol(t.pu)
                    draw(mCage, c, lit = false)
                    when (t.pu) {
                        Pu.SHIELD -> { push(); S(.42f, .42f, .42f); draw(mSphere, c, floatArrayOf(c[0] * .6f, c[1] * .6f, c[2] * .6f)); pop() }
                        Pu.MAGNET -> draw(mMagnetIcon, c, floatArrayOf(c[0] * .6f, c[1] * .6f, c[2] * .6f))
                        Pu.BOOST -> { push(); S(.35f, .8f, .35f); draw(mNose, c, floatArrayOf(c[0] * .6f, c[1] * .6f, c[2] * .6f)); pop() }
                    }
                }
            }
        }

        // the rocket
        val k = min(1f, dt * 10)
        bank += (-vx * .055f - bank) * k; pitch += (vy * .035f - pitch) * k
        val blink = flying && invulnT > 0 && (invulnT * 12).toInt() % 2 == 0
        if (!dead && !blink) {
            Matrix.setIdentityM(model, 0); T(rx, ry, 0f); Rz(bank); Rx(pitch)
            push(); Rx((PI / 2).toFloat()); draw(mBody, WHITE); pop()
            push(); T(0f, 0f, -1.2f); Rx((-PI / 2).toFloat()); draw(mNose, RED); pop()
            push(); T(0f, 0f, .88f); Rx((PI / 2).toFloat()); draw(mTail, STEEL); pop()
            push(); T(0f, .3f, -.35f); S(.2f, .14f, .2f); draw(mSphere, GLASS, GLASS_EM); pop()
            for (i in 0 until 4) {
                val a = (i * PI / 2 + PI / 4).toFloat()
                push(); T(cos(a) * .55f, sin(a) * .55f, .5f); Rz(a - (PI / 2).toFloat()); S(.06f, .55f, .7f); draw(mBox, RED); pop()
            }
        }

        // glowing, see-through parts (added on top)
        blend(true)
        Matrix.setIdentityM(model, 0)
        for (f in 0 until (if (rich) 8 else 5)) {
            val gap = 30f; val span = gap * (if (rich) 8 else 5)
            var fz = f * gap + 10
            fz += kotlin.math.floor((dist - 8 - fz) / span + 1).coerceAtLeast(0f) * span
            val w = HW + .9f; val h = HH + .9f; val th = .08f
            for (b in arrayOf(floatArrayOf(0f, h, w * 2 + th, th), floatArrayOf(0f, -h, w * 2 + th, th), floatArrayOf(w, 0f, th, h * 2), floatArrayOf(-w, 0f, th, h * 2))) {
                Matrix.setIdentityM(model, 0); T(b[0], b[1], -(fz - dist)); S(b[2], b[3], th); draw(mBox, zapCol, lit = false, alpha = .35f, add = true)
            }
        }
        for (t in things) if (t.k == Kind.ZAP) {
            val fl = rnd(.75f, 1.25f)
            Matrix.setIdentityM(model, 0); T(t.x, t.y, -(t.z - dist)); Rz(t.a); S(t.len, .16f * fl, .16f * fl); Rz((PI / 2).toFloat())
            draw(mCyl, zapCol, lit = false, alpha = .8f, add = true)
        }
        if (!dead && !blink) {
            val fs = (if (boostT > 0) 1.8f else 1f) * rnd(.85f, 1.15f)
            Matrix.setIdentityM(model, 0); T(rx, ry, 0f); Rz(bank); Rx(pitch)
            push(); T(0f, 0f, 1.0f + .7f * fs); Rx((PI / 2).toFloat()); S(1f, 1.4f * fs, 1f); draw(mFlame, if (boostT > 0) BOOST else FLAME, lit = false, alpha = .85f, add = true); pop()
            push(); T(0f, 0f, 1.0f + .45f * fs); Rx((PI / 2).toFloat()); S(.55f, .9f * fs, .55f); draw(mFlame, FLAME_IN, lit = false, alpha = .9f, add = true); pop()
            if (shieldOn) { val s = 1.25f * (1 + sin(clock * 8) * .04f); push(); S(s, s, s); draw(mSphere, SHIELD, lit = false, alpha = .22f, add = true); pop()
                push(); Ry(clock); S(1.3f, 1.3f, 1.3f); draw(mCage, SHIELD_W, lit = false, alpha = .5f, add = true); pop() }
            if (magnetT > 0) { push(); Rx(clock * 3); Ry(clock * 2); S(1.6f, 1.6f, 1.6f); draw(mTorus, PINK, lit = false, alpha = .7f, add = true); pop() }
        }
        if (shockAge >= 0) {
            val s = 1 + shockAge * 22
            Matrix.setIdentityM(model, 0); T(shockX, shockY, 0f); S(s, s, s); draw(mRing, FLAME, lit = false, alpha = 1 - shockAge / .9f, add = true)
        }
        // the planet's ring
        GLES20.glUniform2f(uFogR, 0f, 0f)
        Matrix.setIdentityM(model, 0); T(150f, 85f, -330f); Rx(1.2f); Ry(.3f); S(42f, 42f, 42f); draw(mPlanetRing, dustCol, lit = false, alpha = .3f, add = true)
        GLES20.glDisableVertexAttribArray(aPos); GLES20.glDisableVertexAttribArray(aNor)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        // dust, sparks and glows as soft points
        var n = 0
        val dz = if (mvd > 0) mvd else 0f
        val dsz = if (boostT > 0) .5f else .28f
        for (i in 0 until DN) {
            var zz = dust[i * 3 + 2] + dz; if (zz > 20) zz -= 420f; dust[i * 3 + 2] = zz
            if (zz < -VIEW) continue
            val o = n * 7; pts[o] = dust[i * 3]; pts[o + 1] = dust[i * 3 + 1]; pts[o + 2] = zz
            pts[o + 3] = dustCol[0] * .8f; pts[o + 4] = dustCol[1] * .8f; pts[o + 5] = dustCol[2] * .8f; pts[o + 6] = dsz; n++
        }
        for (i in 0 until pAlive) {
            if (n >= MAXPTS) break
            val f = pT[i] / pT0[i]; val o = n * 7
            pts[o] = pX[i]; pts[o + 1] = pY[i]; pts[o + 2] = -(pZ[i] - dist)
            pts[o + 3] = pC[i * 3] * f; pts[o + 4] = pC[i * 3 + 1] * f; pts[o + 5] = pC[i * 3 + 2] * f; pts[o + 6] = if (rich) .45f else .55f; n++
        }
        if (rich) {
            fun glowAt(x: Float, y: Float, z: Float, c: FloatArray, s: Float, a: Float) {
                if (n >= MAXPTS) return
                val o = n * 7; pts[o] = x; pts[o + 1] = y; pts[o + 2] = z; pts[o + 3] = c[0] * a; pts[o + 4] = c[1] * a; pts[o + 5] = c[2] * a; pts[o + 6] = s; n++
            }
            for (t in things) {
                val rz = -(t.z - dist)
                when (t.k) {
                    Kind.STAR -> glowAt(t.x, t.y, rz, GOLD, 2.2f, .45f)
                    Kind.ZAP -> { val c = cos(t.a) * t.len / 2; val s = sin(t.a) * t.len / 2; glowAt(t.x - c, t.y - s, rz, zapCol, 2.6f, .8f); glowAt(t.x + c, t.y + s, rz, zapCol, 2.6f, .8f) }
                    Kind.PU -> glowAt(t.x, t.y, rz, puCol(t.pu), 3.4f, .7f)
                    else -> {}
                }
            }
            if (!dead) glowAt(rx, ry, 1.9f, BOOST, 1.4f, .6f)
        }
        ptsBuf.position(0); ptsBuf.put(pts, 0, n * 7).position(0)
        GLES20.glUseProgram(ptProg)
        GLES20.glUniform2f(pFogR, 70f, VIEW + 5)
        drawPoints(ptsBuf, n)
        blend(false)
    }

    private fun drawPoints(buf: FloatBuffer, n: Int) {
        if (n <= 0) return
        GLES20.glEnableVertexAttribArray(pPos); GLES20.glEnableVertexAttribArray(pCol)
        buf.position(0); GLES20.glVertexAttribPointer(pPos, 3, GLES20.GL_FLOAT, false, 28, buf)
        buf.position(3); GLES20.glVertexAttribPointer(pCol, 4, GLES20.GL_FLOAT, false, 28, buf)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, n)
        GLES20.glDisableVertexAttribArray(pPos); GLES20.glDisableVertexAttribArray(pCol)
        buf.position(0)
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type); GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
        val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw RuntimeException("shader: " + GLES20.glGetShaderInfoLog(s))
        return s
    }
    private fun program(vs: String, fs: String): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs)); GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        return p
    }

    companion object {
        const val LIT_VS = """
            uniform mat4 uP; uniform mat4 uMV; uniform mat3 uN;
            attribute vec3 aPos; attribute vec3 aNor;
            varying vec3 vN; varying float vD;
            void main() { vec4 m = uMV * vec4(aPos, 1.0); vD = -m.z; vN = uN * aNor; gl_Position = uP * m; }
        """
        const val LIT_FS = """
            precision mediump float;
            uniform vec3 uCol; uniform vec3 uEm; uniform float uLit; uniform float uAlpha; uniform float uAdd;
            uniform vec3 uFog; uniform vec2 uFogR; uniform vec3 uL1; uniform vec3 uL2;
            varying vec3 vN; varying float vD;
            void main() {
                vec3 c = uCol;
                if (uLit > 0.5) {
                    vec3 n = normalize(vN);
                    float d1 = max(dot(n, uL1), 0.0), d2 = max(dot(n, uL2), 0.0);
                    float hemi = 0.5 + 0.5 * n.y;
                    c = uCol * (0.22 + 0.25 * hemi + 0.85 * d1 + 0.3 * d2) + uEm + vec3(0.25) * pow(d1, 24.0);
                }
                float f = uFogR.y > 0.0 ? clamp((vD - uFogR.x) / (uFogR.y - uFogR.x), 0.0, 1.0) : 0.0;
                if (uAdd > 0.5) { c *= (1.0 - f) * uAlpha; gl_FragColor = vec4(c, 1.0); }
                else gl_FragColor = vec4(mix(c, uFog, f), 1.0);
            }
        """
        const val PT_VS = """
            uniform mat4 uP; uniform mat4 uV; uniform float uSize; uniform vec2 uFogR;
            attribute vec3 aPos; attribute vec4 aCol;
            varying vec3 vC;
            void main() {
                vec4 m = uV * vec4(aPos, 1.0); float d = max(-m.z, 0.1);
                gl_Position = uP * m;
                gl_PointSize = clamp(uSize * aCol.a / d, 1.0, 96.0);
                float f = uFogR.y > 0.0 ? clamp((d - uFogR.x) / (uFogR.y - uFogR.x), 0.0, 1.0) : 0.0;
                vC = aCol.rgb * (1.0 - f);
            }
        """
        const val PT_FS = """
            precision mediump float;
            varying vec3 vC;
            void main() { float a = 1.0 - smoothstep(0.0, 0.5, length(gl_PointCoord - vec2(0.5))); gl_FragColor = vec4(vC * a * a, 1.0); }
        """
    }
}

/** The 3D view itself. */
class RushSurface(ctx: Context, rich: Boolean, val game: RushGL) : GLSurfaceView(ctx) {
    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(MsaaChooser(if (rich) 4 else 0))
        preserveEGLContextOnPause = true
        setRenderer(game)
        renderMode = RENDERMODE_CONTINUOUSLY
    }
}
