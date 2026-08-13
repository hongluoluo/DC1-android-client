package com.hj.dc1

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
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

class MainActivity : AppCompatActivity() {

    private lateinit var store: DeviceStore
    private lateinit var listContainer: LinearLayout
    private val handler = Handler(Looper.getMainLooper())
    private val busy = mutableSetOf<String>()
    private val cardViews = mutableMapOf<String, DeviceCard>()

    private class DeviceCard(
        val statusTv: TextView,
        val ipTv: TextView,
        val dot: View,
        val switchDots: List<View>
    )

    private val poller = object : Runnable {
        override fun run() {
            refreshList()
            handler.postDelayed(this, store.intervalMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        store = DeviceStore(this)
        store.migrateOldPrefs()

        listContainer = findViewById(R.id.deviceList)
        findViewById<MaterialButton>(R.id.btnAddDevice).setOnClickListener { showAddDialog() }
        findViewById<ImageButton>(R.id.btnGlobalSettings).setOnClickListener { showIntervalDialog() }

        handler.post(poller)
    }

    override fun onResume() {
        super.onResume()
        refreshList() // 从详情页返回后刷新（可能改了别名/IP）
    }

    override fun onDestroy() {
        handler.removeCallbacks(poller)
        super.onDestroy()
    }

    // ---------- 列表渲染 ----------

    private fun refreshList() {
        listContainer.removeAllViews()
        cardViews.clear()

        val devices = store.devices()
        if (devices.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.empty_devices)
                gravity = Gravity.CENTER
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
                setPadding(0, dp(48), 0, dp(48))
            }
            listContainer.addView(empty)
            return
        }

        devices.forEach { d ->
            listContainer.addView(buildCard(d))
            pollDevice(d.id)
        }
    }

    private fun buildCard(d: DeviceStore.Device): MaterialCardView {
        val card = MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            radius = dp(12).toFloat()
            cardElevation = dp(1).toFloat()
            isClickable = true
            isFocusable = true
            setOnClickListener {
                startActivity(
                    Intent(this@MainActivity, DeviceActivity::class.java)
                        .putExtra(DeviceActivity.EXTRA_DEVICE_ID, d.id)
                )
            }
            setOnLongClickListener {
                showDeviceActions(d)
                true
            }
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        // 第一行：名称 + 状态点 + 状态文字
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val nameTv = TextView(this).apply {
            text = d.name
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val dot = View(this).apply {
            background = ContextCompat.getDrawable(this@MainActivity, R.drawable.dot_offline)
            layoutParams = LinearLayout.LayoutParams(dp(12), dp(12))
        }
        val statusTv = TextView(this).apply {
            text = getString(R.string.checking)
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
        }
        row1.addView(nameTv)
        row1.addView(dot)
        row1.addView(statusTv)

        // 第二行：IP + 4个开关状态点
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        val ipTv = TextView(this).apply {
            text = d.ip
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val switchDots = (0 until 4).map { i ->
            View(this).apply {
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.dot_off)
                layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                    marginStart = dp(6)
                }
            }
        }
        row2.addView(ipTv)
        switchDots.forEach { row2.addView(it) }

        col.addView(row1)
        col.addView(row2)
        card.addView(col)

        cardViews[d.id] = DeviceCard(statusTv, ipTv, dot, switchDots)
        return card
    }

    // ---------- 状态轮询 ----------

    private fun pollDevice(id: String) {
        if (id in busy) return
        busy += id
        val d = store.find(id) ?: return

        Thread {
            val data = Dc1Api.getStatus(d.ip)
            runOnUiThread {
                busy -= id
                val card = cardViews[id] ?: return@runOnUiThread
                if (data == null) {
                    card.statusTv.text = getString(R.string.offline)
                    card.statusTv.setTextColor(
                        ContextCompat.getColor(this, R.color.bad_color)
                    )
                    card.dot.background = ContextCompat.getDrawable(this, R.drawable.dot_bad)
                    card.switchDots.forEach {
                        it.background = ContextCompat.getDrawable(this, R.drawable.dot_off)
                    }
                } else {
                    card.statusTv.text = getString(R.string.online)
                    card.statusTv.setTextColor(
                        ContextCompat.getColor(this, R.color.on_color)
                    )
                    card.dot.background = ContextCompat.getDrawable(this, R.drawable.dot_online)
                    card.switchDots.forEachIndexed { i, v ->
                        val on = data.optInt("power${i + 1}") == 1
                        v.background = ContextCompat.getDrawable(
                            this, if (on) R.drawable.dot_on else R.drawable.dot_off
                        )
                    }
                }
            }
        }.start()
    }

    // ---------- 添加/编辑/删除 ----------

    private fun showAddDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_device, null)
        val etName = view.findViewById<EditText>(R.id.etDevName)
        val etIp = view.findViewById<EditText>(R.id.etIp)

        AlertDialog.Builder(this)
            .setTitle(R.string.add_title)
            .setView(view)
            .setNegativeButton(R.string.d_cancel, null)
            .setPositiveButton(R.string.d_ok) { _, _ ->
                val name = etName.text.toString().trim().ifEmpty { "插线板${store.devices().size + 1}" }
                val ip = etIp.text.toString().trim()
                if (ip.isNotEmpty()) {
                    store.addDevice(name, ip)
                    refreshList()
                    Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.toast_fail, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun showDeviceActions(d: DeviceStore.Device) {
        val options = arrayOf(getString(R.string.action_edit), getString(R.string.action_delete))
        AlertDialog.Builder(this)
            .setTitle(d.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showEditDialog(d)
                    1 -> confirmDelete(d)
                }
            }
            .show()
    }

    private fun showEditDialog(d: DeviceStore.Device) {
        val view = layoutInflater.inflate(R.layout.dialog_device, null)
        val etName = view.findViewById<EditText>(R.id.etDevName)
        val etIp = view.findViewById<EditText>(R.id.etIp)
        etName.setText(d.name)
        etIp.setText(d.ip)

        AlertDialog.Builder(this)
            .setTitle(R.string.edit_title)
            .setView(view)
            .setNegativeButton(R.string.d_cancel, null)
            .setPositiveButton(R.string.d_save) { _, _ ->
                val name = etName.text.toString().trim().ifEmpty { d.name }
                val ip = etIp.text.toString().trim()
                if (ip.isNotEmpty()) {
                    store.updateDevice(d.id, name, ip, d.switchNames)
                    refreshList()
                } else {
                    Toast.makeText(this, R.string.toast_fail, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun confirmDelete(d: DeviceStore.Device) {
        AlertDialog.Builder(this)
            .setTitle(R.string.action_delete)
            .setMessage(getString(R.string.confirm_delete, d.name))
            .setNegativeButton(R.string.d_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                store.deleteDevice(d.id)
                refreshList()
                Toast.makeText(this, R.string.toast_deleted, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ---------- 全局设置 ----------

    private fun showIntervalDialog() {
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setText((store.intervalMs / 1000).toString())
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.d_interval)
            .setView(et)
            .setNegativeButton(R.string.d_cancel, null)
            .setPositiveButton(R.string.d_save) { _, _ ->
                val secs = et.text.toString().toLongOrNull()?.coerceIn(1, 10) ?: 3
                store.intervalMs = secs * 1000
                handler.removeCallbacks(poller)
                handler.post(poller)
                Toast.makeText(this, R.string.toast_saved, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ---------- 工具 ----------

    private fun dp(v: Int): Int = (resources.displayMetrics.density * v).toInt()
}
