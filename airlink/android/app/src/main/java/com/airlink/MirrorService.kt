package com.airlink

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.util.Timer
import kotlin.concurrent.timerTask

class MirrorService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var link: Link? = null
    private var thread: HandlerThread? = null
    private var pinger: Timer? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(i: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        if (i == null) { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("mirror", "Screen mirroring", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "mirror").setContentTitle("AirLink")
            .setContentText("Mirroring your screen to the PC")
            .setSmallIcon(android.R.drawable.ic_menu_share).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val data = i.getParcelableExtra<Intent>("data")!!
        projection = mpm.getMediaProjection(i.getIntExtra("rc", 0), data).also {
            it.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, main)
        }
        val host = i.getStringExtra("host")!!; val port = i.getIntExtra("port", 7000); val pin = i.getStringExtra("pin")!!
        Thread {
            try {
                val l = Link(host, port, pin)
                l.send(0, Build.MODEL.toByteArray())
                link = l
                startCapture(l)
            } catch (e: Exception) {
                toast("Could not connect: wrong PIN, or PC not reachable")
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun startCapture(l: Link) {
        val dm = resources.displayMetrics
        val w = 720
        val h = (w * dm.heightPixels.toFloat() / dm.widthPixels).toInt() and 1.inv()
        thread = HandlerThread("capture").also { it.start() }
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2).also { r ->
            r.setOnImageAvailableListener({ ir ->
                val img = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val p = img.planes[0]
                    val bmp = Bitmap.createBitmap(p.rowStride / p.pixelStride, h, Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(p.buffer)
                    val bos = ByteArrayOutputStream()
                    Bitmap.createBitmap(bmp, 0, 0, w, h).compress(Bitmap.CompressFormat.JPEG, 60, bos)
                    l.send(1, bos.toByteArray())
                } catch (e: Exception) {
                    stopSelf()
                } finally {
                    img.close()
                }
            }, Handler(thread!!.looper))
        }
        display = projection!!.createVirtualDisplay("airlink", w, h, dm.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, null)
        // The screen only produces frames when it changes, so ping to keep the link alive.
        pinger = Timer().also { it.schedule(timerTask { try { l.send(2, ByteArray(0)) } catch (e: Exception) { stopSelf() } }, 5000, 5000) }
    }

    private fun toast(m: String) = main.post { Toast.makeText(applicationContext, m, Toast.LENGTH_LONG).show() }

    override fun onDestroy() {
        pinger?.cancel()
        display?.release(); reader?.close(); projection?.stop()
        thread?.quitSafely(); link?.close()
        super.onDestroy()
    }
}
