package image;

import android.graphics.ColorSpace;
import android.nfc.FormatException;

public class ColorHelper {
    public static String rgbDecString2HexString(String value) throws FormatException {
        String[] v = value.trim().split("[,，]");
        StringBuilder builder = new StringBuilder();
        builder.append("#");
        if (v.length != 3) {
            throw new FormatException("RGB 字符串格式化失败:" + value);
        } else {
            for (String i:v) {
                int iv = Integer.parseInt(i.trim());
                if (iv > 255) {
                    iv = 255;
                }
                if (iv < 0) {
                    iv = 0;
                }
                String hex = Integer.toHexString(iv);
                if (hex.length() == 1) {
                    hex = "0" + hex;
                }
                builder.append(hex);
            }
        }
        return builder.toString();
    }
}
