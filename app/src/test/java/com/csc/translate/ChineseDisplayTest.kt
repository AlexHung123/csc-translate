package com.csc.translate

import com.csc.translate.session.ChineseDisplay
import com.csc.translate.session.sourceForDisplay
import com.csc.translate.session.targetForDisplay
import org.junit.Assert.assertEquals
import org.junit.Test

class ChineseDisplayTest {
    @Test
    fun convertsSimplifiedToTraditional() {
        assertEquals("這是繁體中文", ChineseDisplay.toTraditional("这是繁体中文"))
        assertEquals("頭髮", ChineseDisplay.toTraditional("头发"))
        assertEquals("軟件開發", ChineseDisplay.toTraditional("软件开发"))
    }

    @Test
    fun leavesEnglishUnchanged() {
        assertEquals("Hello NASA", ChineseDisplay.toTraditional("Hello NASA"))
    }

    @Test
    fun displayHelpersConvertChinese() {
        assertEquals("你好，世界", sourceForDisplay("你好，世界", "zh-en"))
        assertEquals("這是測試", targetForDisplay("这是测试"))
    }
}
