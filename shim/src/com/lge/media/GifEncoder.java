package com.lge.media;

import android.graphics.Bitmap;

/** Stage-1 stub: LG used native/Hexagon encoders. Always reports failure. */
public class GifEncoder {
    public GifEncoder() {}
    public void setDelay(int ms) {}
    public boolean start(String path) { return false; }
    public boolean addFrame(Bitmap b) { return false; }
    public boolean addFrameDouble(Bitmap a, Bitmap b) { return false; }
    public boolean addFrameTriple(Bitmap a, Bitmap b, Bitmap c) { return false; }
    public boolean finish() { return false; }
}
