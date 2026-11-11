package com.fang.myapplication;

import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.RelativeLayout;

import com.fang.myapplication.model.NALPacket;
import com.fang.myapplication.model.PCMPacket;
import com.fang.myapplication.player.AudioPlayer;
import com.fang.myapplication.player.VideoPlayer;

public class RaopServer implements SurfaceHolder.Callback {

    static {
        System.loadLibrary("raop_server");
        System.loadLibrary("play-lib");
    }
    private static final String TAG = "RaopServer";
    private VideoPlayer mVideoPlayer;
    private AudioPlayer mAudioPlayer;
    private SurfaceView mSurfaceView;
    private long mServerId = 0;

    /** Sizes the picture to the phone's own shape (centred, bars at the sides) instead of stretching it. */
    private final VideoPlayer.SizeListener mSizeListener = new VideoPlayer.SizeListener() {
        @Override
        public void onVideoSize(final int width, final int height) {
            mSurfaceView.post(new Runnable() {
                @Override
                public void run() {
                    View parent = (View) mSurfaceView.getParent();
                    int pw = parent.getWidth(), ph = parent.getHeight();
                    if (pw <= 0 || ph <= 0) return;
                    float scale = Math.min((float) pw / width, (float) ph / height);
                    RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                            Math.round(width * scale), Math.round(height * scale));
                    lp.addRule(RelativeLayout.CENTER_IN_PARENT);
                    mSurfaceView.setLayoutParams(lp);
                }
            });
        }
    };

    public RaopServer(SurfaceView surfaceView) {
        mSurfaceView = surfaceView;
        mSurfaceView.getHolder().addCallback(this);
        mAudioPlayer = new AudioPlayer();
        mAudioPlayer.start();
    }

    public void onRecvVideoData(byte[] nal, int nalType, long dts, long pts) {
        NALPacket nalPacket = new NALPacket();
        nalPacket.nalData = nal;
        nalPacket.nalType = nalType;
        nalPacket.pts = pts;
        VideoPlayer player = mVideoPlayer;
        if (player != null) player.addPacker(nalPacket);
    }

    public void onRecvAudioData(short[] pcm, long pts) {
        PCMPacket pcmPacket = new PCMPacket();
        pcmPacket.data = pcm;
        pcmPacket.pts = pts;
        mAudioPlayer.addPacker(pcmPacket);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {

    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (mVideoPlayer == null) {
            mVideoPlayer = new VideoPlayer(holder.getSurface(), mSizeListener);
            mVideoPlayer.start();
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {

    }

    public void startServer() {
        if (mServerId == 0) {
            mServerId = start();
        }
    }

    public void stopServer() {
        if (mServerId != 0) {
            stop(mServerId);
        }
        mServerId = 0;

    }

    public int getPort() {
        if (mServerId != 0) {
            return getPort(mServerId);
        }
        return 0;
    }

    private native long start();
    private native void stop(long serverId);
    private native int getPort(long serverId);
}
