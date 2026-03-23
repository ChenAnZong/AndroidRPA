package image;

import androidx.annotation.NonNull;

class ImageRegion {
    int x;
    int y;
    int h;
    int w;

    @NonNull
    @Override
    public String toString() {
        return "ImageRegion{" +
                "x=" + x +
                ", y=" + y +
                ", h=" + h +
                ", w=" + w +
                '}';
    }

    public ImageRegion(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }
}