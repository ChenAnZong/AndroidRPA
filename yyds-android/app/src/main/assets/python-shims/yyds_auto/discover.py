"""局域网设备自动扫描 — 并发 TCP 探测 + 设备信息采集"""

from __future__ import annotations

import ipaddress
import logging
import socket
import struct
from concurrent.futures import ThreadPoolExecutor, as_completed
from typing import List, Optional, Tuple, Union

from .client import HttpClient
from .types import DiscoveredDevice

logger = logging.getLogger("yyds_auto")

DEFAULT_PORT = 61140
DEFAULT_TIMEOUT = 0.3
DEFAULT_MAX_WORKERS = 128


# ---------- 网络接口探测 ----------

def _get_local_subnets() -> List[str]:
    """获取本机所有网络接口的子网（CIDR 格式）"""
    subnets: List[str] = []

    try:
        # 跨平台方案：通过 socket 获取本机 IP，推算 /24 子网
        hostname = socket.gethostname()
        addrs = socket.getaddrinfo(hostname, None, socket.AF_INET)
        seen = set()
        for _, _, _, _, sockaddr in addrs:
            ip = sockaddr[0]
            if ip.startswith("127.") or ip in seen:
                continue
            seen.add(ip)
            # 假设 /24 子网
            net = ipaddress.IPv4Network(f"{ip}/24", strict=False)
            subnets.append(str(net))
    except Exception:
        pass

    # 备选：尝试 netifaces
    if not subnets:
        try:
            import netifaces
            for iface in netifaces.interfaces():
                addrs = netifaces.ifaddresses(iface)
                for info in addrs.get(netifaces.AF_INET, []):
                    ip = info.get("addr", "")
                    mask = info.get("netmask", "255.255.255.0")
                    if ip.startswith("127.") or not ip:
                        continue
                    net = ipaddress.IPv4Network(f"{ip}/{mask}", strict=False)
                    subnets.append(str(net))
        except ImportError:
            pass

    # 最后兜底：尝试连接外部地址获取本机 IP
    if not subnets:
        try:
            s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            s.connect(("8.8.8.8", 80))
            ip = s.getsockname()[0]
            s.close()
            net = ipaddress.IPv4Network(f"{ip}/24", strict=False)
            subnets.append(str(net))
        except Exception:
            pass

    return list(set(subnets))


def _get_local_ips() -> set:
    """获取本机所有 IP 地址"""
    local_ips = {"127.0.0.1"}
    try:
        hostname = socket.gethostname()
        for info in socket.getaddrinfo(hostname, None, socket.AF_INET):
            local_ips.add(info[4][0])
    except Exception:
        pass
    return local_ips


# ---------- TCP 探测 ----------

def _tcp_probe(ip: str, port: int, timeout: float) -> Optional[str]:
    """TCP 连接探测，成功返回 IP，失败返回 None"""
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        sock.settimeout(timeout)
        result = sock.connect_ex((ip, port))
        sock.close()
        if result == 0:
            return ip
    except Exception:
        pass
    return None


def _fetch_device_info(ip: str, port: int) -> Optional[DiscoveredDevice]:
    """获取设备信息，确认是 yyds.py 引擎"""
    try:
        client = HttpClient(host=ip, port=port, timeout=3)
        # GET / 返回引擎信息
        resp = client.get("/", timeout=3)
        if resp.status_code != 200:
            return None

        text = resp.text
        dev = DiscoveredDevice(ip=ip, port=port)

        # 尝试解析 JSON 响应
        try:
            import json
            data = json.loads(text)
            if isinstance(data, dict):
                dev.model = data.get("model", "")
                dev.brand = data.get("brand", "")
                dev.android_version = data.get("androidVersion", data.get("android_version", ""))
                dev.engine_version = data.get("version", data.get("engineVersion", ""))
        except (ValueError, TypeError):
            # 非 JSON 响应，但端口可达，仍视为有效设备
            if "yyds" in text.lower() or "engine" in text.lower():
                pass
            else:
                return None

        return dev
    except Exception:
        return None


# ---------- 公开 API ----------

def discover(
    subnet: Union[str, List[str], None] = None,
    port: int = DEFAULT_PORT,
    timeout: float = DEFAULT_TIMEOUT,
    max_workers: int = DEFAULT_MAX_WORKERS,
) -> List[DiscoveredDevice]:
    """扫描局域网中运行 yyds.py 引擎的设备

    Args:
        subnet: 子网范围（CIDR 格式），如 "192.168.1.0/24"。
                可传入列表扫描多个子网。None 则自动检测本机网段。
        port: 引擎端口，默认 61140
        timeout: 单个 IP 探测超时（秒），默认 0.3
        max_workers: 并发线程数，默认 128

    Returns:
        发现的设备列表

    Example:
        >>> import yyds_auto
        >>> devices = yyds_auto.discover()
        >>> for dev in devices:
        ...     print(f"{dev.ip} - {dev.model}")
        >>> d = devices[0].connect()
    """
    # 确定扫描范围
    if subnet is None:
        subnets = _get_local_subnets()
        if not subnets:
            logger.warning("无法自动检测本机网段，请手动指定 subnet 参数")
            return []
        logger.info("自动检测到子网: %s", ", ".join(subnets))
    elif isinstance(subnet, str):
        subnets = [subnet]
    else:
        subnets = list(subnet)

    # 收集所有待探测 IP
    local_ips = _get_local_ips()
    all_ips: List[str] = []
    for sn in subnets:
        try:
            network = ipaddress.IPv4Network(sn, strict=False)
            for host in network.hosts():
                ip = str(host)
                if ip not in local_ips:
                    all_ips.append(ip)
        except ValueError as e:
            logger.warning("无效子网 %s: %s", sn, e)

    if not all_ips:
        return []

    logger.info("开始扫描 %d 个 IP (端口 %d) ...", len(all_ips), port)

    # 阶段 1: 并发 TCP 探测
    alive_ips: List[str] = []
    with ThreadPoolExecutor(max_workers=max_workers) as pool:
        futures = {pool.submit(_tcp_probe, ip, port, timeout): ip for ip in all_ips}
        for future in as_completed(futures):
            result = future.result()
            if result:
                alive_ips.append(result)

    if not alive_ips:
        logger.info("未发现存活设备")
        return []

    logger.info("发现 %d 个端口存活，正在获取设备信息 ...", len(alive_ips))

    # 阶段 2: 获取设备信息
    devices: List[DiscoveredDevice] = []
    with ThreadPoolExecutor(max_workers=min(len(alive_ips), 16)) as pool:
        futures = {pool.submit(_fetch_device_info, ip, port): ip for ip in alive_ips}
        for future in as_completed(futures):
            dev = future.result()
            if dev:
                devices.append(dev)

    # 按 IP 排序
    devices.sort(key=lambda d: tuple(int(x) for x in d.ip.split(".")))
    logger.info("扫描完成，发现 %d 台设备", len(devices))
    return devices
