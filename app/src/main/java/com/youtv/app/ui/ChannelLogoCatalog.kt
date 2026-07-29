package com.youtv.app.ui

import androidx.annotation.DrawableRes
import com.youtv.app.R
import java.util.Locale

internal object ChannelLogoCatalog {
    @DrawableRes
    fun drawableFor(channelName: String): Int? = when (keyFor(channelName)) {
        "cctv_1" -> R.drawable.cctv_1
        "cctv_2" -> R.drawable.cctv_2
        "cctv_3" -> R.drawable.cctv_3
        "cctv_4" -> R.drawable.cctv_4
        "cctv_5" -> R.drawable.cctv_5
        "cctv_5_plus" -> R.drawable.cctv_5_plus
        "cctv_6" -> R.drawable.cctv_6
        "cctv_7" -> R.drawable.cctv_7
        "cctv_8" -> R.drawable.cctv_8
        "cctv_9" -> R.drawable.cctv_9
        "cctv_10" -> R.drawable.cctv_10
        "cctv_11" -> R.drawable.cctv_11
        "cctv_12" -> R.drawable.cctv_12
        "cctv_13" -> R.drawable.cctv_13
        "cctv_14" -> R.drawable.cctv_14
        "cctv_15" -> R.drawable.cctv_15
        "cctv_16" -> R.drawable.cctv_16
        "cctv_17" -> R.drawable.cctv_17
        "cctv_4k" -> R.drawable.cctv_4k
        "cctv_8k" -> R.drawable.cctv_8k
        "cctv_4_europe" -> R.drawable.cctv_4_europe
        "cctv_4_americas" -> R.drawable.cctv_4_americas
        "cetv_1" -> R.drawable.cetv_1
        "cetv_2" -> R.drawable.cetv_2
        "cetv_3" -> R.drawable.cetv_3
        "cetv_4" -> R.drawable.cetv_4
        "cgtn" -> R.drawable.cgtn
        "cgtn_documentary" -> R.drawable.cgtn_documentary
        "cgtn_french" -> R.drawable.cgtn_french
        "cgtn_russian" -> R.drawable.cgtn_russian
        "cgtn_spanish" -> R.drawable.cgtn_spanish
        "cgtn_arabic" -> R.drawable.cgtn_arabic
        "satellite_anhui" -> R.drawable.satellite_anhui
        "satellite_beijing" -> R.drawable.satellite_beijing
        "satellite_bingtuan" -> R.drawable.satellite_bingtuan
        "satellite_chongqing" -> R.drawable.satellite_chongqing
        "satellite_dongfang" -> R.drawable.satellite_dongfang
        "satellite_dongnan" -> R.drawable.satellite_dongnan
        "satellite_gansu" -> R.drawable.satellite_gansu
        "satellite_guangdong" -> R.drawable.satellite_guangdong
        "satellite_guangxi" -> R.drawable.satellite_guangxi
        "satellite_guizhou" -> R.drawable.satellite_guizhou
        "satellite_hainan" -> R.drawable.satellite_hainan
        "satellite_hebei" -> R.drawable.satellite_hebei
        "satellite_heilongjiang" -> R.drawable.satellite_heilongjiang
        "satellite_henan" -> R.drawable.satellite_henan
        "satellite_hubei" -> R.drawable.satellite_hubei
        "satellite_hunan" -> R.drawable.satellite_hunan
        "satellite_jiangsu" -> R.drawable.satellite_jiangsu
        "satellite_jiangxi" -> R.drawable.satellite_jiangxi
        "satellite_jilin" -> R.drawable.satellite_jilin
        "satellite_liaoning" -> R.drawable.satellite_liaoning
        "satellite_neimenggu" -> R.drawable.satellite_neimenggu
        "satellite_ningxia" -> R.drawable.satellite_ningxia
        "satellite_qinghai" -> R.drawable.satellite_qinghai
        "satellite_shandong" -> R.drawable.satellite_shandong
        "satellite_shanxi" -> R.drawable.satellite_shanxi
        "satellite_shaanxi" -> R.drawable.satellite_shaanxi
        "satellite_shenzhen" -> R.drawable.satellite_shenzhen
        "satellite_sichuan" -> R.drawable.satellite_sichuan
        "satellite_tianjin" -> R.drawable.satellite_tianjin
        "satellite_xinjiang" -> R.drawable.satellite_xinjiang
        "satellite_xizang" -> R.drawable.satellite_xizang
        "satellite_yunnan" -> R.drawable.satellite_yunnan
        "satellite_zhejiang" -> R.drawable.satellite_zhejiang
        else -> null
    }

