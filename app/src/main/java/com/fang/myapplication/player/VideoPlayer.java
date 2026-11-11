package com.fang.myapplication.player;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import com.fang.myapplication.model.NALPacket;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Decodes the mirrored H.264 stream straight onto the surface, as fast as frames arrive.
 *
 * The one thing to know: a decoder can only start at a keyframe, and some senders (a Mac) send exactly one, at the
 * start of the session. So a frame must never be dropped (nothing after it would decode correctly again), and a
 * decoder that is lost (an error, the surface gone) cannot simply start again mid-stream. What can be done:
 * keep the frames since the last keyframe while there is no surface, and when the picture cannot be recovered ask
 * for a new session, which makes the sender send a new keyframe.
 */
public class VideoPlayer extends Thread {

    public interface Callbacks {
        void onVideoSize(int width, int height);

        /** The picture cannot be recovered in this session: ending it makes the sender start a new one. */
        void onNeedNewSession();
    }

    private static final String TAG = "VideoPlayer";
    private static final String MIME = "video/avc";

    /** Frames kept while there is no surface (the app is in the background): the latest keyframe and what follows. */
    private static final int MAX_PENDING_BYTES = 24 * 1024 * 1024;
    /** A queue this long (about 30 s) means the decoder is hopelessly behind: give up on the session. */
    private static final int MAX_QUEUED = 900;
    /** How long to wait for a keyframe after the decoder was lost before asking for a new session. */
    private static final long KEYFRAME_WAIT_MS = 3000;
    /** Do not ask for a new session more often than this. */
    private static final long NEW_SESSION_BACKOFF_MS = 20000;
    /** After the decoder fails to start, wait this long before trying again. */
    private static final long RETRY_MS = 500;

    // Markers put in the queue, so they are handled in order with the frames around them.
    private static final int KIND_CONFIG = 0;
    private static final int KIND_SESSION_START = -1;
    private static final int KIND_SESSION_END = -2;
    private static final int KIND_LOST = -3;

    private final Callbacks mCallbacks;
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
    private long mNewSessionAskedAt = 0;
    private long mLastKeyframeAt = 0;
    private final ArrayList<NALPacket> mPending = new ArrayList<NALPacket>();
    private int mPendingBytes = 0;

    // Once a second in the log: how much arrived, how much was shown.
    private long mStatsAt = 0;
    private int mFedFrames = 0;
    private int mShownFrames = 0;

