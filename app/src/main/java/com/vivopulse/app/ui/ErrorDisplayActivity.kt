package com.vivopulse.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import android.view.ViewGroup
import android.widget.LinearLayout
import com.vivopulse.signal.AppLogger

/**
 * Activity to display application crashes and logs.
 * Launched by CrashHandler when an uncaught exception occurs.
 */
class ErrorDisplayActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val errorMsg = intent.getStringExtra("error") ?: "Unknown Error"
        val stackTrace = intent.getStringExtra("stacktrace") ?: ""
        
        // Simple programmatic UI to avoid layout XML dependency
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(32, 32, 32, 32)
        }

        val title = TextView(this).apply {
            text = "Application Crash"
            textSize = 24f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, 16)
        }
        layout.addView(title)

        val message = TextView(this).apply {
            text = "The application encountered a critical error and could not start."
            textSize = 16f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(message)

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val logText = TextView(this).apply {
            text = "$errorMsg\n\n$stackTrace\n\nFull Log:\n" + AppLogger.getLogContent()
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        scrollView.addView(logText)
        layout.addView(scrollView)
        
        val buttonLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 0)
        }
        
        val copyButton = Button(this).apply {
            text = "Copy Log"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Crash Log", logText.text)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this@ErrorDisplayActivity, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }
        buttonLayout.addView(copyButton)
        
        val restartButton = Button(this).apply {
            text = "Restart App"
            setOnClickListener {
                val i = baseContext.packageManager.getLaunchIntentForPackage(baseContext.packageName)
                i?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(i)
                finish()
            }
        }
        buttonLayout.addView(restartButton)

        layout.addView(buttonLayout)
        setContentView(layout)
    }
}
