package com.lge.appbox.updateproxy;
import android.content.Context;
public class RequestForm {
    public static class RequestFormBuilder {
        public RequestFormBuilder(Context c) {}
        public RequestFormBuilder setRequestModel(String model) { return this; }
        public RequestForm build() { return new RequestForm(); }
    }
}
