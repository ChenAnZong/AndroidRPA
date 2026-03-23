"""
测试中文 API 别名 — 确保所有中文函数名都能正确映射到英文函数
"""
import pytest
from unittest.mock import patch, MagicMock


class TestChineseApiAliases:
    """中文 API 别名导入测试"""

    def test_touch_aliases_importable(self):
        """触摸类中文别名可导入"""
        from yyds import 点击, 双击, 随机点击, 长按, 滑动
        from yyds import 上滑, 下滑, 左滑, 右滑
        from yyds import 手势, 双指缩小, 双指放大, 点击目标
        assert callable(点击)
        assert callable(双击)
        assert callable(滑动)
        assert callable(长按)
        assert callable(手势)

    def test_key_aliases_importable(self):
        """按键类中文别名可导入"""
        from yyds import 返回键, 主页键, 菜单键, 确认键, 按键
        assert callable(返回键)
        assert callable(主页键)
        assert callable(按键)

    def test_ocr_aliases_importable(self):
        """OCR 类中文别名可导入"""
        from yyds import 截图, 文字识别, 识别文字, 屏幕找字, 屏幕找字_首个
        from yyds import 文字点击, 文字点击任一, 文字全部存在, 文字任一存在
        assert callable(截图)
        assert callable(文字识别)
        assert 文字识别 is 识别文字  # 两个别名指向同一函数

    def test_yolo_aliases_importable(self):
        """YOLO 类中文别名可导入"""
        from yyds import 目标检测, 屏幕找目标, 屏幕找目标_首个
        assert callable(目标检测)

    def test_image_aliases_importable(self):
        """找图类中文别名可导入"""
        from yyds import 找图, 屏幕找图, 屏幕找图_首个, 模板匹配
        from yyds import 找图点击, 找图点击_最高匹配, 图片相似度
        assert callable(找图)
        assert callable(图片相似度)

    def test_color_aliases_importable(self):
        """找色类中文别名可导入"""
        from yyds import 找色, 取色, 多点取色
        assert callable(找色)

    def test_ui_aliases_importable(self):
        """控件类中文别名可导入"""
        from yyds import 控件匹配, 控件存在, 控件父级, 控件子级, 控件兄弟, 控件偏移, 控件树
        assert callable(控件匹配)
        assert callable(控件存在)

    def test_wait_aliases_importable(self):
        """等待类中文别名可导入"""
        from yyds import 等待文字, 等待文字消失, 等待控件, 等待控件消失
        from yyds import 等待图片, 等待页面, 等待应用
        from yyds import 等待并点击文字, 等待并点击控件
        from yyds import 等待画面变化, 等待画面稳定
        assert callable(等待文字)
        assert callable(等待并点击文字)

    def test_swipe_find_aliases_importable(self):
        """滑动查找类中文别名可导入"""
        from yyds import 滑动找字, 滑动找控件, 滑动找图
        assert callable(滑动找字)

    def test_app_aliases_importable(self):
        """应用管理类中文别名可导入"""
        from yyds import 打开应用, 关闭应用, 打开网址, 应用置顶
        from yyds import 应用是否运行, 是否在应用内
        from yyds import 卸载应用, 清除应用数据, 从桌面打开应用, 回到桌面
        assert callable(打开应用)
        assert callable(关闭应用)

    def test_input_aliases_importable(self):
        """输入类中文别名可导入"""
        from yyds import 输入文字, 智能输入, 清空输入, 设置文本
        from yyds import 复制到剪贴板, 获取剪贴板
        assert callable(输入文字)

    def test_device_aliases_importable(self):
        """设备信息类中文别名可导入"""
        from yyds import 屏幕尺寸, 前台应用, 前台包名, 设备编号, 设备型号, 网络是否在线
        assert callable(屏幕尺寸)
        assert callable(设备型号)

    def test_system_aliases_importable(self):
        """系统功能类中文别名可导入"""
        from yyds import 执行命令, 提示, 提示打印, 下载文件, 拉取文件, 推送文件
        from yyds import 等待, 假等待, 随机等待
        assert callable(执行命令)
        assert callable(等待)

    def test_permission_aliases_importable(self):
        """权限和设备控制类中文别名可导入"""
        from yyds import 授予权限, 撤销权限, 获取通知
        from yyds import 获取WiFi信息, 设置WiFi开关, 设置飞行模式
        from yyds import 获取屏幕方向, 设置屏幕亮度, 获取电池信息
        assert callable(授予权限)

    def test_scale_aliases_importable(self):
        """分辨率适配类中文别名可导入"""
        from yyds import 坐标缩放, 缩放点击
        assert callable(坐标缩放)

    def test_backup_aliases_importable(self):
        """数据备份类中文别名可导入"""
        from yyds import 应用数据备份, 应用数据恢复, 应用安装包备份, 安装应用
        assert callable(应用数据备份)

    def test_model_aliases_importable(self):
        """模型管理类中文别名可导入"""
        from yyds import 重载YOLO模型, 重载OCR模型, YOLO模型信息
        assert callable(重载YOLO模型)


