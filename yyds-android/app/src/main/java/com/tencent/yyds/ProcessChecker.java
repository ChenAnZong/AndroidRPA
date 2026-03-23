package com.tencent.yyds;

import android.os.FileUtils;
import android.os.StatFs;

import java.io.File;

public class ProcessChecker {
    public static boolean isProcessRunning(String processName) {
        File procDirectory = new File("/proc");
        File[] procFiles = procDirectory.listFiles();

        if (procFiles != null) {
            for (File file : procFiles) {

                if (file.isDirectory() && file.getName().matches("[0-9]+")) {
                    File cmdlineFile = new File(file, "cmdline");
                    if (cmdlineFile.exists()) {
                        String cmdline = readCmdlineFromFile(cmdlineFile);
                        if (cmdline.contains(processName)) {
                            return true; // Process found
                        }
                    }
                }
            }
        }

        return false; // Process not found
    }

    public static boolean isProcessRunningSameUser(String processName) {
        File procDirectory = new File("/proc");
        File[] procFiles = procDirectory.listFiles();

        if (procFiles != null) {
            for (File file : procFiles) {
                if (file.isDirectory() && file.getName().matches("[0-9]+")) {
                    File cmdlineFile = new File(file, "cmdline");
                    if (cmdlineFile.exists()) {
                        String cmdline = readCmdlineFromFile(cmdlineFile);
                        if (cmdline.contains(processName)) {
                            return true; // Process found
                        }
                    }
                }
            }
        }

        return false; // Process not found
    }


    private static String readCmdlineFromFile(File file) {
        try {
            java.util.Scanner scanner = new java.util.Scanner(file).useDelimiter("\\z");
            return scanner.hasNext() ? scanner.next() : "";
        } catch (java.io.FileNotFoundException ex) {
            System.err.println("Error reading file: " + ex.getMessage());
        }

        return "";
    }
}
