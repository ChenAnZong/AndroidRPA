package pyengine;

import java.io.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.RSAKeyGenParameterSpec;
import java.util.*;
import java.util.zip.*;

import uiautomator.ExtSystem;

/**
 * APK V1 (JAR) 签名器
 *
 * 实现纯Java的V1签名流程:
 * 1. 生成RSA密钥对和自签名X.509证书
 * 2. 计算所有ZIP条目的SHA-256摘要 → MANIFEST.MF
 * 3. 计算MANIFEST.MF各节的摘要 → CERT.SF
 * 4. 用私钥签名CERT.SF → CERT.RSA (PKCS#7格式)
 * 5. 将META-INF/MANIFEST.MF, CERT.SF, CERT.RSA写入APK
 */
public class ApkV1Signer {

    private static final String TAG = "ApkV1Signer";
    private static final String KEY_STORE_PATH = "/data/local/tmp/yyds_pack_sign.dat";

    // 缓存密钥对和证书
    private static KeyPair cachedKeyPair;
    private static byte[] cachedCertDer;

    /**
     * 对APK进行V1签名
     *
     * @param inputPath  未签名APK路径
     * @param outputPath 签名后APK输出路径
     */
    public static void signApk(String inputPath, String outputPath) throws Exception {
        ExtSystem.printDebugLog(TAG + ": 开始签名 " + inputPath);

        // 1. 确保有密钥
        ensureKeyPair();

        // 2. 流式计算摘要（不把整个APK存到内存）
        ZipFile inputZip = new ZipFile(inputPath);
        List<String> entryNames = new ArrayList<>();
        Enumeration<? extends ZipEntry> zipEntries = inputZip.entries();
        while (zipEntries.hasMoreElements()) {
            ZipEntry entry = zipEntries.nextElement();
            if (entry.isDirectory()) continue;
            if (entry.getName().startsWith("META-INF/")) continue;
            entryNames.add(entry.getName());
        }

        // 3. 生成 MANIFEST.MF（流式：每次只读一个条目计算SHA-256）
        StringBuilder manifestSb = new StringBuilder();
        manifestSb.append("Manifest-Version: 1.0\r\n");
        manifestSb.append("Created-By: Yyds.Auto APK Packager\r\n");
        manifestSb.append("\r\n");

        for (String name : entryNames) {
            ZipEntry entry = inputZip.getEntry(name);
            if (entry == null) continue;
            byte[] digest = sha256Stream(inputZip.getInputStream(entry));
            String b64 = base64Encode(digest);
            manifestSb.append("Name: ").append(name).append("\r\n");
            manifestSb.append("SHA-256-Digest: ").append(b64).append("\r\n");
            manifestSb.append("\r\n");
        }
        inputZip.close();
        byte[] manifestBytes = manifestSb.toString().getBytes(StandardCharsets.UTF_8);

        // 4. 生成 CERT.SF
        byte[] manifestDigest = sha256(manifestBytes);
        StringBuilder certSfSb = new StringBuilder();
        certSfSb.append("Signature-Version: 1.0\r\n");
        certSfSb.append("Created-By: Yyds.Auto APK Packager\r\n");
        certSfSb.append("SHA-256-Digest-Manifest: ").append(base64Encode(manifestDigest)).append("\r\n");
        certSfSb.append("\r\n");

        // 为MANIFEST.MF中每个条目节生成摘要
        String manifestStr = manifestSb.toString();
        int idx = manifestStr.indexOf("\r\n\r\n"); // 跳过主属性
        if (idx >= 0) {
            String entriesSection = manifestStr.substring(idx + 4);
            String[] sections = entriesSection.split("\r\n\r\n");
            for (String section : sections) {
                if (section.trim().isEmpty()) continue;
                String sectionWithTerminator = section + "\r\n\r\n";
                byte[] sectionDigest = sha256(sectionWithTerminator.getBytes(StandardCharsets.UTF_8));
                int nameStart = section.indexOf("Name: ");
                if (nameStart < 0) continue;
                int nameEnd = section.indexOf("\r\n", nameStart);
                String nameLine = section.substring(nameStart, nameEnd);
                certSfSb.append(nameLine).append("\r\n");
                certSfSb.append("SHA-256-Digest: ").append(base64Encode(sectionDigest)).append("\r\n");
                certSfSb.append("\r\n");
            }
        }
        byte[] certSfBytes = certSfSb.toString().getBytes(StandardCharsets.UTF_8);

        // 5. 生成 CERT.RSA (PKCS#7)
        byte[] certRsa = createPkcs7Signature(certSfBytes, cachedKeyPair.getPrivate(), cachedCertDer);

        // 6. 写入签名后的APK（逐条目流式复制）
        ZipFile inputZip2 = new ZipFile(inputPath);
        ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(outputPath)));

        // 先写签名文件
        writeZipEntry(zos, "META-INF/MANIFEST.MF", manifestBytes);
        writeZipEntry(zos, "META-INF/CERT.SF", certSfBytes);
        writeZipEntry(zos, "META-INF/CERT.RSA", certRsa);

        // 流式复制其他条目
        zipEntries = inputZip2.entries();
        while (zipEntries.hasMoreElements()) {
            ZipEntry entry = zipEntries.nextElement();
            if (entry.isDirectory()) continue;
            if (entry.getName().startsWith("META-INF/")) continue;

            ZipEntry newEntry = new ZipEntry(entry.getName());
            // .so 文件强制 DEFLATED，避免 STORED 模式下 ZIP 对齐丢失导致安装失败
            boolean forceDeflate = entry.getName().endsWith(".so");
            if (entry.getMethod() == ZipEntry.STORED && !forceDeflate) {
                newEntry.setMethod(ZipEntry.STORED);
                newEntry.setSize(entry.getSize());
                newEntry.setCompressedSize(entry.getSize());
                newEntry.setCrc(entry.getCrc());
                zos.putNextEntry(newEntry);
                copyStream(inputZip2.getInputStream(entry), zos);
            } else {
                newEntry.setMethod(ZipEntry.DEFLATED);
                zos.putNextEntry(newEntry);
                copyStream(inputZip2.getInputStream(entry), zos);
            }
            zos.closeEntry();
        }

        zos.close();
        inputZip2.close();
        ExtSystem.printDebugLog(TAG + ": 签名完成 " + outputPath);
    }

    // ====================================================================
    // 密钥管理
    // ====================================================================

    private static synchronized void ensureKeyPair() throws Exception {
        if (cachedKeyPair != null && cachedCertDer != null) return;

        File keyFile = new File(KEY_STORE_PATH);
        if (keyFile.exists()) {
            try {
                loadKeyData(keyFile);
                if (cachedKeyPair != null && cachedCertDer != null) return;
            } catch (Exception e) {
                ExtSystem.printDebugLog(TAG + ": 加载已有密钥失败，重新生成");
            }
        }

        // 生成新密钥对
        ExtSystem.printDebugLog(TAG + ": 生成RSA密钥对...");
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(new RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4));
        cachedKeyPair = kpg.generateKeyPair();

        // 生成自签名证书
        cachedCertDer = generateSelfSignedCert(cachedKeyPair);

        // 保存
        saveKeyData(keyFile);
        ExtSystem.printDebugLog(TAG + ": 密钥已生成并保存");
    }

    private static void saveKeyData(File file) throws Exception {
        file.getParentFile().mkdirs();
        DataOutputStream dos = new DataOutputStream(new FileOutputStream(file));
        byte[] privKey = cachedKeyPair.getPrivate().getEncoded();
        byte[] pubKey = cachedKeyPair.getPublic().getEncoded();
        dos.writeInt(privKey.length);
        dos.write(privKey);
        dos.writeInt(pubKey.length);
        dos.write(pubKey);
        dos.writeInt(cachedCertDer.length);
        dos.write(cachedCertDer);
        dos.close();
    }

    private static void loadKeyData(File file) throws Exception {
        DataInputStream dis = new DataInputStream(new FileInputStream(file));
        int privLen = dis.readInt();
        byte[] privBytes = new byte[privLen];
        dis.readFully(privBytes);
        int pubLen = dis.readInt();
        byte[] pubBytes = new byte[pubLen];
        dis.readFully(pubBytes);
        int certLen = dis.readInt();
        byte[] certBytes = new byte[certLen];
        dis.readFully(certBytes);
        dis.close();

        java.security.KeyFactory kf = java.security.KeyFactory.getInstance("RSA");
        PrivateKey priv = kf.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(privBytes));
        PublicKey pub = kf.generatePublic(new java.security.spec.X509EncodedKeySpec(pubBytes));
        cachedKeyPair = new KeyPair(pub, priv);
        cachedCertDer = certBytes;
    }

    // ====================================================================
    // 自签名X.509证书生成（纯DER编码）
    // ====================================================================

    private static byte[] generateSelfSignedCert(KeyPair keyPair) throws Exception {
        // TBS (To Be Signed) 证书内容
        byte[] version = derExplicit(0, derInteger(2)); // v3
        byte[] serialNumber = derInteger(new BigInteger(64, new SecureRandom()));
        byte[] signAlgo = derSequence(derOid(OID_SHA256_RSA), derNull());
        byte[] issuer = derSequence(derSet(derSequence(derOid(OID_CN), derUtf8String("YydsAuto"))));
        // 有效期10年（确保notAfter不超过2049年UTCTime上限）
        Date notBefore = new Date();
        Date notAfter = new Date(System.currentTimeMillis() + 10L * 365 * 24 * 3600 * 1000);
        byte[] validity = derSequence(
                encodeTime(notBefore),
                encodeTime(notAfter)
        );
        byte[] subject = issuer; // 自签名：subject == issuer
        byte[] pubKeyInfo = keyPair.getPublic().getEncoded(); // SubjectPublicKeyInfo (已经是DER)

        byte[] tbsCert = derSequence(version, serialNumber, signAlgo, issuer, validity, subject, pubKeyInfo);

        // 用私钥签名TBS
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(keyPair.getPrivate());
        sig.update(tbsCert);
        byte[] signature = sig.sign();

        // 完整证书 = SEQUENCE { tbsCert, signatureAlgorithm, signatureValue }
        return derSequence(tbsCert, signAlgo, derBitString(signature));
    }

    // ====================================================================
    // PKCS#7 签名块生成
    // ====================================================================

    private static byte[] createPkcs7Signature(byte[] data, PrivateKey privateKey, byte[] certDer) throws Exception {
        // 对数据签名
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(privateKey);
        sig.update(data);
        byte[] signatureBytes = sig.sign();

        // 解析证书中的issuer和serialNumber用于SignerInfo
        // 简单方法：从证书DER中提取
        byte[][] issuerAndSerial = extractIssuerAndSerial(certDer);
        byte[] issuerDer = issuerAndSerial[0];
        byte[] serialDer = issuerAndSerial[1];

        // 构建 SignerInfo
        // issuerDer 已包含SEQUENCE tag，与serialDer拼接形成issuerAndSerialNumber
        byte[] issuerAndSerialNumber = derConstructed(0x30, issuerDer, serialDer);
        byte[] signerInfo = derSequence(
                derInteger(1), // version
                issuerAndSerialNumber, // issuerAndSerialNumber
                derSequence(derOid(OID_SHA256), derNull()), // digestAlgorithm
                derSequence(derOid(OID_RSA), derNull()), // digestEncryptionAlgorithm
                derOctetString(signatureBytes) // encryptedDigest
        );

        // 构建 SignedData
        byte[] signedData = derSequence(
                derInteger(1), // version
                derSet(derSequence(derOid(OID_SHA256), derNull())), // digestAlgorithms
                derSequence(derOid(OID_PKCS7_DATA)), // contentInfo
                derImplicit(0, certDer), // certificates [0] IMPLICIT
                derSet(signerInfo) // signerInfos
        );

        // 构建 ContentInfo
        return derSequence(
                derOid(OID_PKCS7_SIGNED_DATA),
                derExplicit(0, signedData)
        );
    }

    /**
     * 从DER证书中提取 issuer 和 serialNumber
     */
    private static byte[][] extractIssuerAndSerial(byte[] certDer) {
        try {
            // Certificate = SEQUENCE { tbsCert, ... }
            // tbsCert = SEQUENCE { version[0], serialNumber, signAlgo, issuer, ... }
            int[] pos = {0};
            parseDerTag(certDer, pos); // outer SEQUENCE tag
            int outerLen = parseDerLength(certDer, pos);

            // tbsCert SEQUENCE
            parseDerTag(certDer, pos);
            int tbsLen = parseDerLength(certDer, pos);
            int tbsContentStart = pos[0];

            // version [0] EXPLICIT - optional, skip if present
            if ((certDer[pos[0]] & 0xFF) == 0xA0) {
                parseDerTag(certDer, pos);
                int vLen = parseDerLength(certDer, pos);
                pos[0] += vLen;
            }

            // serialNumber INTEGER
            int serialStart = pos[0];
            parseDerTag(certDer, pos);
            int serialBodyLen = parseDerLength(certDer, pos);
            int serialEnd = pos[0] + serialBodyLen;
            byte[] serialDer = Arrays.copyOfRange(certDer, serialStart, serialEnd);
            pos[0] = serialEnd;

            // signatureAlgorithm SEQUENCE - skip
            parseDerTag(certDer, pos);
            int algoLen = parseDerLength(certDer, pos);
            pos[0] += algoLen;

            // issuer SEQUENCE
            int issuerStart = pos[0];
            parseDerTag(certDer, pos);
            int issuerBodyLen = parseDerLength(certDer, pos);
            int issuerEnd = pos[0] + issuerBodyLen;
            byte[] issuerDer = Arrays.copyOfRange(certDer, issuerStart, issuerEnd);

            return new byte[][]{issuerDer, serialDer};
        } catch (Exception e) {
            ExtSystem.printDebugError(TAG + ": 提取issuer/serial失败", e);
            // 回退：使用简单的issuer和serial
            byte[] defaultIssuer = derSequence(derSet(derSequence(derOid(OID_CN), derUtf8String("YydsAuto"))));
            byte[] defaultSerial = derInteger(1);
            return new byte[][]{defaultIssuer, defaultSerial};
        }
    }

    // ====================================================================
    // DER 编码工具方法
    // ====================================================================

    // OID 字节常量
    private static final byte[] OID_SHA256 = {0x60, (byte) 0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01};
    private static final byte[] OID_SHA256_RSA = {0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B};
    private static final byte[] OID_RSA = {0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x01};
    private static final byte[] OID_PKCS7_SIGNED_DATA = {0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x07, 0x02};
    private static final byte[] OID_PKCS7_DATA = {0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x07, 0x01};
    private static final byte[] OID_CN = {0x55, 0x04, 0x03};

    private static byte[] derSequence(byte[]... items) {
        return derConstructed(0x30, items);
    }

    private static byte[] derSet(byte[]... items) {
        return derConstructed(0x31, items);
    }

    private static byte[] derConstructed(int tag, byte[]... items) {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        for (byte[] item : items) {
            content.write(item, 0, item.length);
        }
        byte[] contentBytes = content.toByteArray();
        return derTlv(tag, contentBytes);
    }

    private static byte[] derTlv(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        writeDerLength(out, content.length);
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    private static byte[] derInteger(long value) {
        return derInteger(BigInteger.valueOf(value));
    }

    private static byte[] derInteger(BigInteger value) {
        byte[] encoded = value.toByteArray();
        return derTlv(0x02, encoded);
    }

    private static byte[] derOid(byte[] oidBytes) {
        return derTlv(0x06, oidBytes);
    }

    private static byte[] derNull() {
        return new byte[]{0x05, 0x00};
    }

    private static byte[] derOctetString(byte[] data) {
        return derTlv(0x04, data);
    }

    private static byte[] derBitString(byte[] data) {
        // BIT STRING: 前导一个字节表示未使用位数（通常为0）
        byte[] content = new byte[data.length + 1];
        content[0] = 0; // unused bits
        System.arraycopy(data, 0, content, 1, data.length);
        return derTlv(0x03, content);
    }

    private static byte[] derUtf8String(String s) {
        return derTlv(0x0C, s.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] encodeTime(Date date) {
        java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        cal.setTime(date);
        int year = cal.get(java.util.Calendar.YEAR);
        if (year >= 2050) {
            return derGeneralizedTime(date);
        }
        return derUtcTime(date);
    }

    private static byte[] derUtcTime(Date date) {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyMMddHHmmss'Z'");
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return derTlv(0x17, sdf.format(date).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] derGeneralizedTime(Date date) {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyyMMddHHmmss'Z'");
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return derTlv(0x18, sdf.format(date).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] derExplicit(int tag, byte[] content) {
        return derTlv(0xA0 | tag, content);
    }

    private static byte[] derImplicit(int tag, byte[] content) {
        // IMPLICIT [tag] CONSTRUCTED: 覆盖原始tag但保留内容
        return derTlv(0xA0 | tag, content);
    }

    private static void writeDerLength(ByteArrayOutputStream out, int length) {
        if (length < 0x80) {
            out.write(length);
        } else if (length < 0x100) {
            out.write(0x81);
            out.write(length);
        } else if (length < 0x10000) {
            out.write(0x82);
            out.write((length >> 8) & 0xFF);
            out.write(length & 0xFF);
        } else if (length < 0x1000000) {
            out.write(0x83);
            out.write((length >> 16) & 0xFF);
            out.write((length >> 8) & 0xFF);
            out.write(length & 0xFF);
        } else {
            out.write(0x84);
            out.write((length >> 24) & 0xFF);
            out.write((length >> 16) & 0xFF);
            out.write((length >> 8) & 0xFF);
            out.write(length & 0xFF);
        }
    }

    // DER 解析辅助
    private static int parseDerTag(byte[] data, int[] pos) {
        int tag = data[pos[0]++] & 0xFF;
        return tag;
    }

    private static int parseDerLength(byte[] data, int[] pos) {
        int first = data[pos[0]++] & 0xFF;
        if (first < 0x80) return first;
        int numBytes = first & 0x7F;
        int length = 0;
        for (int i = 0; i < numBytes; i++) {
            length = (length << 8) | (data[pos[0]++] & 0xFF);
        }
        return length;
    }

    // ====================================================================
    // 工具方法
    // ====================================================================

    private static byte[] sha256(byte[] data) throws NoSuchAlgorithmException {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static byte[] sha256Stream(InputStream is) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) {
            md.update(buf, 0, n);
        }
        is.close();
        return md.digest();
    }

    private static void copyStream(InputStream is, OutputStream os) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) {
            os.write(buf, 0, n);
        }
        is.close();
    }

    private static String base64Encode(byte[] data) {
        return android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
    }

    private static byte[] readStream(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) {
            baos.write(buf, 0, n);
        }
        is.close();
        return baos.toByteArray();
    }

    /**
     * ZIP对齐（等同 Android SDK zipalign 工具）
     * STORED 条目对齐到 4 字节边界，.so 文件对齐到 4096 字节（页）边界。
     * V1签名基于文件内容摘要，对齐只修改ZIP结构（extra字段），不影响签名有效性。
     */
    public static void zipalignApk(String inputPath, String outputPath) throws Exception {
        ExtSystem.printDebugLog(TAG + ": 开始ZIP对齐 " + inputPath);

        ZipFile inputZip = new ZipFile(inputPath);
        CountingOutputStream cos = new CountingOutputStream(new FileOutputStream(outputPath));
        ZipOutputStream zos = new ZipOutputStream(cos);

        try {
            Enumeration<? extends ZipEntry> entries = inputZip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;

                ZipEntry newEntry = new ZipEntry(entry.getName());

                if (entry.getMethod() == ZipEntry.STORED) {
                    newEntry.setMethod(ZipEntry.STORED);
                    newEntry.setSize(entry.getSize());
                    newEntry.setCompressedSize(entry.getSize());
                    newEntry.setCrc(entry.getCrc());

                    // 计算对齐填充：普通文件4字节，.so文件4096字节（页对齐）
                    int alignment = entry.getName().endsWith(".so") ? 4096 : 4;
                    long headerStart = cos.getCount();
                    int nameLen = entry.getName().getBytes(StandardCharsets.UTF_8).length;
                    // ZIP local file header = 30 bytes + filename + extra
                    // 数据起始 = headerStart + 30 + nameLen + extraLen
                    // 需要: (headerStart + 30 + nameLen + extraLen) % alignment == 0
                    int padding = (int) ((alignment - ((headerStart + 30 + nameLen) % alignment)) % alignment);
                    if (padding > 0) {
                        newEntry.setExtra(new byte[padding]);
                    }
                } else {
                    newEntry.setMethod(ZipEntry.DEFLATED);
                }

                zos.putNextEntry(newEntry);
                copyStream(inputZip.getInputStream(entry), zos);
                zos.closeEntry();
            }
        } finally {
            zos.close();
            inputZip.close();
        }

        ExtSystem.printDebugLog(TAG + ": ZIP对齐完成 " + outputPath +
                " (" + new File(outputPath).length() + " bytes)");
    }

    // ====================================================================
    // 工具方法
    // ====================================================================

    /**
     * 记录写入字节数的 OutputStream 包装器，用于 zipalign 计算偏移
     */
    private static class CountingOutputStream extends FilterOutputStream {
        private long count = 0;

        CountingOutputStream(OutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }

        long getCount() {
            return count;
        }
    }

    private static void writeZipEntry(ZipOutputStream zos, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.DEFLATED);
        zos.putNextEntry(entry);
        zos.write(data);
        zos.closeEntry();
    }
}
