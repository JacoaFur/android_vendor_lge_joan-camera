package com.lge.media;

import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import com.lge.os.ShimLogger;

/**
 * Stage-1+ shim for com.lge.media.CamcorderProfileEx.
 *
 * Implements the exact 8-field colon-separated format required by
 * SettingIntegrationManual.{makeSupportedList, getFrameRateList, makeBitratePreference}:
 *   previewSize:videoSize:fpsList:br_1_2fps:br_24_30fps:br_50_60fps:br_100_120fps:br_240fps
 */
public class CamcorderProfileEx {
    private static final String TAG = "CamcorderProfileEx";
    static { ShimLogger.init(); }

    public static final int QUALITY_LOW = 0, QUALITY_HIGH = 1, QUALITY_QCIF = 2, QUALITY_CIF = 3,
            QUALITY_480P = 4, QUALITY_720P = 5, QUALITY_1080P = 6, QUALITY_QVGA = 7, QUALITY_2160P = 8,
            QUALITY_4kUHD = 8, QUALITY_WVGA = 9, QUALITY_VGA = 10, QUALITY_WQVGA = 11, QUALITY_FWVGA = 12,
            QUALITY_4kDCI = 13, QUALITY_HEVC720P = 14, QUALITY_HEVC1080P = 15, QUALITY_HEVC4kUHD = 16,
            QUALITY_HEVC4kDCI = 17, QUALITY_LIST_START = 0, QUALITY_LIST_END = 17;
    public static final int QUALITY_TIME_LAPSE_LOW = 1000, QUALITY_TIME_LAPSE_HIGH = 1001,
            QUALITY_TIME_LAPSE_QCIF = 1002, QUALITY_TIME_LAPSE_CIF = 1003, QUALITY_TIME_LAPSE_480P = 1004,
            QUALITY_TIME_LAPSE_720P = 1005, QUALITY_TIME_LAPSE_1080P = 1006, QUALITY_TIME_LAPSE_QVGA = 1007,
            QUALITY_TIME_LAPSE_2160P = 1008, QUALITY_TIME_LAPSE_FWVGA = 1009, QUALITY_TIME_LAPSE_WVGA = 1010,
            QUALITY_TIME_LAPSE_VGA = 1011, QUALITY_TIME_LAPSE_WQVGA = 1012,
            QUALITY_TIME_LAPSE_LIST_START = 1000, QUALITY_TIME_LAPSE_LIST_END = 1012;
    public static final int QUALITY_HIGH_SPEED_LOW = 2000, QUALITY_HIGH_SPEED_HIGH = 2001,
            QUALITY_HIGH_SPEED_480P = 2002, QUALITY_HIGH_SPEED_720P = 2003, QUALITY_HFR720P = 2003,
            QUALITY_HIGH_SPEED_1080P = 2004, QUALITY_HIGH_SPEED_LIST_START = 2000, QUALITY_HIGH_SPEED_LIST_END = 2004;
    public static final int QUALITY_LGE_1080P_60 = 10012, QUALITY_LGE_1080P_120 = 10013, QUALITY_LGE_1080P_240 = 10014,
            QUALITY_LGE_720P_60 = 10015, QUALITY_LGE_720P_120 = 10016, QUALITY_LGE_720P_240 = 10017,
            QUALITY_LGE_2160P_60 = 10018, QUALITY_LGE_HFR_LIST_START = 10012, QUALITY_LGE_HFR_LIST_END = 10017;

    public int duration, quality, fileFormat, videoCodec, videoBitRate, videoFrameRate, videoFrameWidth,
            videoFrameHeight, audioCodec, audioBitRate, audioSampleRate, audioChannels;

    public CamcorderProfileEx(int duration, int quality, int fileFormat, int videoCodec, int videoBitRate,
                              int videoFrameRate, int videoFrameWidth, int videoFrameHeight, int audioCodec,
                              int audioBitRate, int audioSampleRate, int audioChannels) {
        this.duration = duration; this.quality = quality; this.fileFormat = fileFormat;
        this.videoCodec = videoCodec; this.videoBitRate = videoBitRate; this.videoFrameRate = videoFrameRate;
        this.videoFrameWidth = videoFrameWidth; this.videoFrameHeight = videoFrameHeight;
        this.audioCodec = audioCodec; this.audioBitRate = audioBitRate;
        this.audioSampleRate = audioSampleRate; this.audioChannels = audioChannels;
    }

    private static boolean aosp(int q) {
        return (q >= 0 && q <= 8) || (q >= 1000 && q <= 1008) || (q >= 2000 && q <= 2004);
    }

