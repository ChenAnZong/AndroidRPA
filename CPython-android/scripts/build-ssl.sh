#!/usr/bin/env bash
# ============================================================
# 交叉编译 OpenSSL + 构建 _ssl.so for Android aarch64
# 在 WSL/Linux 环境中运行
# ============================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BASE_DIR="$(dirname "$SCRIPT_DIR")"
BUILD_DIR="$BASE_DIR/build"
CROSS_DIR="$BUILD_DIR/aarch64/cross"
INSTALL_DIR="$BUILD_DIR/aarch64/install"

NDK_VERSION="r27c"
NDK_DIR="$BUILD_DIR/android-ndk-$NDK_VERSION"
API_LEVEL=24
ARCH="aarch64"
TARGET="aarch64-linux-android"
OPENSSL_VERSION="3.2.3"

OPENSSL_SRC_DIR="$BUILD_DIR/openssl-$OPENSSL_VERSION"
OPENSSL_INSTALL_DIR="$BUILD_DIR/openssl-android-$ARCH"

PYTHON_MAJOR_MINOR="3.13"

# ============================================================
# 1. 下载 NDK（如果不存在）
# ============================================================
download_ndk() {
    if [ -d "$NDK_DIR" ]; then
        echo "[1/4] NDK 已存在: $NDK_DIR"
        return
    fi
    echo "[1/4] 下载 Android NDK $NDK_VERSION..."
    local NDK_ZIP="$BUILD_DIR/android-ndk-$NDK_VERSION-linux.zip"
    if [ ! -f "$NDK_ZIP" ]; then
        wget -q --show-progress -O "$NDK_ZIP" \
            "https://dl.google.com/android/repository/android-ndk-$NDK_VERSION-linux.zip"
    fi
    echo "  解压 NDK..."
    unzip -q -o "$NDK_ZIP" -d "$BUILD_DIR"
    echo "  NDK 就绪: $NDK_DIR"
}

# ============================================================
# 2. 交叉编译 OpenSSL
# ============================================================
build_openssl() {
    if [ -f "$OPENSSL_INSTALL_DIR/lib/libssl.a" ]; then
        echo "[2/4] OpenSSL 已编译: $OPENSSL_INSTALL_DIR"
        return
    fi

    echo "[2/4] 交叉编译 OpenSSL $OPENSSL_VERSION for $TARGET..."

    # 下载 OpenSSL 源码
    if [ ! -d "$OPENSSL_SRC_DIR" ]; then
        local OPENSSL_TAR="$BUILD_DIR/openssl-$OPENSSL_VERSION.tar.gz"
        if [ ! -f "$OPENSSL_TAR" ]; then
            echo "  下载 OpenSSL 源码..."
            wget -q --show-progress -O "$OPENSSL_TAR" \
                "https://github.com/openssl/openssl/releases/download/openssl-$OPENSSL_VERSION/openssl-$OPENSSL_VERSION.tar.gz"
        fi
        tar xzf "$OPENSSL_TAR" -C "$BUILD_DIR"
    fi

    local TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64"
    export ANDROID_NDK_ROOT="$NDK_DIR"
    export ANDROID_NDK_HOME="$NDK_DIR"
    export PATH="$TOOLCHAIN/bin:$PATH"

    cd "$OPENSSL_SRC_DIR"

    # 清理之前的构建
    make clean 2>/dev/null || true

    echo "  配置 OpenSSL..."
    ./Configure android-arm64 \
        --prefix="$OPENSSL_INSTALL_DIR" \
        --openssldir="$OPENSSL_INSTALL_DIR/ssl" \
        -D__ANDROID_API__=$API_LEVEL \
        no-shared \
        no-tests \
        no-ui-console \
        no-engine \
        no-comp \
        2>&1 | tail -5

    echo "  编译 OpenSSL（这需要几分钟）..."
    make -j$(nproc) 2>&1 | tail -5

    echo "  安装 OpenSSL..."
    make install_sw 2>&1 | tail -3

    echo "  OpenSSL 编译完成: $OPENSSL_INSTALL_DIR"
    ls -la "$OPENSSL_INSTALL_DIR/lib/"libssl* "$OPENSSL_INSTALL_DIR/lib/"libcrypto*
}

