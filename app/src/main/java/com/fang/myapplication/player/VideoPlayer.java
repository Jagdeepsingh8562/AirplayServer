package com.fang.myapplication.player;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import com.fang.myapplication.model.NALPacket;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Decodes the mirrored H.264 stream straight onto the surface, as fast as frames arrive, and recovers from what
 * goes wrong in real use: the stream ending, a new session starting, the surface going away (the app sent to the
 * background and brought back), the decoder failing, and the decoder falling behind.
 */
public class VideoPlayer extends Thread {

    public interface SizeListener {
        void onVideoSize(int width, int height);
    }

    private static final String TAG = "VideoPlayer";
    private static final String MIME = "video/avc";

    /** More than this waiting (about 1.5 s at 30 fps) means the decoder is behind: skip to the next keyframe. */
    private static final int MAX_QUEUED = 45;
    /** After the decoder is restarted, wait this long for a keyframe, then try whatever arrives. */
    private static final long KEYFRAME_WAIT_MS = 3000;
    /** After the decoder fails to start, wait this long before trying again. */
    private static final long RETRY_MS = 500;

    // Markers put in the queue, so they are handled in order with the frames around them.
    private static final int KIND_CONFIG = 0;
    private static final int KIND_SESSION_START = -1;
    private static final int KIND_SESSION_END = -2;

    private final SizeListener mSizeListener;
    private final LinkedBlockingQueue<NALPacket> mQueue = new LinkedBlockingQueue<NALPacket>();
    private final MediaCodec.BufferInfo mInfo = new MediaCodec.BufferInfo();

    private volatile Surface mSurface;       // null while there is nothing to draw on
    private volatile byte[] mConfig;         // the latest SPS/PPS: what a decoder started mid-stream needs first
    private volatile boolean mIsEnd = false;

    // Used only on this thread.
    private MediaCodec mDecoder;
    private Surface mDecoderSurface;
    private boolean mNeedKeyframe = true;
    private long mKeyframeWaitSince = 0;
    private long mRetryAt = 0;

    // Once a second in the log: how much arrived, how much was shown.
    private long mStatsAt = 0;
    private int mFedFrames = 0;
    private int mShownFrames = 0;

    public VideoPlayer(SizeListener sizeListener) {
        mSizeListener = sizeListener;
    }

    /** The surface to draw on, or null when it has gone (the app is in the background). */
    public void setSurface(Surface surface) {
        mSurface = surface;
    }

    /** A new mirroring session starts: forget the old one's frames, configuration and decoder state. */
    public void newSession() {
        mConfig = null;
        mQueue.clear();
        mQueue.add(marker(KIND_SESSION_START));
    }

    /** The session is over: stop decoding and release the decoder so the next session starts clean. */
    public void endSession() {
        mConfig = null;
        mQueue.clear();
        mQueue.add(marker(KIND_SESSION_END));
    }

    public void addPacker(NALPacket nalPacket) {
        if (nalPacket.nalType == KIND_CONFIG) {
            mConfig = nalPacket.nalData;
        }
        mQueue.add(nalPacket);
    }

    public void end() {
        mIsEnd = true;
        interrupt();
    }

    private static NALPacket marker(int kind) {
        NALPacket packet = new NALPacket();
        packet.nalType = kind;
        return packet;
    }

    @Override
    public void run() {
        while (!mIsEnd) {
            try {
                NALPacket packet = mQueue.poll(20, TimeUnit.MILLISECONDS);
                // The surface changed or went away: the decoder is tied to the old one.
                if (mDecoder != null && mDecoderSurface != mSurface) {
                    Log.i(TAG, "surface changed: restarting the decoder");
                    releaseDecoder();
                    mNeedKeyframe = true;
                }
                if (packet != null) {
                    handle(packet);
                }
                drain();
            } catch (InterruptedException e) {
                break;
            } catch (RuntimeException e) {
                // A MediaCodec that has failed throws on every call from then on: start a new one.
                Log.e(TAG, "decoder error, restarting it", e);
                releaseDecoder();
                mNeedKeyframe = true;
                mRetryAt = System.currentTimeMillis() + RETRY_MS;
            }
            logStats();
        }
        releaseDecoder();
    }

