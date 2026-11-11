package com.fang.myapplication.player;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import com.fang.myapplication.model.NALPacket;

import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Decodes the mirrored H.264 stream straight onto the surface, as fast as frames arrive. */
public class VideoPlayer extends Thread {

    public interface SizeListener {
        void onVideoSize(int width, int height);
    }

    private static final String TAG = "VideoPlayer";
    private static final String MIME = "video/avc";

    private final Surface mSurface;
    private final SizeListener mSizeListener;
    private final LinkedBlockingQueue<NALPacket> mQueue = new LinkedBlockingQueue<NALPacket>();
    private final MediaCodec.BufferInfo mInfo = new MediaCodec.BufferInfo();
    private MediaCodec mDecoder;
    private volatile boolean mIsEnd = false;

    // Once a second in the log: how much arrived, how much was shown.
    private long mStatsAt = 0;
    private int mFedFrames = 0;
    private int mShownFrames = 0;

    public VideoPlayer(Surface surface, SizeListener sizeListener) {
        mSurface = surface;
        mSizeListener = sizeListener;
    }

    public void addPacker(NALPacket nalPacket) {
        mQueue.add(nalPacket);
    }

    public void end() {
        mIsEnd = true;
        interrupt();
    }

    private void initDecoder() throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat(MIME, 1920, 1080);
        // Lets the decoder change picture size (a phone turned sideways) without a restart.
        format.setInteger("max-width", 1920);
        format.setInteger("max-height", 1920);
        mDecoder = MediaCodec.createDecoderByType(MIME);
        mDecoder.configure(format, mSurface, null, 0);
        mDecoder.setVideoScalingMode(MediaCodec.VIDEO_SCALING_MODE_SCALE_TO_FIT);
        mDecoder.start();
    }

    @Override
    public void run() {
        try {
            initDecoder();
        } catch (Exception e) {
            Log.e(TAG, "decoder start failed", e);
            return;
        }
        while (!mIsEnd) {
            try {
                NALPacket packet = mQueue.poll(10, TimeUnit.MILLISECONDS);
                if (packet != null) {
                    feed(packet);
                }
                drain();
            } catch (InterruptedException e) {
                break;
            } catch (Exception e) {
                Log.e(TAG, "decode error", e);
            }
            logStats();
        }
        try {
            mDecoder.stop();
            mDecoder.release();
        } catch (Exception e) {
            Log.w(TAG, "decoder release", e);
        }
    }

    private void feed(NALPacket packet) {
        for (int attempt = 0; attempt < 5 && !mIsEnd; attempt++) {
            int index = mDecoder.dequeueInputBuffer(5000);
            if (index >= 0) {
                ByteBuffer input = mDecoder.getInputBuffer(index);
                input.clear();
                input.put(packet.nalData);
                mDecoder.queueInputBuffer(index, 0, packet.nalData.length, packet.pts, 0);
                mFedFrames++;
                return;
            }
            drain(); // the decoder is full of finished pictures: show them, then try again
        }
        Log.w(TAG, "decoder busy, dropped a frame");
    }

    private void drain() {
        while (true) {
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