# ============================================================
# 3. 构建 _ssl.so 和 _hashlib.so
# ============================================================
build_ssl_module() {
    echo "[3/4] 构建 _ssl.so 和 _hashlib.so..."

    local TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64"
    local CC_BIN="${TOOLCHAIN}/bin/${TARGET}${API_LEVEL}-clang"
    local PYTHON_INCLUDE="$INSTALL_DIR/include/python${PYTHON_MAJOR_MINOR}"
    local PYTHON_LIB="$INSTALL_DIR/lib"
    local DYNLOAD_DIR="$INSTALL_DIR/lib/python${PYTHON_MAJOR_MINOR}/lib-dynload"
    local CPYTHON_SRC="$CROSS_DIR"  # CPython 交叉编译源码目录

    # 确认源文件存在
    if [ ! -f "$CPYTHON_SRC/Modules/_ssl.c" ]; then
        # 尝试从 CPython 源码下载
        echo "  CPython 源码中找不到 _ssl.c，尝试从源码目录查找..."
        CPYTHON_SRC=$(find "$BUILD_DIR" -name "_ssl.c" -path "*/Modules/*" -exec dirname {} \; | head -1)
        CPYTHON_SRC="$(dirname "$CPYTHON_SRC")"  # 回到 CPython 根目录
        if [ -z "$CPYTHON_SRC" ] || [ ! -f "$CPYTHON_SRC/Modules/_ssl.c" ]; then
            echo "  错误: 找不到 CPython 源码中的 _ssl.c"
            echo "  尝试下载 CPython 源码..."
            local CPYTHON_TAR="$BUILD_DIR/cpython-${PYTHON_MAJOR_MINOR}.2.tar.gz"
            if [ ! -f "$CPYTHON_TAR" ]; then
                wget -q --show-progress -O "$CPYTHON_TAR" \
                    "https://www.python.org/ftp/python/${PYTHON_MAJOR_MINOR}.2/Python-${PYTHON_MAJOR_MINOR}.2.tgz"
            fi
            tar xzf "$CPYTHON_TAR" -C "$BUILD_DIR"
            CPYTHON_SRC="$BUILD_DIR/Python-${PYTHON_MAJOR_MINOR}.2"
        fi
    fi

    echo "  CPython 源码: $CPYTHON_SRC"
    echo "  OpenSSL: $OPENSSL_INSTALL_DIR"

    local CROSS_BUILD="$BUILD_DIR/aarch64/cross"
    local CFLAGS="-I$PYTHON_INCLUDE -I$OPENSSL_INSTALL_DIR/include -I$CPYTHON_SRC/Include -I$CPYTHON_SRC/Include/internal -I$CPYTHON_SRC -I$CROSS_BUILD -fPIC -DNDEBUG -O2"
    local LDFLAGS="-L$OPENSSL_INSTALL_DIR/lib -L$PYTHON_LIB -shared -lpython3.13"

    mkdir -p "$DYNLOAD_DIR"

    # 构建 _ssl.so
    echo "  编译 _ssl.so..."
    "$CC_BIN" $CFLAGS \
        -c "$CPYTHON_SRC/Modules/_ssl.c" \
        -o "$BUILD_DIR/_ssl.o" 2>&1

    "$CC_BIN" $LDFLAGS \
        "$BUILD_DIR/_ssl.o" \
        -lssl -lcrypto \
        -o "$DYNLOAD_DIR/_ssl.cpython-313-aarch64-linux-android.so" 2>&1

    echo "  _ssl.so 构建成功"

    # 构建 _hashlib.so
    echo "  编译 _hashlib.so..."
    "$CC_BIN" $CFLAGS \
        -c "$CPYTHON_SRC/Modules/_hashopenssl.c" \
        -o "$BUILD_DIR/_hashlib.o" 2>&1

    "$CC_BIN" $LDFLAGS \
        "$BUILD_DIR/_hashlib.o" \
        -lcrypto \
        -o "$DYNLOAD_DIR/_hashlib.cpython-313-aarch64-linux-android.so" 2>&1

    echo "  _hashlib.so 构建成功"

    ls -la "$DYNLOAD_DIR/"*ssl* "$DYNLOAD_DIR/"*hashlib* 2>/dev/null
}

# ============================================================
# 4. 打包输出
# ============================================================
package_output() {
    echo "[4/4] 输出文件:"
    echo "  _ssl.so:    $INSTALL_DIR/lib/python${PYTHON_MAJOR_MINOR}/lib-dynload/_ssl.cpython-313-aarch64-linux-android.so"
    echo "  _hashlib.so: $INSTALL_DIR/lib/python${PYTHON_MAJOR_MINOR}/lib-dynload/_hashlib.cpython-313-aarch64-linux-android.so"
    echo ""
    echo "  OpenSSL 静态库已链接进 .so，无需额外部署 libssl/libcrypto"
    echo ""
    echo "===== 完成 ====="
}

# ============================================================
# 主流程
# ============================================================
main() {
    mkdir -p "$BUILD_DIR"
    download_ndk
    build_openssl
    build_ssl_module
    package_output
}

main
