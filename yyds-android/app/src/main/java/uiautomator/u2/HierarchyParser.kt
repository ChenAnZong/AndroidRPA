package uiautomator.u2

import android.graphics.Point
import android.graphics.Rect
import android.util.LruCache
import image.ImageHelper
import org.w3c.dom.Element
import org.w3c.dom.Node
import uiautomator.ExtSystem
import uiautomator.compat.WindowManagerWrapper
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory


object HierarchyParser {
    private val currentNodeTree:MutableList<NodeObject> = mutableListOf<NodeObject>()
    private val cacheNodeTree:LruCache<Long, MutableList<NodeObject>> = LruCache(10)
    val screenPoint:Point by lazy { WindowManagerWrapper().displaySize }

    private val positionLimitKey = setOf("x", "y", "h", "w")
    private val sizeLimitKey = setOf("width", "height")
    private val allSpecialKey = positionLimitKey.plus(sizeLimitKey)

    private fun dumpMatchWidgetRect(matchParams:Map<String, String>,
                                    list: MutableList<NodeObject>,
                                    maxMatchCount: Int):List<NodeObject> {
        val matchNode = mutableListOf<NodeObject>()

        for (scanNode in list) {
            if (matchNode.size == maxMatchCount) {
                return matchNode
            }

            fun isMatchAllParams(curNode:NodeObject, curParams:Map<String, String>):Boolean {
                if (curParams.isEmpty()) return true;
                var count = 0
                // 开始进行元素匹配!

                val positionKeyCount = curParams.count { it -> positionLimitKey.contains(it.key) }
                if (positionKeyCount > 0) {
                    val rect1 = ImageHelper.parseScreenRect(screenPoint, curParams)
                    val rect2 = Rect(curNode.bounds.p1x, curNode.bounds.p1y, curNode.bounds.p2x, curNode.bounds.p2y)
                    if (!rect1.contains(rect2)) {
                        return false
                    }
                }

                for ((key , keyValue) in curParams) {
                    curNode.myNode as Element
                    // 无效的Key
                    if (!allSpecialKey.contains(key) && !curNode.myNode.hasAttribute(key)) {
                        ExtSystem.printDebugLog("# 无效匹配Key, 跳过:$key")
                        count++
                        continue
                    }

                    // 使用范围去匹配数值
                    val isKeyValueUseRange = "[>, <]\\d+".toRegex().matches(keyValue)

                    // @匹配控件大小
                    if (sizeLimitKey.contains(key)) {
                        if (isKeyValueUseRange) {
                            val symbol = keyValue[0]
                            val numValue = keyValue.substring(1).toDouble()
                            if (key == "width") {
                                if (symbol == '>' && curNode.bounds.width > numValue) {
                                    count++
                                }
                                if (symbol == '<' && curNode.bounds.width < numValue) {
                                    count++
                                }
                            }

                            if (key == "height") {
                                if (symbol == '>' && curNode.bounds.height > numValue) {
                                    count++
                                }
                                if (symbol == '<' && curNode.bounds.height < numValue) {
                                    count++
                                }
                            }
                        } else {
                            val numValue = keyValue.toDouble()
                            if (key == "width" && curNode.bounds.width.toDouble() == numValue) {
                                count++
                            }

                            if (key == "height" && curNode.bounds.height.toDouble() == numValue) {
                                count++
                            }
                        }
                        continue
                    }

                    // @正则匹配内容或内容等值
                    val nodeValue = curNode.myNode.getAttribute(key) ?: continue

                    if (nodeValue.equals(keyValue)
                            || keyValue.toRegex(setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL, RegexOption.UNIX_LINES)).matches(nodeValue)) {
                        count++
                        continue
                    }

                    // @范围属性值
                    if (isKeyValueUseRange) {
                        val originNumValue = try { nodeValue.toDouble() } catch(e:Exception) { null }
                        if (originNumValue != null) {
                            val symbol = keyValue[0]
                            val numValue = keyValue.substring(1).toDouble()
                            if ((symbol == '>' && originNumValue > numValue)
                                    || (symbol == '<' && originNumValue < numValue)) {
                                count++
                                continue
                            }
                        }
                    }
                }
                return count + positionKeyCount == curParams.size
            }

            val thisKv = matchParams.filter { !it.key.startsWith("par_") && !it.key.startsWith("sib_")  && !it.key.startsWith("chi_")  }
            val parKv = mutableMapOf<String,String>().apply {
                matchParams.filter { it.key.startsWith("par_") }.forEach  {
                    entry ->  this[entry.key.substring(4)] = entry.value
                }
            }
            val sibKv = mutableMapOf<String,String>().apply {
                matchParams.filter { it.key.startsWith("sib_") }.forEach { entry ->
                    this[entry.key.substring(4)] = entry.value
                }
            }
            val chiKv = mutableMapOf<String,String>().apply {
                matchParams.filter { it.key.startsWith("chi_") }.forEach { entry ->
                    this[entry.key.substring(4)] = entry.value
                }
            }
            // 合适元素
            // 存在一个关系节点 ? 满足所有给定的关系！ 所以说只可以锚定一个节点
            if (
                    isMatchAllParams(scanNode, thisKv)
                    &&
                    (parKv.isEmpty() || scanNode.getAllParentNodeObject().any { isMatchAllParams(it, parKv) } )
                    &&
                    (sibKv.isEmpty() || scanNode.getAllSiblingNodeObject().any { isMatchAllParams(it, sibKv) } )
                    &&
                    (chiKv.isEmpty() || scanNode.getChildNodeObject().any { isMatchAllParams(it, chiKv) } )
            ) {
                matchNode.add(scanNode)
            }
        }

