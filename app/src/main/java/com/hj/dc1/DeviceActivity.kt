package com.hj.dc1

import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import org.json.JSONObject

/** 单设备控制页：4开关 + 倒计时 + 定时任务 + 全开全关 + 电量统计 + 设备设置 */
class DeviceActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DEVICE_ID = "device_id"
    }

    private lateinit var store: DeviceStore
    private var deviceId: String = ""
    private val handler = Handler(Looper.getMainLooper())
    private val cards = mutableListOf<SwitchCard>()
    private var polling = false

    /** 每通道倒计时剩余秒（本地每秒递减显示，轮询校正） */
    private val timerRemaining = LongArray(4)
    /** 每通道倒计时目标动作（true=到时开启） */
    private val timerTargets = BooleanArray(4)
    /** 固件是否支持倒计时（状态里无 timer1 字段则为老固件） */
    private var timerSupported = false

    /** 每通道定时任务状态字符串（固件返回 "HH:MM-HH:MM" / "--" 等） */
    private val schedStatus = Array(4) { "--" }
    /** 固件是否支持定时任务（状态里无 sched1 字段则为老固件） */
    private var schedSupported = false

    private lateinit var statusDot: View
    private lateinit var statusText: TextView

    private class SwitchCard(
        val index: Int,
        val nameTv: TextView,
        val subTv: TextView,
        val sw: DcSwitch,
        val card: MaterialCardView,
        val timerTv: TextView,
        val timerBtn: TextView,
        val schedTv: TextView,
        val schedBtn: TextView
    )

    private val poller = object : Runnable {
        override fun run() {
            pollStatus()
            handler.postDelayed(this, store.intervalMs)
        }
    }

    /** 每秒本地递减倒计时显示，保证秒级平滑（不产生网络请求） */
    private val ticker = object : Runnable {
        override fun run() {
            var active = false
            for (i in 0 until 4) {
                if (timerRemaining[i] > 0) {
                    timerRemaining[i]--
                    active = true
                }
            }
            if (active) updateTimerViews()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device)

        store = DeviceStore(this)
        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: return finish()
        val device = store.find(deviceId) ?: return finish()

        supportActionBar?.title = device.name
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        findViewById<ImageButton>(R.id.btnDeviceSettings).setOnClickListener { showSettings() }
        findViewById<MaterialButton>(R.id.btnAllOn).setOnClickListener { sendAll(true) }
        findViewById<MaterialButton>(R.id.btnAllOff).setOnClickListener { sendAll(false) }

        buildSwitchCards()
        initEnergyLabels()
        refreshCardNames()

        handler.post(poller)
        handler.post(ticker)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        handler.removeCallbacks(poller)
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    private fun device(): DeviceStore.Device? = store.find(deviceId)

    // ---------- 开关卡片 ----------

    private fun buildSwitchCards() {
        val container = findViewById<LinearLayout>(R.id.cardContainer)
        for (i in 0 until 4) {
            val card = MaterialCardView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
                radius = dp(12).toFloat()
                cardElevation = dp(1).toFloat()
                // 触屏控制仅限拨钮与倒计时按钮本身，整卡不可点击
                isClickable = false
                isFocusable = false
            }

            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
            }

            // 第一行：左侧信息 + 中间开关拨钮 + 右侧倒计时按钮
            val row1 = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            // 左侧：名称 + 状态（两行）
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val nameTv = TextView(this).apply {
                textSize = 18f
                setTextColor(ContextCompat.getColor(this@DeviceActivity, R.color.text_primary))
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            val subTv = TextView(this).apply {
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@DeviceActivity, R.color.text_secondary))
                text = getString(R.string.state_unknown)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // 中间列：开关拨钮居中，下方浅色字标注"开关"；整列可点击=触摸区域
            val centerCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                isClickable = true
                isFocusable = true
                setOnClickListener { toggleSwitch(i) }
            }

            val sw = DcSwitch(this).apply {
                setOnClickListener { toggleSwitch(i) }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            val swLabel = TextView(this).apply {
                text = getString(R.string.switch_label)
                textSize = 10f
                setTextColor(ContextCompat.getColor(this@DeviceActivity, R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            centerCol.addView(sw)
            centerCol.addView(swLabel)

            // 右侧列：整体靠右对齐，内部垂直排列（按钮贴右缘，状态文字居中于按钮下）
            val rightCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val rightInner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // 倒计时按钮（紫色圆角）
            val timerBtn = TextView(this).apply {
                text = getString(R.string.timer_btn)
                textSize = 12f
                isClickable = true
                isFocusable = true
                setTextColor(Color.WHITE)
                setBackgroundResource(R.drawable.bg_timer_btn)
                setPadding(dp(14), dp(6), dp(14), dp(6))
                gravity = Gravity.CENTER
                setOnClickListener { showTimerDialog(i) }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // 倒计时状态（无倒计时 / ⏱ 剩余时间），在按钮下方
            val timerTv = TextView(this).apply {
                text = getString(R.string.timer_none)
                textSize = 10f
                maxLines = 1
                setTextColor(ContextCompat.getColor(this@DeviceActivity, R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // 定时按钮（紫色圆角，与倒计时按钮同风格）
            val schedBtn = TextView(this).apply {
                text = getString(R.string.sched_btn)
                textSize = 12f
                isClickable = true
                isFocusable = true
                setTextColor(Color.WHITE)
                setBackgroundResource(R.drawable.bg_timer_btn)
                setPadding(dp(14), dp(6), dp(14), dp(6))
                gravity = Gravity.CENTER
                setOnClickListener { showScheduleDialog(i) }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            // 定时状态（无定时 / 时间区间），在定时按钮下方
            val schedTv = TextView(this).apply {
                text = getString(R.string.sched_none)
                textSize = 10f
                maxLines = 1
                setTextColor(ContextCompat.getColor(this@DeviceActivity, R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            rightInner.addView(timerBtn)
            rightInner.addView(timerTv)
            rightInner.addView(schedBtn)
            rightInner.addView(schedTv)
            rightCol.addView(rightInner)

            texts.addView(nameTv)
            texts.addView(subTv)
            row1.addView(texts)
            row1.addView(centerCol)
            row1.addView(rightCol)

            col.addView(row1)
            card.addView(col)
            container.addView(card)

            cards.add(SwitchCard(i, nameTv, subTv, sw, card, timerTv, timerBtn, schedTv, schedBtn))
        }
    }

    private fun refreshCardNames() {
        val d = device() ?: return
        cards.forEach { c ->
            c.nameTv.text = d.switchNames.getOrElse(c.index) { "开关${c.index + 1}" }
        }
    }

    private fun toggleSwitch(i: Int) {
        val card = cards[i]
        val target = !card.sw.isChecked
        card.sw.isChecked = target // 乐观更新，失败后由轮询纠正
        card.subTv.text = if (target) getString(R.string.state_on) else getString(R.string.state_off)

        val d = device() ?: return
        val ip = d.ip
        Thread {
            val data = Dc1Api.setSwitch(ip, i + 1, target)
            runOnUiThread {
                if (data == null) {
                    Toast.makeText(this, R.string.toast_fail, Toast.LENGTH_SHORT).show()
                    pollStatus()
                } else {
                    applySwitchStates(data)
                    applyEnergy(data)
                    applyTimer(data)
                    applySchedule(data)
                    setOnline(true)
                }
            }
        }.start()
    }

    private fun sendAll(on: Boolean) {
        val d = device() ?: return
        val ip = d.ip
        Toast.makeText(this, if (on) R.string.all_on else R.string.all_off, Toast.LENGTH_SHORT).show()
        Thread {
            for (c in 1..4) {
                Dc1Api.setSwitch(ip, c, on)
            }
            pollStatus()
        }.start()
    }

    private fun applySwitchStates(data: JSONObject) {
        cards.forEach { c ->
            val on = data.optInt("power${c.index + 1}") == 1
            c.sw.isChecked = on
            c.subTv.text = if (on) getString(R.string.state_on) else getString(R.string.state_off)
        }
    }

    // ---------- 倒计时 ----------

    /** 从状态JSON解析倒计时字段（老固件无 timer1 字段 → 不支持） */
    private fun applyTimer(data: JSONObject) {
        if (data.has("timer1")) {
            timerSupported = true
            for (i in 0 until 4) {
                timerRemaining[i] = data.optLong("timer${i + 1}")
                timerTargets[i] = data.optString("timer${i + 1}target") == "on"
            }
        } else if (timerSupported) {
            // 固件被降级/状态异常：清空显示
            for (i in 0 until 4) timerRemaining[i] = 0
        }
        updateTimerViews()
    }

    private fun updateTimerViews() {
        cards.forEach { c ->
            val i = c.index
            val rem = timerRemaining[i]
            val showTimer = timerSupported
            // 老固件不支持倒计时时隐藏倒计时状态与按钮
            c.timerTv.visibility = if (showTimer) View.VISIBLE else View.GONE
            c.timerBtn.visibility = if (showTimer) View.VISIBLE else View.GONE
            if (!showTimer) return@forEach

            if (rem > 0) {
                val text = if (timerTargets[i]) {
                    getString(R.string.timer_active_on, formatRemaining(rem))
                } else {
                    getString(R.string.timer_active_off, formatRemaining(rem))
                }
                c.timerTv.text = text
                c.timerTv.setTextColor(ContextCompat.getColor(this, R.color.on_color))
            } else {
                c.timerTv.text = getString(R.string.timer_none)
                c.timerTv.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            }
        }
    }

    private fun formatRemaining(sec: Long): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return if (h > 0) {
            "%d:%02d:%02d".format(h, m, s)
        } else {
            "%02d:%02d".format(m, s)
        }
    }

    private fun showTimerDialog(i: Int) {
        if (!timerSupported) {
            Toast.makeText(this, R.string.timer_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        val d = device() ?: return
        val view = layoutInflater.inflate(R.layout.dialog_timer, null)
        val etH = view.findViewById<EditText>(R.id.etHours)
        val etM = view.findViewById<EditText>(R.id.etMins)
        val rbOn = view.findViewById<RadioButton>(R.id.rbOn)
        val rbOff = view.findViewById<RadioButton>(R.id.rbOff)
        val channel = i + 1

        // 已有倒计时则预填（取最近一次状态）
        val rem = timerRemaining[i]
        if (rem > 0) {
            etH.setText((rem / 3600).toString())
            etM.setText(((rem % 3600) / 60).toString())
        }
        if (timerTargets[i]) rbOn.isChecked = true else rbOff.isChecked = true

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.timer_btn) + " · ${d.switchNames.getOrElse(i) { "开关$channel" }}")
            .setView(view)
            .setNegativeButton(R.string.d_cancel, null)
            .setNeutralButton(R.string.timer_cancel_btn) { _, _ -> sendTimer(channel, 0, false) }
            .setPositiveButton(R.string.timer_start) { _, _ ->
                val h = etH.text.toString().toLongOrNull() ?: 0
                val m = etM.text.toString().toLongOrNull() ?: 0
                val total = h * 3600 + m * 60
                if (total <= 0 || total > 24 * 3600 - 1) {
                    Toast.makeText(this, R.string.timer_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                sendTimer(channel, total, rbOn.isChecked)
            }
            .show()
    }

    private fun sendTimer(channel: Int, seconds: Long, targetOn: Boolean) {
        val d = device() ?: return
        val ip = d.ip
        Thread {
            val ok = Dc1Api.setTimer(ip, channel, seconds, targetOn)
            runOnUiThread {
                if (ok) {
                    pollStatus()
                } else {
                    Toast.makeText(this, R.string.toast_fail, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    // ---------- 定时任务 ----------

    /** 从状态JSON解析定时任务字段（老固件无 sched1 字段 → 不支持） */
    private fun applySchedule(data: JSONObject) {
        if (data.has("sched1")) {
            schedSupported = true
            for (i in 0 until 4) {
                schedStatus[i] = data.optString("sched${i + 1}", "--")
            }
        } else if (schedSupported) {
            for (i in 0 until 4) schedStatus[i] = "--"
        }
        updateScheduleViews()
    }

    private fun updateScheduleViews() {
        cards.forEach { c ->
            val i = c.index
            val showSched = schedSupported
            c.schedTv.visibility = if (showSched) View.VISIBLE else View.GONE
            c.schedBtn.visibility = if (showSched) View.VISIBLE else View.GONE
            if (!showSched) return@forEach

            val st = schedStatus[i]
            if (st == "--" || st.isEmpty()) {
                c.schedTv.text = getString(R.string.sched_none)
                c.schedTv.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            } else {
                c.schedTv.text = "⏰ $st"
                c.schedTv.setTextColor(ContextCompat.getColor(this, R.color.sched_color))
            }
        }
    }

    private fun showScheduleDialog(i: Int) {
        if (!schedSupported) {
            Toast.makeText(this, R.string.sched_unsupported, Toast.LENGTH_SHORT).show()
            return
        }
        val d = device() ?: return
        val view = layoutInflater.inflate(R.layout.dialog_schedule, null)
        val etOnHh = view.findViewById<EditText>(R.id.etOnHh)
        val etOnMm = view.findViewById<EditText>(R.id.etOnMm)
        val etOffHh = view.findViewById<EditText>(R.id.etOffHh)
        val etOffMm = view.findViewById<EditText>(R.id.etOffMm)
        val dayContainer = view.findViewById<LinearLayout>(R.id.dayContainer)
        val channel = i + 1

        // 从当前状态解析开/关时间（"HH:MM-HH:MM" 等格式）
        val st = schedStatus[i]
        var onHh = -1; var onMm = -1; var offHh = -1; var offMm = -1
        if (st.length >= 5 && st[2] == ':') {
            val parts = st.split("-")
            if (parts.size >= 1 && parts[0].length == 5 && parts[0][2] == ':') {
                onHh = parts[0].substring(0, 2).toIntOrNull() ?: -1
                onMm = parts[0].substring(3, 5).toIntOrNull() ?: -1
            }
            if (parts.size >= 2 && parts[1].length == 5 && parts[1][2] == ':') {
                offHh = parts[1].substring(0, 2).toIntOrNull() ?: -1
                offMm = parts[1].substring(3, 5).toIntOrNull() ?: -1
            }
        }
        if (onHh >= 0) { etOnHh.setText(onHh.toString()); etOnMm.setText(onMm.toString()) }
        if (offHh >= 0) { etOffHh.setText(offHh.toString()); etOffMm.setText(offMm.toString()) }

        // 星期选择（bit0=周一..bit6=周日）
        val weekLabels = arrayOf("一", "二", "三", "四", "五", "六", "日")
        var days = store.schedDays(deviceId, channel)
        val dayViews = mutableListOf<TextView>()
        for (k in 0 until 7) {
            val tv = TextView(this).apply {
                text = weekLabels[k]
                textSize = 13f
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply {
                    marginEnd = dp(4)
                }
                setOnClickListener {
                    val bit = 1 shl k
                    days = days xor bit
                    refreshDayButtons(dayViews, days)
                }
            }
            dayViews.add(tv)
            dayContainer.addView(tv)
        }
        refreshDayButtons(dayViews, days)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.sched_title) + " · ${d.switchNames.getOrElse(i) { "开关$channel" }}")
            .setView(view)
            .setNegativeButton(R.string.d_cancel, null)
            .setNeutralButton(R.string.sched_clear) { _, _ -> sendClearSchedule(channel) }
            .setPositiveButton(R.string.d_save) { _, _ ->
                val onH = etOnHh.text.toString().toIntOrNull()
                val onM = etOnMm.text.toString().toIntOrNull()
                val offH = etOffHh.text.toString().toIntOrNull()
                val offM = etOffMm.text.toString().toIntOrNull()
                var onMin = -1
                var offMin = -1
                if (onH != null && onM != null && onH in 0..23 && onM in 0..59) onMin = onH * 60 + onM
                if (offH != null && offM != null && offH in 0..23 && offM in 0..59) offMin = offH * 60 + offM
                if (onMin < 0 && offMin < 0) {
                    Toast.makeText(this, R.string.sched_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                sendSchedule(channel, onMin, offMin, days)
            }
            .show()
    }

    private fun refreshDayButtons(dayViews: List<TextView>, days: Int) {
        dayViews.forEachIndexed { k, tv ->
            val on = (days shr k) and 1 == 1
            if (on) {
                tv.setBackgroundResource(R.drawable.bg_timer_btn)
                tv.setTextColor(Color.WHITE)
            } else {
                tv.setBackgroundResource(R.drawable.bg_day_off)
                tv.setTextColor(0xFF616161.toInt())
            }
        }
    }

    private fun sendSchedule(channel: Int, onMin: Int, offMin: Int, days: Int) {
        val d = device() ?: return
        val ip = d.ip
        store.setSchedDays(deviceId, channel, days)
        Thread {
            val ok = Dc1Api.setSchedule(ip, channel, onMin, offMin, days)
            runOnUiThread {
                if (ok) {
                    pollStatus()
                } else {
                    Toast.makeText(this, R.string.toast_fail, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun sendClearSchedule(channel: Int) {
        val d = device() ?: return
        val ip = d.ip
        store.setSchedDays(deviceId, channel, 0)
        Thread {
            val ok = Dc1Api.clearSchedule(ip, channel)
            runOnUiThread {
                if (ok) {
                    pollStatus()
                } else {
                    Toast.makeText(this, R.string.toast_fail, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    // ---------- 状态轮询 ----------

    private fun pollStatus() {
        if (polling) return
        polling = true
        val d = device() ?: return
        val ip = d.ip
        Thread {
            val data = Dc1Api.getStatus(ip)
            runOnUiThread {
                polling = false
                if (data == null) {
                    setOnline(false)
                } else {
                    applySwitchStates(data)
                    applyEnergy(data)
                    applyTimer(data)
                    applySchedule(data)
                    setOnline(true)
                }
            }
        }.start()
    }

    private fun setOnline(online: Boolean) {
        statusDot.background = ContextCompat.getDrawable(
            this, if (online) R.drawable.dot_online else R.drawable.dot_bad
        )
        val d = device()
        statusText.text = if (online) {
            getString(R.string.status_online, d?.ip ?: "")
        } else {
            getString(R.string.status_offline)
        }
    }

    // ---------- 电量显示 ----------

    private fun initEnergyLabels() {
        setEnergyLabel(R.id.volt, R.string.e_voltage)
        setEnergyLabel(R.id.amp, R.string.e_current)
        setEnergyLabel(R.id.watt, R.string.e_power)
        setEnergyLabel(R.id.factor, R.string.e_factor)
        setEnergyLabel(R.id.today, R.string.e_today)
        setEnergyLabel(R.id.yesterday, R.string.e_yesterday)
        setEnergyLabel(R.id.total, R.string.e_total)
    }

    private fun setEnergyLabel(cellId: Int, strId: Int) {
        findViewById<View>(cellId).findViewById<TextView>(R.id.label).setText(strId)
    }

    private fun applyEnergy(data: JSONObject) {
        setEnergyValue(R.id.volt, data.optString("voltage", "") + " V")
        setEnergyValue(R.id.amp, data.optString("current", "") + " A")
        setEnergyValue(R.id.watt, data.optString("power", "") + " W")
        setEnergyValue(R.id.factor, data.optString("factor", ""))
        setEnergyValue(R.id.today, data.optString("today", "") + " kWh")
        setEnergyValue(R.id.yesterday, data.optString("yesterday", "") + " kWh")
        setEnergyValue(R.id.total, data.optString("total", "") + " kWh")
    }

    private fun setEnergyValue(cellId: Int, text: String) {
        findViewById<View>(cellId).findViewById<TextView>(R.id.value).text = text
    }

    // ---------- 设备设置 ----------

    private fun showSettings() {
        val d = device() ?: return
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val etName = view.findViewById<EditText>(R.id.etDevName)
        val etIp = view.findViewById<EditText>(R.id.etIp)
        val etName1 = view.findViewById<EditText>(R.id.etName1)
        val etName2 = view.findViewById<EditText>(R.id.etName2)
        val etName3 = view.findViewById<EditText>(R.id.etName3)
        val etName4 = view.findViewById<EditText>(R.id.etName4)
        val names = listOf(etName1, etName2, etName3, etName4)

        etName.setText(d.name)
        etIp.setText(d.ip)
        names.forEachIndexed { i, et ->
            et.setText(d.switchNames.getOrElse(i) { "开关${i + 1}" })
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.d_title)
            .setView(view)
            .setNegativeButton(R.string.d_cancel, null)
            .setPositiveButton(R.string.d_save) { _, _ ->
                val newName = etName.text.toString().trim().ifEmpty { d.name }
                val newIp = etIp.text.toString().trim()
                if (newIp.isEmpty()) {
                    Toast.makeText(this, R.string.toast_fail, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val newNames = names.map { et ->
                    et.text.toString().trim().ifEmpty { "开关" }
                }
                store.updateDevice(d.id, newName, newIp, newNames)
                supportActionBar?.title = newName
                refreshCardNames()
                handler.removeCallbacks(poller)
                handler.post(poller)
                Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ---------- 工具 ----------

    private fun dp(v: Int): Int = (resources.displayMetrics.density * v).toInt()
}
