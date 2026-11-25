package dev.moriya.vpn.extensions

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.net.TrafficStats
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock

/**
 * Speed meter extension — a low-power real-time network speed
 * meter in the status bar (v1.5.0, DESIGN.md §9.8).
 *
 * Why it lives here (Moriya, main chat, 2026-11-25): the meter
 * needs a resident process, and this app already is one — the
 * always-on VPN the system revives. A separate app would mean a
 * second resident process and a second foreground service for the
 * same job.
 *
 * Measurement: [TrafficStats] total Rx/Tx counters (the whole
 * device's traffic, mobile + Wi-Fi — counting inside the tunnel
 * is not required), sampled on the interval the user picks from
 * the preset dropdown. The rate/format math is pure and tested
 * ([SpeedMeterLogic]); this class is the Android-facing shell.
 *
 * Display: a dedicated notification — its own channel and id,
 * separate from the VPN foreground-service notification — whose
 * small icon is a bitmap rendered from the speed string (the
 * Internet Speed Meter pattern). The string is always
 * "XX.X MB/s" (see [SpeedMeterLogic]), and notify() is called
 * ONLY when that string changes, so at a steady rate the icon
 * costs nothing. No history is kept and nothing is logged.
 *
 * Power discipline:
 * - screen off stops polling and icon updates entirely (the
 *   screen receiver below); on screen-on the byte baseline is
 *   reset, so traffic that moved while the screen was off does
 *   not spike the first reading;
 * - the extension's switch being off cancels the notification,
 *   unregisters the receiver and removes every pending tick —
 *   zero polling;
 * - the poll itself is two counter reads and, usually, no
 *   notification at all.
 *
 * If the platform counters are unsupported (TrafficStats returns
 * [TrafficStats.UNSUPPORTED]), the status line says so and no
 * icon is shown — the meter degrades to a report, never a crash.
 */
class SpeedMeterExtension(
    override val info: ExtensionInfo,
) : VpnExtension {

    @Volatile private var appContext: Context? = null
    private var settings: ExtensionSettings? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private val sampler = SpeedMeterLogic.Sampler()

    @Volatile private var screenOn = true
    @Volatile private var countersUnsupported = false
    @Volatile private var lastDisplay: String? = null
    @Volatile private var lastSample: SpeedMeterLogic.Sample? = null
    @Volatile private var receiverRegistered = false

    private val tick = Runnable { poll() }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    // The status bar is not visible; updating the
                    // icon would be pure waste. The notification
                    // itself stays, showing the last reading.
                    screenOn = false
                    handler?.removeCallbacks(tick)
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenOn = true
                    // Fresh baseline: the first sample after wake
                    // only re-arms the sampler, so the off-period's
                    // traffic never appears as a spike.
                    sampler.reset()
                    val h = handler ?: return
                    h.removeCallbacks(tick)
                    h.post(tick)
                }
            }
        }
    }

    override fun start(context: Context) {
        appContext = context.applicationContext
        settings = ExtensionSettings(context)
        createNotificationChannel(context)
        sampler.reset()
        lastDisplay = null
        lastSample = null
        countersUnsupported = false
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        screenOn = power?.isInteractive ?: true

        val worker = HandlerThread("mokavpn-speed-meter").apply { start() }
        thread = worker
        handler = Handler(worker.looper)

        // ACTION_SCREEN_ON/OFF are protected system broadcasts;
        // they can only ever come from the system.
        context.registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
        )
        receiverRegistered = true
        handler?.post(tick)
    }

    override fun stop() {
        handler?.removeCallbacks(tick)
        thread?.quitSafely()
        handler = null
        thread = null
        val context = appContext
        if (receiverRegistered && context != null) {
            runCatching { context.unregisterReceiver(screenReceiver) }
        }
        receiverRegistered = false
        if (context != null) {
            notificationManager(context).cancel(NOTIFICATION_ID)
        }
        appContext = null
        settings = null
    }

    override fun statusText(): String {
        val interval = settings?.speedMeterIntervalSeconds()
            ?: SpeedMeterLogic.DEFAULT_INTERVAL_SECONDS
        if (countersUnsupported) {
            return "traffic counters unavailable on this device"
        }
        val sample = lastSample ?: return "measuring… · every $interval s"
        return "down ${SpeedMeterLogic.formatRate(sample.rxBytesPerSecond)}" +
            " · up ${SpeedMeterLogic.formatRate(sample.txBytesPerSecond)}" +
            " · every $interval s"
    }

    private fun poll() {
        val context = appContext ?: return
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        if (rx == TrafficStats.UNSUPPORTED.toLong() ||
            tx == TrafficStats.UNSUPPORTED.toLong()
        ) {
            // No counters on this device: report via the status
            // line, show no icon, keep the (cheap) cadence so the
            // state is re-checked rather than latched forever.
            if (!countersUnsupported) {
                countersUnsupported = true
                notificationManager(context).cancel(NOTIFICATION_ID)
                lastDisplay = null
            }
        } else {
            countersUnsupported = false
            val sample = sampler.onSample(rx, tx, SystemClock.elapsedRealtime())
            if (sample != null) {
                lastSample = sample
                val display = sample.display
                // The load-bearing frugality: the notification —
                // and with it the icon bitmap render — happens
                // only when the displayed string actually changes.
                if (display != lastDisplay) {
                    lastDisplay = display
                    showNotification(context, sample)
                }
            }
        }
        scheduleNext()
    }

    private fun scheduleNext() {
        val h = handler ?: return
        if (!screenOn) return
        val intervalSeconds = settings?.speedMeterIntervalSeconds()
            ?: SpeedMeterLogic.DEFAULT_INTERVAL_SECONDS
        // The interval is read fresh for every tick, so a change
        // in the UI dropdown applies live, with no restart.
        h.postDelayed(tick, intervalSeconds * 1000L)
    }

    private fun showNotification(context: Context, sample: SpeedMeterLogic.Sample) {
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(renderIcon(sample.display))
            .setContentTitle("Network speed: ${sample.display}")
            .setContentText(
                "down ${SpeedMeterLogic.formatRate(sample.rxBytesPerSecond)}" +
                    " · up ${SpeedMeterLogic.formatRate(sample.txBytesPerSecond)}",
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
        notificationManager(context).notify(NOTIFICATION_ID, notification)
    }

    /**
     * Render the speed string as the notification's small icon:
     * the number large over the "MB/s" unit, white on transparent
     * (the system tints status-bar icons; the alpha shape is what
     * carries). The format's fixed width ("XX.X") keeps the layout
     * identical from one reading to the next.
     */
    private fun renderIcon(display: String): Icon {
        val size = 96
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val value = display.removeSuffix(" MB/s")
        val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            // "0.0"–"9.9" is three glyphs; two-digit values get a
            // slightly smaller size so they stay inside the icon.
            textSize = if (value.length <= 3) 50f else 42f
        }
        val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
            textSize = 28f
        }
        canvas.drawText(value, size / 2f, 50f, valuePaint)
        canvas.drawText("MB/s", size / 2f, 88f, unitPaint)
        return Icon.createWithBitmap(bitmap)
    }

    private fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Speed meter",
            // Low: the icon's presence is the whole point; it must
            // never make a sound or slide the shade open.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Current network speed, shown as the notification icon"
            setShowBadge(false)
        }
        notificationManager(context).createNotificationChannel(channel)
    }

    private fun notificationManager(context: Context): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val CHANNEL_ID = "moka-vpn-speed-meter"

        /** The foreground-service notification is id 1 (MokaVpnService). */
        private const val NOTIFICATION_ID = 2
    }
}
