package com.example.ui.webview

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import com.example.network.ConnectionCutoffVpnService

class FloatingShieldService : Service() {
    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var scaleXAnimator: android.animation.ObjectAnimator? = null
    private var scaleYAnimator: android.animation.ObjectAnimator? = null

    companion object {
        var isServiceRunning = false
        var buttonSizeDp = 56
        var idleTransparencyPercent = 100
        var onCutoffTriggered: (() -> Unit)? = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true
        showFloatingOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "UPDATE_STYLE") {
            updateFloatingStyle()
        }
        return START_STICKY
    }

    private fun showFloatingOverlay() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val context = this
        val floatButton = ImageView(context).apply {
            imageAlpha = 220
            // Set standard refresh/sync icon
            setImageResource(android.R.drawable.ic_popup_sync)
            setColorFilter(AndroidColor.parseColor("#3C2414")) // Leather brown icon tint
            
            // Gold gaming themed border styles
            val defaultShape = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(AndroidColor.parseColor("#F3C32B")) // Clash Gold Background
                setStroke(6, AndroidColor.parseColor("#423C35")) // Deep wood charcoal border
            }

            val pressedShape = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(AndroidColor.parseColor("#B08C15")) // Pressed Darker Gold
                setStroke(6, AndroidColor.parseColor("#423C35"))
            }

            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), pressedShape)
                addState(intArrayOf(), defaultShape)
            }

            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            
            // Set initial alpha based on user's transparency preference
            alpha = (idleTransparencyPercent / 100f).coerceIn(0.15f, 1f)
        }

        // Continuous gentle pulsing/breathing animation for visual dynamism
        scaleXAnimator = android.animation.ObjectAnimator.ofFloat(floatButton, "scaleX", 1.0f, 1.08f, 1.0f).apply {
            duration = 1800
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.RESTART
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }
        scaleYAnimator = android.animation.ObjectAnimator.ofFloat(floatButton, "scaleY", 1.0f, 1.08f, 1.0f).apply {
            duration = 1800
            repeatCount = android.animation.ValueAnimator.INFINITE
            repeatMode = android.animation.ValueAnimator.RESTART
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            start()
        }

        val density = resources.displayMetrics.density
        val sizePx = (buttonSizeDp * density).toInt()

        val layoutParams = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // Position it around the right-center of the screen
            val displayMetrics = resources.displayMetrics
            x = displayMetrics.widthPixels - sizePx - (24 * density).toInt()
            y = (displayMetrics.heightPixels / 2) - (sizePx / 2)
        }

        // Elegant Touch Drag & Snap to borders implementation
        floatButton.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var isMoving = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isMoving = false
                        floatButton.alpha = 1.0f // Highlight to full visibility during drag
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - initialTouchX).toInt()
                        val dy = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(dx) > 12 || Math.abs(dy) > 12) {
                            isMoving = true
                        }
                        layoutParams.x = initialX + dx
                        layoutParams.y = initialY + dy
                        try {
                            windowManager.updateViewLayout(floatButton, layoutParams)
                        } catch (e: Exception) {
                            // ignore container recycling issues
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        floatButton.alpha = (idleTransparencyPercent / 100f).coerceIn(0.15f, 1f)
                        if (!isMoving) {
                            triggerCutoffAction()
                        } else {
                            // Snap to nearest screen sidebar edge
                            val metrics = resources.displayMetrics
                            val midHorizontal = metrics.widthPixels / 2
                            val margin = (16 * density).toInt()
                            layoutParams.x = if (layoutParams.x + (sizePx / 2) < midHorizontal) {
                                margin
                            } else {
                                metrics.widthPixels - sizePx - margin
                            }
                            try {
                                windowManager.updateViewLayout(floatButton, layoutParams)
                            } catch (e: Exception) {
                                // ignore
                            }
                        }
                        return true
                    }
                }
                return false
            }
        })

        floatingView = floatButton
        windowManager.addView(floatButton, layoutParams)
    }

    private fun updateFloatingStyle() {
        val view = floatingView as? ImageView ?: return
        val density = resources.displayMetrics.density
        val sizePx = (buttonSizeDp * density).toInt()

        val layoutParams = view.layoutParams as? WindowManager.LayoutParams ?: return
        layoutParams.width = sizePx
        layoutParams.height = sizePx
        
        view.alpha = (idleTransparencyPercent / 100f).coerceIn(0.15f, 1f)
        try {
            windowManager.updateViewLayout(view, layoutParams)
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun triggerCutoffAction() {
        // Run the cutoff globally inside manager
        onCutoffTriggered?.invoke()

        val startIntent = Intent(this, ConnectionCutoffVpnService::class.java).apply {
            action = "START_VPN"
        }
        try {
            startService(startIntent)
        } catch (e: Exception) {
            // Background permission error handle
        }

        Toast.makeText(this, "CoC Shield: Network Interrupted!", Toast.LENGTH_SHORT).show()

        // Schedule exact 1.0s stop
        Handler(Looper.getMainLooper()).postDelayed({
            val stopIntent = Intent(this, ConnectionCutoffVpnService::class.java).apply {
                action = "STOP_VPN"
            }
            try {
                startService(stopIntent)
            } catch (e: Exception) {
                // ignore
            }
        }, 1000)
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        scaleXAnimator?.cancel()
        scaleYAnimator?.cancel()
        scaleXAnimator = null
        scaleYAnimator = null
        floatingView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // ignore
            }
        }
        floatingView = null
    }
}