    private static boolean inLists(int q) {
        return (q >= QUALITY_LIST_START && q <= QUALITY_LIST_END)
                || (q >= QUALITY_TIME_LAPSE_LIST_START && q <= QUALITY_TIME_LAPSE_LIST_END)
                || (q >= QUALITY_HIGH_SPEED_LIST_START && q <= QUALITY_HIGH_SPEED_LIST_END)
                || (q >= QUALITY_LGE_HFR_LIST_START && q <= 10018);
    }

    public static CamcorderProfileEx get(int quality) { return get(0, quality); }

    public static CamcorderProfileEx get(int cameraId, int quality) {
        int safeCamId = (cameraId < 0 || cameraId > 2) ? 0 : cameraId;
        if (!inLists(quality)) {
            ShimLogger.logW(TAG, "get(cam=" + cameraId + ", q=" + quality + ") unsupported quality, falling back to QUALITY_HIGH");
            quality = QUALITY_HIGH;
        }
        try {
            if (aosp(quality)) {
                CamcorderProfile p = null;
                if (CamcorderProfile.hasProfile(safeCamId, quality)) {
                    p = CamcorderProfile.get(safeCamId, quality);
                } else if (CamcorderProfile.hasProfile(0, quality)) {
                    p = CamcorderProfile.get(0, quality);
                }
                if (p != null) {
                    return new CamcorderProfileEx(p.duration, quality, p.fileFormat, p.videoCodec, p.videoBitRate,
                            p.videoFrameRate, p.videoFrameWidth, p.videoFrameHeight, p.audioCodec, p.audioBitRate,
                            p.audioSampleRate, p.audioChannels);
                }
            }
            CamcorderProfile base = CamcorderProfile.get(0, CamcorderProfile.hasProfile(0, QUALITY_1080P) ? QUALITY_1080P : QUALITY_HIGH);
            int w = base.videoFrameWidth, h = base.videoFrameHeight, fps = base.videoFrameRate, br = base.videoBitRate;
            int vcodec = base.videoCodec;
            switch (quality) {
                case QUALITY_WVGA: case QUALITY_TIME_LAPSE_WVGA: w = 800; h = 480; br = 4000000; break;
                case QUALITY_VGA: case QUALITY_TIME_LAPSE_VGA: w = 640; h = 480; br = 3000000; break;
                case QUALITY_WQVGA: case QUALITY_TIME_LAPSE_WQVGA: w = 400; h = 240; br = 1500000; break;
                case QUALITY_FWVGA: case QUALITY_TIME_LAPSE_FWVGA: w = 854; h = 480; br = 4000000; break;
                case QUALITY_4kDCI: w = 4096; h = 2160; br = 48000000; break;
                case QUALITY_HEVC720P: w = 1280; h = 720; br = 10000000; vcodec = MediaRecorder.VideoEncoder.HEVC; break;
                case QUALITY_HEVC1080P: w = 1920; h = 1080; br = 17000000; vcodec = MediaRecorder.VideoEncoder.HEVC; break;
                case QUALITY_HEVC4kUHD: w = 3840; h = 2160; br = 36000000; vcodec = MediaRecorder.VideoEncoder.HEVC; break;
                case QUALITY_HEVC4kDCI: w = 4096; h = 2160; br = 36000000; vcodec = MediaRecorder.VideoEncoder.HEVC; break;
                case QUALITY_LGE_1080P_60: w = 1920; h = 1080; fps = 60; br = 24000000; break;
                case QUALITY_LGE_1080P_120: w = 1920; h = 1080; fps = 120; br = 36000000; break;
                case QUALITY_LGE_1080P_240: w = 1920; h = 1080; fps = 240; br = 48000000; break;
                case QUALITY_LGE_720P_60: w = 1280; h = 720; fps = 60; br = 16000000; break;
                case QUALITY_LGE_720P_120: w = 1280; h = 720; fps = 120; br = 24000000; break;
                case QUALITY_LGE_720P_240: w = 1280; h = 720; fps = 240; br = 36000000; break;
                case QUALITY_LGE_2160P_60: w = 3840; h = 2160; fps = 60; br = 64000000; break;
                default: break;
            }
            return new CamcorderProfileEx(base.duration, quality, base.fileFormat, vcodec, br,
                    fps, w, h, base.audioCodec, base.audioBitRate, base.audioSampleRate, base.audioChannels);
        } catch (Throwable t) {
            ShimLogger.logE(TAG, "get(cam=" + cameraId + ", q=" + quality + ") failed", t);
            return null;
        }
    }

    public static boolean hasProfile(int quality) { return hasProfile(0, quality); }

