package com.univerindream.maicaiassistant.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.os.Build
import android.view.*
import android.view.accessibility.AccessibilityEvent
import androidx.core.view.isGone
import com.blankj.utilcode.util.*
import com.elvishew.xlog.XLog
import com.univerindream.maicaiassistant.*
import com.univerindream.maicaiassistant.databinding.ActionBarBinding
import com.univerindream.maicaiassistant.databinding.ActionLogBinding
import kotlinx.coroutines.*
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong


class GlobalActionBarService : AccessibilityService() {

    private lateinit var actionBarBinding: ActionBarBinding
    private val mActionBarLayoutParams: WindowManager.LayoutParams by lazy {
        WindowManager.LayoutParams().also {
            it.format = PixelFormat.TRANSLUCENT
            it.flags =
                it.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            it.width = WindowManager.LayoutParams.WRAP_CONTENT
            it.height = WindowManager.LayoutParams.WRAP_CONTENT
            it.gravity = Gravity.START or Gravity.TOP
            it.x = ScreenUtils.getScreenWidth() - ConvertUtils.dp2px(40f)
            it.y = ScreenUtils.getScreenHeight() / 2 - ConvertUtils.dp2px(88f)
        }
    }

    private lateinit var actionLogBinding: ActionLogBinding
    private val mActionLogLayoutParams: WindowManager.LayoutParams by lazy {
        WindowManager.LayoutParams().also {
            it.format = PixelFormat.TRANSLUCENT
            it.flags =
                it.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            it.width = WindowManager.LayoutParams.WRAP_CONTENT
            it.height = WindowManager.LayoutParams.WRAP_CONTENT
            it.gravity = Gravity.START or Gravity.TOP
            it.x = ConvertUtils.dp2px(20f)
            it.y = ConvertUtils.dp2px(20f)
        }
    }

    private val mWindowManager by lazy {
        getSystemService(WINDOW_SERVICE) as WindowManager
    }

    private var mSnapUpStatus = AtomicBoolean(false)
    private var mLoopStartTime = AtomicLong(System.currentTimeMillis())

    private val mLogInfo = ArrayDeque<String>()
    private var mStepLog = LinkedHashMap<Int, MCStepLog>()

    private var mForegroundPackageName: String = ""
    private var mForegroundClassName: String = ""
    private var mForegroundWindowId: Int = -1

    private val mServiceScope = CoroutineScope(Dispatchers.Main + Job())

    private var mClassNameByWindowId = mutableMapOf<Int, String>()
    private val mCurClassNameByRootWindow: String
        get() = mClassNameByWindowId[rootInActiveWindow?.windowId] ?: ""

    override fun onCreate() {
        super.onCreate()

        MHUtil.startForegroundService(this)
    }

    override fun onServiceConnected() {
        XLog.v("onServiceConnected")

        // Create an overlay and display the action bar
        initBar()
        initLog()

        EventBus.getDefault().register(this)

        runLoop()
    }

