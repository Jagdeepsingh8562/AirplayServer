package com.fang.myapplication.player;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import com.fang.myapplication.model.PCMPacket;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class AudioPlayer extends Thread {

    private static final String TAG = "AudioPlayer";

    private AudioTrack mTrack;
    private final int mChannel = AudioFormat.CHANNEL_OUT_STEREO;
    private final int mSampleRate = 44100;
    private final int mAudioFormat = AudioFormat.ENCODING_PCM_16BIT;
    private volatile boolean isStopThread = false;
    private final LinkedBlockingQueue<PCMPacket> mQueue = new LinkedBlockingQueue<PCMPacket>();

    private long mStatsAt = 0;
    private int mPackets = 0;
    private int mTotalPackets = 0;
    private volatile int mDropped = 0;

    public AudioPlayer() {
        // Twice the minimum: enough not to underrun, small enough not to trail the picture.
        int buffer = AudioTrack.getMinBufferSize(mSampleRate, mChannel, mAudioFormat) * 2;
        mTrack = new AudioTrack(AudioManager.STREAM_MUSIC, mSampleRate, mChannel, mAudioFormat,
                buffer, AudioTrack.MODE_STREAM);
        mTrack.play();
        Log.i(TAG, "audio track ready, state " + mTrack.getState() + ", buffer " + buffer);
    }

    /** The phone sends its first second or so of sound ahead of time: playing all of it would keep the
     *  sound that much behind the picture, so only a short queue is kept and the oldest packets go. */
    private static final int MAX_QUEUED = 6; // about 65 ms

    public void addPacker(PCMPacket pcmPacket) {
        mQueue.add(pcmPacket);
        while (mQueue.size() > MAX_QUEUED) {
            mQueue.poll();
            mDropped++;
        }
    }

    @Override
    public void run() {
        while (!isStopThread) {
            try {
                PCMPacket packet = mQueue.poll(50, TimeUnit.MILLISECONDS);
                if (packet != null && mTrack != null) {
                    mTrack.write(packet.data, 0, packet.data.length);
                    mPackets++;
                    mTotalPackets++;
                }
            } catch (InterruptedException e) {
                break;
            }
            long now = System.currentTimeMillis();
            if (now - mStatsAt >= 1000) {
                if (mStatsAt != 0 && mPackets > 0) {
                    Log.i(TAG, "audio: " + mPackets + " packets/s (total " + mTotalPackets + "), queued " + mQueue.size() + ", dropped so far " + mDropped);
                }
                mStatsAt = now;
                mPackets = 0;
            }
        }
    }

    public void stopPlay() {
        isStopThread = true;
        interrupt();
        if (mTrack != null) {
            mTrack.flush();
            mTrack.stop();
            mTrack.release();
            mTrack = null;
        }
    }
}
