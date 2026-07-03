package com.example.simplesshconnector

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView

class TerminalActivity : Activity(), TerminalSessionController.Listener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val preferences by lazy {
        AppPreferences.from(this)
    }

    private lateinit var outputTextView: TextView
    private lateinit var outputScrollView: ScrollView
    private lateinit var commandEditText: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var copyCommandButton: ImageButton
    private lateinit var refreshButton: ImageButton
    private lateinit var autocompleteButton: ImageButton
    private lateinit var previousCommandButton: ImageButton
    private lateinit var nextCommandButton: ImageButton
    private lateinit var exitButton: Button
    private var terminalCanSend = false
    private var remoteInputPrimed = false
    private var remoteInputPromptColumn: Int? = null
    private var inputSyncGeneration = 0
    private var shouldFollowTerminalOutput = true
    private var userScrollingOutput = false
    private val stopTrackingManualOutputScroll = Runnable {
        userScrollingOutput = false
        shouldFollowTerminalOutput = isOutputScrolledToBottom()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)
        outputTextView = findViewById(R.id.outputTextView)
        outputScrollView = findViewById(R.id.outputScrollView)
        commandEditText = findViewById(R.id.commandEditText)
        sendButton = findViewById(R.id.sendButton)
        copyCommandButton = findViewById(R.id.copyCommandButton)
        refreshButton = findViewById(R.id.refreshButton)
        autocompleteButton = findViewById(R.id.autocompleteButton)
        previousCommandButton = findViewById(R.id.previousCommandButton)
        nextCommandButton = findViewById(R.id.nextCommandButton)
        exitButton = findViewById(R.id.exitButton)
        trackManualOutputScroll()
        keepCommandInputAboveKeyboard(findViewById(R.id.terminalRoot))
        commandEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCommand()
                true
            } else {
                false
            }
        }
        sendButton.setOnClickListener {
            sendCommand()
        }
        copyCommandButton.setOnClickListener {
            copyCommand()
        }
        refreshButton.setOnClickListener {
            refreshTerminal()
        }
        exitButton.setOnClickListener {
            TerminalSessionManager.sendBytes(CONTROL_C_BYTES)
            remoteInputPrimed = false
            remoteInputPromptColumn = null
        }
        autocompleteButton.setOnClickListener {
            sendInputEditingKey(TAB_KEY_BYTES)
        }
        previousCommandButton.setOnClickListener {
            sendInputEditingKey(UP_ARROW_KEY_BYTES)
        }
        nextCommandButton.setOnClickListener {
            sendInputEditingKey(DOWN_ARROW_KEY_BYTES)
        }
        TerminalSessionManager.attachListener(this)
        connectShell()
    }

    override fun onDestroy() {
        TerminalSessionManager.detachListener(this)
        super.onDestroy()
    }

    override fun onTerminalOutputChanged(output: CharSequence) {
        val shouldScrollToBottom = shouldFollowTerminalOutput || isOutputScrolledToBottom()
        outputTextView.text = output
        if (shouldScrollToBottom) {
            scrollOutputToBottom()
        }
    }

    override fun onTerminalEnabledChanged(enabled: Boolean) {
        setTerminalControlsAvailable(enabled)
        if (enabled) {
            focusCommandInput()
        }
    }

    override fun onTerminalConnectionLost() {
        showShortToast(getString(R.string.message_terminal_connection_lost))
    }

    override fun onTerminalConnectionRecovered() {
        showShortToast(getString(R.string.message_terminal_connection_recovered))
    }

    override fun onTerminalDisconnected() {
        setTerminalControlsAvailable(false)
    }

    override fun onTerminalConnectionUnavailable() {
        showShortToast(getString(R.string.message_terminal_not_connected))
    }

    private fun keepCommandInputAboveKeyboard(rootView: View) {
        val initialPaddingLeft = rootView.paddingLeft
        val initialPaddingTop = rootView.paddingTop
        val initialPaddingRight = rootView.paddingRight
        val initialPaddingBottom = rootView.paddingBottom
        val keyboardClearancePx = KEYBOARD_CLEARANCE_DP.dpToPx()
        var statusTopInset = 0
        var navigationBottomInset = 0
        var imeBottomInset = 0
        var imeVisible = false

        fun updateRootPadding() {
            val visibleFrame = Rect()
            rootView.getWindowVisibleDisplayFrame(visibleFrame)
            val fullWindowHeight = rootView.rootView.height
            val contentHeight = rootView.height
            val visibleWindowBottom = visibleFrame.bottom
            val keyboardOverlap = (fullWindowHeight - visibleWindowBottom).coerceAtLeast(0)
            val keyboardHeight = (maxOf(imeBottomInset, keyboardOverlap) - navigationBottomInset).coerceAtLeast(0)
            val keyboardVisible = imeVisible || keyboardHeight > KEYBOARD_VISIBILITY_THRESHOLD_DP.dpToPx()
            val contentAlreadyResized = keyboardVisible &&
                fullWindowHeight - contentHeight >= keyboardHeight - KEYBOARD_RESIZE_TOLERANCE_DP.dpToPx()
            val keyboardPadding = if (keyboardVisible && !contentAlreadyResized) {
                keyboardHeight
            } else {
                0
            }
            val bottomPadding = if (keyboardVisible) {
                initialPaddingBottom + keyboardClearancePx + keyboardPadding
            } else {
                TERMINAL_BOTTOM_PADDING_WITHOUT_KEYBOARD_DP.dpToPx() + navigationBottomInset
            }

            val topPadding = initialPaddingTop + statusTopInset
            if (
                rootView.paddingLeft != initialPaddingLeft ||
                rootView.paddingTop != topPadding ||
                rootView.paddingRight != initialPaddingRight ||
                rootView.paddingBottom != bottomPadding
            ) {
                rootView.setPadding(
                    initialPaddingLeft,
                    topPadding,
                    initialPaddingRight,
                    bottomPadding
                )
            }
            if (keyboardVisible && shouldFollowTerminalOutput) {
                scrollOutputToBottom()
            }
        }

        rootView.setOnApplyWindowInsetsListener { _, insets ->
            imeBottomInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.ime()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            statusTopInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.statusBars()).top
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop
            }
            navigationBottomInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                0
            }
            imeVisible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                insets.isVisible(WindowInsets.Type.ime()) && imeBottomInset > navigationBottomInset
            } else {
                imeBottomInset > 0
            }
            updateRootPadding()
            insets
        }
        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            updateRootPadding()
        }
        rootView.requestApplyInsets()
    }

    private fun Int.dpToPx(): Int = (this * resources.displayMetrics.density).toInt()

    private fun connectShell() {
        val serverConfig = preferences.serverConfig()
        if (!serverConfig.hasTerminalConnectionInfo()) {
            showShortToast(getString(R.string.message_ssh_target_and_key_required))
            finish()
            return
        }

        setTerminalControlsAvailable(false)
        TerminalSessionManager.connect(this, serverConfig.terminalProfile())
    }

    private fun refreshTerminal() {
        val serverConfig = preferences.serverConfig()
        if (!serverConfig.hasTerminalConnectionInfo()) {
            showShortToast(getString(R.string.message_ssh_target_and_key_required))
            return
        }

        remoteInputPrimed = false
        remoteInputPromptColumn = null
        inputSyncGeneration++
        shouldFollowTerminalOutput = true
        setTerminalControlsAvailable(false)
        TerminalSessionManager.refresh(this, serverConfig.terminalProfile())
    }

    private fun sendCommand() {
        if (!terminalCanSend) {
            return
        }

        val command = commandEditText.text.toString()
        if (remoteInputPrimed) {
            TerminalSessionManager.sendPrimedInputCommand(command)
            remoteInputPrimed = false
            remoteInputPromptColumn = null
        } else {
            TerminalSessionManager.sendCommand(command)
        }
        shouldFollowTerminalOutput = true
        if (command.isNotBlank()) {
            commandEditText.setText("")
        }
    }

    private fun sendInputEditingKey(keyBytes: ByteArray) {
        if (!terminalCanSend) {
            return
        }

        val command = commandEditText.text.toString()
        val promptColumn = remoteInputPromptColumn ?: TerminalSessionManager.currentInputPromptColumn()
        remoteInputPrimed = true
        remoteInputPromptColumn = promptColumn
        TerminalSessionManager.sendInputEditingKey(command, keyBytes)
        if (keyBytes.contentEquals(TAB_KEY_BYTES)) {
            syncCommandInputAfterRemoteEdit(promptColumn, expectedPrefix = command)
        } else {
            syncCommandInputAfterRemoteEdit(promptColumn, waitUntilDifferentFrom = command)
        }
    }

    private fun syncCommandInputAfterRemoteEdit(
        promptColumn: Int,
        expectedPrefix: String? = null,
        waitUntilDifferentFrom: String? = null,
        attempt: Int = 1
    ) {
        val generation = ++inputSyncGeneration
        mainHandler.postDelayed({
            if (generation != inputSyncGeneration || !remoteInputPrimed) {
                return@postDelayed
            }
            val remoteInput = TerminalSessionManager.currentInputAfterPromptColumn(promptColumn)
            val waitingForEcho = !expectedPrefix.isNullOrEmpty() &&
                !remoteInput.startsWith(expectedPrefix) &&
                attempt < INPUT_EDIT_SYNC_MAX_ATTEMPTS
            val waitingForHistory = waitUntilDifferentFrom != null &&
                remoteInput == waitUntilDifferentFrom &&
                attempt < INPUT_EDIT_SYNC_MAX_ATTEMPTS
            if (waitingForEcho) {
                syncCommandInputAfterRemoteEdit(promptColumn, expectedPrefix, waitUntilDifferentFrom, attempt + 1)
                return@postDelayed
            }
            if (waitingForHistory) {
                syncCommandInputAfterRemoteEdit(promptColumn, expectedPrefix, waitUntilDifferentFrom, attempt + 1)
                return@postDelayed
            }
            commandEditText.setText(remoteInput)
            commandEditText.setSelection(commandEditText.text.length)
            remoteInputPromptColumn = promptColumn
            focusCommandInput()
        }, INPUT_EDIT_SYNC_DELAY_MS)
    }

    private fun setTerminalControlsAvailable(canSend: Boolean) {
        terminalCanSend = canSend
        commandEditText.isEnabled = true
        copyCommandButton.isEnabled = true
        sendButton.isEnabled = canSend
        exitButton.isEnabled = canSend
        autocompleteButton.isEnabled = canSend
        previousCommandButton.isEnabled = canSend
        nextCommandButton.isEnabled = canSend
        setSendControlVisualState(canSend)
        if (!canSend) {
            remoteInputPrimed = false
            remoteInputPromptColumn = null
            inputSyncGeneration++
            focusCommandInput()
        }
    }

    private fun setSendControlVisualState(enabled: Boolean) {
        val alpha = if (enabled) ENABLED_CONTROL_ALPHA else DISABLED_CONTROL_ALPHA
        sendButton.alpha = alpha
        exitButton.alpha = alpha
        autocompleteButton.alpha = alpha
        previousCommandButton.alpha = alpha
        nextCommandButton.alpha = alpha
    }

    private fun scrollOutputToBottom() {
        outputScrollView.post {
            outputTextView.post {
                val outputContent = outputScrollView.getChildAt(0) ?: return@post
                val scrollRange = (outputContent.bottom + outputScrollView.paddingBottom - outputScrollView.height)
                    .coerceAtLeast(0)
                outputScrollView.scrollTo(0, scrollRange)
                shouldFollowTerminalOutput = true
            }
        }
    }

    private fun trackManualOutputScroll() {
        outputScrollView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    mainHandler.removeCallbacks(stopTrackingManualOutputScroll)
                    userScrollingOutput = true
                    shouldFollowTerminalOutput = false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    scheduleStopTrackingManualOutputScroll()
                }
            }
            false
        }
        outputScrollView.viewTreeObserver.addOnScrollChangedListener {
            if (userScrollingOutput) {
                shouldFollowTerminalOutput = isOutputScrolledToBottom()
                scheduleStopTrackingManualOutputScroll()
            }
        }
    }

    private fun scheduleStopTrackingManualOutputScroll() {
        mainHandler.removeCallbacks(stopTrackingManualOutputScroll)
        mainHandler.postDelayed(stopTrackingManualOutputScroll, OUTPUT_MANUAL_SCROLL_IDLE_DELAY_MS)
    }

    private fun isOutputScrolledToBottom(): Boolean {
        val outputContent = outputScrollView.getChildAt(0) ?: return true
        val scrollRange = (outputContent.bottom + outputScrollView.paddingBottom - outputScrollView.height)
            .coerceAtLeast(0)
        return scrollRange - outputScrollView.scrollY <= OUTPUT_SCROLL_BOTTOM_TOLERANCE_PX
    }

    private fun focusCommandInput() {
        commandEditText.requestFocus()
        val inputMethodManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethodManager.showSoftInput(commandEditText, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun copyCommand() {
        val command = commandEditText.text.toString()
        if (command.isBlank()) {
            showShortToast(getString(R.string.message_no_command))
            return
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.clipboard_terminal_command), command))
        showShortToast(getString(R.string.message_command_copied))
    }

    private companion object {
        val CONTROL_C_BYTES = byteArrayOf(0x03)
        val TAB_KEY_BYTES = byteArrayOf(0x09)
        val UP_ARROW_KEY_BYTES = "\u001B[A".toByteArray(Charsets.UTF_8)
        val DOWN_ARROW_KEY_BYTES = "\u001B[B".toByteArray(Charsets.UTF_8)
        const val INPUT_EDIT_SYNC_DELAY_MS = 150L
        const val INPUT_EDIT_SYNC_MAX_ATTEMPTS = 6
        const val KEYBOARD_CLEARANCE_DP = 8
        const val KEYBOARD_VISIBILITY_THRESHOLD_DP = 80
        const val KEYBOARD_RESIZE_TOLERANCE_DP = 24
        const val TERMINAL_BOTTOM_PADDING_WITHOUT_KEYBOARD_DP = 8
        const val OUTPUT_SCROLL_BOTTOM_TOLERANCE_PX = 24
        const val OUTPUT_MANUAL_SCROLL_IDLE_DELAY_MS = 250L
        const val ENABLED_CONTROL_ALPHA = 1.0f
        const val DISABLED_CONTROL_ALPHA = 0.38f
    }
}