    override fun onDestroy() {
        super.onDestroy()
        EventBus.getDefault().unregister(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pName = event.packageName?.toString() ?: ""
        val cName = event.className?.toString() ?: ""
        val windowId = event.windowId
        val source = event.source

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                XLog.v("TYPE_WINDOW_CONTENT_CHANGED - %s - %s", event, source)
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                XLog.v("TYPE_WINDOW_STATE_CHANGED - %s - %s", event, source)
                if (pName == "com.android.systemui" || pName == "android") return
                mForegroundPackageName = pName
                mForegroundClassName = cName
                mForegroundWindowId = windowId
                mClassNameByWindowId[windowId] = cName
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                XLog.v("TYPE_VIEW_CLICKED - %s - %s", event, source)
            }
            else -> {}
        }
    }

    override fun onInterrupt() {}

    @SuppressLint("ClickableViewAccessibility")
    private fun initBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            mActionBarLayoutParams.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            mActionBarLayoutParams.type = WindowManager.LayoutParams.TYPE_PHONE
        }
        actionBarBinding = ActionBarBinding.inflate(LayoutInflater.from(this))
        mWindowManager.addView(actionBarBinding.root, mActionBarLayoutParams)

        actionBarBinding.btnConfig.setOnClickListener {
            AppUtils.launchApp(AppUtils.getAppPackageName())
        }
        actionBarBinding.btnAlarmOff.setOnClickListener {
            MHUtil.stopRingTone()
        }
        actionBarBinding.btnSnapUp.setOnClickListener {
            if (mSnapUpStatus.get()) {
                cancelTask()
            } else {
                enableTask()
            }
        }

        var x = 0
        var y = 0
        actionBarBinding.btnMove.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    x = event.rawX.toInt()
                    y = event.rawY.toInt()
                }
                MotionEvent.ACTION_MOVE -> {
                    val nowX = event.rawX.toInt()
                    val nowY = event.rawY.toInt()
                    val movedX = nowX - x
                    val movedY = nowY - y
                    x = nowX
                    y = nowY

                    mActionBarLayoutParams.x = mActionBarLayoutParams.x + movedX
                    mActionBarLayoutParams.y = mActionBarLayoutParams.y + movedY
                    mWindowManager.updateViewLayout(actionBarBinding.root, mActionBarLayoutParams);
                }
            }
            return@setOnTouchListener true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initLog() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            mActionLogLayoutParams.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else {
            mActionLogLayoutParams.type = WindowManager.LayoutParams.TYPE_PHONE
        }
        actionLogBinding = ActionLogBinding.inflate(LayoutInflater.from(this))
        actionLogBinding.root.visibility = View.GONE
        mWindowManager.addView(actionLogBinding.root, mActionLogLayoutParams)

        actionLogBinding.ivLogClose.setOnClickListener {
            actionLogBinding.root.visibility = View.GONE
        }

        var x = 0
        var y = 0
        actionLogBinding.ivLogMove.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    x = event.rawX.toInt()
                    y = event.rawY.toInt()
                }
                MotionEvent.ACTION_MOVE -> {
                    val nowX = event.rawX.toInt()
                    val nowY = event.rawY.toInt()
                    val movedX = nowX - x
                    val movedY = nowY - y
                    x = nowX
                    y = nowY

                    mActionLogLayoutParams.x = mActionLogLayoutParams.x + movedX
                    mActionLogLayoutParams.y = mActionLogLayoutParams.y + movedY
                    mWindowManager.updateViewLayout(actionLogBinding.root, mActionLogLayoutParams);
                }
            }
            return@setOnTouchListener true
        }
    }

    private fun runLoop() {
        mServiceScope.launch {
            while (true) {
                if (mSnapUpStatus.get()) {
                    XLog.v("loop - curWindowClassName - $mCurClassNameByRootWindow")

                    //任务时长是否达标
                    if (System.currentTimeMillis() > mLoopStartTime.get() + MHData.buyMinTime * 1000 * 60) {
                        val message = "已执行了 ${MHData.buyMinTime} 分钟"
                        XLog.i("loop - %s", message)
                        MHUtil.notify("完成提示", message)
                        cancelTask()
                        continue
                    }

                    //循环执行步骤
                    val steps = MHConfig.curMCSolution.steps.filter { it.isEnable }
                    for ((index, step) in steps.withIndex()) {
                        val isMatchCond = step.condList.all {
                            MHUtil.stepCond(
                                rootInActiveWindow,
                                mForegroundPackageName,
                                mForegroundClassName,
                                it.type,
                                it.node
                            )
                        }
                        if (!isMatchCond) continue

                        //步骤只执行一次
                        if (step.isExecuteOnce && mStepLog.containsKey(index)) {
                            continue
                        }

                        XLog.v("steps - ${step.name}")

                        //更新日记
                        updateLogInfo(
                            "${
                                TimeUtils.millis2String(
                                    System.currentTimeMillis(),
                                    "mm:ss SSS"
                                )
                            } ${step.name}"
                        )

                        //步骤记录
                        val prevLogStep = mStepLog.values.lastOrNull()
                        if (prevLogStep == null || prevLogStep.index != index || step.isRepeat) {
                            mStepLog.remove(index)
                            mStepLog[index] = MCStepLog(index, step, System.currentTimeMillis())
                        }

                        //步骤警报
                        if (step.isAlarm) {
                            MHUtil.notify("响铃提示", "\"${step.name}\" 步骤，触发响铃")
                            MHUtil.playRingTone()
                        }

                        //步骤人工
                        if (step.isManual) {
                            MHUtil.notify("人工提示", "\"${step.name}\" 步骤，人工操作")
                            cancelTask()
                            break
                        }

                        val handleResult =
                            MHUtil.stepHandle(this@GlobalActionBarService, rootInActiveWindow, step.handle)
                        XLog.v("steps - ${step.name} - 执行结果 - $handleResult")

                        //步骤失败返回
                        if (step.isFailBack && !handleResult) {
                            repeat(step.failBackCount) {
                                performGlobalAction(GLOBAL_ACTION_BACK)
                                delay(200)
                            }
                            continue
                        }

                        //步骤不可重复且最后一个
                        if (!step.isRepeat && step == steps.last()) {
                            MHUtil.notify("终止提示", "\"${step.name}\" 为最后步骤，终止任务")
                            cancelTask()
                        }

                        //符合条件，终止本次流程
                        break
                    }

                    //10s 异常捕捉
                    mStepLog.values.lastOrNull()?.let {
                        val executeTime = it.executionTime
                        if (executeTime + 10 * 1000 < System.currentTimeMillis()) {
                            val message = "\"${it.step.name}\" 步骤执行失败!!!"
                            XLog.e("${it.step.name} %s", message)
                            MHUtil.notify("失败提示", message)
                            ToastUtils.showLong(message)
                            if (MHData.wrongAlarmStatus) MHUtil.playRingTone()
                            cancelTask()
                        }
                    }

                    delay(100)
                } else {
                    delay(1000)
                }
            }
        }
    }

    private fun enableTask() {
        mSnapUpStatus.set(true)
        mLoopStartTime.set(System.currentTimeMillis())
        mStepLog.clear()
        updateSnapUpButton()

        mLogInfo.clear()
        actionLogBinding.root.visibility = View.VISIBLE
    }

    private fun cancelTask() {
        mSnapUpStatus.set(false)
        updateSnapUpButton()

        actionLogBinding.root.visibility = View.GONE
    }

    private fun updateSnapUpButton() {
        if (mSnapUpStatus.get() == actionBarBinding.btnSnapUp.tag) return
        mServiceScope.launch {
            withContext(Dispatchers.Main) {
                actionBarBinding.btnSnapUp.setImageResource(if (mSnapUpStatus.get()) R.drawable.baseline_stop_24 else R.drawable.baseline_play_arrow_24)
            }
        }
    }

    private suspend fun updateLogInfo(info: String) {
        if (actionLogBinding.root.isGone) return

        if (mLogInfo.size >= 6) mLogInfo.removeLast()
        mLogInfo.addFirst(info)
        withContext(Dispatchers.Main) {
            actionLogBinding.tvLogInfo.text = mLogInfo.joinToString("\n")
        }
    }

    class SubAlarm

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun subAlarm(event: SubAlarm) {
        XLog.d("subAlarm")
        enableTask()
    }

}