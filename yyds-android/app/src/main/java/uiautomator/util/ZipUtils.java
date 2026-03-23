package uiautomator.util;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import uiautomator.ExtSystem;

/**
 * @author baronzhang (baron[dot]zhanglei[at]gmail[dot]com)
 *         16/4/5
 */
public class ZipUtils {

    /**
     * 解压zip包
     *
     * @param zipFilePath zip文件路径
     * @param outputPath  输出路径
     * @throws IOException
     */
    public static void unZipFolder(String zipFilePath, String outputPath) throws IOException {

        ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(zipFilePath));
        ZipEntry zipEntry;

        String zipEntryName;

        while ((zipEntry = zipInputStream.getNextEntry()) != null) {
            zipEntryName = zipEntry.getName();
            if (zipEntry.isDirectory()) {
                zipEntryName = zipEntryName.substring(0, zipEntryName.length() - 1);
                File folder = new File(outputPath + File.separator + zipEntryName);
                folder.mkdirs();
            } else {
                File file = new File(outputPath + File.separator + zipEntryName);
                if (file.isDirectory()) continue;
                Log.d("createNewFile", file.getAbsolutePath());
                if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
                file.createNewFile();
                FileOutputStream fileOutputStream = new FileOutputStream(file);
                int len;
                byte[] buffer = new byte[1024];
                while ((len = zipInputStream.read(buffer)) != -1) {
                    fileOutputStream.write(buffer, 0, len);
                    fileOutputStream.flush();
                }
                fileOutputStream.close();
            }
        }

        zipInputStream.close();
    }

    /**
     * 解压zip包
     *
     * @param zipFilePath zip文件路径
     * @param outputPath  输出路径
     * @throws IOException
     */
    public static void unZipSoFile(String zipFilePath, String outputPath) throws IOException {

        ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(zipFilePath));
        ZipEntry zipEntry;

        String zipEntryName;
        if (new File(outputPath).exists()) {
            new File(outputPath).mkdirs();
        }
        while ((zipEntry = zipInputStream.getNextEntry()) != null) {
            zipEntryName = zipEntry.getName();
            if (zipEntry.isDirectory()) {
                zipEntryName = zipEntryName.substring(0, zipEntryName.length() - 1);
                File folder = new File(outputPath + File.separator + zipEntryName);
                folder.mkdirs();
            } else {
                File file = new File(outputPath + File.separator + zipEntryName);
                if (!file.getAbsolutePath().endsWith(".so")) continue;
                Log.d("createNewFile", file.getAbsolutePath());
                if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
                file.createNewFile();
                FileOutputStream fileOutputStream = new FileOutputStream(file);
                int len;
                byte[] buffer = new byte[1024];
                while ((len = zipInputStream.read(buffer)) != -1) {
                    fileOutputStream.write(buffer, 0, len);
                    fileOutputStream.flush();
                }
                fileOutputStream.close();
            }
        }

        zipInputStream.close();
    }
    public static void unZipAssetsFile(String zipFilePath, String outputPath) throws IOException {

        ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(zipFilePath));
        ZipEntry zipEntry;

        String zipEntryName;
        if (new File(outputPath).exists()) {
            new File(outputPath).mkdirs();
        }
        while ((zipEntry = zipInputStream.getNextEntry()) != null) {
            zipEntryName = zipEntry.getName();
            if (zipEntry.isDirectory()) {
                zipEntryName = zipEntryName.substring(0, zipEntryName.length() - 1);
                File folder = new File(outputPath + File.separator + zipEntryName);
                folder.mkdirs();
            } else {
                File file = new File(outputPath + File.separator + zipEntryName);
                if (file.getAbsolutePath().contains("assets")) {
                    ExtSystem.printInfo("Assets:::",file.getAbsolutePath());
                }

                if (!file.getAbsolutePath().contains("/assets/")) continue;
                // Log.d("createAssetsFile", file.getAbsolutePath());
                if (!file.getParentFile().exists()) file.getParentFile().mkdirs();
                file.createNewFile();
                FileOutputStream fileOutputStream = new FileOutputStream(file);
                int len;
                byte[] buffer = new byte[1024];
                while ((len = zipInputStream.read(buffer)) != -1) {
                    fileOutputStream.write(buffer, 0, len);
                    fileOutputStream.flush();
                }
                fileOutputStream.close();
            }
        }

        zipInputStream.close();
    }

    public static void zipFolder(String sourceFilePath, String zipFilePath) throws IOException {

        ZipOutputStream zipOutputStream = new ZipOutputStream(new FileOutputStream(zipFilePath));

        File file = new File(sourceFilePath);

        zipFiles(file.getParent() + File.separator, file.getName(), zipOutputStream);

        zipOutputStream.close();
    }

    private static void zipFiles(String folderPath, String filePath, ZipOutputStream zipOutputStream) throws IOException {

        if (zipOutputStream == null)
            return;

        File file = new File(folderPath + filePath);
        if (file.isFile()) {

            ZipEntry zipEntry = new ZipEntry(filePath);
            FileInputStream fileInputStream = new FileInputStream(file);
            zipOutputStream.putNextEntry(zipEntry);
            int len;
            byte[] buffer = new byte[1024];
            while ((len = fileInputStream.read(buffer)) != -1) {
                zipOutputStream.write(buffer, 0, len);
            }
            zipOutputStream.closeEntry();
        } else {
            String[] fileList = file.list();
            if (fileList.length <= 0) {
                ZipEntry zipEntry = new ZipEntry(filePath + File.separator);
                zipOutputStream.putNextEntry(zipEntry);
                zipOutputStream.closeEntry();
            }
            for (String aFileList : fileList) {
                zipFiles(folderPath, filePath + File.separator + aFileList, zipOutputStream);
            }
        }
    }

    public static InputStream upZip(String zipFilePath, String filePath) throws IOException {

        ZipFile zipFile = new ZipFile(zipFilePath);
        ZipEntry zipEntry = zipFile.getEntry(filePath);
        return zipFile.getInputStream(zipEntry);
    }

    /**
     * 获取zip包中的文件列表,默认包含folder和file
     *
     * @return zip包中的文件列表
     * @throws IOException
     */
    public static List<File> getFileList(String zipFilePath) throws IOException {
        return getFileList(zipFilePath, true, true);
    }

    /**
     * 获取zip包中的文件列表
     *
     * @param zipFilePath         zip包路径
     * @param isNeedContainFolder 返回的结果中是否需要包含文件夹(folder)
     * @param isNeedContainFile   返回的结果中是否需要包含文件(file)
     * @return zip包中的文件列表
     * @throws IOException
     */
    public static List<File> getFileList(String zipFilePath, boolean isNeedContainFolder, boolean isNeedContainFile) throws IOException {
        List<File> fileList = new ArrayList<>();
        ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(zipFilePath));
        ZipEntry zipEntry;
        String zipEntryName;
        while ((zipEntry = zipInputStream.getNextEntry()) != null) {
            zipEntryName = zipEntry.getName();
            if (zipEntry.isDirectory()) {

                zipEntryName = zipEntryName.substring(0, zipEntryName.length() - 1);
                File folder = new File(zipEntryName);
                if (isNeedContainFolder) {
                    fileList.add(folder);
                }
            } else {
                File file = new File(zipEntryName);
                if (isNeedContainFile) {
                    fileList.add(file);
                }
            }
        }
        zipInputStream.close();
        return fileList;
    }
}