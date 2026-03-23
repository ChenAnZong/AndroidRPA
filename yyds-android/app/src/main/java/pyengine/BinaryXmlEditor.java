package pyengine;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import uiautomator.ExtSystem;

/**
 * 二进制资源文件编辑器
 * 用于修改 resources.arsc 中的应用名和 AndroidManifest.xml 中的包名
 *
 * 支持:
 * - UTF-8 和 UTF-16 编码的字符串池
 * - 任意长度的字符串替换（完整重建字符串池）
 * - resources.arsc 全局字符串池编辑
 * - AndroidManifest.xml (AXML) 字符串池编辑
 */
public class BinaryXmlEditor {

    private static final String TAG = "BinaryXmlEditor";

    // Chunk types
    private static final int RES_STRING_POOL_TYPE = 0x0001;
    private static final int RES_XML_TYPE = 0x0003;
    private static final int RES_TABLE_TYPE = 0x0002;

    // String pool flags
    private static final int UTF8_FLAG = 0x00000100;
    private static final int SORTED_FLAG = 0x00000001;

    /**
     * 修改 resources.arsc 中的应用名
     * 查找全局字符串池中的 oldAppName，替换为 newAppName
     */
    public static byte[] editResourcesAppName(byte[] data, String oldAppName, String newAppName) {
        if (oldAppName.equals(newAppName)) return data;

        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int fileType = buf.getShort(0) & 0xFFFF;
        int fileHeaderSize = buf.getShort(2) & 0xFFFF;

        if (fileType != RES_TABLE_TYPE) {
            ExtSystem.printDebugLog(TAG + ": Not a resources.arsc file (type=0x" + Integer.toHexString(fileType) + ")");
            return data;
        }

        // 全局字符串池紧跟在 ResTable_header 之后
        int spOffset = fileHeaderSize; // 通常是 12
        return replaceStringInChunk(data, spOffset, oldAppName, newAppName);
    }

    /**
     * 修改 AndroidManifest.xml 中的包名
     * 替换字符串池中所有包含 oldPackage 前缀的字符串
     */
    public static byte[] editManifestPackageName(byte[] data, String oldPackage, String newPackage) {
        if (oldPackage.equals(newPackage)) return data;

        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int fileType = buf.getShort(0) & 0xFFFF;
        int fileHeaderSize = buf.getShort(2) & 0xFFFF;

        if (fileType != RES_XML_TYPE) {
            ExtSystem.printDebugLog(TAG + ": Not a binary XML file (type=0x" + Integer.toHexString(fileType) + ")");
            return data;
        }

        // 字符串池紧跟在文件头之后
        int spOffset = fileHeaderSize; // 通常是 8
        return replaceStringPrefixInChunk(data, spOffset, oldPackage, newPackage);
    }

    /**
     * 在指定偏移处的字符串池中查找并替换精确匹配的字符串
     */
    private static byte[] replaceStringInChunk(byte[] fileData, int spOffset, String oldStr, String newStr) {
        ByteBuffer buf = ByteBuffer.wrap(fileData).order(ByteOrder.LITTLE_ENDIAN);
        int spType = buf.getShort(spOffset) & 0xFFFF;
        if (spType != RES_STRING_POOL_TYPE) {
            ExtSystem.printDebugLog(TAG + ": Expected string pool at offset " + spOffset +
                    " but found type=0x" + Integer.toHexString(spType));
            return fileData;
        }
        int spChunkSize = buf.getInt(spOffset + 4);

        // 解析字符串池
        StringPool pool = StringPool.parse(fileData, spOffset);
        if (pool == null) return fileData;

        // 查找并替换：先精确匹配，再尝试包含匹配
        boolean found = false;
        for (int i = 0; i < pool.strings.size(); i++) {
            if (pool.strings.get(i).equals(oldStr)) {
                pool.strings.set(i, newStr);
                found = true;
                ExtSystem.printDebugLog(TAG + ": 精确替换字符串 [" + i + "] '" + oldStr + "' -> '" + newStr + "'");
                break;
            }
        }
        // 精确匹配失败时，尝试包含匹配（处理 "Yyds.Auto脚本引擎" 等变体）
        if (!found) {
            for (int i = 0; i < pool.strings.size(); i++) {
                String s = pool.strings.get(i);
                if (s.contains(oldStr) && s.length() < 50) { // 长度限制避免误替换代码字符串
                    String replaced = s.replace(oldStr, newStr);
                    pool.strings.set(i, replaced);
                    found = true;
                    ExtSystem.printDebugLog(TAG + ": 模糊替换字符串 [" + i + "] '" + s + "' -> '" + replaced + "'");
                }
            }
        }

        if (!found) {
            ExtSystem.printDebugLog(TAG + ": 未找到字符串 '" + oldStr + "' (共 " + pool.strings.size() + " 个字符串)");
            return fileData;
        }

        return rebuildFileWithNewPool(fileData, spOffset, spChunkSize, pool);
    }

