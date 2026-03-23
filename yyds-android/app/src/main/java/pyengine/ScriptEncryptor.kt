package pyengine

import android.util.Base64
import uiautomator.ExtSystem
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.util.zip.Deflater
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 脚本加密器 — 使用 AES-256-GCM 加密 Python 脚本文件
 *
 * 加密文件格式 (.pye):
 *   [4 bytes]  Magic: "YENC" (0x59 0x45 0x4E 0x43)
 *   [1 byte]   Version: 0x02
 *   [12 bytes] GCM IV/Nonce (每文件随机)
 *   [N bytes]  AES-GCM 密文 (末尾自带 16 字节认证标签)
 *
 * 密钥派生 (白盒方案 V2):
 *   keyMaterial = nativeDeriveKeyMaterial() ← Native C层白盒计算
 *   password    = keyMaterial + salt        ← 拼接随机盐
 *   aesKey      = PBKDF2(password, salt, 100000, 256-bit)
 *
 * 安全层次:
 *   L1. Native白盒密钥材料 — 密钥通过native C多轮混合运算计算，非存储型
 *   L2. 反调试检测 — TracerPid + 时间侧信道，阻止动态分析
 *   L3. Python源码混淆 — 加密前先zlib压缩+base64编码，双层防护
 *   L4. AES-256-GCM — 工业级认证加密，防篡改
 *   L5. 每文件随机IV + 每次打包随机salt
 *   L6. PBKDF2 100000轮 — 抗暴力破解
 *   L7. 运行时解密文件仅存于shell权限目录，进程退出后清理
 */
object ScriptEncryptor {

    private const val TAG = "ScriptEncryptor"

    // ---- 加密文件格式常量 ----
    private val MAGIC = byteArrayOf(0x59, 0x45, 0x4E, 0x43) // "YENC"
    private const val FORMAT_VERSION: Byte = 0x02
    private const val HEADER_SIZE = 5          // magic(4) + version(1)
    private const val GCM_IV_LENGTH = 12       // bytes
    private const val GCM_TAG_BITS = 128       // bits (16 bytes)
    private const val AES_KEY_BITS = 256       // bits (32 bytes)
    private const val PBKDF2_ITERATIONS = 100000
    private const val SALT_LENGTH = 16         // bytes

    /** 加密文件扩展名 */
    const val ENCRYPTED_EXT = "pye"

    // ---- Native白盒密钥 ----
    @Volatile private var nativeLoaded = false
    @Volatile private var nativeLoadAttempted = false

    /** JNI: Native层白盒密钥派生 (script_crypto.c) */
    private external fun nativeDeriveKeyMaterial(envSeed: Int): ByteArray

    private fun ensureNativeLoaded(): Boolean {
        if (!nativeLoadAttempted) {
            synchronized(this) {
                if (!nativeLoadAttempted) {
                    try {
                        System.loadLibrary("script_crypto")
                        nativeLoaded = true
                        ExtSystem.printDebugLog("$TAG: native白盒密钥库已加载")
                    } catch (e: UnsatisfiedLinkError) {
                        ExtSystem.printDebugLog("$TAG: native库未加载，使用Java回退: ${e.message}")
                    }
                    nativeLoadAttempted = true
                }
            }
        }
        return nativeLoaded
    }

    /**
     * 计算环境种子 — 传入native层参与白盒运算
     * 固定值确保加密/解密一致性，但增加native函数调用复杂度
     */
    private fun computeEnvSeed(): Int {
        val marker = "YYDS_SCRIPT_ENC_V2"
        var h = 0
        for (c in marker) h = h * 31 + c.code
        return h
    }

    // ==================== 公开 API ====================