    private void handle(NALPacket packet) {
        switch (packet.nalType) {
            case KIND_SESSION_START:
            case KIND_SESSION_END:
                releaseDecoder();
                mNeedKeyframe = true;
                mKeyframeWaitSince = 0;
                return;
            case KIND_CONFIG:
                if (mDecoder != null) {
                    feed(packet.nalData, packet.pts);
                }
                return;
            default:
                break;
        }

        Surface surface = mSurface;
        if (surface == null || !surface.isValid()) {
            mNeedKeyframe = true; // nothing to draw on: what is missed cannot be picked up mid-stream
            return;
        }
        if (mQueue.size() > MAX_QUEUED) {
            mNeedKeyframe = true; // behind: drop frames until the next keyframe, which is quick as it decodes nothing
        }
        if (mNeedKeyframe) {
            long now = System.currentTimeMillis();
            if (!containsKeyframe(packet.nalData)) {
                if (mKeyframeWaitSince == 0) {
                    mKeyframeWaitSince = now;
                }
                if (now - mKeyframeWaitSince <= KEYFRAME_WAIT_MS) {
                    return;
                }
                Log.w(TAG, "no keyframe for " + KEYFRAME_WAIT_MS + " ms, decoding what arrives");
            }
            mNeedKeyframe = false;
            mKeyframeWaitSince = 0;
        }
        if (mDecoder == null) {
            if (System.currentTimeMillis() < mRetryAt) {
                mNeedKeyframe = true;
                return;
            }
            createDecoder(surface);
        }
        feed(packet.nalData, packet.pts);
    }

    private void createDecoder(Surface surface) {
        MediaFormat format = MediaFormat.createVideoFormat(MIME, 1920, 1080);
        // Lets the decoder change picture size (a phone turned sideways) without a restart.
        format.setInteger("max-width", 1920);
        format.setInteger("max-height", 1920);
        MediaCodec codec;
        try {
            codec = MediaCodec.createDecoderByType(MIME);
        } catch (IOException e) {
            throw new RuntimeException("no H.264 decoder", e);
        }
        try {
            codec.configure(format, surface, null, 0);
            codec.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT);
            codec.start();
        } catch (RuntimeException e) {
            codec.release();
            throw e;
        }
        mDecoder = codec;
        mDecoderSurface = surface;
        Log.i(TAG, "decoder started: " + codec.getName());
        byte[] config = mConfig;
        if (config != null) {
            feed(config, 0);
        }
    }

    private void releaseDecoder() {
        MediaCodec decoder = mDecoder;
        mDecoder = null;
        mDecoderSurface = null;
        if (decoder == null) {
            return;
        }
        try {
            decoder.stop();
        } catch (RuntimeException e) {
            Log.w(TAG, "decoder stop", e);
        }
        try {
            decoder.release();
        } catch (RuntimeException e) {
            Log.w(TAG, "decoder release", e);
        }
    }

    private void feed(byte[] data, long pts) {
        for (int attempt = 0; attempt < 5 && !mIsEnd && mDecoder != null; attempt++) {
            int index = mDecoder.dequeueInputBuffer(5000);
            if (index >= 0) {
                ByteBuffer input = mDecoder.getInputBuffer(index);
                input.clear();
                input.put(data);
                mDecoder.queueInputBuffer(index, 0, data.length, pts, 0);
                mFedFrames++;
                return;
            }
            drain(); // the decoder is full of finished pictures: show them, then try again
        }
        Log.w(TAG, "decoder busy, dropped a frame");
    }

    private void drain() {
        while (mDecoder != null) {
            int index = mDecoder.dequeueOutputBuffer(mInfo, 0);
            if (index >= 0) {
                mDecoder.releaseOutputBuffer(index, true); // show it now
                mShownFrames++;
            } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                reportSize(mDecoder.getOutputFormat());
            } else if (index != MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                return; // INFO_TRY_AGAIN_LATER
            }
        }
    }

    /**
     * Whether this frame is a keyframe (an IDR slice, NAL type 5). The units start with 00 00 01 or 00 00 00 01,
     * which cannot occur inside a unit, so a scan for them is reliable. The first slice (type 1 or 5) decides.
     */
    private static boolean containsKeyframe(byte[] data) {
        if (data == null) {
            return false;
        }
        for (int i = 0; i + 3 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                int type = data[i + 3] & 0x1f;
                if (type == 5) {
                    return true;
                }
                if (type == 1) {
                    return false;
                }
                i += 2;
            }
        }
        return false;
    }

    private void reportSize(MediaFormat format) {
        int width = format.getInteger(MediaFormat.KEY_WIDTH);
        int height = format.getInteger(MediaFormat.KEY_HEIGHT);
        if (format.containsKey("crop-left") && format.containsKey("crop-right")
                && format.containsKey("crop-top") && format.containsKey("crop-bottom")) {
            width = format.getInteger("crop-right") - format.getInteger("crop-left") + 1;
            height = format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1;
        }
        Log.i(TAG, "picture size " + width + "x" + height);
        if (mSizeListener != null && width > 0 && height > 0) {
            mSizeListener.onVideoSize(width, height);
        }
    }

    private void logStats() {
        long now = System.currentTimeMillis();
        if (now - mStatsAt < 1000) return;
        if (mStatsAt != 0 && (mFedFrames > 0 || mShownFrames > 0)) {
            Log.i(TAG, "video: fed " + mFedFrames + "/s, shown " + mShownFrames + "/s, queued " + mQueue.size());
        }
        mStatsAt = now;
        mFedFrames = 0;
        mShownFrames = 0;
    }
}
