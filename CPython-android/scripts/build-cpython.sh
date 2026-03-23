#!/usr/bin/env bash
# ============================================================
# CPython for Android 交叉编译脚本
# 在 WSL/Linux 环境中运行
#
# 用法: ./build-cpython.sh [arm64|arm|x86_64|x86] [python_version]
# 默认: arm64 3.13.2
#
# 依赖: gcc, make, wget/curl, pkg-config, zlib1g-dev, libffi-dev, libssl-dev
# ============================================================

set -euo pipefail

# ============================================================
# 配置
# ============================================================
TARGET_ABI="${1:-arm64}"
PYTHON_VERSION="${2:-3.13.2}"
PYTHON_MAJOR_MINOR=$(echo "$PYTHON_VERSION" | cut -d. -f1,2)  # e.g. 3.13

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
BUILD_DIR="$PROJECT_DIR/build"
DIST_DIR="$PROJECT_DIR/dist"
SRC_DIR="$PROJECT_DIR/cpython-src"
NDK_DIR="$PROJECT_DIR/android-ndk"

# NDK 版本 (r27c LTS, 支持 Python 3.13 Android 构建)
NDK_VERSION="r27c"
NDK_ARCHIVE="android-ndk-${NDK_VERSION}-linux.zip"
NDK_URL="https://dl.google.com/android/repository/${NDK_ARCHIVE}"

# Python 源码
PYTHON_ARCHIVE="Python-${PYTHON_VERSION}.tar.xz"
PYTHON_URL="https://www.python.org/ftp/python/${PYTHON_VERSION}/${PYTHON_ARCHIVE}"

# Android API level (匹配 minSdkVersion=24)
API_LEVEL=24

# ABI 映射
case "$TARGET_ABI" in
    arm64|aarch64)
        ANDROID_TRIPLE="aarch64-linux-android"
        ARCH="aarch64"
        ;;
    arm|armeabi-v7a)
        ANDROID_TRIPLE="armv7a-linux-androideabi"
        ARCH="arm"
        ;;
    x86_64)
        ANDROID_TRIPLE="x86_64-linux-android"
        ARCH="x86_64"
        ;;
    x86)
        ANDROID_TRIPLE="i686-linux-android"
        ARCH="x86"
        ;;
    *)
        echo "未知 ABI: $TARGET_ABI (支持: arm64, arm, x86_64, x86)"
        exit 1
        ;;
esac

HOST_TRIPLE="${ANDROID_TRIPLE}${API_LEVEL}"
BUILD_SUBDIR="$BUILD_DIR/$ARCH"
HOST_BUILD_DIR="$BUILD_DIR/host"
INSTALL_DIR="$BUILD_SUBDIR/install"

echo "============================================================"
echo " CPython ${PYTHON_VERSION} for Android 交叉编译"
echo " ABI:        $TARGET_ABI ($ARCH)"
echo " Triple:     $HOST_TRIPLE"
echo " API Level:  $API_LEVEL"
echo " Build Dir:  $BUILD_SUBDIR"
echo " Install:    $INSTALL_DIR"
echo "============================================================"

# ============================================================
# 1. 安装系统依赖
# ============================================================
install_deps() {
    echo ""
    echo "[1/7] 检查并安装系统依赖..."

    local NEED_INSTALL=0
    for pkg in build-essential zlib1g-dev libffi-dev libssl-dev pkg-config wget unzip; do
        if ! dpkg -s "$pkg" &>/dev/null; then
            NEED_INSTALL=1
            break
        fi
    done

    if [ "$NEED_INSTALL" -eq 1 ]; then
        echo "  安装缺失的依赖包..."
        sudo apt-get update -qq
        sudo apt-get install -y -qq build-essential zlib1g-dev libffi-dev libssl-dev \
            pkg-config wget unzip lzma liblzma-dev libreadline-dev libncurses5-dev \
            libncursesw5-dev libbz2-dev libsqlite3-dev uuid-dev
    else
        echo "  所有依赖已安装"
    fi
}

