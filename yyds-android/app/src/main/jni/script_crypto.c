/**
 * script_crypto.c — 白盒密钥派生 (Native层)
 *
 * 设计思想:
 *   密钥材料不以任何形式"存储"在二进制中，而是通过多轮数学运算
 *   从看似标准的密码学常量中"计算"得出。攻击者即使拿到 stripped .so：
 *   1. 需要 IDA Pro + ARM反汇编能力（比jadx反编译Dalvik难10x+）
 *   2. 需要完整模拟 16轮混合运算才能得到密钥
 *   3. 常量伪装为SHA-256/Blowfish标准值，增加误导
 *   4. 反调试检测使动态分析受阻
 *   5. volatile + memset 防止编译器优化掉栈清理
 *
 * 安全层次:
 *   Layer 1: Native代码（stripped .so, 需ARM逆向能力）
 *   Layer 2: 计算型密钥（非存储型，无法pattern-match搜索）
 *   Layer 3: 常量伪装（混淆真实用途）
 *   Layer 4: 反调试（TracerPid检测 + 时间检测）
 *   Layer 5: 栈清理（防止内存dump提取）
 */

#include <jni.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#include <android/log.h>

#define LOG_TAG "ScriptCrypto"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* ================================================================
 * 反调试检测
 * ================================================================ */

/**
 * 检查 /proc/self/status 中的 TracerPid
 * 非零表示进程被ptrace附加（调试器/Frida/strace）
 */
static int check_tracer_pid(void) {
    FILE *f = fopen("/proc/self/status", "r");
    if (!f) return 0;
    char line[256];
    int traced = 0;
    while (fgets(line, sizeof(line), f)) {
        if (strncmp(line, "TracerPid:", 10) == 0) {
            long pid = strtol(line + 10, NULL, 10);
            if (pid != 0) traced = 1;
            break;
        }
    }
    fclose(f);
    return traced;
}

/**
 * 时间侧信道检测
 * 如果两次调用之间间隔异常长，可能有断点/单步调试
 */
static volatile long g_last_ts = 0;

static int check_timing_anomaly(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    long now_ms = ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
    if (g_last_ts != 0) {
        long delta = now_ms - g_last_ts;
        /* 正常调用间隔不应超过5秒; 调试器单步可能导致大延迟 */
        if (delta > 5000) {
            g_last_ts = now_ms;
            return 1;
        }
    }
    g_last_ts = now_ms;
    return 0;
}

/* ================================================================
 * 白盒密钥计算核心
 *
 * 密钥材料通过以下步骤计算:
 * 1. 初始状态 = SHA-256 初始哈希值 H0..H7 (公开常量)
 * 2. 混合常量 = 应用专属秘密值 (伪装为密码学常量)
 * 3. 16轮 splitmix 风格混合运算
 * 4. 输出 32 字节密钥材料
 *
 * 安全性分析:
 * - 初始状态是公开的 → 不泄露秘密
 * - 混合常量是秘密 → 嵌入在native代码中
 * - 混合运算是单向的 → 知道输出无法反推常量
 * - 16轮混合 → 完全扩散，每个输出bit依赖所有输入bit
 * ================================================================ */

/* 右旋转 */
#define ROTR32(x, n) (((x) >> (n)) | ((x) << (32 - (n))))

/**
 * SHA-256 初始哈希值 (H0..H7)
 * 来源: 前8个素数的平方根的小数部分
 * 这些是公开常量，用作初始状态种子
 */
static const uint32_t SHA256_H[8] = {
    0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A,
    0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19
};

/**
 * 应用专属混合常量 (SECRET)
 *
 * 这些值看起来像标准密码学常量（与SHA-256 K表、Blowfish P-array
 * 等格式相似），但实际上是本应用独有的秘密值。
 *
 * 逆向工程师看到这些值时，很可能误认为是标准常量而忽略它们。
 * 即使识别出不是标准常量，也无法仅从这些值推导出最终密钥——
 * 还需要完整模拟下方的16轮混合运算。
 *
 * 选择原则:
 * - 高熵（每个值的汉明重量约16）
 * - 不是任何已知标准的精确值
 * - 视觉上与密码学常量相似
 */
static const uint32_t WB_MIXER[8] = {
    0x7E2B84F1, 0x3A9C5D06, 0xC8F13E72, 0x54D0A9B3,
    0x196BE8C4, 0xAF742D50, 0x63E9B187, 0xDB05F6CA
};

/**
 * 轮常量 — 每轮使用不同的扰动值
 * 同样伪装为密码学常量风格
 */
static const uint32_t ROUND_K[16] = {
    0xB7E15163, 0x8AED2A6A, 0x5DB8C971, 0x30846878,
    0x0350077F, 0xD61BA686, 0xA8E7458D, 0x7BB2E494,
    0x4E7E839B, 0x214A22A2, 0xF415C1A9, 0xC6E160B0,
    0x99ACFFB7, 0x6C789EBE, 0x3F443DC5, 0x120FDCCC
};

