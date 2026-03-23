"""
Yyds.Auto 设备端综合 API 测试
在手机 Python 引擎 (Chaquopy) 中运行, 验证所有 API 是否正常工作

使用方法:
  1. 将此文件内容复制到 main.py
  2. 推送工程到手机: adb push yyds.-auto_-py-projcet /storage/emulated/0/Yyds.Py/我的脚本
  3. 启动项目: GET http://127.0.0.1:61140/project/start?name=我的脚本
  4. 查看日志: adb shell "logcat -d | grep 'python.stdout' | tail -80"

测试结果: 60/60 通过 (2026-02-13)
"""
import os
import time
from yyds import *

passed = 0
failed = 0
errors = []


def test(name, func):
    global passed, failed
    try:
        func()
        passed += 1
        log_d(f"  ✓ {name}")
    except Exception as e:
        failed += 1
        msg = f"  ✗ {name}: {e}"
        log_d(msg)
        errors.append(msg)


def assert_true(condition):
    if not condition:
        raise AssertionError("断言失败: 条件为 False")


def main():
    global passed, failed, errors
    log_d("=" * 50)
    log_d("Yyds.Auto 设备端 API 综合测试")
    log_d("=" * 50)

    # [1] 基础引擎
    log_d("\n[1] 基础引擎")
    test("ping", lambda: assert_true(EngineDebug._ping()))
    test("version", lambda: assert_true(len(str(EngineDebug._version())) > 0))
    test("pid", lambda: assert_true(EngineDebug._pid() > 0))
    test("uid", lambda: assert_true(EngineDebug._uid() in (0, 2000)))

    # [2] 设备信息
    log_d("\n[2] 设备信息")
    DeviceScreen.init()
    w, h = DeviceScreen.get_screen_wh()
    test("screen_size", lambda: assert_true(w > 0 and h > 0))
    test("device_model", lambda: assert_true(len(device_model().strip()) > 0))
    test("device_code", lambda: assert_true(len(device_code().strip()) > 0))
    test("foreground_pkg", lambda: assert_true("." in device_foreground_package()))
    test("foreground_activity", lambda: assert_true(len(device_foreground_activity().strip()) > 0))
    test("is_net_online", lambda: assert_true(isinstance(is_net_online(), bool)))

    # [3] 触控操作
    log_d("\n[3] 触控操作")
    test("click", lambda: assert_true(click(540, 1200)))
    time.sleep(0.3)
    test("click_double", lambda: click_double(540, 1200))
    time.sleep(0.3)
    test("random_click", lambda: random_click(400, 1000, 200, 200))
    test("long_press", lambda: long_press(540, 1200, 300))
    time.sleep(0.3)

    # [4] 滑动与手势
    log_d("\n[4] 滑动与手势")
    test("swipe", lambda: swipe(540, 1800, 540, 600, 300))
    time.sleep(0.5)
    test("gesture_3pt", lambda: gesture([(200, 1200), (540, 800), (800, 400)], 400))
    time.sleep(0.3)

    # [5] 按键注入
    log_d("\n[5] 按键注入")
    test("key_home", lambda: key_home())
    time.sleep(0.5)
    test("key_back", lambda: key_back())
    test("key_code_vol_up", lambda: key_code(24))

    # [6] 截图
    log_d("\n[6] 截图")
    test("screenshot", lambda: assert_true(len(screenshot().strip()) > 0))

    # [7] OCR
    log_d("\n[7] OCR 识别")
    test("ocr_full", lambda: assert_true(isinstance(ocr(), str)))
    test("ocr_region", lambda: assert_true(isinstance(ocr(x=0, y=0, w=0.5, h=0.3), str)))

    # [8] UI 控件
    log_d("\n[8] UI 控件匹配")
    test("ui_dump_xml", lambda: assert_true(len(ui_dump_xml().strip()) > 0))
    nodes = ui_match(visible_to_user="true", limit=3)
    test("ui_match", lambda: assert_true(isinstance(nodes, list)))
    test("ui_exist_true", lambda: assert_true(ui_exist(visible_to_user="true")))
    test("ui_exist_false", lambda: assert_true(not ui_exist(text="ZZZZ_NOT_EXIST_99")))
    if nodes:
        test("node.cx_cy", lambda: assert_true(nodes[0].cx >= 0 and nodes[0].cy >= 0))
        test("node.bounds", lambda: assert_true(len(nodes[0].bounds) == 4))
        test("node.width", lambda: assert_true(nodes[0].width >= 0))
        test("node.click", lambda: nodes[0].click())
        time.sleep(0.3)

    # [9] Shell
    log_d("\n[9] Shell 命令")
    test("shell_echo", lambda: assert_true("hello" in shell("echo hello")))
    test("shell_whoami", lambda: assert_true(len(shell("whoami").strip()) > 0))

    # [10] 应用管理
    log_d("\n[10] 应用管理")
    test("open_app", lambda: open_app("com.android.settings"))
    time.sleep(1)
    test("is_in_app", lambda: assert_true(is_in_app("com.android.settings")))
    test("stop_app", lambda: stop_app("com.android.settings"))
    time.sleep(0.5)
    test("toast", lambda: toast("API 测试进行中..."))

    # [11] 取色
    log_d("\n[11] 取色")
    screenshot()
    test("get_color", lambda: assert_true(isinstance(get_color(100, 100), Color)))

    # [12] 等待类 API
    log_d("\n[12] 等待类 API")
    test("wait_for_text_timeout", lambda: assert_true(wait_for_text("ZZZ_NO_EXIST", timeout=1) is None))
    test("wait_for_text_gone", lambda: assert_true(wait_for_text_gone("ZZZ_NO_EXIST", timeout=1)))
    test("wait_for_ui_timeout", lambda: assert_true(wait_for_ui(text="ZZZ_NO_EXIST", timeout=1) is None))
    test("wait_for_ui_gone", lambda: assert_true(wait_for_ui_gone(text="ZZZ_NO_EXIST", timeout=1)))
    pkg = device_foreground_package().strip()
    test("wait_for_package_cur", lambda: assert_true(wait_for_package(pkg, timeout=2)))
    test("wait_for_package_fake", lambda: assert_true(not wait_for_package("com.fake.x", timeout=1)))
    test("wait_and_click_text_timeout", lambda: assert_true(not wait_and_click_text("ZZZ", timeout=1)))
    test("wait_and_click_ui_timeout", lambda: assert_true(not wait_and_click_ui(text="ZZZ", timeout=1)))
    act = device_foreground_activity().strip()
    test("wait_for_activity_cur", lambda: assert_true(wait_for_activity(act.split(".")[-1], timeout=2)))
    test("wait_for_activity_fake", lambda: assert_true(not wait_for_activity("FakeActivity999", timeout=1)))
    test("wait_for_image_timeout", lambda: assert_true(wait_for_image("img/not_exist_999.png", timeout=1) is None))

    # [13] 设备扩展
    log_d("\n[13] 设备扩展 API")
    test("get_battery_info", lambda: assert_true("level" in get_battery_info()))
    test("get_screen_orientation", lambda: assert_true(get_screen_orientation() in (0, 1, 2, 3)))
    test("get_wifi_info", lambda: assert_true(isinstance(get_wifi_info(), dict)))
    test("get_notifications", lambda: assert_true(len(get_notifications()) > 0))
    test("set_screen_brightness", lambda: set_screen_brightness(128))

    # [14] 应用管理扩展
    log_d("\n[14] 应用管理扩展")
    test("grant_permission", lambda: assert_true(isinstance(
        grant_permission("com.yyds.auto", "android.permission.READ_EXTERNAL_STORAGE"), bool)))
    test("revoke_permission", lambda: assert_true(isinstance(
        revoke_permission("com.miui.calculator", "android.permission.CAMERA"), bool)))
    test("app_clear_data", lambda: assert_true(isinstance(app_clear_data("com.miui.calculator"), bool)))

    # [15] 分辨率适配
    log_d("\n[15] 分辨率适配")
    sx, sy = scale_pos(540, 1200)
    test("scale_pos", lambda: assert_true(isinstance(sx, int) and isinstance(sy, int)))
    test("click_scaled", lambda: click_scaled(540, 1200))

    # [15.5] 手势扩展
    log_d("\n[15.5] 手势扩展")
    test("pinch_in", lambda: pinch_in(540, 1200, distance=200, duration=300))
    time.sleep(0.3)
    test("pinch_out", lambda: pinch_out(540, 1200, distance=200, duration=300))
    time.sleep(0.3)

    # [15.6] 屏幕变化检测
    log_d("\n[15.6] 屏幕变化检测")
    screenshot("/data/local/tmp/_test_sim1.png")
    screenshot("/data/local/tmp/_test_sim2.png")
    test("image_similarity", lambda: assert_true(image_similarity("/data/local/tmp/_test_sim1.png", "/data/local/tmp/_test_sim2.png") > 0.9))
    test("wait_screen_stable", lambda: assert_true(isinstance(wait_screen_stable(stable_duration=0.5, timeout=3, interval=0.3), bool)))
    test("wait_screen_change_timeout", lambda: assert_true(not wait_screen_change(timeout=1, interval=0.3)))

    # [15.7] Node 扩展方法
    log_d("\n[15.7] Node 扩展方法")
    nodes2 = ui_match(visible_to_user="true", limit=3)
    if nodes2:
        test("node.long_press", lambda: nodes2[0].long_press(300))
        time.sleep(0.3)
        if nodes2[0].is_scroll_able:
            test("node.scroll_forward", lambda: nodes2[0].scroll_forward())
            test("node.scroll_backward", lambda: nodes2[0].scroll_backward())

    # [16] 工具函数
    log_d("\n[16] 工具函数")
    test("md5_str", lambda: assert_true(md5_str("hello") == "5d41402abc4b2a76b9719d911017c592"))
    test("format_time", lambda: assert_true(len(format_time()) > 0))
    test("Point", lambda: assert_true(Point(10, 20).x == 10))
    test("Color", lambda: assert_true(Color(255, 0, 0).r == 255))
    test("Color.similarity", lambda: assert_true(Color(100, 100, 100).similarity_to(Color(100, 100, 100)) > 0.99))

    # [17] 配置
    log_d("\n[17] 配置读取")
    test("project_name", lambda: assert_true(len(ProjectEnvironment.current_project()) > 0))
    test("config_path", lambda: assert_true("Yyds.Py" in Config.get_config_path()))
    test("read_ui_value", lambda: Config.read_ui_value("text-notice"))

    # 汇总
    log_d("\n" + "=" * 50)
    total = passed + failed
    log_d(f"测试完成: {total} 项, 通过 {passed}, 失败 {failed}")
    if errors:
        log_d("失败列表:")
        for e in errors:
            log_d(e)
    log_d("=" * 50)
    toast(f"测试完成: {passed}/{total} 通过" + (f", {failed} 失败!" if failed else " 全部通过!"))


if not os.path.exists("/sdcard"):
    main()
