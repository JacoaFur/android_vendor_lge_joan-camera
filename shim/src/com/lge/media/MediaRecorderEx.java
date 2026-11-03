package com.lge.media;

import android.hardware.Camera;
import android.media.MediaRecorder;
import com.lge.os.ShimLogger;

/**
 * Enhanced shim for com.lge.media.MediaRecorderEx:
 * 1. Drops LG-only StagefrightRecorder parameters cleanly without poisoning recorder state.
 * 2. Sanitizes video dimensions (2160x1080 -> 2560x1280, 1440x720 -> 1280x720) to match
 *    the msm8998 Camera2Client HAL3 stream table.
 * 3. Clamps API1 video frame rate to <= 30fps (Camera2Client API1 only exposes 15,20,24,30).
 * 4. Maps custom LG AudioSource constants (> 10, e.g. Hi-Fi / AudioZoom / Concert) to CAMCORDER.
 * 5. Logs all recorder lifecycle events via ShimLogger.
 */
public class MediaRecorderEx extends MediaRecorder {
    private static final String TAG = "MediaRecorderEx";
    static { ShimLogger.init(); }

    public MediaRecorderEx() {
        super();
        ShimLogger.logI(TAG, "new MediaRecorderEx()");
    }

    public void setParameter(String nameValuePair) {
        if (nameValuePair == null) return;
        ShimLogger.logD(TAG, "setParameter(ignored LG param): " + nameValuePair);
    }

    public void changeMaxFileSize(long size) {
        ShimLogger.logI(TAG, "changeMaxFileSize(" + size + ")");
        if (size <= 0) return;
        try {
            super.setMaxFileSize(size);
        } catch (Throwable t) {
            ShimLogger.logW(TAG, "setMaxFileSize failed: " + t.getMessage());
        }
    }

    public void setAudioZooming() {
        ShimLogger.logD(TAG, "setAudioZooming()");
    }

    public void setAudioZooming(int level) {
        ShimLogger.logD(TAG, "setAudioZooming(" + level + ")");
    }

    public void setRecordAngle(int angle) {
        ShimLogger.logD(TAG, "setRecordAngle(" + angle + ")");
    }

    public void setProfile(CamcorderProfileEx profile) {
        if (profile == null) return;
        ShimLogger.logI(TAG, "setProfile(CamcorderProfileEx: " + profile.videoFrameWidth + "x" + profile.videoFrameHeight
                + "@" + profile.videoFrameRate + "fps, br=" + profile.videoBitRate + ")");
        setOutputFormat(profile.fileFormat);
        setVideoFrameRate(profile.videoFrameRate);
        setVideoSize(profile.videoFrameWidth, profile.videoFrameHeight);
        setVideoEncodingBitRate(profile.videoBitRate);
        setVideoEncoder(profile.videoCodec);
        if (profile.quality < 1000 || profile.quality >= 2000) {
            setAudioEncodingBitRate(profile.audioBitRate);
            setAudioChannels(profile.audioChannels);
            setAudioSamplingRate(profile.audioSampleRate);
            setAudioEncoder(profile.audioCodec);
        }
    }

    @Override
    public void setCamera(Camera c) {
        ShimLogger.logI(TAG, "setCamera(" + c + ")");
        super.setCamera(c);
    }

    @Override
    public void setAudioSource(int audio_source) {
        int safeSource = audio_source;
        if (safeSource < 0 || safeSource > 10) {
            ShimLogger.logW(TAG, "Mapping LG custom AudioSource " + audio_source + " -> CAMCORDER (5)");
            safeSource = AudioSource.CAMCORDER;
        } else {
            ShimLogger.logI(TAG, "setAudioSource(" + safeSource + ")");
        }
        try {
            super.setAudioSource(safeSource);
        } catch (RuntimeException e) {
            ShimLogger.logW(TAG, "setAudioSource(" + safeSource + ") failed (" + e.getMessage() + "); falling back to MIC (1)");
            super.setAudioSource(AudioSource.MIC);
        }
    }

    @Override
    public void setVideoSource(int video_source) {
        ShimLogger.logI(TAG, "setVideoSource(" + video_source + ")");
        super.setVideoSource(video_source);
    }