    /**
     * 生成随机 16 字节盐值
     */
    fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_LENGTH)
        SecureRandom().nextBytes(salt)
        return salt
    }

    /** 盐值 → Base64 字符串 (存入 pack_config.json) */
    fun saltToBase64(salt: ByteArray): String =
        Base64.encodeToString(salt, Base64.NO_WRAP)

    /** Base64 字符串 → 盐值 */
    fun saltFromBase64(b64: String): ByteArray =
        Base64.decode(b64, Base64.NO_WRAP)

    /**
     * 加密整个项目目录
     *
     * - `.py` 文件 → 加密为 `.pye`
     * - 其他文件（.config, .jpg, .txt 等）→ 原样复制
     * - 跳过隐藏文件和 `__pycache__`
     *
     * @param projectDir 源项目目录
     * @param outputDir  加密输出目录（不能与 projectDir 相同）
     * @param salt       加密盐值
     * @return 加密的 .py 文件数量
     */
    fun encryptProject(projectDir: File, outputDir: File, salt: ByteArray): Int {
        require(projectDir.absolutePath != outputDir.absolutePath) { "输入输出目录不能相同" }
        if (!outputDir.exists()) outputDir.mkdirs()

        val key = deriveKey(salt)
        var count = 0

        projectDir.walkTopDown()
            .filter { it.isFile }
            .filter { !it.name.startsWith(".") }
            .filter { "__pycache__" !in it.absolutePath }
            .forEach { file ->
                val relativePath = file.relativeTo(projectDir).path
                if (file.extension == "py") {
                    // 混淆 + 加密 .py → .pye
                    val rawSource = file.readBytes()
                    val obfuscated = obfuscatePythonSource(rawSource)
                    val encPath = relativePath.removeSuffix(".py") + ".$ENCRYPTED_EXT"
                    val outFile = File(outputDir, encPath)
                    outFile.parentFile?.mkdirs()
                    outFile.writeBytes(encryptBytes(obfuscated, key))
                    count++
                } else {
                    // 原样复制非 .py 文件
                    val outFile = File(outputDir, relativePath)
                    outFile.parentFile?.mkdirs()
                    file.copyTo(outFile, overwrite = true)
                }
            }

        ExtSystem.printDebugLog("$TAG: encryptProject 完成, $count 个 .py 文件已加密")
        return count
    }

    /**
     * 就地解密项目目录中的 .pye 文件
     *
     * 每个 `.pye` 解密为同名 `.py`，解密成功后删除 `.pye` 原文件。
     *
     * @param projectDir 包含 .pye 文件的项目目录
     * @param salt       加密盐值
     * @return 成功解密的文件数量
     */
    fun decryptProjectInPlace(projectDir: File, salt: ByteArray): Int {
        val key = deriveKey(salt)
        var count = 0

        // 先收集再遍历，避免 walkTopDown 期间修改文件系统
        val pyeFiles = projectDir.walkTopDown()
            .filter { it.isFile && it.extension == ENCRYPTED_EXT }
            .toList()

        for (pyeFile in pyeFiles) {
            val decrypted = decryptBytes(pyeFile.readBytes(), key)
            if (decrypted != null) {
                val pyFile = File(pyeFile.absolutePath.removeSuffix(".$ENCRYPTED_EXT") + ".py")
                pyFile.writeBytes(decrypted)
                pyeFile.delete()
                count++
            } else {
                ExtSystem.printDebugLog("$TAG: 解密失败: ${pyeFile.name}")
            }
        }

        ExtSystem.printDebugLog("$TAG: decryptProjectInPlace 完成, $count 个文件已解密")
        return count
    }

    /**
     * 清理项目目录中的解密后 .py 文件和 __pycache__
     * 用于进程退出时安全清理
     */
    fun cleanupDecryptedFiles(projectDir: File) {
        if (!projectDir.exists()) return
        try {
            // 删除所有 .py 文件
            projectDir.walkTopDown()
                .filter { it.isFile && it.extension == "py" }
                .forEach { it.delete() }
            // 删除 __pycache__ 目录
            projectDir.walkTopDown()
                .filter { it.isDirectory && it.name == "__pycache__" }
                .sortedByDescending { it.absolutePath.length } // 深度优先
                .forEach { it.deleteRecursively() }

            ExtSystem.printDebugLog("$TAG: cleanupDecryptedFiles 完成")
        } catch (e: Exception) {
            ExtSystem.printDebugError("$TAG: 清理解密文件失败", e)
        }
    }

    /**
     * 检查字节数据是否为 YENC 加密格式
     */
    fun isEncryptedData(data: ByteArray): Boolean {
        if (data.size < HEADER_SIZE + GCM_IV_LENGTH + GCM_TAG_BITS / 8) return false
        return data[0] == MAGIC[0] && data[1] == MAGIC[1]
                && data[2] == MAGIC[2] && data[3] == MAGIC[3]
                && data[4] == FORMAT_VERSION
    }

    /**
     * 检查项目目录中是否存在 .pye 文件
     */
    fun hasEncryptedScripts(dir: File): Boolean =
        dir.walkTopDown().any { it.isFile && it.extension == ENCRYPTED_EXT }

    // ==================== 内部实现 ====================

    /**
     * Python源码混淆 — 加密前的预处理
     *
     * 将源码 zlib压缩 + base64编码，包裹在 exec(compile(...)) 中。
     * 即使攻击者突破AES加密，看到的也不是明文Python源码，而是:
     *   import zlib,base64;exec(compile(zlib.decompress(base64.b64decode(b'...')),'<enc>','exec'))
     *
     * 攻击者还需额外解码才能得到原始代码，增加了逆向工程成本。
     */
    private fun obfuscatePythonSource(source: ByteArray): ByteArray {
        if (source.isEmpty()) return source

        // Step 1: zlib 压缩 (与 Python zlib.compress 兼容)
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(source)
        deflater.finish()
        val compBuf = ByteArrayOutputStream(source.size)
        val tmpBuf = ByteArray(4096)
        while (!deflater.finished()) {
            val n = deflater.deflate(tmpBuf)
            compBuf.write(tmpBuf, 0, n)
        }
        deflater.end()

        // Step 2: Base64 编码 (NO_WRAP → 无换行)
        val b64 = Base64.encodeToString(compBuf.toByteArray(), Base64.NO_WRAP)

        // Step 3: 生成自解码 Python 包装器
        // 使用 compile() 保留正确的 exec 语义和异常栈
        val wrapper = "import zlib,base64;exec(compile(zlib.decompress(base64.b64decode(b'$b64')),'<enc>','exec'))\n"
        return wrapper.toByteArray(Charsets.UTF_8)
    }

    /**
     * 加密单个字节数组 → YENC 格式
     */
    private fun encryptBytes(plaintext: ByteArray, key: ByteArray): ByteArray {
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, iv)
        )
        val ciphertext = cipher.doFinal(plaintext) // 包含 GCM 认证标签

        val out = ByteArrayOutputStream(HEADER_SIZE + GCM_IV_LENGTH + ciphertext.size)
        out.write(MAGIC)
        out.write(FORMAT_VERSION.toInt())
        out.write(iv)
        out.write(ciphertext)
        return out.toByteArray()
    }

    /**
     * 解密 YENC 格式字节数组 → 明文
     * @return 明文字节，格式错误或解密失败返回 null
     */
    private fun decryptBytes(data: ByteArray, key: ByteArray): ByteArray? {
        return try {
            val minSize = HEADER_SIZE + GCM_IV_LENGTH + GCM_TAG_BITS / 8
            if (data.size < minSize) return null

            // 校验 magic + version
            for (i in MAGIC.indices) {
                if (data[i] != MAGIC[i]) return null
            }
            if (data[4] != FORMAT_VERSION) return null

            val iv = data.copyOfRange(HEADER_SIZE, HEADER_SIZE + GCM_IV_LENGTH)
            val ciphertext = data.copyOfRange(HEADER_SIZE + GCM_IV_LENGTH, data.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, iv)
            )
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            ExtSystem.printDebugError("$TAG: decryptBytes 失败", e)
            null
        }
    }

    /**
     * 从盐值派生 AES-256 密钥
     *
     * 流程: PBKDF2( password = keyMaterial+salt, salt, 100000, 256bit )
     * 密钥材料优先从native白盒获取，不可用时回退到Java混淆方案
     */
    internal fun deriveKey(salt: ByteArray): ByteArray {
        val km = getKeyMaterial()
        val password = ByteArray(km.size + salt.size)
        System.arraycopy(km, 0, password, 0, km.size)
        System.arraycopy(salt, 0, password, km.size, salt.size)

        // PBKDF2 要求 char[]，用 ISO-8859-1 保证字节 ↔ char 一对一映射
        val spec = PBEKeySpec(
            String(password, Charsets.ISO_8859_1).toCharArray(),
            salt,
            PBKDF2_ITERATIONS,
            AES_KEY_BITS
        )
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val derived = factory.generateSecret(spec).encoded

        // 清理中间密钥材料
        password.fill(0)
        km.fill(0)

        return derived
    }

    /**
     * 获取密钥材料 — 优先使用 Native 白盒方案
     *
     * 优先级:
     *   1. Native C 白盒计算 (script_crypto.c) — 密钥通过多轮数学运算生成
     *   2. Java XOR 混淆回退 — native库不可用时使用
     *
     * 注意: 两种方案产生**不同的**密钥材料！
     * 加密/解密必须使用相同方案。正式发布时确保native库可用。
     */
    private fun getKeyMaterial(): ByteArray {
        if (ensureNativeLoaded()) {
            try {
                val km = nativeDeriveKeyMaterial(computeEnvSeed())
                if (km.size == 32) return km
            } catch (e: Exception) {
                ExtSystem.printDebugError("$TAG: native密钥派生异常，回退Java", e)
            }
        }
        return getKeyMaterialFallback()
    }

    /**
     * Java层回退密钥材料 — 当native库不可用时使用
     *
     * 三段 XOR 混淆 + R8/ProGuard 字节码混淆
     * 安全性低于native方案，但保证功能可用
     */
    private fun getKeyMaterialFallback(): ByteArray {
        ExtSystem.printDebugLog("$TAG: 使用Java回退密钥材料")
        val s1 = intArrayOf(0xF9, 0x25, 0x41, 0xBE, 0xC8, 0x07, 0x92, 0x6C, 0xAB)
        val s2 = intArrayOf(0x8D, 0x71, 0xA4, 0x1A, 0xC9, 0x46, 0xFF, 0x34, 0xE2)
        val s3 = intArrayOf(0x0A, 0xD4, 0x4D, 0x91, 0x6F, 0xF3, 0x28, 0xAC, 0x17)
        val xk = intArrayOf(0x5A, 0xC3, 0x87)

        val result = ByteArray(s1.size + s2.size + s3.size)
        for (i in s1.indices) result[i] = (s1[i] xor xk[0]).toByte()
        for (i in s2.indices) result[s1.size + i] = (s2[i] xor xk[1]).toByte()
        for (i in s3.indices) result[s1.size + s2.size + i] = (s3[i] xor xk[2]).toByte()
        return result
    }
}
