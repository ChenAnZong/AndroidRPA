project(ai C CXX)

cmake_minimum_required(VERSION 3.4.1)

# ============================================================
# Android 15+ 要求所有 ELF LOAD 段按 16KB 页面对齐
# 对所有 target（shared lib + executable）统一添加链接器标志
# ============================================================
add_link_options(-Wl,-z,max-page-size=16384)

# assume built-in pthreads on MacOS

if (${IS_APP_RELEASE} EQUAL 1)
     message(WARNING "NDK 编译为发行模式")
     add_definitions(-DIS_APP_RELEASE=${IS_APP_RELEASE})
endif()

set(ncnn_DIR ${CMAKE_SOURCE_DIR}/ncnn/${ANDROID_ABI}/lib/cmake/ncnn)
set(OpenCV_DIR ${CMAKE_SOURCE_DIR}/opencv-mobile-4.5.4-android/sdk/native/jni)

find_package(ncnn REQUIRED)
find_package(OpenCV REQUIRED core imgproc)
find_package(xunwind REQUIRED CONFIG)


file(GLOB SOURCES1 "${CMAKE_CURRENT_SOURCE_DIR}/*.cpp")
# 排除keeper.cpp，它是独立可执行文件，不应编入libai.so
list(REMOVE_ITEM SOURCES1 "${CMAKE_CURRENT_SOURCE_DIR}/keeper.cpp")
# 排除cpython_bridge.cpp，它是独立共享库，依赖libpython3.13
list(REMOVE_ITEM SOURCES1 "${CMAKE_CURRENT_SOURCE_DIR}/cpython_bridge.cpp")
#file(GLOB SOURCES2 "libevdev/libevdev/*.c")
#file(GLOB SOURCES33 "libevdev/include/*")



add_library(ai
        SHARED
        ${SOURCES1}
        main.cpp
        custom.cpp
        clipper.cpp
        common.cpp
        ncnn_paddle_ocr.cpp
        ncnn_yolov8.cpp
        image.cpp
        rootauto/main.cpp
        yyds/yyds.cpp
        )


target_link_libraries(ai
        ncnn
        xunwind::xunwind
        ${OpenCV_LIBS}
        jnigraphics
)

# yyds.keep native守护进程（独立可执行文件，不依赖zygote）
add_executable(yyds_keep keeper.cpp)
target_link_libraries(yyds_keep log)
# 命名为 libyyds_keep.so 以便被Gradle打包进APK的lib目录
set_target_properties(yyds_keep PROPERTIES
        OUTPUT_NAME "yyds_keep"
)
# 复制到共享库输出目录，Gradle会自动将其打包到APK
add_custom_command(TARGET yyds_keep POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E copy
        $<TARGET_FILE:yyds_keep>
        ${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libyyds_keep.so
)

# ============================================================
# 脚本加密 — 白盒密钥派生 (独立共享库，仅依赖log)
# 不编入libai.so，因为yyds.py进程不加载libai
# ============================================================
add_library(script_crypto SHARED script_crypto.c)
target_link_libraries(script_crypto log)

# ============================================================
# CPython 嵌入式桥接库（替代 Chaquopy）
# 依赖交叉编译的 libpython3.13.so + Python.h 头文件
# 头文件和库由 CPython-android/scripts/build-cpython.sh 生成
# ============================================================
set(CPYTHON_DIR "${CMAKE_SOURCE_DIR}/../../../../../CPython-android")

# CPython 头文件路径
set(CPYTHON_INCLUDE_DIR "${CPYTHON_DIR}/include")
# CPython 预编译库路径（按ABI分目录）
set(CPYTHON_LIB_DIR "${CPYTHON_DIR}/libs/${ANDROID_ABI}")

# 检查 CPython 头文件是否存在
if(EXISTS "${CPYTHON_INCLUDE_DIR}/Python.h")
    message(STATUS "CPython headers found: ${CPYTHON_INCLUDE_DIR}")

    # 声明预编译的 libpython3.13 IMPORTED 库
    add_library(python3.13 SHARED IMPORTED)
    set_target_properties(python3.13 PROPERTIES
            IMPORTED_LOCATION "${CPYTHON_LIB_DIR}/libpython3.13.so"
    )

    # 构建 cpython_bridge 共享库
    add_library(cpython_bridge SHARED cpython_bridge.cpp)
    target_include_directories(cpython_bridge PRIVATE ${CPYTHON_INCLUDE_DIR})
    target_link_libraries(cpython_bridge python3.13 log)

    # 复制 libpython3.13.so 到输出目录，使其被打包进 APK
    add_custom_command(TARGET cpython_bridge POST_BUILD
            COMMAND ${CMAKE_COMMAND} -E copy_if_different
            "${CPYTHON_LIB_DIR}/libpython3.13.so"
            ${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libpython3.13.so
    )
else()
    message(WARNING "CPython headers not found at ${CPYTHON_INCLUDE_DIR}/Python.h")
    message(WARNING "跳过 cpython_bridge 构建。请先运行 CPython-android/build.bat 编译 CPython。")
endif()