    @Override
    public void setOutputFormat(int output_format) {
        int safeFmt = (output_format < 0 || output_format > 11) ? OutputFormat.MPEG_4 : output_format;
        ShimLogger.logI(TAG, "setOutputFormat(" + output_format + " -> " + safeFmt + ")");
        super.setOutputFormat(safeFmt);
    }

    @Override
    public void setVideoSize(int width, int height) {
        int w = width;
        int h = height;
        // Camera 0/2 HAL video-size-values has 2560x1280 for 2:1 (18:9), not 2160x1080 or 1440x720
        if (w == 2160 && h == 1080) {
            w = 2560;
            h = 1280;
            ShimLogger.logI(TAG, "Mapping 18:9 video size 2160x1080 -> HAL-supported 2560x1280");
        } else if (w == 1440 && h == 720) {
            w = 1280;
            h = 720;
            ShimLogger.logI(TAG, "Mapping video size 1440x720 -> HAL-supported 1280x720");
        } else {
            ShimLogger.logI(TAG, "setVideoSize(" + w + "x" + h + ")");
        }
        super.setVideoSize(w, h);
    }

    @Override
    public void setVideoFrameRate(int rate) {
        int safeRate = rate;
        if (safeRate > 30) {
            ShimLogger.logW(TAG, "Clamping API1 videoFrameRate " + rate + " -> 30 (HAL API1 max is 30fps)");
            safeRate = 30;
        } else if (safeRate <= 0) {
            safeRate = 30;
        } else {
            ShimLogger.logI(TAG, "setVideoFrameRate(" + safeRate + ")");
        }
        super.setVideoFrameRate(safeRate);
    }

    @Override
    public void setVideoEncoder(int video_encoder) {
        ShimLogger.logI(TAG, "setVideoEncoder(" + video_encoder + ")");
        try {
            super.setVideoEncoder(video_encoder);
        } catch (RuntimeException e) {
            ShimLogger.logW(TAG, "setVideoEncoder(" + video_encoder + ") failed; falling back to H264");
            super.setVideoEncoder(VideoEncoder.H264);
        }
    }

    @Override
    public void setAudioEncoder(int audio_encoder) {
        int safeEnc = (audio_encoder < 0 || audio_encoder > 7) ? AudioEncoder.AAC : audio_encoder;
        ShimLogger.logI(TAG, "setAudioEncoder(" + audio_encoder + " -> " + safeEnc + ")");
        try {
            super.setAudioEncoder(safeEnc);
        } catch (RuntimeException e) {
            ShimLogger.logW(TAG, "setAudioEncoder(" + safeEnc + ") failed; falling back to AAC");
            super.setAudioEncoder(AudioEncoder.AAC);
        }
    }

    @Override
    public void prepare() throws java.io.IOException {
        ShimLogger.logI(TAG, "prepare() start");
        try {
            super.prepare();
            ShimLogger.logI(TAG, "prepare() SUCCEEDED");
        } catch (Throwable t) {
            ShimLogger.logE(TAG, "prepare() FAILED", t);
            if (t instanceof java.io.IOException) throw (java.io.IOException) t;
            if (t instanceof RuntimeException) throw (RuntimeException) t;
            throw new RuntimeException(t);
        }
    }

    @Override
    public void start() {
        ShimLogger.logI(TAG, "start() recording");
        try {
            super.start();
            ShimLogger.logI(TAG, "start() SUCCEEDED");
        } catch (Throwable t) {
            ShimLogger.logE(TAG, "start() FAILED", t);
            if (t instanceof RuntimeException) throw (RuntimeException) t;
            throw new RuntimeException(t);
        }
    }

    @Override
    public void stop() {
        ShimLogger.logI(TAG, "stop() recording");
        try {
            super.stop();
            ShimLogger.logI(TAG, "stop() SUCCEEDED");
        } catch (Throwable t) {
            ShimLogger.logE(TAG, "stop() FAILED", t);
            if (t instanceof RuntimeException) throw (RuntimeException) t;
            throw new RuntimeException(t);
        }
    }
}
