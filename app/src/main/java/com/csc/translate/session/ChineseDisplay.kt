package com.csc.translate.session

import com.github.houbb.opencc4j.util.ZhConverterUtil

object ChineseDisplay {
    fun toTraditional(text: String): String {
        if (text.isEmpty()) return text
        return runCatching { ZhConverterUtil.toTraditional(text) }.getOrDefault(text)
    }
}

fun targetForDisplay(raw: String): String = ChineseDisplay.toTraditional(raw)
