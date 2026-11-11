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
    private final VideoPlayer mVideoPlayer;
    private final AudioPlayer mAudioPlayer;
    private final SurfaceView mSurfaceView;
    private volatile View mIdleView;
    private long mServerId = 0;
    /** A mirroring session is on screen: set by its first frame, cleared when the native side says it ended. */
    private volatile boolean mActive = false;

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
        // The players live as long as the server; the video one waits for a surface and for frames.
        mVideoPlayer = new VideoPlayer(mSizeListener);
        mVideoPlayer.start();
    }

    /** The view shown while no session is on screen (it covers the last picture of the one that ended). */
    public void setIdleView(View idleView) {
        mIdleView = idleView;
    }

    private void showIdle(final boolean show) {
        final View idle = mIdleView;
        if (idle == null) return;
        mSurfaceView.post(new Runnable() {
            @Override
            public void run() {
                idle.setVisibility(show ? View.VISIBLE : View.GONE);
            }
        });
    }

    // Called from native threads.
    public void onRecvVideoData(byte[] nal, int nalType, long dts, long pts) {
        if (!mActive) {
            mActive = true;
            Log.i(TAG, "mirroring started");
            mVideoPlayer.newSession();
            showIdle(false);
        }
        NALPacket nalPacket = new NALPacket();
        nalPacket.nalData = nal;
        nalPacket.nalType = nalType;
        nalPacket.pts = pts;
        mVideoPlayer.addPacker(nalPacket);
    }

    public void onRecvAudioData(short[] pcm, long pts) {
        PCMPacket pcmPacket = new PCMPacket();
        pcmPacket.data = pcm;
        pcmPacket.pts = pts;
        mAudioPlayer.addPacker(pcmPacket);
    }

    /** The mirror stream is over, whatever the reason (the phone stopped it, the connection died, a new session replaced it). */
    public void onVideoEnded() {
        Log.i(TAG, "mirroring ended");
        mActive = false;
        mVideoPlayer.endSession();
        mAudioPlayer.flush();
        showIdle(true);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {

    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        mVideoPlayer.setSurface(holder.getSurface());
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // The app went to the background: the video player drops its decoder and picks up again on the next surface.
        mVideoPlayer.setSurface(null);
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

    /** Stops the server and the two player threads, which hold the decoder and the audio track: call it when the app closes. */
    public void release() {
        stopServer();
        mVideoPlayer.end();
        mAudioPlayer.stopPlay();
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
