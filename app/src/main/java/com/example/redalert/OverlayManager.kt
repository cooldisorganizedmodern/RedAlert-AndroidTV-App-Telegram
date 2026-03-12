package com.example.redalert

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

object OverlayManager {
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    
    private const val LAST_PAGE_TIMEOUT = 25000L
    private const val PAGE_SIZE = 18
    private const val PAGE_DELAY_MS = 2500L
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val alertQueue = Channel<ParsedAlert>(Channel.UNLIMITED)
    
    data class OverlayState(val title: String, val subTitle: String?, val cities: List<String>)
    private val _overlayState = MutableStateFlow<OverlayState?>(null)
    
    private var workerJob: Job? = null
    private var uiUpdateJob: Job? = null

    fun showAlert(context: Context, alert: ParsedAlert) {
        // ALWAYS use applicationContext to prevent Activity/Service memory leaks
        val appContext = context.applicationContext
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { showAlertInternal(appContext, alert) }
        } else {
            showAlertInternal(appContext, alert)
        }
    }

    private fun showAlertInternal(appContext: Context, alert: ParsedAlert) {
        alertQueue.trySend(alert)
        startWorkersIfNeeded(appContext)
    }

    private fun startWorkersIfNeeded(appContext: Context) {
        if (uiUpdateJob?.isActive != true) {
            uiUpdateJob = scope.launch(Dispatchers.Main) {
                // collectLatest guarantees only the LATEST state runs the delay timeout block.
                // If a new state is emitted before LAST_PAGE_TIMEOUT, it cancels the previous delay!
                _overlayState.collectLatest { state ->
                    if (state == null) {
                        removeOverlay(appContext)
                    } else {
                        ensureOverlayVisible(appContext)
                        updateOverlayText(state.title, state.subTitle)
                        displayCities(appContext, state.cities)
                        
                        // Passively wait to hide the UI. No while-loops or manual timeout math needed.
                        delay(LAST_PAGE_TIMEOUT)
                        _overlayState.value = null
                    }
                }
            }
        }

        if (workerJob?.isActive != true) {
            workerJob = scope.launch(Dispatchers.Default) {
                try {
                    var currentTitle = ""
                    var currentSubtitle: String? = null
                    val pendingCitiesBuffer = mutableListOf<String>()

                    while (true) {
                        // 1. Initial/Empty Queue Wait
                        if (pendingCitiesBuffer.isEmpty() && _overlayState.value == null) {
                            val alert = alertQueue.receive()
                            currentTitle = alert.title
                            currentSubtitle = alert.subTitle
                            pendingCitiesBuffer.addAll(alert.cities)
                            
                            // Handle cases where the initial alert is a "Flash" with no cities
                            if (alert.cities.isEmpty()) {
                                _overlayState.value = OverlayState(currentTitle, currentSubtitle, emptyList())
                            }
                            continue
                        }

                        // 2. We have cities in buffer, so we need to page them to the screen
                        if (pendingCitiesBuffer.isNotEmpty()) {
                            // Extract up to 18 cities for this page
                            val chunkToDisplay = pendingCitiesBuffer.take(PAGE_SIZE)
                            // Remove strictly by index to prevent deleting duplicate city names later in the list
                            repeat(chunkToDisplay.size) { pendingCitiesBuffer.removeAt(0) }

                            // Emit chunk to UI immediately
                            _overlayState.value = OverlayState(currentTitle, currentSubtitle, chunkToDisplay)

                            // If there are MORE cities waiting in the buffer for the NEXT page,
                            // we MUST delay here so the user can read this current full page before rotation.
                            if (pendingCitiesBuffer.isNotEmpty()) {
                                delay(PAGE_DELAY_MS)
                                
                                // Peek to see if a quick alert arrived while paginating, just to capture subtitles
                                val quickPeek = alertQueue.tryReceive().getOrNull()
                                if (quickPeek != null) {
                                    if (quickPeek.title == currentTitle) {
                                        currentSubtitle = quickPeek.subTitle
                                        pendingCitiesBuffer.addAll(quickPeek.cities)
                                    } else {
                                        // We throw away the old pagination, a new alert is taking priority immediately
                                        currentTitle = quickPeek.title
                                        currentSubtitle = quickPeek.subTitle
                                        pendingCitiesBuffer.clear()
                                        pendingCitiesBuffer.addAll(quickPeek.cities)
                                    }
                                }
                            }
                            continue // loop around. If buffer is now empty, it will fall into block 3.
                        }

                        // 3. Buffer is EMPTY, but Screen is SHOWING cities (or an empty flash).
                        // We wait up to LAST_PAGE_TIMEOUT (25s) for new alerts before clearing the screen.
                        if (pendingCitiesBuffer.isEmpty() && _overlayState.value != null) {
                            val newAlert = withTimeoutOrNull(LAST_PAGE_TIMEOUT) { alertQueue.receive() }

                            if (newAlert == null) {
                                // Timeout finished, no new alerts. Clean up and reset.
                                _overlayState.value = null
                                currentTitle = ""
                                currentSubtitle = null
                            } else {
                                // 4. THE INTERRUPTION LOGIC
                                // A new alert arrived DURING the 25-second wait!
                                if (newAlert.title == currentTitle) {
                                    // The alert type matches!
                                    // Update subtitle just in case it changed
                                    currentSubtitle = newAlert.subTitle
                                    
                                    val currentDisplayedCities = _overlayState.value?.cities ?: emptyList()
                                    val spaceRemaining = PAGE_SIZE - currentDisplayedCities.size
                                    
                                    if (spaceRemaining > 0 && newAlert.cities.isNotEmpty()) {
                                        // We have space on the screen! Fill it up.
                                        val appendCities = newAlert.cities.take(spaceRemaining)
                                        
                                        // Add to the screen immediately
                                        val combinedDisplay = currentDisplayedCities.toMutableList().apply { addAll(appendCities) }
                                        _overlayState.value = OverlayState(currentTitle, currentSubtitle, combinedDisplay)

                                        // Store any overflow for the next page rotation
                                        val overflowCities = newAlert.cities.drop(spaceRemaining)
                                        pendingCitiesBuffer.addAll(overflowCities)

                                        // Only delay if we actually added overflow to the buffer (meaning a new page is coming)
                                        if (pendingCitiesBuffer.isNotEmpty()) {
                                            delay(PAGE_DELAY_MS)
                                        }
                                    } else {
                                        // Screen is exactly full (or new alert had 0 cities).
                                        // Just queue up the new cities and delay to let the user read current screen 
                                        // before next page rotation kicks in.
                                        pendingCitiesBuffer.addAll(newAlert.cities)
                                        if (pendingCitiesBuffer.isNotEmpty()) {
                                            delay(PAGE_DELAY_MS)
                                        }
                                    }
                                } else {
                                    // The alert type is completely different
                                    // Delay to let the user absorb the CURRENT screen before a jarring context switch
                                    delay(PAGE_DELAY_MS)
                                    
                                    // Clear out the state and adopt the new alert
                                    _overlayState.value = null
                                    currentTitle = newAlert.title
                                    currentSubtitle = newAlert.subTitle
                                    pendingCitiesBuffer.clear()
                                    pendingCitiesBuffer.addAll(newAlert.cities)
                                    // Loop restarts to chunk the new buffer!
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    _overlayState.value = null
                }
            }
        }
    }

    private fun ensureOverlayVisible(context: Context) {
        if (overlayView == null) {
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val inflater = LayoutInflater.from(context)
            overlayView = inflater.inflate(R.layout.view_alert_overlay, null)
            
            val widthInDp = 256f
            val widthInPx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 
                widthInDp, 
                context.resources.displayMetrics
            ).toInt()

            val layoutParams = WindowManager.LayoutParams(
                widthInPx,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) 
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
                else 
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT
            )
            
            layoutParams.gravity = Gravity.TOP or Gravity.END
            layoutParams.x = 48
            layoutParams.y = 0
            layoutParams.windowAnimations = R.style.OverlayAnimation
            
            try {
                windowManager?.addView(overlayView, layoutParams)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun updateOverlayText(title: String, subTitle: String?) {
        val tvTitle = overlayView?.findViewById<TextView>(R.id.tvAlertTitle)
        val tvSubTitle = overlayView?.findViewById<TextView>(R.id.tvAlertSubTitle)
        
        tvTitle?.text = title
        if (!subTitle.isNullOrBlank()) {
            tvSubTitle?.visibility = View.VISIBLE
            tvSubTitle?.text = subTitle
        } else {
            tvSubTitle?.visibility = View.GONE
        }
    }

    private fun displayCities(context: Context, cities: List<String>) {
        val llCities = overlayView?.findViewById<LinearLayout>(R.id.llAlertCities)
        llCities?.removeAllViews()
        
        for (city in cities) {
            // Memory Leak strictly avoided: using application context passed down safely
            val tv = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.RIGHT
                }
                text = city
                setTextColor(android.graphics.Color.WHITE)
                textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.RIGHT
                textDirection = View.TEXT_DIRECTION_RTL
                setPadding(0, 4, 0, 4)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            llCities?.addView(tv)
            
            val divider = View(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (1 * context.resources.displayMetrics.density).toInt()
                ).apply {
                    setMargins(0, 0, 0, 0)
                }
                setBackgroundColor(android.graphics.Color.parseColor("#33FFFFFF"))
            }
            llCities?.addView(divider)
        }
    }

    private fun removeOverlay(context: Context) {
        if (overlayView != null) {
            try {
                val wm = windowManager ?: context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(overlayView)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            overlayView = null
            windowManager = null
        }
    }
}
