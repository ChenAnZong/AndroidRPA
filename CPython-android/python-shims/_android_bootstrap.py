"""
Android Bootstrap Module for Embedded CPython

在 CPython 解释器初始化后由 cpython_bridge.cpp 自动加载。
负责所有 Python 层面的 Android 适配，替代 Chaquopy 内部的平台适配层。

适配内容：
  1. SSL/TLS 证书配置 — 让 requests/urllib3/aiohttp 正常工作
  2. tempfile 目录修正 — Android 没有 /tmp
  3. multiprocessing 适配 — Android 上 fork() 不可靠
  4. locale 安全默认值 — Android 缺少 locale 数据库
  5. DNS 兼容性 — Android 特殊的 DNS 解析
  6. 文件系统编码 — 确保 UTF-8
"""

import os
import sys


def _setup_ssl_certificates():
    """
    配置 SSL/TLS CA 证书。

    Android 没有 /etc/ssl/certs，Python 的 ssl 模块默认找不到 CA 证书，
    导致所有 HTTPS 请求失败（requests, urllib3, aiohttp 等）。

    策略（按优先级）：
    1. 如果安装了 certifi 包，使用其 CA bundle
    2. 尝试 Android 系统 CA 证书目录
    3. 设置 SSL 环境变量指向找到的证书
    """
    # 已经设置过就跳过
    if os.environ.get("SSL_CERT_FILE"):
        return

    cert_file = None

    # 策略1: certifi 包（最可靠，pip install certifi）
    try:
        import certifi
        cert_file = certifi.where()
    except ImportError:
        pass

    # 策略2: Android 系统 CA 证书
    if not cert_file:
        android_ca_paths = [
            "/system/etc/security/cacerts",       # 系统证书目录
            "/apex/com.android.conscrypt/cacerts", # Android 14+ Conscrypt module
            "/etc/ssl/certs",                      # 标准 Linux 路径（某些ROM）
        ]
        for ca_dir in android_ca_paths:
            if os.path.isdir(ca_dir) and os.listdir(ca_dir):
                os.environ["SSL_CERT_DIR"] = ca_dir
                break

    # 策略3: 合并 Android 系统证书为单个 PEM 文件
    if not cert_file and not os.environ.get("SSL_CERT_DIR"):
        ca_dir = "/system/etc/security/cacerts"
        if os.path.isdir(ca_dir):
            # 合并所有单独的证书文件为一个 PEM bundle
            combined = os.path.join(os.environ.get("TMPDIR", "/data/local/tmp"), "ca-certificates.pem")
            if not os.path.exists(combined):
                try:
                    with open(combined, "w") as out:
                        for f in sorted(os.listdir(ca_dir)):
                            fp = os.path.join(ca_dir, f)
                            if os.path.isfile(fp):
                                with open(fp, "r") as cert:
                                    out.write(cert.read())
                                    out.write("\n")
                    cert_file = combined
                except Exception:
                    pass
            else:
                cert_file = combined

    if cert_file:
        os.environ["SSL_CERT_FILE"] = cert_file

    # 尝试 patch ssl 模块的默认上下文
    try:
        import ssl
        _orig_create_default = ssl.create_default_context.__wrapped__ if hasattr(ssl.create_default_context, '__wrapped__') else None

        if cert_file or os.environ.get("SSL_CERT_DIR"):
            _real_create_default = ssl.create_default_context
            def _patched_create_default(purpose=ssl.Purpose.SERVER_AUTH, cafile=None, capath=None, cadata=None):
                if cafile is None and cadata is None:
                    cafile = cert_file
                if capath is None:
                    capath = os.environ.get("SSL_CERT_DIR")
                ctx = _real_create_default(purpose, cafile=cafile, capath=capath, cadata=cadata)
                return ctx
            _patched_create_default.__wrapped__ = _real_create_default
            ssl.create_default_context = _patched_create_default
    except ImportError:
        pass  # ssl 模块未编译（不太可能但安全处理）


def _setup_tempfile():
    """
    确保 tempfile 模块使用正确的临时目录。

    Android 没有 /tmp 目录。C层已经设置了 TMPDIR 环境变量，
    但 tempfile 模块在导入时缓存了 tempdir，需要显式重置。
    """
    tmpdir = os.environ.get("TMPDIR")
    if tmpdir:
        os.makedirs(tmpdir, exist_ok=True)
        try:
            import tempfile
            tempfile.tempdir = tmpdir
        except ImportError:
            pass