    /**
     * 在指定偏移处的字符串池中查找并替换所有以 oldPrefix 开头的字符串
     */
    private static byte[] replaceStringPrefixInChunk(byte[] fileData, int spOffset, String oldPrefix, String newPrefix) {
        ByteBuffer buf = ByteBuffer.wrap(fileData).order(ByteOrder.LITTLE_ENDIAN);
        int spType = buf.getShort(spOffset) & 0xFFFF;
        if (spType != RES_STRING_POOL_TYPE) return fileData;
        int spChunkSize = buf.getInt(spOffset + 4);

        StringPool pool = StringPool.parse(fileData, spOffset);
        if (pool == null) return fileData;

        boolean anyReplaced = false;
        for (int i = 0; i < pool.strings.size(); i++) {
            String s = pool.strings.get(i);
            if (s.equals(oldPrefix)) {
                pool.strings.set(i, newPrefix);
                anyReplaced = true;
                ExtSystem.printDebugLog(TAG + ": 替换 [" + i + "] '" + s + "' -> '" + newPrefix + "'");
            } else if (s.startsWith(oldPrefix + ".")) {
                String replaced = newPrefix + s.substring(oldPrefix.length());
                pool.strings.set(i, replaced);
                anyReplaced = true;
                ExtSystem.printDebugLog(TAG + ": 替换前缀 [" + i + "] '" + s + "' -> '" + replaced + "'");
            }
        }

        if (!anyReplaced) {
            ExtSystem.printDebugLog(TAG + ": 未找到包含前缀 '" + oldPrefix + "' 的字符串");
            return fileData;
        }

        return rebuildFileWithNewPool(fileData, spOffset, spChunkSize, pool);
    }

    /**
     * 用新的字符串池重建文件
     */
    private static byte[] rebuildFileWithNewPool(byte[] fileData, int spOffset, int oldSpSize, StringPool pool) {
        int fileHeaderSize = spOffset; // 字符串池之前的就是文件头
        byte[] newPoolBytes = pool.rebuild();
        int sizeDiff = newPoolBytes.length - oldSpSize;

        // 字符串池之后的数据
        int restOffset = spOffset + oldSpSize;
        int restSize = fileData.length - restOffset;
        int newFileSize = fileData.length + sizeDiff;

        byte[] result = new byte[newFileSize];

        // 1. 复制文件头（不变）
        System.arraycopy(fileData, 0, result, 0, fileHeaderSize);

        // 2. 写入新字符串池
        System.arraycopy(newPoolBytes, 0, result, fileHeaderSize, newPoolBytes.length);

        // 3. 复制剩余数据（不变）
        System.arraycopy(fileData, restOffset, result, fileHeaderSize + newPoolBytes.length, restSize);

        // 4. 更新文件头中的总大小
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).putInt(4, newFileSize);