# ============================================================
# 2. 下载 Android NDK
# ============================================================
download_ndk() {
    echo ""
    echo "[2/7] 准备 Android NDK ${NDK_VERSION}..."

    # 检查NDK是否已存在
    if [ -d "$NDK_DIR" ] && [ -f "$NDK_DIR/build/cmake/android.toolchain.cmake" ]; then
        echo "  NDK 已存在: $NDK_DIR"
        return 0
    fi

    local NDK_ARCHIVE_PATH="$PROJECT_DIR/$NDK_ARCHIVE"

    if [ ! -f "$NDK_ARCHIVE_PATH" ]; then
        echo "  下载 NDK (约1.5GB，请耐心等待)..."
        wget -q --show-progress -O "$NDK_ARCHIVE_PATH" "$NDK_URL"
    fi

    echo "  解压 NDK..."
    unzip -q -o "$NDK_ARCHIVE_PATH" -d "$PROJECT_DIR"
    mv "$PROJECT_DIR/android-ndk-${NDK_VERSION}" "$NDK_DIR"
    rm -f "$NDK_ARCHIVE_PATH"
    echo "  NDK 准备完成: $NDK_DIR"
}

# ============================================================
# 3. 下载 CPython 源码
# ============================================================
download_cpython() {
    echo ""
    echo "[3/7] 准备 CPython ${PYTHON_VERSION} 源码..."

    if [ -d "$SRC_DIR" ] && [ -f "$SRC_DIR/configure" ]; then
        echo "  源码已存在: $SRC_DIR"
        return 0
    fi

    local ARCHIVE_PATH="$PROJECT_DIR/$PYTHON_ARCHIVE"

    if [ ! -f "$ARCHIVE_PATH" ]; then
        echo "  下载 CPython 源码..."
        wget -q --show-progress -O "$ARCHIVE_PATH" "$PYTHON_URL"
    fi

    echo "  解压源码..."
    mkdir -p "$SRC_DIR"
    tar xf "$ARCHIVE_PATH" -C "$PROJECT_DIR"
    mv "$PROJECT_DIR/Python-${PYTHON_VERSION}"/* "$SRC_DIR/"
    rm -rf "$PROJECT_DIR/Python-${PYTHON_VERSION}"
    rm -f "$ARCHIVE_PATH"
    echo "  源码准备完成: $SRC_DIR"
}

# ============================================================
# 4. 构建宿主 Python (用于交叉编译)
# ============================================================
build_host_python() {
    echo ""
    echo "[4/7] 构建宿主 Python ${PYTHON_VERSION}..."

    # 检查是否已构建（WSL 上可能是 python.exe 而非 python）
    if [ -f "$HOST_BUILD_DIR/python.exe" ] || [ -x "$HOST_BUILD_DIR/python" ]; then
        echo "  宿主 Python 已存在"
    else
        mkdir -p "$HOST_BUILD_DIR"
        cd "$HOST_BUILD_DIR"

        "$SRC_DIR/configure" \
            --prefix="$HOST_BUILD_DIR/install" \
            2>&1 | tail -5

        make -j"$(nproc)" 2>&1 | tail -3
        echo "  宿主 Python 构建完成"
    fi

    # 确定宿主 Python 二进制路径（WSL/Windows FS 上是 python.exe）
    if [ -f "$HOST_BUILD_DIR/python.exe" ]; then
        HOST_PYTHON="$HOST_BUILD_DIR/python.exe"
    elif [ -x "$HOST_BUILD_DIR/python" ]; then
        HOST_PYTHON="$HOST_BUILD_DIR/python"
    else
        echo "  错误: 宿主 Python 构建失败，找不到二进制文件"
        exit 1
    fi
    echo "  宿主 Python: $HOST_PYTHON"
}

# ============================================================
# 5. 交叉编译 CPython for Android
# ============================================================
cross_compile_cpython() {
    echo ""
    echo "[5/7] 交叉编译 CPython for Android ($ARCH)..."

    # NDK 工具链路径
    local TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64"
    local SYSROOT="$TOOLCHAIN/sysroot"

    if [ ! -d "$TOOLCHAIN" ]; then
        echo "  错误: NDK工具链不存在: $TOOLCHAIN"
        exit 1
    fi

    # 编译器
    local CC="${TOOLCHAIN}/bin/${HOST_TRIPLE}-clang"
    local CXX="${TOOLCHAIN}/bin/${HOST_TRIPLE}-clang++"
    local AR="${TOOLCHAIN}/bin/llvm-ar"
    local RANLIB="${TOOLCHAIN}/bin/llvm-ranlib"
    local STRIP="${TOOLCHAIN}/bin/llvm-strip"
    local READELF="${TOOLCHAIN}/bin/llvm-readelf"

    # 检查编译器
    if [ ! -f "$CC" ]; then
        echo "  错误: 编译器不存在: $CC"
        echo "  可用编译器:"
        ls "$TOOLCHAIN/bin/" | grep -i "$ARCH" | head -10
        exit 1
    fi

    mkdir -p "$BUILD_SUBDIR/cross"
    cd "$BUILD_SUBDIR/cross"

    # 配置交叉编译
    # CPython 3.13+ 支持 --with-build-python 指定宿主python用于生成
    local CONFIG_ARGS=(
        "--host=${ANDROID_TRIPLE}"
        "--build=x86_64-linux-gnu"
        "--with-build-python=$HOST_PYTHON"
        "--prefix=$INSTALL_DIR"
        "--enable-shared"
        "--without-ensurepip"
        "--without-pymalloc"
        "--disable-ipv6"
        "ac_cv_file__dev_ptmx=no"
        "ac_cv_file__dev_ptc=no"
        # Android NDK 不提供以下库，显式告知 configure 跳过
        "ac_cv_header_readline_readline_h=no"
        "ac_cv_header_curses_h=no"
        "ac_cv_header_ncurses_h=no"
        "ac_cv_lib_readline_readline=no"
        "ac_cv_header_uuid_uuid_h=no"
    )

    # 设置环境变量（防止宿主 pkg-config 泄漏路径到交叉编译）
    unset PKG_CONFIG_PATH PKG_CONFIG_LIBDIR
    export PKG_CONFIG=/nonexistent
    export CC="$CC"
    export CXX="$CXX"
    export AR="$AR"
    export RANLIB="$RANLIB"
    export STRIP="$STRIP"
    export READELF="$READELF"
    export CFLAGS="-fPIC -O2"
    # -Wl,-z,max-page-size=16384: Android 15+ 要求 ELF LOAD 段 16KB 页面对齐
    export LDFLAGS="-L${SYSROOT}/usr/lib/${ANDROID_TRIPLE}/${API_LEVEL} -Wl,-z,max-page-size=16384"
    export CPPFLAGS="-I${SYSROOT}/usr/include"

    if [ ! -f "Makefile" ]; then
        echo "  配置中..."
        "$SRC_DIR/configure" "${CONFIG_ARGS[@]}" 2>&1 | tail -10
    fi

    echo "  编译中 (使用 $(nproc) 核心)..."
    make -j"$(nproc)" 2>&1 | tail -5

    echo "  安装到 $INSTALL_DIR ..."
    make install 2>&1 | tail -3

    # Strip 二进制文件减小体积
    echo "  Strip 二进制文件..."
    find "$INSTALL_DIR" -name "*.so" -exec "$STRIP" --strip-unneeded {} \; 2>/dev/null || true
    "$STRIP" --strip-unneeded "$INSTALL_DIR/bin/python${PYTHON_MAJOR_MINOR}" 2>/dev/null || true

    echo "  交叉编译完成"
}

# ============================================================
# 6. 将 pip/setuptools 预装到交叉编译产物的 site-packages
#    使用宿主 Python 下载纯 Python wheel 并解压到目标 site-packages，
#    这样 pip 随 APK 内置，设备端无需网络即可使用 pip install。
# ============================================================
setup_pip() {
    echo ""
    echo "[6/7] 将 pip + setuptools 预装到 site-packages..."

    local SITE_PACKAGES="$INSTALL_DIR/lib/python${PYTHON_MAJOR_MINOR}/site-packages"
    mkdir -p "$SITE_PACKAGES"

    # 用宿主 Python 下载 pip/setuptools/wheel 的纯 Python wheel 到临时目录
    local WHEEL_DIR="$BUILD_DIR/pip-wheels"
    mkdir -p "$WHEEL_DIR"

    echo "  下载 pip + setuptools + wheel 的 whl 包..."
    "$HOST_PYTHON" -m pip download \
        --only-binary=:all: \
        --no-deps \
        --dest "$WHEEL_DIR" \
        pip setuptools wheel certifi 2>&1 | tail -5

    # 解压所有 whl 到 site-packages（whl 就是 zip 格式）
    echo "  解压 wheel 到 $SITE_PACKAGES ..."
    for whl in "$WHEEL_DIR"/*.whl; do
        [ -f "$whl" ] || continue
        echo "    $(basename "$whl")"
        unzip -q -o "$whl" -d "$SITE_PACKAGES"
    done

    # 创建 pip 可执行脚本（bin/pip3）
    local BIN_DIR="$INSTALL_DIR/bin"
    mkdir -p "$BIN_DIR"
    cat > "$BIN_DIR/pip3" << 'PIP_EOF'
#!/usr/bin/env python3
import sys
from pip._internal.cli.main import main
sys.exit(main())
PIP_EOF
    chmod +x "$BIN_DIR/pip3"
    ln -sf pip3 "$BIN_DIR/pip"

    # 验证
    local PIP_VER=$(ls "$WHEEL_DIR"/pip-*.whl 2>/dev/null | head -1 | sed 's/.*pip-\([^-]*\)-.*/\1/')
    local SETUP_VER=$(ls "$WHEEL_DIR"/setuptools-*.whl 2>/dev/null | head -1 | sed 's/.*setuptools-\([^-]*\)-.*/\1/')
    echo "  预装完成: pip=$PIP_VER, setuptools=$SETUP_VER"
    echo "  site-packages: $SITE_PACKAGES"
    ls "$SITE_PACKAGES" | head -20
}

