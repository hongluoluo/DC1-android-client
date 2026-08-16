package com.hj.dc1

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 多设备配置存储：设备列表(JSON) + 全局刷新间隔 */
class DeviceStore(context: Context) {

    private val sp = context.getSharedPreferences("dc1", Context.MODE_PRIVATE)

    data class Device(
        val id: String,
        var name: String,
        var ip: String,
        var switchNames: MutableList<String> = mutableListOf("开关1", "开关2", "开关3", "开关4")
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("name", name)
            put("ip", ip)
            put("switches", JSONArray(switchNames))
        }

        companion object {
            fun fromJson(o: JSONObject): Device {
                val names = mutableListOf<String>()
                val arr = o.optJSONArray("switches")
                if (arr != null) {
                    for (i in 0 until arr.length()) names.add(arr.getString(i))
                }
                while (names.size < 4) names.add("开关${names.size + 1}")
                return Device(
                    o.getString("id"),
                    o.optString("name", o.optString("ip")),
                    o.getString("ip"),
                    names
                )
            }
        }
    }

    fun devices(): MutableList<Device> {
        val list = mutableListOf<Device>()
        val raw = sp.getString("devices", null)
        if (raw != null) {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) list.add(Device.fromJson(arr.getJSONObject(i)))
        }
        return list
    }

    fun saveDevices(list: List<Device>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp.edit().putString("devices", arr.toString()).apply()
    }

    fun find(id: String): Device? = devices().firstOrNull { it.id == id }

    fun addDevice(name: String, ip: String) {
        val list = devices()
        list.add(Device(System.currentTimeMillis().toString(), name, ip))
        saveDevices(list)
    }

    fun updateDevice(id: String, name: String, ip: String, switchNames: List<String>) {
        val list = devices()
        val d = list.firstOrNull { it.id == id } ?: return
        d.name = name
        d.ip = ip
        switchNames.forEachIndexed { i, n -> if (i < d.switchNames.size) d.switchNames[i] = n }
        saveDevices(list)
    }

    fun deleteDevice(id: String) {
        val list = devices().filterNot { it.id == id }
        saveDevices(list)
    }

    /** 全局刷新间隔（毫秒） */
    var intervalMs: Long
        get() = sp.getLong("interval", 2500)
        set(value) = sp.edit().putLong("interval", value).apply()

    /**
     * 定时任务的星期位掩码（本地存储，因固件 /get_status 不返回星期）
     * bit0=周一 .. bit6=周日
     */
    fun schedDays(deviceId: String, channel: Int): Int =
        sp.getInt("scheddays_${deviceId}_$channel", 0)

    fun setSchedDays(deviceId: String, channel: Int, days: Int) =
        sp.edit().putInt("scheddays_${deviceId}_$channel", days).apply()

    /** v1.0 单设备数据迁移 */
    fun migrateOldPrefs() {
        if (!sp.contains("devices")) {
            val oldIp = sp.getString("ip", "192.168.5.83")!!
            val dev = Device(
                id = System.currentTimeMillis().toString(),
                name = "插线板1",
                ip = oldIp
            )
            for (i in 0 until 4) {
                dev.switchNames[i] = sp.getString("name$i", "开关${i + 1}")!!
            }
            saveDevices(listOf(dev))
        }
    }
}