        ExtSystem.printDebugLog(TAG + ": 文件重建完成, 原始=" + fileData.length +
                " 新=" + newFileSize + " 差异=" + sizeDiff);
        return result;
    }

    // ====================================================================
    // 字符串池解析与重建
    // ====================================================================

    static class StringPool {
        List<String> strings = new ArrayList<>();
        boolean isUtf8;

        // 样式数据原样保留
        int styleCount;
        int[] styleOffsets;
        byte[] styleData;

        /**
         * 从文件数据中解析字符串池
         *
         * @param fileData 完整文件数据
         * @param chunkOffset 字符串池 chunk 在文件中的起始偏移
         */
        static StringPool parse(byte[] fileData, int chunkOffset) {
            try {
                StringPool pool = new StringPool();
                ByteBuffer buf = ByteBuffer.wrap(fileData).order(ByteOrder.LITTLE_ENDIAN);

                int headerSize = buf.getShort(chunkOffset + 2) & 0xFFFF; // 通常 28
                int chunkSize = buf.getInt(chunkOffset + 4);
                int stringCount = buf.getInt(chunkOffset + 8);
                int styleCount = buf.getInt(chunkOffset + 12);
                int flags = buf.getInt(chunkOffset + 16);
                int stringsStart = buf.getInt(chunkOffset + 20);
                int stylesStart = buf.getInt(chunkOffset + 24);

                pool.isUtf8 = (flags & UTF8_FLAG) != 0;
                pool.styleCount = styleCount;

                // 读取字符串偏移表
                int[] stringOffsets = new int[stringCount];
                int offsetTableStart = chunkOffset + headerSize;
                for (int i = 0; i < stringCount; i++) {
                    stringOffsets[i] = buf.getInt(offsetTableStart + i * 4);
                }

                // 读取样式偏移表
                if (styleCount > 0) {
                    pool.styleOffsets = new int[styleCount];
                    int styleOffsetTableStart = offsetTableStart + stringCount * 4;
                    for (int i = 0; i < styleCount; i++) {
                        pool.styleOffsets[i] = buf.getInt(styleOffsetTableStart + i * 4);
                    }
                }

                // 字符串数据绝对起始位置
                int strDataAbsStart = chunkOffset + stringsStart;

                // 解析每个字符串
                for (int i = 0; i < stringCount; i++) {
                    int strAbsOffset = strDataAbsStart + stringOffsets[i];
                    if (pool.isUtf8) {
                        pool.strings.add(parseUtf8String(fileData, strAbsOffset));
                    } else {
                        pool.strings.add(parseUtf16String(fileData, strAbsOffset));
                    }
                }

                // 保留样式数据（原样复制）
                if (styleCount > 0 && stylesStart > 0) {
                    int styleDataAbsStart = chunkOffset + stylesStart;
                    int styleDataEnd = chunkOffset + chunkSize;
                    if (styleDataAbsStart < styleDataEnd && styleDataAbsStart < fileData.length) {
                        int len = Math.min(styleDataEnd - styleDataAbsStart, fileData.length - styleDataAbsStart);
                        pool.styleData = Arrays.copyOfRange(fileData, styleDataAbsStart, styleDataAbsStart + len);
                    }
                }

                return pool;
            } catch (Exception e) {
                ExtSystem.printDebugError(TAG + ": 解析字符串池失败", e);
                return null;
            }
        }

        /**
         * 重建字符串池为完整的 chunk 字节
         */
        byte[] rebuild() {
            int headerSize = 28;
            int stringCount = strings.size();

            // 编码所有字符串
            ByteArrayOutputStream strDataStream = new ByteArrayOutputStream();
            int[] newOffsets = new int[stringCount];

            for (int i = 0; i < stringCount; i++) {
                newOffsets[i] = strDataStream.size();
                if (isUtf8) {
                    writeUtf8String(strDataStream, strings.get(i));
                } else {
                    writeUtf16String(strDataStream, strings.get(i));
                }
            }
            byte[] strData = strDataStream.toByteArray();

            // 计算各区域大小
            int offsetsSize = stringCount * 4;
            int styleOffsetsSize = styleCount * 4;
            int stringsStart = headerSize + offsetsSize + styleOffsetsSize;

            // 字符串数据后4字节对齐
            int strDataPaddedLen = strData.length;
            if (strDataPaddedLen % 4 != 0) {
                strDataPaddedLen += 4 - (strDataPaddedLen % 4);
            }

            int stylesStart = 0;
            int styleDataLen = 0;
            if (styleCount > 0 && styleData != null && styleData.length > 0) {
                stylesStart = stringsStart + strDataPaddedLen;
                styleDataLen = styleData.length;
            }

            int chunkSize;
            if (stylesStart > 0) {
                chunkSize = stylesStart + styleDataLen;
            } else {
                chunkSize = stringsStart + strDataPaddedLen;
            }
            // chunk 总大小4字节对齐
            if (chunkSize % 4 != 0) {
                chunkSize += 4 - (chunkSize % 4);
            }

            byte[] result = new byte[chunkSize];
            ByteBuffer out = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);

            // 写入 chunk 头
            out.putShort(0, (short) RES_STRING_POOL_TYPE);
            out.putShort(2, (short) headerSize);
            out.putInt(4, chunkSize);
            out.putInt(8, stringCount);
            out.putInt(12, styleCount);
            out.putInt(16, isUtf8 ? UTF8_FLAG : 0);
            out.putInt(20, stringsStart);
            out.putInt(24, stylesStart);

            // 写入字符串偏移表
            for (int i = 0; i < stringCount; i++) {
                out.putInt(headerSize + i * 4, newOffsets[i]);
            }

            // 写入样式偏移表（不变）
            if (styleCount > 0 && styleOffsets != null) {
                for (int i = 0; i < styleCount; i++) {
                    out.putInt(headerSize + offsetsSize + i * 4, styleOffsets[i]);
                }
            }

            // 写入字符串数据
            System.arraycopy(strData, 0, result, stringsStart, strData.length);

            // 写入样式数据
            if (stylesStart > 0 && styleData != null) {
                System.arraycopy(styleData, 0, result, stylesStart, styleData.length);
            }

            return result;
        }

        // ============================================================
        // UTF-8 字符串编解码
        // ============================================================

        private static String parseUtf8String(byte[] data, int offset) {
            int pos = offset;

            // UTF-16 字符数（1或2字节）
            int charCount;
            if ((data[pos] & 0x80) != 0) {
                charCount = ((data[pos] & 0x7F) << 8) | (data[pos + 1] & 0xFF);
                pos += 2;
            } else {
                charCount = data[pos] & 0xFF;
                pos += 1;
            }

            // UTF-8 字节数（1或2字节）
            int byteCount;
            if ((data[pos] & 0x80) != 0) {
                byteCount = ((data[pos] & 0x7F) << 8) | (data[pos + 1] & 0xFF);
                pos += 2;
            } else {
                byteCount = data[pos] & 0xFF;
                pos += 1;
            }

            if (pos + byteCount > data.length) {
                return "";
            }
            return new String(data, pos, byteCount, StandardCharsets.UTF_8);
        }

        private static void writeUtf8String(ByteArrayOutputStream out, String s) {
            byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
            int charCount = s.length();
            int byteCount = utf8.length;

            // 写入 UTF-16 字符数
            if (charCount >= 0x80) {
                out.write((charCount >> 8) | 0x80);
                out.write(charCount & 0xFF);
            } else {
                out.write(charCount);
            }

            // 写入 UTF-8 字节数
            if (byteCount >= 0x80) {
                out.write((byteCount >> 8) | 0x80);
                out.write(byteCount & 0xFF);
            } else {
                out.write(byteCount);
            }

            // 写入 UTF-8 数据
            out.write(utf8, 0, utf8.length);

            // null 终止符
            out.write(0);
        }

        // ============================================================
        // UTF-16 字符串编解码
        // ============================================================

        private static String parseUtf16String(byte[] data, int offset) {
            ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            int charCount = buf.getShort(offset) & 0xFFFF;
            int dataOffset = offset + 2;

            if ((charCount & 0x8000) != 0) {
                // 4字节长度: high word (& 0x7FFF) << 16 | low word
                int high = charCount & 0x7FFF;
                int low = buf.getShort(offset + 2) & 0xFFFF;
                charCount = (high << 16) | low;
                dataOffset = offset + 4;
            }

            if (dataOffset + charCount * 2 > data.length) {
                return "";
            }

            char[] chars = new char[charCount];
            for (int i = 0; i < charCount; i++) {
                chars[i] = (char) (buf.getShort(dataOffset + i * 2) & 0xFFFF);
            }
            return new String(chars);
        }

        private static void writeUtf16String(ByteArrayOutputStream out, String s) {
            int charCount = s.length();

            // 写入字符数（Little-Endian uint16）
            if (charCount >= 0x8000) {
                // 4字节长度编码
                int high = (charCount >> 16) | 0x8000;
                out.write(high & 0xFF);
                out.write((high >> 8) & 0xFF);
                int low = charCount & 0xFFFF;
                out.write(low & 0xFF);
                out.write((low >> 8) & 0xFF);
            } else {
                out.write(charCount & 0xFF);
                out.write((charCount >> 8) & 0xFF);
            }

            // 写入 UTF-16LE 数据
            for (int i = 0; i < charCount; i++) {
                char c = s.charAt(i);
                out.write(c & 0xFF);
                out.write((c >> 8) & 0xFF);
            }

            // null 终止符（2字节）
            out.write(0);
            out.write(0);
        }
    }
}
