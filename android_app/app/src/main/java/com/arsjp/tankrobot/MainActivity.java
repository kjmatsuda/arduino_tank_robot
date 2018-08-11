/*********************************************************
 *     Module   :   MainActivity.java                    *
 *     Author   :   R.Tei                                *
 *     Date     :   2015.4.14                            *
 '********************************************************/

package com.arsjp.tankrobot;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Resources;
import android.graphics.drawable.LevelListDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import com.arsjp.blelib.BleService;
import com.arsjp.blelib.ScanActivity;


public class MainActivity extends Activity {
    public static final String TAG = "Main";            //デバッグログ用のタグ名
    private static final int REQUEST_SCAN_RESULTS = 1;//BLE接続先探しメッセージの識別定数
    private BluetoothDevice mDevice = null;             //BluetoothDeviceのインスタンス
    private Messenger mService = null;                  //Messengerインスタンス
    private boolean mConnect = false;                  //デバイスのBLE通信接続状態フラグ
    private long preTimeMS = 0;                         //前回メッセージ送信時刻（ミリ秒）

    // TODO モータの平均制御値、最大制御値はどこに記載してある？
    private static final int AVG_POWER = 120;       //モータの平均制御値
    private static final int MAX_POWER = 180;       //モータの最大制御値
    private static final float MAX_ANGLE = (float) 45.0;  //ハンドル回転角度の最大値[°]
    private static final int ANGLE_STEP = 5;        //ハンドル回転描画ステップ [°]

    private SensorManager mSensorManager = null;    //センサマネージャのインスタンス
    private SensorEventListener mSensorEventListener = null;  //センサイベント処理
    private int mDir = 0;                            //進行方向の状態
    private float mCurAng;                          //現在のハンドルの角度
    private ImageView mViewWheel;                   //ハンドルイメージビューのインスタンス
    private RadioGroup mDrive;                      //ラジオボタングループのインスタンス

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        //operation GUI goes into hiding.
        findViewById(R.id.conn).setVisibility(View.GONE);
        Log.d(TAG, "onCreate()");

        setView();
        setSensor();

        //start the service
        Intent serviceIntent = new Intent(this, BleService.class);
        bindService(serviceIntent, mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy()");
        unbindService(mConnection);
        mConnection = null;
    }

    @Override
    protected void onPause() {
        super.onPause();
        mDrive.check(R.id.btnStop);
    }

    public void onConnectButtonClick(View view) {
        //scan BLE devices around.
        Intent newIntent = new Intent(MainActivity.this, ScanActivity.class);
        startActivityForResult(newIntent, REQUEST_SCAN_RESULTS);
    }