class TestChineseAliasesIdentity:
    """验证中文别名与英文函数是同一个对象"""

    def test_click_is_same(self):
        from yyds import 点击, click
        assert 点击 is click

    def test_swipe_is_same(self):
        from yyds import 滑动, swipe
        assert 滑动 is swipe

    def test_screenshot_is_same(self):
        from yyds import 截图, screenshot
        assert 截图 is screenshot

    def test_open_app_is_same(self):
        from yyds import 打开应用, open_app
        assert 打开应用 is open_app

    def test_sleep_is_same(self):
        from yyds import 等待, sleep
        assert 等待 is sleep

    def test_shell_is_same(self):
        from yyds import 执行命令, shell
        assert 执行命令 is shell

    def test_ui_match_is_same(self):
        from yyds import 控件匹配, ui_match
        assert 控件匹配 is ui_match

    def test_ocr_is_same(self):
        from yyds import 文字识别, ocr
        assert 文字识别 is ocr


class TestChineseApiExecution:
    """验证中文 API 实际可调用"""

    @patch("yyds.auto_api.engine_call")
    def test_点击(self, mock_call):
        mock_call.return_value = "ok"
        from yyds import 点击
        点击(540, 1200)
        mock_call.assert_called()

    @patch("yyds.auto_api.engine_call")
    def test_返回键(self, mock_call):
        mock_call.return_value = "ok"
        from yyds import 返回键
        返回键()
        mock_call.assert_called()

    @patch("yyds.auto_api.engine_call")
    def test_打开应用(self, mock_call):
        mock_call.return_value = "ok"
        from yyds import 打开应用
        打开应用("com.tencent.mm")
        mock_call.assert_called()


class TestTemplates:
    """场景模板测试"""

    def test_templates_importable(self):
        """模板函数可导入"""
        from yyds import 启动并等待, 关闭并重启
        from yyds import 滑动找字并点击, 重复点击直到
        from yyds import 输入中文, 条件等待循环
        from yyds import 安全点击文字, 批量点击文字
        assert callable(启动并等待)
        assert callable(滑动找字并点击)
        assert callable(重复点击直到)
        assert callable(输入中文)
        assert callable(条件等待循环)
        assert callable(安全点击文字)
        assert callable(批量点击文字)

    @patch("yyds.auto_api_templates.open_app")
    @patch("yyds.auto_api_templates.wait_for_package")
    def test_启动并等待_basic(self, mock_wait, mock_open):
        """启动并等待 — 无条件时等待应用进入前台"""
        mock_wait.return_value = True
        from yyds import 启动并等待
        result = 启动并等待("com.test.app", 等待秒数=5)
        mock_open.assert_called_once_with("com.test.app")
        assert result is True

    @patch("yyds.auto_api_templates.screen_ocr_x")
    @patch("yyds.auto_api_templates.click_target")
    @patch("yyds.auto_api_templates.swipe_to_find_text")
    def test_滑动找字并点击_found(self, mock_swipe_find, mock_click, mock_ocr):
        """滑动找字并点击 — 找到时点击"""
        mock_result = MagicMock()
        mock_swipe_find.return_value = [mock_result]
        from yyds import 滑动找字并点击
        result = 滑动找字并点击("设置")
        assert result is True
        mock_click.assert_called_once_with(mock_result)

    @patch("yyds.auto_api_templates.swipe_to_find_text")
    def test_滑动找字并点击_not_found(self, mock_swipe_find):
        """滑动找字并点击 — 未找到返回 False"""
        mock_swipe_find.return_value = None
        from yyds import 滑动找字并点击
        result = 滑动找字并点击("不存在的文字", 最大滑动次数=2)
        assert result is False

    def test_条件等待循环_immediate(self):
        """条件等待循环 — 条件立即满足"""
        from yyds import 条件等待循环
        result = 条件等待循环(lambda: True, 超时秒数=1)
        assert result is True

    def test_条件等待循环_timeout(self):
        """条件等待循环 — 超时"""
        from yyds import 条件等待循环
        result = 条件等待循环(lambda: False, 超时秒数=0.5, 间隔=0.1)
        assert result is False

    @patch("yyds.auto_api_templates.click")
    @patch("yyds.auto_api_templates.screen_ocr_x")
    def test_重复点击直到_found(self, mock_ocr, mock_click):
        """重复点击直到 — 出现文字后停止"""
        mock_ocr.side_effect = [[], [MagicMock()]]  # 第二次找到
        from yyds import 重复点击直到
        result = 重复点击直到(540, 1200, 出现文字="成功", 间隔=0.01)
        assert result is True
        assert mock_click.call_count == 2
