package com.omarea.krscript.ui

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.omarea.common.ui.DialogHelper
import com.omarea.krscript.R
import com.omarea.krscript.executor.ShellExecutor
import com.omarea.krscript.model.RunnableNode
import com.omarea.krscript.model.ShellHandlerBase
import kotlinx.android.synthetic.main.kr_dialog_log.action_progress
import kotlinx.android.synthetic.main.kr_dialog_log.btn_copy
import kotlinx.android.synthetic.main.kr_dialog_log.btn_exit
import kotlinx.android.synthetic.main.kr_dialog_log.btn_hide
import kotlinx.android.synthetic.main.kr_dialog_log.desc
import kotlinx.android.synthetic.main.kr_dialog_log.shell_output
import kotlinx.android.synthetic.main.kr_dialog_log.status
import kotlinx.android.synthetic.main.kr_dialog_log.title


class DialogLogFragment : androidx.fragment.app.DialogFragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        currentView = inflater.inflate(R.layout.kr_dialog_log, container)
        return currentView
    }

    private var running = false
    private var nodeInfo: RunnableNode? = null
    private lateinit var onExit: Runnable
    private lateinit var script: String
    private var params: HashMap<String, String>? = null
    private var themeResId: Int = 0
    private lateinit var currentView: View

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return Dialog(activity!!, if (themeResId != 0) themeResId else R.style.kr_full_screen_dialog_light)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val activity = this.activity
        if (activity != null) {
            dialog?.window?.run {
                DialogHelper.setWindowBlurBg(this, activity)
            }
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        if (nodeInfo != null) {
            nodeInfo?.run {
                if (reloadPage) {
                    btn_hide.visibility = View.GONE
                }

                val shellHandler = openExecutor(this)

                if (shellHandler != null) {
                    ShellExecutor().execute(activity, this, script, onExit, params, shellHandler)
                }
            }
        } else {
            dismiss()
        }
    }

    private fun openExecutor(nodeInfo: RunnableNode): ShellHandlerBase {
        var forceStopRunnable: Runnable? = null

        btn_hide.setOnClickListener {
            closeView()
        }
        btn_exit.setOnClickListener {
            if (running) {
                forceStopRunnable?.run()
            }
            closeView()
        }

        btn_copy.setOnClickListener {
            try {
                val myClipboard: ClipboardManager = this.context!!.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val myClip: ClipData = ClipData.newPlainText("text", shell_output.text.toString())
                myClipboard.primaryClip = myClip
                Toast.makeText(context, getString(R.string.copy_success), Toast.LENGTH_SHORT).show()
            } catch (ex: Exception) {
                Toast.makeText(context, getString(R.string.copy_fail), Toast.LENGTH_SHORT).show()
            }
        }
        if (nodeInfo.interruptable) {
            btn_hide?.visibility = View.VISIBLE
            btn_exit?.visibility = View.VISIBLE
        } else {
            btn_hide?.visibility = View.GONE
            btn_exit?.visibility = View.GONE
        }

        if (nodeInfo.title.isNotEmpty()) {
            title.text = nodeInfo.title
        } else {
            title.visibility = View.GONE
        }

        if (nodeInfo.desc.isNotEmpty()) {
            desc.text = nodeInfo.desc
        } else {
            desc.visibility = View.GONE
        }

        status.text = getString(R.string.kr_log_running)
        action_progress.isIndeterminate = true
        return MyShellHandler(object : IActionEventHandler {
            override fun onCompleted() {
                running = false

                onExit.run()
                if (btn_hide != null) {
                    btn_hide.visibility = View.GONE
                    btn_exit.visibility = View.VISIBLE
                    action_progress.visibility = View.GONE
                }

                status.text = getString(if (hasError()) R.string.kr_log_failed else R.string.kr_log_done)
                isCancelable = true
            }

            override fun onSuccess() {
                if (nodeInfo.autoOff) {
                    closeView()
                }
            }

            override fun onStart(forceStop: Runnable?) {
                running = true
                status.text = getString(R.string.kr_log_running)

                if (nodeInfo.interruptable && forceStop != null) {
                    btn_exit.visibility = View.VISIBLE
                } else {
                    btn_exit.visibility = View.GONE
                }
                forceStopRunnable = forceStop
            }

        }, shell_output, action_progress)
    }

    private fun hasError(): Boolean {
        return shellOutputHandler?.hasError() == true
    }

    private var shellOutputHandler: MyShellHandler? = null

    @FunctionalInterface
    interface IActionEventHandler {
        fun onStart(forceStop: Runnable?)
        fun onSuccess()
        fun onCompleted()
    }

    class MyShellHandler(
            private var actionEventHandler: IActionEventHandler,
            private var logView: TextView,
            private var shellProgress: ProgressBar) : ShellHandlerBase() {

        private fun getColor(resId: Int): Int {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                context!!.getColor(resId)
            } else {
                context!!.resources.getColor(resId)
            }
        }

        private val context = logView.context
        private val errorColor = getColor(R.color.kr_shell_log_error)
        private val basicColor = getColor(R.color.kr_shell_log_basic)
        private val scriptColor = getColor(R.color.kr_shell_log_script)
        private val endColor = getColor(R.color.kr_shell_log_end)

        private var hasError = false
        private val outputBuffer = SpannableStringBuilder()
        private var lastProgressLength = 0
        private var lastProgressActive = false

        companion object {
            private const val MAX_OUTPUT_CHARS = 60000
            private val ANSI_PATTERN = Regex("(?:\\u001B)?\\[[0-?]*[ -/]*[@-~]")
            private val PERCENT_PATTERN = Regex("(\\d+(?:\\.\\d+)?)\\s*%")
        }

        fun hasError(): Boolean = hasError

        override fun handleMessage(msg: Message) {
            when (msg.what) {
                EVENT_EXIT -> onExit(msg.obj)
                EVENT_START -> onStart(msg.obj)
                EVENT_REDE -> onReaderMsg(msg.obj)
                EVENT_READ_ERROR -> onError(msg.obj)
                EVENT_WRITE -> onWrite(msg.obj)
            }
        }

        override fun onReader(msg: Any) {
            updateLog(msg, basicColor)
        }

        override fun onWrite(msg: Any) {
            updateLog(msg, scriptColor)
        }

        override fun onError(msg: Any) {
            hasError = true
            updateLog(msg, errorColor)
        }

        override fun onStart(forceStop: Runnable?) {
            actionEventHandler.onStart(forceStop)
        }

        override fun onProgress(current: Int, total: Int) {
            when (current) {
                -1 -> {
                    this.shellProgress.visibility = View.VISIBLE
                    this.shellProgress.isIndeterminate = true
                }
                total -> this.shellProgress.visibility = View.GONE
                else -> {
                    this.shellProgress.visibility = View.VISIBLE
                    this.shellProgress.isIndeterminate = false
                    this.shellProgress.max = total
                    this.shellProgress.progress = current
                }
            }
        }

        override fun onStart(msg: Any?) {
            this.logView.text = ""
            outputBuffer.clear()
            lastProgressLength = 0
            lastProgressActive = false
        }

        override fun onExit(msg: Any?) {
            updateLog(context.getString(if (hasError) R.string.kr_shell_finish_error else R.string.kr_shell_completed), endColor)
            actionEventHandler.onCompleted()
            if (!hasError) {
                actionEventHandler.onSuccess()
            }
        }

        override fun updateLog(msg: SpannableString?) {
            if (msg != null) {
                val spans = msg.getSpans(0, msg.length, ForegroundColorSpan::class.java)
                val color = if (spans.isNotEmpty()) spans[0].foregroundColor else basicColor
                val cleaned = ANSI_PATTERN.replace(msg.toString(), "")
                    .replace("\r", "")
                    .replace("\u0008", "")

                if (cleaned.isBlank()) {
                    return
                }

                this.logView.post {
                    appendCleanOutput(cleaned, color)
                    logView.text = outputBuffer
                    (logView.parent as ScrollView).fullScroll(ScrollView.FOCUS_DOWN)
                }
            }
        }

        private fun appendCleanOutput(text: String, color: Int) {
            val lines = text.split('\n')
            for (rawLine in lines) {
                val line = rawLine.trimEnd()
                if (line.isBlank()) continue

                val progressMatch = if (line.contains("Extract:", ignoreCase = true)) {
                    PERCENT_PATTERN.find(line)
                } else {
                    null
                }

                if (progressMatch != null) {
                    val progressText = context.getString(
                        R.string.kr_log_extracting,
                        progressMatch.groupValues[1]
                    )
                    val styled = SpannableString(progressText)
                    styled.setSpan(ForegroundColorSpan(endColor), 0, styled.length, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)

                    if (lastProgressActive && lastProgressLength <= outputBuffer.length) {
                        val start = outputBuffer.length - lastProgressLength
                        outputBuffer.replace(start, outputBuffer.length, styled)
                    } else {
                        if (outputBuffer.isNotEmpty()) outputBuffer.append('\n')
                        outputBuffer.append(styled)
                    }
                    lastProgressLength = styled.length
                    lastProgressActive = true
                } else {
                    if (lastProgressActive) {
                        outputBuffer.append('\n')
                        lastProgressActive = false
                        lastProgressLength = 0
                    }

                    val styled = SpannableString(line)
                    styled.setSpan(ForegroundColorSpan(color), 0, styled.length, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (outputBuffer.isNotEmpty()) outputBuffer.append('\n')
                    outputBuffer.append(styled)
                }
            }

            if (outputBuffer.length > MAX_OUTPUT_CHARS) {
                val textValue = outputBuffer.toString()
                val removeUntil = textValue.indexOf('\n', outputBuffer.length - MAX_OUTPUT_CHARS / 2)
                if (removeUntil >= 0) {
                    outputBuffer.delete(0, removeUntil + 1)
                }
                lastProgressActive = false
                lastProgressLength = 0
            }
        }
    }

    private fun closeView() {
        try {
            dismiss()
        } catch (_: java.lang.Exception) {
        }
    }

    private var onDismissRunnable: Runnable? = null
    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDismissRunnable?.run()
        onDismissRunnable = null
    }

    companion object {
        fun create(nodeInfo: RunnableNode,
                   onExit: Runnable,
                   onDismiss: Runnable,
                   script: String,
                   params: HashMap<String, String>?,
                   darkMode: Boolean = false): DialogLogFragment {
            val fragment = DialogLogFragment()
            fragment.nodeInfo = nodeInfo
            fragment.onExit = onExit
            fragment.script = script
            fragment.params = params
            fragment.themeResId = if (darkMode) R.style.kr_full_screen_dialog_dark else R.style.kr_full_screen_dialog_light
            fragment.onDismissRunnable = onDismiss

            return fragment
        }
    }
}