# ============================================================
# 7. 打包输出
# ============================================================
package_output() {
    echo ""
    echo "[7/7] 打包输出..."

    mkdir -p "$DIST_DIR"

    # ============================================================
    # 7a. 复制 headers 和 libs 到 CMake 可发现位置
    # Android Studio CMake 编译 cpython_bridge.cpp 时需要这些文件
    # ============================================================
    echo "  复制 headers 和 libs 到 CMake 目录..."

    # ABI 名称映射（CMake 使用 Android ABI 名称）
    local ANDROID_ABI
    case "$ARCH" in
        aarch64) ANDROID_ABI="arm64-v8a" ;;
        arm)     ANDROID_ABI="armeabi-v7a" ;;
        x86_64)  ANDROID_ABI="x86_64" ;;
        x86)     ANDROID_ABI="x86" ;;
    esac

    # 复制 Python.h 等头文件
    local CMAKE_INCLUDE_DIR="$PROJECT_DIR/include"
    rm -rf "$CMAKE_INCLUDE_DIR"
    mkdir -p "$CMAKE_INCLUDE_DIR"
    if [ -d "$INSTALL_DIR/include/python${PYTHON_MAJOR_MINOR}" ]; then
        cp -r "$INSTALL_DIR/include/python${PYTHON_MAJOR_MINOR}/"* "$CMAKE_INCLUDE_DIR/"
    fi
    echo "  headers -> $CMAKE_INCLUDE_DIR"

    # 复制 libpython3.13.so
    local CMAKE_LIB_DIR="$PROJECT_DIR/libs/${ANDROID_ABI}"
    mkdir -p "$CMAKE_LIB_DIR"
    local PY_SO=$(find "$INSTALL_DIR/lib" -name "libpython${PYTHON_MAJOR_MINOR}*.so" -type f | head -1)
    if [ -n "$PY_SO" ]; then
        cp "$PY_SO" "$CMAKE_LIB_DIR/libpython${PYTHON_MAJOR_MINOR}.so"
        echo "  lib -> $CMAKE_LIB_DIR/libpython${PYTHON_MAJOR_MINOR}.so"
    else
        echo "  警告: 未找到 libpython*.so，尝试查找静态库..."
        local PY_A=$(find "$INSTALL_DIR/lib" -name "libpython${PYTHON_MAJOR_MINOR}*.a" -type f | head -1)
        if [ -n "$PY_A" ]; then
            cp "$PY_A" "$CMAKE_LIB_DIR/"
            echo "  lib (static) -> $CMAKE_LIB_DIR/"
        fi
    fi

    # 复制 python-shims 到 dist
    if [ -d "$PROJECT_DIR/python-shims" ]; then
        echo "  复制 python-shims..."
        mkdir -p "$DIST_DIR/python-shims"
        cp -r "$PROJECT_DIR/python-shims/"* "$DIST_DIR/python-shims/"
    fi

    # ============================================================
    # 7b. 清理安装目录（用于设备部署的 tarball）
    # ============================================================
    echo "  清理测试和文档..."
    rm -rf "$INSTALL_DIR/lib/python${PYTHON_MAJOR_MINOR}/test"
    rm -rf "$INSTALL_DIR/lib/python${PYTHON_MAJOR_MINOR}/unittest/test"
    rm -rf "$INSTALL_DIR/lib/python${PYTHON_MAJOR_MINOR}/idlelib"
    rm -rf "$INSTALL_DIR/lib/python${PYTHON_MAJOR_MINOR}/tkinter"
    rm -rf "$INSTALL_DIR/lib/python${PYTHON_MAJOR_MINOR}/turtle*"
    rm -rf "$INSTALL_DIR/share"
    # 保留 ensurepip（备用）和 site-packages（内置 pip/setuptools/wheel/certifi）
    # 注意：不要删除 .dist-info 目录！pip list/show 依赖它们来识别已安装的包
    find "$INSTALL_DIR" -name "__pycache__" -type d -exec rm -rf {} + 2>/dev/null || true
    find "$INSTALL_DIR" -name "*.pyc" -delete 2>/dev/null || true

    # 打包
    local OUTPUT_NAME="cpython-${PYTHON_VERSION}-android-${ANDROID_ABI}-api${API_LEVEL}"
    local TARBALL="$DIST_DIR/${OUTPUT_NAME}.tar.gz"

    echo "  打包: $TARBALL"
    cd "$(dirname "$INSTALL_DIR")"
    tar czf "$TARBALL" -C "$(dirname "$INSTALL_DIR")" "$(basename "$INSTALL_DIR")"

    # 输出信息
    local SIZE=$(du -sh "$TARBALL" | cut -f1)
    local INSTALL_SIZE=$(du -sh "$INSTALL_DIR" | cut -f1)
    echo ""
    echo "============================================================"
    echo " 编译完成!"
    echo " 输出文件:   $TARBALL ($SIZE)"
    echo " 安装目录:   $INSTALL_DIR ($INSTALL_SIZE)"
    echo " Python:     ${PYTHON_VERSION}"
    echo " 架构:       ${ANDROID_ABI} (${ARCH})"
    echo " API Level:  ${API_LEVEL}"
    echo ""
    echo " CMake 集成文件:"
    echo "   headers:  $CMAKE_INCLUDE_DIR/Python.h"
    echo "   library:  $CMAKE_LIB_DIR/libpython${PYTHON_MAJOR_MINOR}.so"
    echo ""
    echo " 部署步骤:"
    echo "   1. 推送到设备: adb push $INSTALL_DIR /data/local/tmp/python3"
    echo "   2. 设置权限:   adb shell chmod -R 755 /data/local/tmp/python3"
    echo "   3. 推送shims:  adb push $DIST_DIR/python-shims /data/local/tmp/cache/python-shims"
    echo "   4. 测试:       adb shell /data/local/tmp/python3/bin/python3 -c 'import pip; print(f\"pip {pip.__version__} 已内置\")'"
    echo ""
    echo " pip 已内置到 site-packages，无需额外安装步骤"
    echo "============================================================"
}

# ============================================================
# 主流程
# ============================================================
main() {
    mkdir -p "$BUILD_DIR" "$DIST_DIR"

    install_deps
    download_ndk
    download_cpython
    build_host_python
    cross_compile_cpython
    setup_pip
    package_output
}

main
