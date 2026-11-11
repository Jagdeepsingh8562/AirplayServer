package com.fang.myapplication;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity implements View.OnClickListener {

    public static String TAG = "MainActivity";

    private RaopServer mRaopServer;
    private DNSNotify mDNSNotify;

    private SurfaceView mSurfaceView;
    private Button mBtnControl;
    private TextView mTxtDevice;
    private boolean mIsStart = false;
    private View mIdleView;
    private volatile boolean mResumed = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getSystemService(Context.NSD_SERVICE);
        mBtnControl = findViewById(R.id.btn_control);
        mTxtDevice = findViewById(R.id.txt_device);
        mBtnControl.setOnClickListener(this);
        mSurfaceView = findViewById(R.id.surface);
        mRaopServer = new RaopServer(mSurfaceView);
        mIdleView = findViewById(R.id.idle);
        mRaopServer.setListener(new RaopServer.Listener() {
            @Override
            public void onMirroringChanged(final boolean active) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showMirroring(active);
                    }
                });
            }
        });
        mDNSNotify = new DNSNotify();
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Start listening at once: on a TV nobody wants to find the Start button first.
        mBtnControl.post(new Runnable() {
            @Override
            public void run() {
                if (!mIsStart) onClick(mBtnControl);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        mResumed = true;
    }

    @Override
    protected void onPause() {
        mResumed = false;
        super.onPause();
    }

    /**
     * Mirroring: the picture alone, full screen, and this app in front. Not mirroring: the "Ready" screen with its
     * button and the name.
     */
    private void showMirroring(boolean active) {
        int chrome = active ? View.GONE : View.VISIBLE;
        mIdleView.setVisibility(chrome);
        mBtnControl.setVisibility(chrome);
        mTxtDevice.setVisibility(chrome);
        if (!active) {
            mBtnControl.requestFocus();
        } else if (!mResumed) {
            // The phone started mirroring while another app was in front: bring this one up to show it.
            startActivity(new Intent(this, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        }
    }

    @Override
    protected void onDestroy() {
        if (mIsStart) {
            stopServer();
            mIsStart = false;
        }
        // The player threads hold the decoder and the audio track; without this a second launch could not get them.
        mRaopServer.release();
        super.onDestroy();
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.btn_control) {
            {
                if (!mIsStart) {
                    startServer();
                    mTxtDevice.setText("AirPlay name: " + mDNSNotify.getDeviceName());
                } else {
                    stopServer();
                    mTxtDevice.setText("Not started");
                }
                mIsStart = !mIsStart;
                mBtnControl.setText(mIsStart ? "Stop" : "Start");
            }
        }
    }

    private void startServer() {
        mDNSNotify.changeDeviceName();
        // One server answers both services, so whichever port the phone picks gets an answer.
        mRaopServer.startServer();
        int port = mRaopServer.getPort();
        if (port == 0) {
            Toast.makeText(this.getApplicationContext(), "AirPlay service failed to start", Toast.LENGTH_SHORT).show();
        } else {
            mDNSNotify.registerAirplay(port);
            mDNSNotify.registerRaop(port);
        }
        Log.d(TAG, "AirPlay and RAOP port = " + port);
    }

    private void stopServer() {
        mDNSNotify.stop();
        mRaopServer.stopServer();
    }

}


