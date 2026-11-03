package com.lge.media;

/** Stage-1 stub of LG's video merge/trim engine (native). Every call reports an error (-1). */
public class MediaEditor {
    public interface OnInfoListener { void onInfo(MediaEditor editor, int what, int extra); }
    private static MediaEditor sInstance;
    private MediaEditor() {}
    public static synchronized MediaEditor getInstance() {
        if (sInstance == null) sInstance = new MediaEditor();
        return sInstance;
    }
    public void setOnInfoListener(OnInfoListener l) {}
    public int setInputFile(String path) { return -1; }
    public int mergeAdd(String path) { return -1; }
    public int mergeSave(String path) { return -1; }
    public int getWidth(int idx) { return 0; }
    public int getHeight(int idx) { return 0; }
    public int reset() { return -1; }
    public void release() {}
}
