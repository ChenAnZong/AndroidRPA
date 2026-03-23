"""
测试模块 05: UI 控件自动化
验证控件树抓取、控件匹配、控件关系查询等接口
"""
import pytest
from yyds import *


class TestUiDump:
    """控件树抓取测试"""

    def test_ui_dump_xml(self):
        """抓取控件布局 XML"""
        path = ui_dump_xml()
        assert path is not None and len(path.strip()) > 0, f"ui_dump_xml 返回路径不应为空, 实际: {path!r}"
        print(f"  控件树 XML 路径: {path.strip()}")

    def test_ui_dump_xml_custom_path(self):
        """自定义路径抓取"""
        custom = "/data/local/tmp/test_dump.xml"
        path = ui_dump_xml(custom)
        assert path is not None, "自定义路径 dump 不应返回 None"
        print(f"  自定义 dump 路径: {path.strip()}")

    def test_ui_dump_xml_all_window(self):
        """抓取所有窗口控件"""
        path = ui_dump_xml(all_window=True)
        assert path is not None, "all_window dump 不应返回 None"
        print(f"  全窗口 dump 路径: {path.strip()}")


class TestUiMatch:
    """控件匹配测试"""

    def test_ui_match_any(self):
        """匹配所有可见控件"""
        nodes = ui_match(visible_to_user="true", limit=5)
        assert isinstance(nodes, list), f"ui_match 应返回 list, 实际: {type(nodes)}"
        print(f"  匹配到 {len(nodes)} 个可见控件 (limit=5)")
        for n in nodes[:3]:
            print(f"    > class={n.class_name}, text={n.text!r}, bounds={n.bound_str}")

    def test_ui_match_by_text(self):
        """按文本匹配控件"""
        # 匹配任意包含文字的控件
        nodes = ui_match(text=".*", limit=3)
        assert isinstance(nodes, list), f"应返回 list, 实际: {type(nodes)}"
        print(f"  文本匹配到 {len(nodes)} 个控件")
        for n in nodes:
            print(f"    > text={n.text!r}, class={n.class_name}")

    def test_ui_match_by_class(self):
        """按类名匹配控件"""
        nodes = ui_match(class_=".*TextView.*", limit=3)
        assert isinstance(nodes, list), f"应返回 list, 实际: {type(nodes)}"
        print(f"  TextView 匹配到 {len(nodes)} 个控件")

    def test_ui_match_from_cache(self):
        """从缓存匹配 — 不重新抓取"""
        # 先正常抓取一次
        ui_match(visible_to_user="true", limit=1)
        # 再从缓存匹配
        nodes = ui_match(match_from_cache=True, visible_to_user="true", limit=3)
        assert isinstance(nodes, list), f"缓存匹配应返回 list, 实际: {type(nodes)}"
        print(f"  缓存匹配到 {len(nodes)} 个控件")

    def test_ui_match_no_result(self):
        """匹配不存在的控件 — 应返回空列表"""
        nodes = ui_match(text="THIS_TEXT_SHOULD_NOT_EXIST_12345")
        assert isinstance(nodes, list), f"应返回 list, 实际: {type(nodes)}"
        assert len(nodes) == 0, f"不存在的文本应匹配 0 个结果, 实际: {len(nodes)}"


class TestUiExist:
    """控件存在性检查测试"""

    def test_ui_exist_true(self):
        """存在性检查 — 应存在可见控件"""
        exists = ui_exist(visible_to_user="true")
        assert isinstance(exists, bool), f"ui_exist 应返回 bool, 实际: {type(exists)}"
        assert exists is True, "当前屏幕应存在可见控件"

    def test_ui_exist_false(self):
        """存在性检查 — 不应存在的控件"""
        exists = ui_exist(text="THIS_SHOULD_NOT_EXIST_99999")
        assert exists is False, "不存在的文本控件 ui_exist 应返回 False"


class TestUiRelation:
    """控件关系查询测试"""

    def test_ui_parent(self):
        """获取父节点"""
        nodes = ui_match(visible_to_user="true", limit=1)
        if not nodes:
            pytest.skip("无可见控件, 跳过父节点测试")
        parents = ui_parent(nodes[0])
        assert isinstance(parents, list), f"ui_parent 应返回 list, 实际: {type(parents)}"
        print(f"  节点 {nodes[0].class_name} 有 {len(parents)} 层父节点")

    def test_ui_child(self):
        """获取子节点"""
        # 找一个容器控件
        nodes = ui_match(class_=".*Layout.*", limit=1)
        if not nodes:
            nodes = ui_match(visible_to_user="true", limit=1)
        if not nodes:
            pytest.skip("无控件, 跳过子节点测试")
        children = ui_child(nodes[0])
        assert isinstance(children, list), f"ui_child 应返回 list, 实际: {type(children)}"
        print(f"  节点 {nodes[0].class_name} 有 {len(children)} 个子节点")

    def test_ui_sib(self):
        """获取兄弟节点"""
        nodes = ui_match(visible_to_user="true", limit=1)
        if not nodes:
            pytest.skip("无控件, 跳过兄弟节点测试")
        sibs = ui_sib(nodes[0])
        assert isinstance(sibs, list), f"ui_sib 应返回 list, 实际: {type(sibs)}"
        print(f"  节点 {nodes[0].class_name} 有 {len(sibs)} 个兄弟节点")