        ExtSystem.printDebugLog("匹配成功数量：" + matchNode.count())
        return matchNode
    }

    @Synchronized
    fun findRectApi(matchParams: Map<String, String>, maxMatchCount: Int, matchFromCache:Boolean, matchAllWindow:Boolean):String {
        // dump 到这个文件里
        val path = "/data/local/tmp/find.xml"
        if (!matchFromCache) {
            val dumpTimeMs = HierarchyDumper.dump(path, matchAllWindow)
            // 解析到缓存中去！
            parse(path, dumpTimeMs)
            cacheNodeTree.put(dumpTimeMs, currentNodeTree)
        }
        matchParams.forEach { t, u ->  ExtSystem.printDebugLog("匹配参数:$t=$u") }
        val nodes =  dumpMatchWidgetRect(matchParams, currentNodeTree, maxMatchCount)
        nodes.map { ExtSystem.printDebugLog("匹配到$it"); }
        return NodeObject.defaultJson.toJson(nodes)
    }

    fun parse(path:String, dumpTimeMs:Long) {
        val factory = DocumentBuilderFactory.newInstance()
        val builder = factory.newDocumentBuilder();
        val doc = builder.parse(File(path))
        var rootNode: Node = doc.documentElement.firstChild
        while (rootNode.nodeType != Node.ELEMENT_NODE) {
            rootNode = rootNode.nextSibling
        }
        currentNodeTree.clear()
        System.out.println("Nodetype: " + rootNode.nodeType)
        parseNode(rootNode, currentNodeTree, dumpTimeMs)
    }

    // 平级解析，线性扫描
    fun parseNode(root:Node, list:MutableList<NodeObject>, dumpTimeMs:Long) {
        val ele = root as Element
        val nodeObject = NodeObject.fromXmlNode(root, dumpTimeMs)
        list.add(nodeObject)
        // 解析所有子
        for (child_index in 0 until ele.childNodes.length) {
            val child = ele.childNodes.item(child_index)
            if (child.nodeType == Node.ELEMENT_NODE) {
                parseNode(child, list, dumpTimeMs)
            }
        }
    }

    fun fetchRelation(ms:Long, hashCode:Int, type:String):String {
        var list = listOf<NodeObject>()
        val tree = cacheNodeTree.get(ms)
        if (tree != null) {
            when(type) {
                "parent" ->  list = tree.first { it.hashCode ==  hashCode }.getAllParentNodeObject()
                "child" ->  list = tree.first { it.hashCode ==  hashCode }.getChildNodeObject()
                "sib" ->  list = tree.first { it.hashCode ==  hashCode }.getAllSiblingNodeObject()
            }
        }
        return NodeObject.defaultJson.toJson(list)
    }
}
