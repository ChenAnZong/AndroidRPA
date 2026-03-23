package uiautomator.u2

import android.graphics.Point
import androidx.annotation.Keep
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.w3c.dom.NodeList
import uiautomator.ExtSystem

// <node NAF="true"
// index="1"
// text=""
// resource-id="com.kiigames.xiaohujibu.xhjb:id/iv_close"
// class="android.widget.ImageView"
// package="com.kiigames.xiaohujibu.xhjb"
// content-desc=""

// checkable="false"
// checked="false"
// clickable="true"
// enabled="true"
// focusable="true"

// focused="false"
// scrollable="false"
// long-clickable="false"
// password="false"
// selected="false"

// visible-to-user="true"
// bounds="[876,348][964,436]" />


data class Bounds(val boundsString: String) {
    private val s = boundsString.split("[", "]", ",").filter { it.isNotEmpty() }
    val p1x = s[0].toInt() // 左上角x
    val p1y = s[1].toInt() // 左上角y
    val p2x = s[2].toInt()
    val p2y = s[3].toInt()

    val width by lazy { p2x - p1x }
    val height by lazy { p2y - p1y }

    val centerPos:Point by lazy {
        val rx = p1x + ((width*0.3).toInt()..(width*0.7).toInt()).random()
        val ry = p1y + ((height*0.3).toInt()..(height*0.7).toInt()).random()
        Point(rx, ry)
    }

    public fun isWidthHeightEqual(w:Int, h:Int):Boolean = w == width && h == height

    override fun toString(): String {
        return "$p1x,$p1y $p2x,$p2y $width,$height"
    }
}

@Keep
data class NodeObject(
        val hashCode:Int,
        val index: Int,

        val text: String,
        val id: String,
        val cls: String,
        val pkg: String,
        val desc: String,

        val isCheckable: Boolean,
        val isChecked: Boolean,
        val isClickAble: Boolean,
        val isEnable: Boolean,
        val isFocusable: Boolean,

        val isFocused: Boolean,
        val isScrollable: Boolean,
        val isLongClickable: Boolean,
        val isPassword: Boolean,
        val isSelected: Boolean,

        val isVisible: Boolean,
        val boundsString: String,
        val parentCount: Int, // xml depth
        val childCount:Int,
        val dumpTimeMs: Long,
        @Transient val myNode: Node, //
) {

    @delegate:Transient
    val mySiblingNodes by lazy { myNode.parentNode?.childNodes }
    @delegate:Transient
    val bounds by lazy { Bounds(boundsString) }
    fun getAllParentNodeObject() :List<NodeObject> {
        val resultNode = mutableListOf<NodeObject>()
        var parentNode: Node? = myNode.parentNode
        while(parentNode != null) {
            if (parentNode.nodeName != "node")  {
                ExtSystem.printDebugLog("搜索父节点, 跳过:" + parentNode.nodeName)
                break
            }
            resultNode.add(fromXmlNode(parentNode, dumpTimeMs))
            parentNode = parentNode.parentNode
        }
        return resultNode
    }
    fun getAllSiblingNodeObject() :List<NodeObject> {
        val resultNode = mutableListOf<NodeObject>()
        if (mySiblingNodes == null) {
            return resultNode
        }
        for (i in 0 until mySiblingNodes!!.length) {
            val node = mySiblingNodes!!.item(i)
            if (!node.isEqualNode(myNode) && node.nodeType == Node.ELEMENT_NODE) {
                resultNode.add(fromXmlNode(node, dumpTimeMs))
            }
        }
        return resultNode
    }
    fun getChildNodeObject():List<NodeObject> {
        val resultNode = mutableListOf<NodeObject>()
        for (i in 0 until myNode.childNodes.length) {
            val node = myNode.childNodes.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                resultNode.add(fromXmlNode(node, dumpTimeMs))
            }
        }
        return resultNode
    }
    fun json():String {
        return defaultJson.toJson(this)
    }

    companion object {
        public val defaultJson = GsonBuilder().create()
        fun fromXmlNode(node:Node, dumpTimeMs: Long):NodeObject {
            val ele = node as Element
            val no = NodeObject(ele.hashCode(),
                try {
                    ele.getAttribute( "index").toInt()
                } catch (e:Exception) {
                    ExtSystem.printDebugError("index节点:" + ele.toString(), e)
                    0
                },
            ele.getAttribute("text"),
            ele.getAttribute("resource-id"),
            ele.getAttribute("class"),
            ele.getAttribute("package"),
            ele.getAttribute("content-desc"),

            ele.getAttribute("checkable") == "true",
            ele.getAttribute("checked") == "true",
            ele.getAttribute("clickable") == "true",
            ele.getAttribute("enabled") == "true",
            ele.getAttribute("focusable") == "true",

            ele.getAttribute("focused") == "true",
            ele.getAttribute("scrollable") == "true",
            ele.getAttribute("long-clickable") == "true",
            ele.getAttribute("password") == "true",
            ele.getAttribute("selected") == "true",
            ele.getAttribute("visible-to-user") == "true",
            ele.getAttribute("bounds"),
            try {
                ele.getAttribute( "parent-count").toInt()
            } catch (e:Exception) { 0 },
            try {
                ele.getAttribute( "child-count").toInt()
            } catch (e:Exception) { 0 }, dumpTimeMs,
            node)
            return no
        }
    }
}