/**
 * 单元素混合函数 (splitmix风格)
 * 基于 MurmurHash3 的 fmix32 变体
 */
static inline uint32_t fmix(uint32_t h) {
    h ^= h >> 16;
    h *= 0x85EBCA6B;
    h ^= h >> 13;
    h *= 0xC2B2AE35;
    h ^= h >> 16;
    return h;
}

/**
 * 四分之一轮函数 (inspired by ChaCha20 QR)
 * 在4个元素之间建立依赖关系
 */
static inline void quarter_round(uint32_t *a, uint32_t *b, uint32_t *c, uint32_t *d) {
    *a += *b; *d ^= *a; *d = ROTR32(*d, 16);
    *c += *d; *b ^= *c; *b = ROTR32(*b, 12);
    *a += *b; *d ^= *a; *d = ROTR32(*d, 8);
    *c += *d; *b ^= *c; *b = ROTR32(*b, 7);
}

/**
 * 核心: 计算32字节密钥材料
 *
 * @param out       输出缓冲区 (32字节)
 * @param env_seed  环境种子 (来自JNI调用者, 增加变化性)
 */
static void wb_compute_key_material(uint8_t out[32], uint32_t env_seed) {
    /* Phase 1: 从SHA-256 H值初始化状态 */
    uint32_t state[8];
    for (int i = 0; i < 8; i++) {
        state[i] = SHA256_H[i];
    }

    /* Phase 2: 注入应用专属混合常量 */
    for (int i = 0; i < 8; i++) {
        state[i] ^= WB_MIXER[i];
    }

    /* Phase 3: 注入环境种子 (增加绑定性) */
    state[0] ^= env_seed;
    state[4] ^= ROTR32(env_seed, 13);

    /* Phase 4: 16轮混合运算 — 完全扩散 */
    for (int round = 0; round < 16; round++) {
        /* 每元素独立混合 + 轮常量注入 */
        for (int i = 0; i < 8; i++) {
            state[i] = fmix(state[i] + ROUND_K[round]);
        }

        /* 列混合: 建立元素间依赖 (ChaCha20风格) */
        quarter_round(&state[0], &state[1], &state[2], &state[3]);
        quarter_round(&state[4], &state[5], &state[6], &state[7]);

        /* 对角线混合: 进一步扩散 */
        quarter_round(&state[0], &state[5], &state[2], &state[7]);
        quarter_round(&state[1], &state[4], &state[3], &state[6]);
    }

    /* Phase 5: 序列化输出 (小端序) */
    for (int i = 0; i < 8; i++) {
        uint32_t v = state[i];
        out[i * 4 + 0] = (uint8_t)(v & 0xFF);
        out[i * 4 + 1] = (uint8_t)((v >> 8) & 0xFF);
        out[i * 4 + 2] = (uint8_t)((v >> 16) & 0xFF);
        out[i * 4 + 3] = (uint8_t)((v >> 24) & 0xFF);
    }

    /* Phase 6: 清理栈上的中间状态 (volatile防止编译器优化掉) */
    volatile uint32_t *vp = state;
    for (int i = 0; i < 8; i++) vp[i] = 0;
}

/* ================================================================
 * JNI 导出函数
 * ================================================================ */

/**
 * 导出给 Kotlin ScriptEncryptor 调用
 *
 * @param env_seed  环境种子 (由Kotlin层从包名等信息计算)
 * @return          32字节密钥材料 (byte[32])
 */
JNIEXPORT jbyteArray JNICALL
Java_pyengine_ScriptEncryptor_nativeDeriveKeyMaterial(
        JNIEnv *env, jobject thiz, jint env_seed) {

    /* 反调试检查 #1: TracerPid */
    if (check_tracer_pid()) {
        /* 被调试时返回错误的密钥材料 (全零 → 解密必定失败) */
        LOGE("Security check failed [T]");
        jbyteArray result = (*env)->NewByteArray(env, 32);
        return result;
    }

    /* 反调试检查 #2: 时间侧信道 (仅警告，不阻断) */
    if (check_timing_anomaly()) {
        LOGE("Security check warning [D]");
        /* 不阻断，但可以在此记录或上报 */
    }

    /* 计算密钥材料 */
    uint8_t key_material[32];
    wb_compute_key_material(key_material, (uint32_t)env_seed);

    /* 创建Java字节数组并复制结果 */
    jbyteArray result = (*env)->NewByteArray(env, 32);
    if (result == NULL) return NULL;
    (*env)->SetByteArrayRegion(env, result, 0, 32, (jbyte*)key_material);

    /* 清理栈上的密钥材料 */
    volatile uint8_t *p = key_material;
    for (int i = 0; i < 32; i++) p[i] = 0;

    return result;
}
