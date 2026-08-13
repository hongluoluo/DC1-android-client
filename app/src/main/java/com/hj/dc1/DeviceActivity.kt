package com.hj.dc1

import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch

/** 单设备控制页：4开关 + 全开全关 + 电量统计 + 设备设置 */
class DeviceActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DEVICE_ID = "device_id"
    }

    private lateinit var store: DeviceStore
    private var deviceId: String = ""
    private val handler = Handler(Looper.getMainLooper())
    private val cards = mutableListOf<SwitchCard>()
    private var polling = false

    private lateinit var statusDot: View
    private lateinit var statusText: TextView

    private class SwitchCard(
        val index: Int,
        val nameTv: TextView,
        val subTv: TextView,
        val sw: MaterialSwitch,
        val card: MaterialCardView
    )

    private val poller = object : Runnable {
        override fun run() {
            pollStatus()
            handler.postDelayed(this, store.intervalMs)
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
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        handler.removeCallbacks(poller)
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
                isClickable = true
                isFocusable = true
                setOnClickListener { toggleSwitch(i) }
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
            }

            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val nameTv = TextView(this).apply {
                textSize = 18f
                setTextColor(ContextCompat.getColor(this@DeviceActivity, R.color.text_primary))
                typeface = Typeface.DEFAULT_BOLD
            }

            val subTv = TextView(this).apply {
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@DeviceActivity, R.color.text_secondary))
                text = getString(R.string.state_unknown)
            }

            val sw = MaterialSwitch(this).apply {
                setOnClickListener { toggleSwitch(i) }
            }

            texts.addView(nameTv)
            texts.addView(subTv)
            row.addView(texts)
            row.addView(sw)
            card.addView(row)
            container.addView(card)

            cards.add(SwitchCard(i, nameTv, subTv, sw, card))
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

    private fun applySwitchStates(data: org.json.JSONObject) {
        cards.forEach { c ->
            val on = data.optInt("power${c.index + 1}") == 1
            c.sw.isChecked = on
            c.subTv.text = if (on) getString(R.string.state_on) else getString(R.string.state_off)
        }
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

    private fun applyEnergy(data: org.json.JSONObject) {
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
