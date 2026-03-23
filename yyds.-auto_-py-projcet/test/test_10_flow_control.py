"""
测试模块 10: 流程控制与装饰器
验证 auto_func 中的装饰器、重试、任务管理等接口 (纯逻辑, 不依赖设备)
"""
import pytest
from yyds import *


class TestTryRun:
    """try_run 异常捕获测试"""

    def test_try_run_success(self):
        """正常函数不应被吞掉返回值"""
        def ok_func():
            return 42
        result = try_run(ok_func)
        assert result == 42, f"try_run 正常应返回 42, 实际: {result}"

    def test_try_run_exception(self):
        """异常函数应返回 None 而不是抛出"""
        def bad_func():
            raise ValueError("boom")
        result = try_run(bad_func)
        assert result is None, f"try_run 异常应返回 None, 实际: {result}"


class TestRetryUntilTrue:
    """retry_until_true 装饰器重试测试
    注意: retry_until_true 是装饰器, 签名为 retry_until_true(retry_time, interval)
    """

    def test_retry_success_first(self):
        """第一次就成功"""
        call_count = [0]

        @retry_until_true(retry_time=3, interval=0)
        def func():
            call_count[0] += 1
            return True

        # func 此时已经是装饰器执行的返回值 (True/False)
        assert func is True
        assert call_count[0] == 1, f"应只调用 1 次, 实际: {call_count[0]}"

    def test_retry_success_third(self):
        """第三次才成功"""
        call_count = [0]

        @retry_until_true(retry_time=5, interval=0)
        def func():
            call_count[0] += 1
            return call_count[0] >= 3

        assert func is True
        assert call_count[0] == 3, f"应调用 3 次, 实际: {call_count[0]}"

    def test_retry_all_fail(self):
        """全部失败"""
        call_count = [0]

        @retry_until_true(retry_time=3, interval=0)
        def func():
            call_count[0] += 1
            return False

        assert func is False
        assert call_count[0] == 3, f"应调用 3 次, 实际: {call_count[0]}"


class TestRunDecorator:
    """@run 装饰器测试"""

    def test_run_decorator(self):
        """@run 装饰器执行函数"""
        executed = [False]

        @run
        def my_task():
            executed[0] = True

        assert executed[0] is True, "@run 装饰器应立即执行函数"

    def test_run_no_hurt_decorator(self):
        """@run_no_hurt 装饰器 — 异常不传播"""
        @run_no_hurt
        def my_bad_task():
            raise RuntimeError("test error")
        # 如果走到这里, 说明异常被吞掉了
        assert True


class TestDo:
    """do 循环调用测试
    签名: do(times, interval, pre_interval, *func)
    """

    def test_do_basic(self):
        """do(times, interval, pre_interval, func) 应调用 times 次"""
        call_count = [0]
        def func():
            call_count[0] += 1
        do(5, 0, False, func)
        assert call_count[0] == 5, f"do 应调用 5 次, 实际: {call_count[0]}"

    def test_do_multi_func(self):
        """do 同时执行多个函数"""
        count_a = [0]
        count_b = [0]
        def fa():
            count_a[0] += 1
        def fb():
            count_b[0] += 1
        do(3, 0, False, fa, fb)
        assert count_a[0] == 3, f"fa 应调用 3 次, 实际: {count_a[0]}"
        assert count_b[0] == 3, f"fb 应调用 3 次, 实际: {count_b[0]}"


class TestRunUntilTrue:
    """run_until_true 测试
    签名: run_until_true(func, max_times)
    """

    def test_run_until_true(self):
        """执行直到返回 True"""
        call_count = [0]
        def func():
            call_count[0] += 1
            return call_count[0] >= 2
        run_until_true(func, max_times=5)
        assert call_count[0] == 2, f"应调用 2 次, 实际: {call_count[0]}"

    def test_run_until_true_all_fail(self):
        """全部失败 — 执行 max_times 次后停止"""
        call_count = [0]
        def func():
            call_count[0] += 1
            return False
        run_until_true(func, max_times=4)
        assert call_count[0] == 4, f"应调用 4 次, 实际: {call_count[0]}"
