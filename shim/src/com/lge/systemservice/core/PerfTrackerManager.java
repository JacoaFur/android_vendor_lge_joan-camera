package com.lge.systemservice.core;
public class PerfTrackerManager {
    public interface IEventSender {
        void notifyActivityBackKey(String a);
        void notifyPerfEvent(int type, String a, String b, String c);
        void notifyPerfEvent(int type, String a, String b, String c, String d);
        void notifyPerfOneTimeEvent(String a, String b, String c);
        void notifyPerfState(String a, String b, String c, String d, String e);
        void notifyRotationCompleted(long t);
        void notifyRotationStarted();
        void notifyScreenTurnedOn();
        void notifyScreenWakeUp(String a);
        void notifyWindowsDrawn(String a, int b);
    }
    private static final IEventSender NULL_SENDER = new IEventSender() {
        public void notifyActivityBackKey(String a) {}
        public void notifyPerfEvent(int type, String a, String b, String c) {}
        public void notifyPerfEvent(int type, String a, String b, String c, String d) {}
        public void notifyPerfOneTimeEvent(String a, String b, String c) {}
        public void notifyPerfState(String a, String b, String c, String d, String e) {}
        public void notifyRotationCompleted(long t) {}
        public void notifyRotationStarted() {}
        public void notifyScreenTurnedOn() {}
        public void notifyScreenWakeUp(String a) {}
        public void notifyWindowsDrawn(String a, int b) {}
    };
    private PerfTrackerManager() {}
    public static synchronized IEventSender getEventSenderInstance() { return NULL_SENDER; }
}
