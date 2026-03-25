package com.example.aiassistant.services

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.view.KeyEvent
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.aiassistant.R
import com.example.aiassistant.domain.AgentExecutionBus

class FloatingChatOverlay(
    private val context: Context,
    private val onStop: () -> Unit,
    private val onBoundsChanged: (Rect) -> Unit = {}
) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val displayView: View = LayoutInflater.from(context).inflate(R.layout.view_floating_chat_display, null)
    private val headerOverlayView: View = LayoutInflater.from(context).inflate(R.layout.view_floating_chat_header, null)
    private val inputOverlayView: View = LayoutInflater.from(context).inflate(R.layout.view_floating_chat_input, null)

    private val headerRoot: View = headerOverlayView.findViewById(R.id.floating_chat_header_root)
    private val headerView: View = headerOverlayView.findViewById(R.id.floating_chat_header)
    private val closeView: ImageView = headerOverlayView.findViewById(R.id.floating_chat_close)
    private val stopView: View = headerOverlayView.findViewById(R.id.floating_chat_stop)
    private val displayRoot: View = displayView.findViewById(R.id.floating_chat_display_root)
    private val scrollView: ScrollView = displayView.findViewById(R.id.floating_chat_scroll)
    private val textView: TextView = displayView.findViewById(R.id.floating_chat_text)
    private val inputRoot: View = inputOverlayView.findViewById(R.id.floating_chat_input_root)
    private val inputView: EditText = inputOverlayView.findViewById(R.id.floating_chat_input)
    private val sendView: ImageButton = inputOverlayView.findViewById(R.id.floating_chat_send)
    private val micView: ImageButton = inputOverlayView.findViewById(R.id.floating_chat_mic)

    private val asrManager = AsrManager(
        context,
        onResult = { text ->
            inputView.setText(text)
            inputView.setSelection(text.length)
        },
        onError = { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    )

    private var isShown = false
    private var automationMode = false
    private var previousX = 16
    private var previousY = 120

    private val displayLayoutParams = createLayoutParams(
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    )

    private val headerLayoutParams = createLayoutParams(
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    )

    private val inputLayoutParams = createLayoutParams(
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    )

    private val displayHeight: Int
    private val displayWidth: Int
    private val inputHeight: Int
    private val inputWidth: Int
    private val headerHeight: Int
    private val headerWidth: Int

    init {
        displayWidth = measureViewWidth(displayView)
        displayHeight = measureViewHeight(displayView)
        headerWidth = measureViewWidth(headerOverlayView)
        headerHeight = measureViewHeight(headerOverlayView)
        inputWidth = measureViewWidth(inputOverlayView)
        inputHeight = measureViewHeight(inputOverlayView)
        setupDrag()
        setupInput()
        stopView.setOnClickListener { onStop() }
        closeView.setOnClickListener { dismiss() }
    }

    fun show() {
        if (isShown) return
        windowManager.addView(displayView, displayLayoutParams)
        windowManager.addView(headerOverlayView, headerLayoutParams)
        windowManager.addView(inputOverlayView, inputLayoutParams)
        isShown = true
        updateOverlayPositions()
    }

    fun dismiss() {
        if (!isShown) return
        asrManager.release()
        windowManager.removeView(displayView)
        windowManager.removeView(headerOverlayView)
        windowManager.removeView(inputOverlayView)
        isShown = false
    }

    fun updateMessages(messages: List<String>) {
        if (!isShown) return
        val nonBlank = messages.map { it.trim() }.filter { it.isNotBlank() }
        val text = if (nonBlank.isEmpty()) {
            context.getString(R.string.floating_chat_empty)
        } else {
            nonBlank.joinToString("\n\n")
        }
        textView.text = text
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    fun setMessageScrollable(enabled: Boolean) {
        val flags = if (enabled) {
            displayLayoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            displayLayoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        if (displayLayoutParams.flags == flags) return
        displayLayoutParams.flags = flags
        if (isShown) {
            windowManager.updateViewLayout(displayView, displayLayoutParams)
        }
    }

    /**
     * 自动化模式下将悬浮窗切换为触摸穿透，避免拦截底层App手势。
     * 同时压缩展示区域，降低对底层内容的遮挡。
     */
    fun setAutomationMode(enabled: Boolean) {
        if (automationMode == enabled) return
        automationMode = enabled

        if (enabled) {
            previousX = displayLayoutParams.x
            previousY = displayLayoutParams.y
            displayLayoutParams.x = 8
            displayLayoutParams.y = 80
            displayView.alpha = 0.3f
            inputOverlayView.visibility = View.GONE
        } else {
            displayLayoutParams.x = previousX
            displayLayoutParams.y = previousY
            displayView.alpha = 1f
            inputOverlayView.visibility = View.VISIBLE
        }

        updateTouchPassThrough(enabled)
        updateOverlayPositions()
    }

    private fun updateTouchPassThrough(passThrough: Boolean) {
        val displayFlags = if (passThrough) {
            displayLayoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            displayLayoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        // Keep header interactive so users can always tap the stop button.
        val headerFlags = headerLayoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        val inputFlags = if (passThrough) {
            inputLayoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            inputLayoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }

        displayLayoutParams.flags = displayFlags
        headerLayoutParams.flags = headerFlags
        inputLayoutParams.flags = inputFlags
    }

    private fun setupInput() {
        inputView.setOnFocusChangeListener { _, hasFocus ->
            setOverlayFocusable(hasFocus)
        }

        // When the overlay is focusable, FLAG_WATCH_OUTSIDE_TOUCH is set so taps
        // outside this window (e.g. on the main activity's chat input) are delivered
        // as ACTION_OUTSIDE. We use this to explicitly release focus and hide the IME,
        // allowing the main interface to receive it properly.
        inputOverlayView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(inputView.windowToken, 0)
                inputView.clearFocus()
            }
            false
        }

        sendView.setOnClickListener {
            val userInput = inputView.text.toString().trim()
            if (userInput.isNotBlank()) {
                AgentExecutionBus.userMessages.tryEmit(userInput)
                inputView.text.clear()
                inputView.clearFocus()
            }
        }

        micView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    micView.alpha = 0.5f
                    asrManager.startRecording()
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    micView.alpha = 1.0f
                    asrManager.stopRecording()
                    true
                }
                else -> false
            }
        }

        inputView.setOnEditorActionListener { _, actionId, event ->
            val isEnterKey = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_SEND || isEnterKey) {
                sendView.performClick()
                return@setOnEditorActionListener true
            }
            return@setOnEditorActionListener false
        }
    }

    private fun setOverlayFocusable(focusable: Boolean) {
        val flags = if (focusable) {
            (inputLayoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()) or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        } else {
            (inputLayoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) and
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH.inv()
        }
        if (inputLayoutParams.flags == flags) return
        inputLayoutParams.flags = flags
        if (isShown) {
            windowManager.updateViewLayout(inputOverlayView, inputLayoutParams)
        }
        if (focusable) {
            inputView.post {
                inputView.requestFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(inputView, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun setupDrag() {
        attachDragHandler(headerRoot, allowImmediate = true, forwardTarget = null)
        attachDragHandler(headerView, allowImmediate = true, forwardTarget = null)
    }

    private fun attachDragHandler(
        view: View,
        allowImmediate: Boolean,
        forwardTarget: View?
    ) {
        view.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0
            private var startY = 0
            private var touchStartX = 0f
            private var touchStartY = 0f
            private var isDragging = false
            private val handler = Handler(Looper.getMainLooper())
            private val longPressRunnable = Runnable { isDragging = true }

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = displayLayoutParams.x
                        startY = displayLayoutParams.y
                        touchStartX = event.rawX
                        touchStartY = event.rawY
                        isDragging = allowImmediate
                        if (!allowImmediate) {
                            handler.postDelayed(
                                longPressRunnable,
                                ViewConfiguration.getLongPressTimeout().toLong()
                            )
                        }
                        return forwardTarget?.onTouchEvent(event) ?: true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (isDragging) {
                            val dx = (touchStartX - event.rawX).toInt()
                            val dy = (event.rawY - touchStartY).toInt()
                            displayLayoutParams.x = startX + dx
                            displayLayoutParams.y = startY + dy
                            updateOverlayPositions()
                            return true
                        }
                        return forwardTarget?.onTouchEvent(event) ?: true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        handler.removeCallbacks(longPressRunnable)
                        isDragging = false
                        return forwardTarget?.onTouchEvent(event) ?: true
                    }
                }
                return false
            }
        })
    }

    private fun createLayoutParams(flags: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            },
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = 16
            y = 120
        }
    }

    private fun updateOverlayPositions() {
        headerLayoutParams.x = displayLayoutParams.x
        headerLayoutParams.y = displayLayoutParams.y
        inputLayoutParams.x = displayLayoutParams.x
        inputLayoutParams.y = displayLayoutParams.y + displayHeight - inputHeight

        if (isShown) {
            windowManager.updateViewLayout(displayView, displayLayoutParams)
            windowManager.updateViewLayout(headerOverlayView, headerLayoutParams)
            windowManager.updateViewLayout(inputOverlayView, inputLayoutParams)
            onBoundsChanged(computeOverlayBounds())
        }
    }

    private fun computeOverlayBounds(): Rect {
        val size = Point()
        windowManager.defaultDisplay.getRealSize(size)
        val screenWidth = size.x
        val displayLeft = screenWidth - displayLayoutParams.x - displayWidth
        val displayTop = displayLayoutParams.y
        val displayRect = Rect(
            displayLeft,
            displayTop,
            displayLeft + displayWidth,
            displayTop + displayHeight
        )

        val inputLeft = screenWidth - inputLayoutParams.x - inputWidth
        val inputTop = inputLayoutParams.y
        val inputRect = Rect(
            inputLeft,
            inputTop,
            inputLeft + inputWidth,
            inputTop + inputHeight
        )

        val headerLeft = screenWidth - headerLayoutParams.x - headerWidth
        val headerTop = headerLayoutParams.y
        val headerRect = Rect(
            headerLeft,
            headerTop,
            headerLeft + headerWidth,
            headerTop + headerHeight
        )

        val unionRect = Rect(displayRect)
        unionRect.union(inputRect)
        unionRect.union(headerRect)
        return unionRect
    }

    private fun measureViewWidth(view: View): Int {
        val widthSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(widthSpec, heightSpec)
        return view.measuredWidth
    }

    private fun measureViewHeight(view: View): Int {
        val widthSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        view.measure(widthSpec, heightSpec)
        return view.measuredHeight
    }
}
