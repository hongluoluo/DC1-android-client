package com.hj.dc1

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 斐讯DC1固件(qlwz esp_dc1) HTTP 控制接口
 *
 * POST /dc1_do   参数 c=1~4, do=on/off/T  → {code, msg, data:{power1..4, ...电量}}
 * POST /get_status 参数 i=0               → {code, msg, data:{...}}
 */
object Dc1Api {

    const val TIMEOUT_MS = 3000

    private fun post(ip: String, path: String, body: String): JSONObject? = try {
        val conn = URL("http://$ip$path").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = TIMEOUT_MS
        conn.readTimeout = TIMEOUT_MS
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        if (conn.responseCode == 200) {
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        } else null
    } catch (e: Exception) {
        null
    }

    /** 明确开/关某个通道（1~4），返回最新状态 data 或 null */
    fun setSwitch(ip: String, channel: Int, on: Boolean): JSONObject? =
        post(ip, "/dc1_do", "do=${if (on) "on" else "off"}&c=$channel")?.optJSONObject("data")

    /** 查询全部状态，返回 data 或 null */
    fun getStatus(ip: String): JSONObject? =
        post(ip, "/get_status", "i=0")?.optJSONObject("data")

    /**
     * 设置/取消倒计时（固件倒计时功能，需 v2020.07.11.2000+ 倒计时版固件）
     * @param seconds >0 设置倒计时(秒)，=0 取消
     * @param targetOn 倒计时结束后执行的动作：true=开启，false=关闭
     */
    fun setTimer(ip: String, channel: Int, seconds: Long, targetOn: Boolean): Boolean {
        val body = "timer_ch=$channel&timer_seconds=$seconds&timer_target=${if (targetOn) "on" else "off"}"
        return post(ip, "/dc1_setting", body)?.optInt("code") == 1
    }

    /**
     * 设置定时任务（每天指定时间开关，可指定星期）
     * @param onMin 开时间(分钟-of-day, 0-1439)，-1 表示不设置
     * @param offMin 关时间(分钟-of-day)，-1 表示不设置
     * @param days 星期位掩码：bit0=周一 .. bit6=周日
     */
    fun setSchedule(ip: String, channel: Int, onMin: Int, offMin: Int, days: Int): Boolean {
        val sb = StringBuilder("sched_ch=$channel")
        if (onMin >= 0) {
            sb.append("&sched_on_hh=${onMin / 60}&sched_on_mm=${onMin % 60}")
        }
        if (offMin >= 0) {
            sb.append("&sched_off_hh=${offMin / 60}&sched_off_mm=${offMin % 60}")
        }
        sb.append("&sched_days=$days")
        return post(ip, "/dc1_setting", sb.toString())?.optInt("code") == 1
    }

    /** 清除某通道的定时任务 */
    fun clearSchedule(ip: String, channel: Int): Boolean =
        post(ip, "/dc1_setting", "sched_ch=$channel&sched_clear=1")?.optInt("code") == 1
}