    internal fun keyFor(channelName: String): String? {
        val name = channelName.logoMatchName()
        if (name.isBlank()) return null

        if (name.startsWith("CCTV")) {
            when {
                name.contains("4美洲") || name.contains("4AMERICA") -> return "cctv_4_americas"
                name.contains("4欧洲") || name.contains("4EUROPE") -> return "cctv_4_europe"
                Regex("^CCTV0?5[+]").containsMatchIn(name) -> return "cctv_5_plus"
                Regex("^CCTV0?4K").containsMatchIn(name) -> return "cctv_4k"
                Regex("^CCTV0?8K").containsMatchIn(name) -> return "cctv_8k"
            }
            Regex("^CCTV0?(1[0-7]|[1-9])(?![0-9])").find(name)?.groupValues?.get(1)
                ?.toIntOrNull()?.let { return "cctv_$it" }
        }

        Regex("^(?:CETV|中国教育(?:电视台)?)[-]?0?([1-4])(?![0-9])").find(name)
            ?.groupValues?.get(1)?.toIntOrNull()?.let { return "cetv_$it" }

        if (name.contains("CGTN")) {
            return when {
                name.contains("纪录") || name.contains("DOCUMENTARY") -> "cgtn_documentary"
                name.contains("法语") || name.contains("FRENCH") -> "cgtn_french"
                name.contains("俄语") || name.contains("RUSSIAN") -> "cgtn_russian"
                name.contains("西语") || name.contains("西班牙") || name.contains("SPANISH") -> "cgtn_spanish"
                name.contains("阿语") || name.contains("阿拉伯") || name.contains("ARABIC") -> "cgtn_arabic"
                else -> "cgtn"
            }
        }

        SATELLITE_ALIASES.firstOrNull { (aliases, _) -> aliases.any(name::contains) }
            ?.let { return it.second }
        return null
    }

    private fun String.logoMatchName(): String = uppercase(Locale.ROOT)
        .replace('＋', '+')
        .replace(Regex("[\\s_\\-—–·()（）\\[\\]【】「」]+"), "")

    private val SATELLITE_ALIASES = listOf(
        listOf("黑龙江卫视") to "satellite_heilongjiang",
        listOf("内蒙古卫视") to "satellite_neimenggu",
        listOf("东方卫视", "上海卫视") to "satellite_dongfang",
        listOf("东南卫视", "福建卫视") to "satellite_dongnan",
        listOf("海南卫视", "旅游卫视") to "satellite_hainan",
        listOf("安徽卫视") to "satellite_anhui",
        listOf("北京卫视") to "satellite_beijing",
        listOf("兵团卫视") to "satellite_bingtuan",
        listOf("重庆卫视") to "satellite_chongqing",
        listOf("甘肃卫视") to "satellite_gansu",
        listOf("广东卫视") to "satellite_guangdong",
        listOf("广西卫视") to "satellite_guangxi",
        listOf("贵州卫视") to "satellite_guizhou",
        listOf("河北卫视") to "satellite_hebei",
        listOf("河南卫视") to "satellite_henan",
        listOf("湖北卫视") to "satellite_hubei",
        listOf("湖南卫视") to "satellite_hunan",
        listOf("江苏卫视") to "satellite_jiangsu",
        listOf("江西卫视") to "satellite_jiangxi",
        listOf("吉林卫视") to "satellite_jilin",
        listOf("辽宁卫视") to "satellite_liaoning",
        listOf("宁夏卫视") to "satellite_ningxia",
        listOf("青海卫视") to "satellite_qinghai",
        listOf("山东卫视") to "satellite_shandong",
        listOf("山西卫视") to "satellite_shanxi",
        listOf("陕西卫视") to "satellite_shaanxi",
        listOf("深圳卫视") to "satellite_shenzhen",
        listOf("四川卫视") to "satellite_sichuan",
        listOf("天津卫视") to "satellite_tianjin",
        listOf("新疆卫视") to "satellite_xinjiang",
        listOf("西藏卫视") to "satellite_xizang",
        listOf("云南卫视") to "satellite_yunnan",
        listOf("浙江卫视") to "satellite_zhejiang",
    )
}
