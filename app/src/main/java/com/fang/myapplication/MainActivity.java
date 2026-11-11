package com.fang.myapplication;

import android.app.Activity;
import android.content.Context;
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
        mRaopServer.setIdleView(findViewById(R.id.idle));
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


