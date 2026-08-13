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
}
