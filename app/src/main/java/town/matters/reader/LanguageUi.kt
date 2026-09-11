package town.matters.reader

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text as MaterialText
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import com.ibm.icu.text.Transliterator

val LocalReaderLanguage = staticCompositionLocalOf { 0 }

/** Display-only conversion: original articles, account names, input and URLs stay intact. */
object ChineseDisplay {
    private val traditional by lazy { Transliterator.getInstance("Simplified-Traditional") }
    private val simplified by lazy { Transliterator.getInstance("Traditional-Simplified") }
    @Synchronized fun convert(text: String, language: Int, interfaceText: Boolean = true): String {
        if (text.none { it in '\u3400'..'\u9fff' }) return text
        var value = text
        if (language == 1 && interfaceText) {
            listOf("登录" to "登入", "账户" to "帳號", "账号" to "帳號", "设置" to "設定",
                "默认" to "預設", "搜索" to "搜尋", "加载" to "載入", "网络" to "網路",
                "保存" to "儲存", "链接" to "連結").forEach { (from, to) -> value = value.replace(from, to) }
        }
        return if (language == 1) traditional.transliterate(value) else simplified.transliterate(value)
    }
}

@Composable
fun uiLabel(text: String): String {
    val language = LocalReaderLanguage.current
    return remember(text, language) { ChineseDisplay.convert(text, language) }
}

@Composable
fun LocalizedText(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified, fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null, lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip, maxLines: Int = Int.MAX_VALUE,
    style: TextStyle = LocalTextStyle.current, original: Boolean = false, content: Boolean = false) {
    val language = LocalReaderLanguage.current
    val displayed = remember(text, language, original, content) {
        if (original) text else ChineseDisplay.convert(text, language, interfaceText = !content)
    }
    MaterialText(displayed, modifier, color = color, fontSize = fontSize, fontWeight = fontWeight,
        fontFamily = fontFamily, lineHeight = lineHeight, overflow = overflow, maxLines = maxLines, style = style)
}

@Composable
fun LocalizedIcon(imageVector: ImageVector, contentDescription: String?, modifier: Modifier = Modifier,
    tint: Color = androidx.compose.material3.LocalContentColor.current) {
    MaterialIcon(imageVector, contentDescription?.let { uiLabel(it) }, modifier, tint)
}
