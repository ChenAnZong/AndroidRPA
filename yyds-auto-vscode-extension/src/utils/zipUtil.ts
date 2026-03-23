/**
 * ZIP 压缩工具 — 移植自 ZipUtility.java
 * 使用 archiver 库实现文件/目录压缩
 */

import * as fs from 'fs';
import * as path from 'path';
import archiver from 'archiver';

/**
 * 将文件列表压缩为 ZIP 文件
 * @param filePaths 要压缩的文件/目录路径列表
 * @param destZipFile 目标 ZIP 文件路径
 */
export function zipFiles(filePaths: string[], destZipFile: string): Promise<void> {
    return new Promise((resolve, reject) => {
        const output = fs.createWriteStream(destZipFile);
        const archive = archiver('zip', { zlib: { level: 6 } });

        output.on('close', () => {
            resolve();
        });

        archive.on('error', (err) => {
            reject(err);
        });

        archive.pipe(output);

        for (const filePath of filePaths) {
            const stat = fs.statSync(filePath);
            const name = path.basename(filePath);

            if (stat.isDirectory()) {
                archive.directory(filePath, name);
            } else {
                archive.file(filePath, { name });
            }
        }

        archive.finalize();
    });
}
