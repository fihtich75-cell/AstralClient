package app.astral.client

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.Choreographer
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

object Metrics {
    private val pool = Executors.newSingleThreadScheduledExecutor()

    @Volatile var fps = 0
    @Volatile var ping = -1
    @Volatile var histPos = 0
    @Volatile var active = true
    val fpsHist = IntArray(60)

    // device state (no game access needed)
    @Volatile var battLevel = -1
    @Volatile var battTemp = Float.NaN
    @Volatile var charging = false
    @Volatile var clock = "--:--"
    private var clockFmt: SimpleDateFormat? = null
    private var clockIs24 = false

    private var timerStart = SystemClock.elapsedRealtime()
    private val clicks = ArrayDeque<Long>()
    private var running = false
    private var pingTask: ScheduledFuture<*>? = null
    private var frames = 0
    private var lastMs = 0L

    private var cachedHost: String? = null
    private var cachedAddr: InetAddress? = null

    private val MAGIC = byteArrayOf(
        0x00, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(), 0xFE.toByte(),
        0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte(), 0xFD.toByte(), 0x12, 0x34, 0x56, 0x78
    )

    fun click() {
        synchronized(clicks) {
            clicks.addLast(SystemClock.elapsedRealtime())
            if (clicks.size > 200) clicks.removeFirst()
        }
    }

    fun cps(): Int = synchronized(clicks) {
        val lim = SystemClock.elapsedRealtime() - 1000
        while (clicks.isNotEmpty() && clicks.first() < lim) clicks.removeFirst()
        clicks.size
    }

    /** Battery level / temperature and the time of day. Cheap (sticky broadcast); call about once a second. */
    fun pollSystem(c: Context) {
        try {
            val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (i != null) {
                val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                battLevel = if (l >= 0 && sc > 0) l * 100 / sc else -1
                val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                battTemp = if (t == Int.MIN_VALUE) Float.NaN else t / 10f
                val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            }
        } catch (_: Exception) {
        }
        try {
            val is24 = DateFormat.is24HourFormat(c)
            val cached = clockFmt
            val f: SimpleDateFormat
            if (cached == null || is24 != clockIs24) {
                f = SimpleDateFormat(if (is24) "HH:mm" else "h:mm a", Locale.getDefault())
                clockFmt = f
                clockIs24 = is24
            } else {
                f = cached
            }
            clock = f.format(Date())
        } catch (_: Exception) {
        }
    }

    fun resetTimer() { timerStart = SystemClock.elapsedRealtime() }

    fun timerStr(): String {
        val s = (SystemClock.elapsedRealtime() - timerStart) / 1000
        return "%02d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
    }

    fun resetPing() { cachedHost = null; cachedAddr = null; ping = -1 }

    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(t: Long) {
            frames++
            val now = t / 1_000_000
            if (lastMs == 0L) lastMs = now
            if (now - lastMs >= 1000) {
                fps = (frames * 1000L / (now - lastMs)).toInt()
                fpsHist[histPos % 60] = fps
                histPos++
                frames = 0
                lastMs = now
            }
            if (running) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** Must be called on the main thread. */
    fun start() {
        if (running) return
        running = true
        lastMs = 0L
        frames = 0
        timerStart = SystemClock.elapsedRealtime()
        Choreographer.getInstance().postFrameCallback(frameCb)
        pingTask = pool.scheduleWithFixedDelay({ if (active) measurePing() }, 0, 2, TimeUnit.SECONDS)
    }

    fun stop() {
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCb)
        pingTask?.cancel(false)
        pingTask = null
    }

    /** RakNet "unconnected ping" (0x01) -> "unconnected pong" (0x1C): the same handshake Bedrock's server list uses. */
    private fun measurePing() {
        ping = try {
            val host = Cfg.pingHost
            val port = Cfg.pingPort
            val addr = if (host == cachedHost && cachedAddr != null) cachedAddr!! else
                InetAddress.getByName(host).also { cachedHost = host; cachedAddr = it }
            DatagramSocket().use { s ->
                s.soTimeout = 1500
                val buf = ByteBuffer.allocate(33)
                buf.put(0x01.toByte())
                buf.putLong(SystemClock.elapsedRealtime())
                buf.put(MAGIC)
                buf.putLong(0x1122334455667788L)
                val out = buf.array()
                val t0 = System.nanoTime()
                s.send(DatagramPacket(out, out.size, addr, port))
                val inb = ByteArray(1500)
                s.receive(DatagramPacket(inb, inb.size))
                if (inb[0] != 0x1C.toByte()) -1 else ((System.nanoTime() - t0) / 1_000_000).toInt()
            }
        } catch (_: Exception) {
            cachedAddr = null
            -1
        }
    }
}
