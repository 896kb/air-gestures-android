package dev.airgestures

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Spike UI: connect/disconnect and a live log (also in logcat, tag AirGestures). */
class MainActivity : Activity() {
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private var link: BandLink? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logView = TextView(this).apply { setTextIsSelectable(true); textSize = 13f }
        scroll = ScrollView(this).apply { addView(logView) }
        val connect = Button(this).apply { text = "Connect"; setOnClickListener { connect() } }
        val disconnect = Button(this).apply { text = "Disconnect"; setOnClickListener { link?.stop() } }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 96, 32, 32)
            addView(LinearLayout(context).apply { addView(connect); addView(disconnect) })
            addView(scroll, LinearLayout.LayoutParams(-1, -1))
        })
        requestPermissions(PERMISSIONS, 1)
    }

    private fun connect() {
        if (PERMISSIONS.any { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }) {
            log("Bluetooth permissions missing")
            requestPermissions(PERMISSIONS, 1)
            return
        }
        link = link ?: BandLink(this, ::log)
        link?.start()
    }

    private fun log(line: String) {
        Log.i("AirGestures", line)
        runOnUiThread {
            logView.append(line + "\n")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    override fun onDestroy() {
        link?.stop()
        super.onDestroy()
    }

    companion object {
        val PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    }
}