    //device discovery result from ScanActivity
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (resultCode) {
            case Activity.RESULT_OK:
                String deviceAddress = data.getStringExtra(BluetoothDevice.EXTRA_DEVICE);
                mDevice = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(deviceAddress);
                findViewById(R.id.scan).setVisibility(View.GONE);
                try {
                    Message msg = Message.obtain(null, BleService.MSG_CONNECT);
                    Bundle bundle = new Bundle();
                    bundle.putString("VALUE", deviceAddress);
                    msg.setData(bundle);
                    mService.send(msg);
                } catch (RemoteException e) {
                    findViewById(R.id.scan).setVisibility(View.VISIBLE);
                    showMessage(getString(R.string.ER12));
                }
                break;
            case Activity.RESULT_CANCELED:
                finish();
                break;
        }
    }

    //connected/disconnected the BLE service
    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            mService = new Messenger(service);
            Log.d(TAG, "onServiceConnected::mService= " + mService);
            try {
                Message msg = Message.obtain(null, BleService.MSG_INITIALIZE);
                msg.replyTo = mClient;
                mService.send(msg);
            } catch (RemoteException e) {
                showMessage(getString(R.string.ER11));
                finish();
            }
        }
        public void onServiceDisconnected(ComponentName classname) {
            mService = null;
        }
    };

    //process incoming messages from the BLE service
    private final Messenger mClient = new Messenger(new IncomingHandler());
    private class IncomingHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case BleService.MSG_CONNECTED:
                    mConnect = true;
                    setTitle("接続中:" + mDevice.getName());
                    findViewById(R.id.scan).setVisibility(View.GONE);
                    findViewById(R.id.conn).setVisibility(View.VISIBLE);
                    break;
                case BleService.MSG_DISCONNECTED:
                    mConnect = false;
                    setTitle("接続切断中");
                    findViewById(R.id.scan).setVisibility(View.VISIBLE);
                    findViewById(R.id.conn).setVisibility(View.GONE);
                    break;
                case BleService.MSG_DATA_RECEIVED:
                    onReceived(msg.getData().getByteArray("DATA"));
                    break;
                case BleService.MSG_BLE_ERROR:
                    String strId = "ER" + msg.arg1;
                    int id = getResources().getIdentifier(strId, "string", getPackageName());
                    showMessage(getString(id));
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }

    //convert the output string to a byte array, then send it by BLE
    private void onSend(String strTX) {
        if (mConnect == false) {
            return;
        }

        Message msg = Message.obtain(null, BleService.MSG_SEND_DATA);
        Bundle bundle = new Bundle();
        bundle.putByteArray("DATA", strTX.getBytes());
        msg.setData(bundle);
        // We want to monitor the service for as long as we are connected to it.
        try {
            mService.send(msg);
            Log.d(TAG, "onSend::" + strTX);
        } catch (RemoteException e) {
            showMessage(getString(R.string.ER13));
        }
    }

    //convert the received byte array to string, then show it on GUI
    private void onReceived(final byte[] data) {
        runOnUiThread(new Runnable() {
            public void run() {
                try {
                    String strRX = new String(data, "UTF-8");
                    Log.d(TAG, "onReceived::" + strRX);
                    switch (strRX.substring(0, 3)) {
                        case "DR(": //DR(..)
                            if (mDir == 0 && strRX != "DR(1,0,0)") {
                                Log.d(TAG, "Resend Stop");
                                onSend("DR(1,0,0)");
                            }
                            break;
                        case "ER(": //ER
                            String strId = "ER" + strRX.substring(3, 4);
                            int id = getResources().getIdentifier(strId, "string", getPackageName());
                            showMessage(getString(id) + ":\r\n" + strRX.substring(6));
                            break;
                        default:
                            showMessage(getString(R.string.ER15) + ":\r\n" + strRX);
                            break;
                    }
                } catch (Exception e) {
                    showMessage(getString(R.string.ER16));
                }
            }
        });
    }

    private void setView() {
        mViewWheel = (ImageView) findViewById(R.id.imgWheel);

        //Drive buttons operation
        mDrive = (RadioGroup)findViewById(R.id.rdgDrive);
        // DONE 前進、停止、バックのイベントリスナーはどこで設定している？
        // -> ここでしている
        mDrive.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            public void onCheckedChanged(RadioGroup grp,int btn) {
                if (btn == R.id.btnStop) {
                    mSensorManager.unregisterListener(mSensorEventListener);
                    mDir = 0;
                    onDrive();
                } else {
                    if (mDir == 0) {
                        mSensorManager.registerListener(mSensorEventListener,
                                mSensorManager.getDefaultSensor(
                                        Sensor.TYPE_GAME_ROTATION_VECTOR),
                                SensorManager.SENSOR_DELAY_GAME);
                        showMessage(getString(R.string.Rotate));
                    }
                    mDir = (btn==R.id.btnForward ? 1 : -1);
                }
            }
        });
    }

    private void setSensor() {
        //get sensor service
        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        // DONE ハンドルを傾けたときのイベントリスナーはどこ？
        // -> ここでしている
        mSensorEventListener = new SensorEventListener() {
            public void onSensorChanged (SensorEvent event) {
                // get data from the sensors
                if ( event.sensor.getType() == Sensor.TYPE_GAME_ROTATION_VECTOR) {
                    if (mConnect) {
                        float[] rotationMatrix = new float[16];
                        mSensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
                        float[] orientations = new float[3];
                        SensorManager.getOrientation(rotationMatrix, orientations);
                        //mCurAng is current orientation angle from -180 to +180
                        mCurAng = (float) Math.toDegrees(orientations[0]);
                        onDrive();
                    }
                }
            }
            public void onAccuracyChanged (Sensor sensor, int accuracy) {}
        };
    }

    private void onDrive() {
        String strId;
        String msg, ang;

        if ( mDir == 0 ) {
            mViewWheel.setBackgroundResource(R.drawable.right0);
            Log.d(TAG, "Stop");
            onSend("DR(1,0,0)");
            ((TextView) findViewById(R.id.txtMSG)).setText(getString(R.string.STOP));
            return;
        }

        long curTimeMS = System.currentTimeMillis();
        if ((curTimeMS - preTimeMS) < 1000) {
            return;
        }
        preTimeMS = curTimeMS;

        if (mCurAng > MAX_ANGLE)
            mCurAng = MAX_ANGLE;
        if (mCurAng < -MAX_ANGLE)
            mCurAng = -MAX_ANGLE;
        // DONE この式で導出するdeltaは何？
        // -> 平均制御値からの差分。最大制御値を超えないように調整している
        int delta = (int) ((float)(MAX_POWER - AVG_POWER) * mCurAng / MAX_ANGLE);

        int rounded_angle = Math.round(mCurAng / ANGLE_STEP) * ANGLE_STEP;
        if (rounded_angle >= 0) {
            strId = "right" + rounded_angle;
            ang = getString(R.string.RIGHT) + delta;
        } else {
            strId = "left" + (-rounded_angle);
            ang = getString(R.string.LEFT) + (-delta);
        }
        int id = getResources().getIdentifier(strId, "drawable", getPackageName());
        mViewWheel.setBackgroundResource(id);

        int left = AVG_POWER + delta;
        int right = AVG_POWER - delta;
        onSend("DR(" + (mDir >= 0 ? 1 : 0) + "," + left + "," + right + ")");
        //Log.d(TAG, String.format("%s %f", strId, mCurAng));
        msg = getString(mDir >= 0 ? R.string.FORWARD : R.string.BACK);
        if (Math.abs(rounded_angle) > 0) {
            msg = msg + " " + ang + "°";
        }
        ((TextView) findViewById(R.id.txtMSG)).setText(msg);
    }

    private void showMessage(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