    public static boolean hasProfile(int cameraId, int quality) {
        int safeCamId = (cameraId < 0 || cameraId > 2) ? 0 : cameraId;
        if (!aosp(quality)) {
            boolean supported = (quality == QUALITY_VGA || quality == QUALITY_WVGA || quality == QUALITY_HEVC720P
                    || quality == QUALITY_HEVC1080P || quality == QUALITY_HEVC4kUHD
                    || quality == QUALITY_LGE_1080P_60 || quality == QUALITY_LGE_720P_120);
            ShimLogger.logD(TAG, "hasProfile(cam=" + cameraId + ", lgQuality=" + quality + ") -> " + supported);
            return supported;
        }
        try {
            boolean res = CamcorderProfile.hasProfile(safeCamId, quality);
            if (!res && safeCamId != 0) res = CamcorderProfile.hasProfile(0, quality);
            return res;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Decoded from SettingIntegrationManual.{makeSupportedList, getFrameRateList, makeBitratePreference}:
     * Format is 8 colon-separated fields:
     *   [0] previewSize
     *   [1] videoSize (must match keys in mVideoSizeEntriesMap)
     *   [2] comma-separated FPS list
     *   [3] 1,2 fps bitrates (High,Medium,Low)
     *   [4] 24,30 fps bitrates (High,Medium,Low)
     *   [5] 50,60 fps bitrates (High,Medium,Low)
     *   [6] 100,120 fps bitrates (High,Medium,Low)
     *   [7] 240 fps bitrates (High,Medium,Low)
     * Or "NotSupport" if the (quality, aspectRatio) combination is not supported.
     */
    public static String getManualSupportedList(int cameraId, int quality, float ratio) {
        String out = "NotSupport";
        boolean is16by9 = Math.abs(ratio - (16.0f / 9.0f)) < 0.05f;
        boolean is21by9 = Math.abs(ratio - (21.0f / 9.0f)) < 0.05f;
        boolean is18by9 = Math.abs(ratio - 2.0f) < 0.05f || Math.abs(ratio - 2.1f) < 0.05f;

        if (quality == QUALITY_2160P) {
            if (is16by9) {
                out = "1920x1080:3840x2160:1,2,24,30"
                    + ":6400000,4800000,3200000"
                    + ":64000000,48000000,36000000"
                    + ":64000000,48000000,36000000"
                    + ":64000000,48000000,36000000"
                    + ":64000000,48000000,36000000";
            }
        } else if (quality == QUALITY_1080P) {
            if (is16by9) {
                out = "1920x1080:1920x1080:1,2,24,30,60,120"
                    + ":2400000,1700000,1200000"
                    + ":24000000,17000000,12000000"
                    + ":36000000,24000000,17000000"
                    + ":48000000,36000000,24000000"
                    + ":48000000,36000000,24000000";
            } else if (is21by9) {
                out = "2560x1080:2560x1080:1,2,24,30,60"
                    + ":2400000,1700000,1200000"
                    + ":24000000,17000000,12000000"
                    + ":36000000,24000000,17000000"
                    + ":48000000,36000000,24000000"
                    + ":48000000,36000000,24000000";
            } else if (is18by9) {
                out = "2160x1080:2160x1080:1,2,24,30,60"
                    + ":2400000,1700000,1200000"
                    + ":24000000,17000000,12000000"
                    + ":36000000,24000000,17000000"
                    + ":48000000,36000000,24000000"
                    + ":48000000,36000000,24000000";
            }
        } else if (quality == QUALITY_720P) {
            if (is16by9) {
                out = "1280x720:1280x720:1,2,24,30,60,120"
                    + ":1200000,800000,500000"
                    + ":12000000,8000000,5000000"
                    + ":17000000,12000000,8000000"
                    + ":36000000,24000000,17000000"
                    + ":48000000,36000000,24000000";
            } else if (is21by9) {
                out = "1680x720:1680x720:1,2,24,30,60"
                    + ":1200000,800000,500000"
                    + ":12000000,8000000,5000000"
                    + ":17000000,12000000,8000000"
                    + ":36000000,24000000,17000000"
                    + ":48000000,36000000,24000000";
            } else if (is18by9) {
                out = "1440x720:1440x720:1,2,24,30,60"
                    + ":1200000,800000,500000"
                    + ":12000000,8000000,5000000"
                    + ":17000000,12000000,8000000"
                    + ":36000000,24000000,17000000"
                    + ":48000000,36000000,24000000";
            }
        }

        ShimLogger.logI(TAG, "getManualSupportedList(cam=" + cameraId + ", quality=" + quality + ", ratio=" + ratio + ") -> '" + out + "'");
        return out;
    }
}
