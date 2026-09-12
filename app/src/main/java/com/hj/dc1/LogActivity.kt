package com.hj.dc1

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton

/** 开关操作记录页：查询/筛选某设备各通道的开关键盘记录 */
class LogActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_DEVICE_ID = "device_id"
    }

    private lateinit var store: DeviceStore
    private var deviceId: String = ""
    private lateinit var logContainer: LinearLayout
    private lateinit var tvTotal: TextView
    private lateinit var spCh: Spinner
    private lateinit var spAct: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        store = DeviceStore(this)
        deviceId = intent.getStringExtra(EXTRA_DEVICE_ID) ?: return finish()
        val d = store.find(deviceId) ?: return finish()

        supportActionBar?.title = getString(R.string.log_title, d.name)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        logContainer = findViewById(R.id.logContainer)
        tvTotal = findViewById(R.id.tvTotal)
        spCh = findViewById(R.id.spCh)
        spAct = findViewById(R.id.spAct)

        val chItems = arrayOf(getString(R.string.log_all_ch), "开关1", "开关2", "开关3", "开关4")
        spCh.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, chItems)
        val actItems = arrayOf(getString(R.string.log_all_act), getString(R.string.log_on), getString(R.string.log_off))
        spAct.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, actItems)

        // 切换筛选条件自动查询（初始化阶段监听器会立即触发一次，用标志位忽略）
        var spinnersReady = false
        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (spinnersReady) loadLog()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        spCh.onItemSelectedListener = listener
        spAct.onItemSelectedListener = listener
        spinnersReady = true

        findViewById<MaterialButton>(R.id.btnRefresh).setOnClickListener { loadLog() }
        loadLog()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun loadLog() {
        val d = store.find(deviceId) ?: return
        val ch = spCh.selectedItemPosition // 0=全部, 1..4=通道
        val act = when (spAct.selectedItemPosition) {
            1 -> "on"
            2 -> "off"
            else -> ""
        }
        tvTotal.text = getString(R.string.log_loading)
        val ip = d.ip
        Thread {
            val result = Dc1Api.getLog(ip, 50, ch, act)
            runOnUiThread {
                if (result == null) {
                    tvTotal.text = getString(R.string.log_fail)
                    logContainer.removeAllViews()
                } else {
                    val (total, rows) = result
                    tvTotal.text = if (total > rows.size) {
                        getString(R.string.log_total_limited, total, rows.size)
                    } else {
                        getString(R.string.log_total, total)
                    }
                    render(rows)
                }
            }
        }.start()
    }

    private fun render(rows: List<LogEntry>) {
        logContainer.removeAllViews()
        if (rows.isEmpty()) {
            val empty = TextView(this).apply {
                text = getString(R.string.log_empty)
                gravity = Gravity.CENTER
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@LogActivity, R.color.text_secondary))
                setPadding(0, dp(48), 0, dp(48))
            }
            logContainer.addView(empty)
            return
        }
        rows.forEach { e ->
            logContainer.addView(buildRow(e))
        }
    }

    private fun buildRow(e: LogEntry): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(7), dp(4), dp(7))
        }

        val tvTime = TextView(this).apply {
            text = e.time
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@LogActivity, R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvCh = TextView(this).apply {
            text = "开关${e.channel}"
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@LogActivity, R.color.text_primary))
            setPadding(dp(10), 0, dp(10), 0)
        }

        val tvAct = TextView(this).apply {
            text = if (e.on) getString(R.string.log_on) else getString(R.string.log_off)
            textSize = 12f
            setTextColor(
                ContextCompat.getColor(
                    this@LogActivity,
                    if (e.on) R.color.on_color else R.color.bad_color
                )
            )
        }

        val tvSrc = TextView(this).apply {
            text = e.source
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@LogActivity, R.color.text_secondary))
            setPadding(dp(10), 0, 0, 0)
        }

        row.addView(tvTime)
        row.addView(tvCh)
        row.addView(tvAct)
        row.addView(tvSrc)
        return row
    }

    private fun dp(v: Int): Int = (resources.displayMetrics.density * v).toInt()
}
