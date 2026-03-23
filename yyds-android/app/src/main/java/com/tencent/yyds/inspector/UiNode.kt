package com.tencent.yyds.inspector

import android.graphics.Rect
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

data class UiNode(
    val index: Int,
    val text: String,
    val resourceId: String,
    val className: String,
    val packageName: String,
    val contentDesc: String,
    val checkable: Boolean,
    val checked: Boolean,
    val clickable: Boolean,
    val enabled: Boolean,
    val focusable: Boolean,
    val focused: Boolean,
    val scrollable: Boolean,
    val longClickable: Boolean,
    val password: Boolean,
    val selected: Boolean,
    val visibleToUser: Boolean,
    val bounds: Rect,
    val depth: Int,
    val children: MutableList<UiNode> = mutableListOf()
) {
    val shortClassName: String
        get() = className.substringAfterLast('.')

    val shortResourceId: String
        get() = resourceId.substringAfterLast('/')

    val displayLabel: String
        get() = when {
            text.isNotEmpty() -> "\"$text\""
            contentDesc.isNotEmpty() -> "[$contentDesc]"
            shortResourceId.isNotEmpty() -> shortResourceId
            else -> shortClassName
        }

    companion object {
        fun parseXml(xml: String): List<UiNode> {
            val allNodes = mutableListOf<UiNode>()
            try {
                val factory = XmlPullParserFactory.newInstance()
                val parser = factory.newPullParser()
                parser.setInput(StringReader(xml))

                val stack = mutableListOf<UiNode>()
                var eventType = parser.eventType
                var depth = 0

                while (eventType != XmlPullParser.END_DOCUMENT) {
                    when (eventType) {
                        XmlPullParser.START_TAG -> {
                            if (parser.name == "node") {
                                val node = parseNode(parser, depth)
                                if (stack.isNotEmpty()) {
                                    stack.last().children.add(node)
                                }
                                stack.add(node)
                                allNodes.add(node)
                                depth++
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            if (parser.name == "node") {
                                stack.removeLastOrNull()
                                depth--
                            }
                        }
                    }
                    eventType = parser.next()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return allNodes
        }

        private fun parseNode(parser: XmlPullParser, depth: Int): UiNode {
            return UiNode(
                index = parser.getAttributeValue(null, "index")?.toIntOrNull() ?: 0,
                text = parser.getAttributeValue(null, "text") ?: "",
                resourceId = parser.getAttributeValue(null, "resource-id") ?: "",
                className = parser.getAttributeValue(null, "class") ?: "",
                packageName = parser.getAttributeValue(null, "package") ?: "",
                contentDesc = parser.getAttributeValue(null, "content-desc") ?: "",
                checkable = parser.getAttributeValue(null, "checkable") == "true",
                checked = parser.getAttributeValue(null, "checked") == "true",
                clickable = parser.getAttributeValue(null, "clickable") == "true",
                enabled = parser.getAttributeValue(null, "enabled") == "true",
                focusable = parser.getAttributeValue(null, "focusable") == "true",
                focused = parser.getAttributeValue(null, "focused") == "true",
                scrollable = parser.getAttributeValue(null, "scrollable") == "true",
                longClickable = parser.getAttributeValue(null, "long-clickable") == "true",
                password = parser.getAttributeValue(null, "password") == "true",
                selected = parser.getAttributeValue(null, "selected") == "true",
                visibleToUser = parser.getAttributeValue(null, "visible-to-user") == "true",
                bounds = parseBounds(parser.getAttributeValue(null, "bounds") ?: "[0,0][0,0]"),
                depth = depth
            )
        }

        private fun parseBounds(boundsStr: String): Rect {
            try {
                val nums = boundsStr.replace("[", "").replace("]", ",")
                    .split(",").filter { it.isNotEmpty() }.map { it.toInt() }
                if (nums.size >= 4) {
                    return Rect(nums[0], nums[1], nums[2], nums[3])
                }
            } catch (_: Exception) {}
            return Rect(0, 0, 0, 0)
        }
    }
}