def _setup_multiprocessing():
    """
    适配 multiprocessing 模块。

    Android 上 fork() 存在严重问题：
    - Zygote 进程模型与 fork 冲突
    - fork 后子进程的 Binder 线程池无效
    - 某些 Android 版本直接禁止 fork

    将默认启动方式改为 'forkserver' 或 'spawn'（如果可用）。
    """
    try:
        import multiprocessing
        # Android 上优先用 spawn（最安全），避免 fork
        if hasattr(multiprocessing, 'set_start_method'):
            try:
                multiprocessing.set_start_method('spawn', force=True)
            except RuntimeError:
                pass  # 已经设置过
    except ImportError:
        pass


def _setup_locale():
    """
    配置 locale 安全默认值。

    Android 没有标准的 locale 数据库（/usr/share/locale 不存在），
    locale.getlocale() 和 locale.getpreferredencoding() 可能返回 None 或崩溃。

    Chaquopy 通过 patch locale 模块来解决。
    """
    try:
        import locale
        # 确保有安全的默认 encoding
        _orig_getpreferredencoding = locale.getpreferredencoding
        def _safe_getpreferredencoding(do_setlocale=True):
            try:
                enc = _orig_getpreferredencoding(do_setlocale)
                if enc:
                    return enc
            except Exception:
                pass
            return "UTF-8"
        locale.getpreferredencoding = _safe_getpreferredencoding

        # 尝试设置 locale，失败了也没关系
        try:
            locale.setlocale(locale.LC_ALL, "C.UTF-8")
        except locale.Error:
            try:
                locale.setlocale(locale.LC_ALL, "C")
            except locale.Error:
                pass
    except ImportError:
        pass


def _setup_filesystem_encoding():
    """
    确保文件系统编码为 UTF-8。

    Android 文件系统使用 UTF-8 编码文件名，
    但 CPython 在某些 locale 配置下可能使用 ASCII。
    """
    if sys.getfilesystemencoding() != 'utf-8':
        try:
            # CPython 3.7+ 支持 UTF-8 mode
            sys.flags  # 确认 sys 已初始化
            os.environ["PYTHONUTF8"] = "1"
        except Exception:
            pass


def _patch_os_module():
    """
    修补 os 模块的 Android 不兼容行为。

    - os.getlogin() 在 Android 上会失败（没有 utmp/wtmp）
    - os.uname() 返回的信息在 Android 上可能不完整
    """
    import os as _os

    # os.getlogin() 在没有终端的进程中会失败
    _orig_getlogin = getattr(_os, 'getlogin', None)
    if _orig_getlogin:
        def _safe_getlogin():
            try:
                return _orig_getlogin()
            except OSError:
                return os.environ.get("USER", os.environ.get("LOGNAME", "shell"))
        _os.getlogin = _safe_getlogin


def _setup_android_paths():
    """
    确保关键目录存在（包括 pip 缓存目录）。
    """
    home = os.environ.get("HOME", "")
    for d in [
        os.environ.get("TMPDIR", ""),
        os.path.join(home, ".cache") if home else "",
        os.path.join(home, ".cache", "pip") if home else "",
        os.path.join(home, ".local") if home else "",
        os.path.join(home, ".local", "lib") if home else "",
    ]:
        if d:
            os.makedirs(d, exist_ok=True)


# ============================================================
# 执行所有适配（模块导入时自动运行）
# ============================================================

def _inject_yyds_builtins():
    """
    将 yyds SDK 的所有公开 API 注入 builtins，
    使 click/screenshot/ocr 等函数像 print 一样全局可用，无需 import。
    """
    try:
        import builtins
        import yyds
        for name in getattr(yyds, '__all__', []):
            setattr(builtins, name, getattr(yyds, name))
        # console 对象也注入
        from yyds.console_shim import console
        builtins.console = console
    except Exception as e:
        print(f"[_android_bootstrap] 注入 yyds builtins 失败: {e}", file=sys.__stderr__)


def _bootstrap():
    """执行所有 Android 适配，按依赖顺序。"""
    try:
        _setup_filesystem_encoding()
        _setup_android_paths()
        _setup_locale()
        _setup_tempfile()
        _setup_multiprocessing()
        _setup_ssl_certificates()
        _patch_os_module()
    except Exception as e:
        # bootstrap 不应该导致整个引擎崩溃
        print(f"[_android_bootstrap] 警告: 部分适配失败: {e}", file=sys.__stderr__)

    # 最后注入 yyds SDK 到 builtins（依赖前面的路径和SSL配置）
    _inject_yyds_builtins()

_bootstrap()
