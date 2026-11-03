package com.lge.app;

import android.app.Dialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;

/** LG's mini-window helper (not an Activity). Backed by a Dialog. */
public class MiniActivity {
    private final Dialog mDialog;
    private final Context mContext;
    public MiniActivity(Context context) { mContext = context; mDialog = new Dialog(context); }
    public void show() { mDialog.show(); }
    public void hide() { mDialog.hide(); }
    public void dismiss() { mDialog.dismiss(); }
    public View findViewById(int id) { return mDialog.findViewById(id); }
    public LayoutInflater getLayoutInflater() { return LayoutInflater.from(mContext); }
    public void setContentView(int layoutResId) { mDialog.setContentView(layoutResId); }
    public void setFixOrientation(boolean fix) {}
    public void setWindowType(int type) {
        try { mDialog.getWindow().setType(type); } catch (Throwable ignored) {}
    }
}
