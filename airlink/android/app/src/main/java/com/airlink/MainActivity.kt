package com.airlink

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.widget.*

class MainActivity : Activity() {
    private lateinit var host: EditText
    private lateinit var pin: EditText
    private lateinit var status: TextView
    private lateinit var nsd: NsdManager
    private var port = 7000
    private var disc: NsdManager.DiscoveryListener? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val pad = (24 * resources.displayMetrics.density).toInt()
        status = TextView(this).apply { text = "Looking for PCs on your Wi-Fi..."; textSize = 16f }
        host = EditText(this).apply { hint = "PC address (filled in automatically)" }
        pin = EditText(this).apply { hint = "6-digit PIN shown on the PC"; inputType = InputType.TYPE_CLASS_NUMBER }
        val start = Button(this).apply { text = "Start mirroring"; setOnClickListener { begin() } }
        val stop = Button(this).apply {
            text = "Stop"
            setOnClickListener { stopService(Intent(this@MainActivity, MirrorService::class.java)); status.text = "Stopped" }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad)
            listOf(status, host, pin, start, stop).forEach { addView(it) }
        })
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        discover()
    }

    private fun discover() {
        nsd = getSystemService(NSD_SERVICE) as NsdManager
        disc = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(s: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(s, object : NsdManager.ResolveListener {
                    override fun onServiceResolved(i: NsdServiceInfo) {
                        runOnUiThread { host.setText(i.host.hostAddress); port = i.port; status.text = "Found ${i.serviceName}" }
                    }
                    override fun onResolveFailed(i: NsdServiceInfo, e: Int) {}
                })
            }
            override fun onServiceLost(s: NsdServiceInfo) {}
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
        }
        nsd.discoverServices("_airlink._tcp.", NsdManager.PROTOCOL_DNS_SD, disc)
    }

    private fun begin() {
        if (host.text.isBlank() || pin.text.length != 6) { status.text = "Enter the PC address and the 6-digit PIN"; return }
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(rq: Int, rc: Int, d: Intent?) {
        if (rq == 1 && rc == RESULT_OK && d != null) {
            startForegroundService(Intent(this, MirrorService::class.java)
                .putExtra("rc", rc).putExtra("data", d)
                .putExtra("host", host.text.toString().trim()).putExtra("port", port)
                .putExtra("pin", pin.text.toString()))
            status.text = "Mirroring started"
        }
    }

    override fun onDestroy() { runCatching { nsd.stopServiceDiscovery(disc) }; super.onDestroy() }
}