    public VideoPlayer(Callbacks callbacks) {
        mCallbacks = callbacks;
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
        if (mQueue.size() > MAX_QUEUED) {
            Log.w(TAG, "decoder far behind, giving the session up");
            mQueue.clear();
            mQueue.add(marker(KIND_LOST));
            return;
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
                    Log.i(TAG, "surface changed: the decoder has to start again");
                    releaseDecoder();
                    mNeedKeyframe = true;
                }
                startFromPending();
                if (packet != null) {
                    handle(packet);
                }
                drain();
            } catch (InterruptedException e) {
                break;
            } catch (RuntimeException e) {
                // A MediaCodec that has failed throws on every call from then on: start a new one.
                Log.e(TAG, "decoder error, starting it again", e);
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
                mPending.clear();
                mPendingBytes = 0;
                mNeedKeyframe = true;
                mKeyframeWaitSince = 0;
                mNewSessionAskedAt = 0;
                mLastKeyframeAt = 0;
                return;
            case KIND_LOST:
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

        boolean keyframe = containsKeyframe(packet.nalData);
        if (keyframe) {
            logKeyframe(packet);
        }
        Surface surface = mSurface;
        if (surface == null || !surface.isValid()) {
            holdForSurface(packet, keyframe);
            return;
        }
        if (mNeedKeyframe) {
            if (!keyframe) {
                // Cannot be decoded without the keyframe: drop it, and if none comes ask for a new session.
                long now = System.currentTimeMillis();
                if (mKeyframeWaitSince == 0) {
                    mKeyframeWaitSince = now;
                }
                if (now - mKeyframeWaitSince > KEYFRAME_WAIT_MS) {
                    askForNewSession();
                }
                return;
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

    private void askForNewSession() {
        long now = System.currentTimeMillis();
        if (mNewSessionAskedAt != 0 && now - mNewSessionAskedAt < NEW_SESSION_BACKOFF_MS) {
            return;
        }
        mNewSessionAskedAt = now;
        Log.w(TAG, "no keyframe for " + KEYFRAME_WAIT_MS + " ms: asking for a new session");
        if (mCallbacks != null) {
            mCallbacks.onNeedNewSession();
        }
    }

    /** How often the sender sends a keyframe decides how a lost picture can come back. */
    private void logKeyframe(NALPacket packet) {
        long now = System.currentTimeMillis();
        Log.i(TAG, "keyframe: " + (packet.nalData.length / 1024) + " KB, "
                + (mLastKeyframeAt == 0 ? "the first" : (now - mLastKeyframeAt) + " ms after the last"));
        mLastKeyframeAt = now;
    }

    /** No surface: keep the latest keyframe and everything after it, up to a size limit, to start from later. */
    private void holdForSurface(NALPacket packet, boolean keyframe) {
        mNeedKeyframe = true;
        if (keyframe) {
            mPending.clear();
            mPendingBytes = 0;
        } else if (mPending.isEmpty()) {
            return; // nothing to start from: the picture will need a new keyframe
        }
        if (mPendingBytes + packet.nalData.length > MAX_PENDING_BYTES) {
            mPending.clear(); // away too long to keep it all
            mPendingBytes = 0;
            return;
        }
        mPending.add(packet);
        mPendingBytes += packet.nalData.length;
    }

    /** A surface has appeared and frames were held: start the decoder from the held keyframe and catch up. */
    private void startFromPending() {
        Surface surface = mSurface;
        if (mDecoder != null || mPending.isEmpty() || surface == null || !surface.isValid()) {
            return;
        }
        if (System.currentTimeMillis() < mRetryAt) {
            return;
        }
        Log.i(TAG, "starting from " + mPending.size() + " held frames");
        createDecoder(surface);
        mNeedKeyframe = false;
        mKeyframeWaitSince = 0;
        for (NALPacket held : mPending) {
            feed(held.nalData, held.pts);
        }
        mPending.clear();
        mPendingBytes = 0;
    }

    private void createDecoder(Surface surface) {
        MediaFormat format = MediaFormat.createVideoFormat(MIME, 1920, 1080);
        // Lets the decoder change picture size (a phone turned sideways) without a restart.
        format.setInteger("max-width", 1920);
        format.setInteger("max-height", 1920);
        // A keyframe of a busy 1080p picture can be far bigger than the decoder's default input buffer.
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2 * 1024 * 1024);
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
        // A decoder just started can take a moment before it accepts input. Wait for it (up to about a second)
        // rather than skip the frame: a frame lost now would corrupt everything after it, and a sender that
        // sends one keyframe per session never sends the repair.
        for (int attempt = 0; attempt < 200 && !mIsEnd && mDecoder != null; attempt++) {
            int index = mDecoder.dequeueInputBuffer(5000);
            if (index >= 0) {
                ByteBuffer input = mDecoder.getInputBuffer(index);
                input.clear();
                if (data.length > input.capacity()) {
                    Log.w(TAG, "frame of " + data.length + " bytes does not fit the decoder's " + input.capacity()
                            + "-byte input");
                    mDecoder.queueInputBuffer(index, 0, 0, pts, 0);
                    return;
                }
                input.put(data);
                mDecoder.queueInputBuffer(index, 0, data.length, pts, 0);
                mFedFrames++;
                return;
            }
            drain(); // the decoder is full of finished pictures: show them, then try again
        }
        if (mDecoder != null) {
            // Still not taking frames: it is stuck. Start a new decoder instead of carrying on with a gap.
            Log.w(TAG, "decoder not accepting frames, starting it again");
            releaseDecoder();
            mNeedKeyframe = true;
        }
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
        if (mCallbacks != null && width > 0 && height > 0) {
            mCallbacks.onVideoSize(width, height);
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
