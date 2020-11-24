package com.example.myapplication;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Camera;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.IOException;
import java.util.List;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private static final String TAG = "MainActivity";

    private Camera mCamera;
    private CameraPreview mPreview;
    private SurfaceView preview;
    private MediaRecorder mediaRecorder;

    private SensorManager sensorManager;
    private Sensor accelerometerSensor;
    private Sensor magnetometerSensor;

    private TextView accelerometerSensorDataTextView;
    private TextView magnetometerSensorDataTextView;
    private TextView angleDataTextView;
    private TextView distanceDataTextView;

    private float[] gravity;
    private float[] geoMagnetic;

    private float azimut;
    private float pitch;
    private float roll;

    private float height = 1.4f;
    private float distance;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Create an instance of Camera
        mCamera = getCameraInstance();

        // Create our Preview view and set it as the content of our activity.
        mPreview = new CameraPreview(this, mCamera);
        FrameLayout preview = (FrameLayout) findViewById(R.id.frameLayoutCamera);
        preview.addView(mPreview);

        String sensor_error = getResources().getString(R.string.error_no_sensor);

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

        // Accelerometer
        accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        accelerometerSensorDataTextView = (TextView) findViewById(R.id.accelerometer_sensor_data);
        if (accelerometerSensor == null) {
            accelerometerSensorDataTextView.setText(sensor_error);
        }

        // Magnetometer
        magnetometerSensor = sensorManager.getDefaultSensor((Sensor.TYPE_MAGNETIC_FIELD));
        magnetometerSensorDataTextView = (TextView) findViewById(R.id.magnetometer_sensor_data);
        if (magnetometerSensor == null) {
            magnetometerSensorDataTextView.setText(sensor_error);
        }

        // Angles
        angleDataTextView = (TextView) findViewById(R.id.angle_data_azimut_pitch_roll);
        distanceDataTextView = (TextView) findViewById(R.id.distance_data);

    }

    @Override
    protected void onStart() {
        super.onStart();

        if (accelerometerSensor != null) {
            sensorManager.registerListener(this, accelerometerSensor,
                SensorManager.SENSOR_DELAY_NORMAL);
        }

        if (magnetometerSensor != null) {
            sensorManager.registerListener(this, magnetometerSensor,
                SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        sensorManager.unregisterListener(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        releaseMediaRecorder();       // if you are using MediaRecorder, release it first
        releaseCamera();              // release the camera immediately on pause event
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        int sensorType = event.sensor.getType();

        // Update sensor values
        switch (sensorType) {
            case Sensor.TYPE_ACCELEROMETER:
                // get the y-axis of the accelerometer sensor
                gravity = event.values;
                accelerometerSensorDataTextView.setText(getResources()
                    .getString(R.string.accelerometer_sensor_data, gravity[0], gravity[1], gravity[2]));
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                // get the y-axis of the magnetometer sensor
                geoMagnetic = event.values;
                magnetometerSensorDataTextView.setText(getResources()
                    .getString(R.string.magnetometer_sensor_data, geoMagnetic[0], geoMagnetic[1], geoMagnetic[2]));
                break;
            default:
                // do nothing
        }

        if (gravity == null || geoMagnetic == null) {
            // do nothing
        } else {
            float Rmat[] = new float[9];
            float Imat[] = new float[9];
            boolean getRotationMatrixSuccess = SensorManager.getRotationMatrix(Rmat, Imat, gravity, geoMagnetic);

            if (getRotationMatrixSuccess) {
                float orientation[] = new float[3];
                SensorManager.getOrientation(Rmat, orientation);

                azimut = 57.29578F * orientation[0];
                pitch = 57.29578F * orientation[1];
                roll = 57.29578F * orientation[2];

                angleDataTextView.setText(getResources().getString(R.string.angle_data_azimut_pitch_roll, azimut, pitch, roll));

                // compute distance
                distance = Math.abs((float) (height * Math.tan(pitch * Math.PI / 180)));

                if (geoMagnetic[1] > 0) {
                    distanceDataTextView.setText(getResources().getString(R.string.distance_data, distance));
                } else {
                    distanceDataTextView.setText(getResources().getString(R.string.distance_data, Float.POSITIVE_INFINITY));
                }

            }
        }

    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

    /** Check if this device has a camera */
    private boolean checkCameraHardware(Context context) {
        if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA)){
            // this device has a camera
            return true;
        } else {
            // no camera on this device
            return false;
        }
    }

    /** A safe way to get an instance of the Camera object. */
    public static Camera getCameraInstance(){
        Camera c = null;
        try {
            c = Camera.open(); // attempt to get a Camera instance
        }
        catch (Exception e){
            // Camera is not available (in use or does not exist)
        }
        return c; // returns null if camera is unavailable
    }

    /** A basic Camera preview class */
    public class CameraPreview extends SurfaceView implements SurfaceHolder.Callback {
        private SurfaceHolder mHolder;
        private Camera mCamera;

        public CameraPreview(Context context, Camera camera) {
            super(context);
            mCamera = camera;

            // Install a SurfaceHolder.Callback so we get notified when the
            // underlying surface is created and destroyed.
            mHolder = getHolder();
            mHolder.addCallback(this);
            // deprecated setting, but required on Android versions prior to 3.0
            mHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        }

        public void surfaceCreated(SurfaceHolder holder) {
            // The Surface has been created, now tell the camera where to draw the preview.
            try {
                mCamera.setPreviewDisplay(holder);
                mCamera.startPreview();
            } catch (IOException e) {
                Log.d(TAG, "Error setting camera preview: " + e.getMessage());
            }
        }

        public void surfaceDestroyed(SurfaceHolder holder) {
            // empty. Take care of releasing the Camera preview in your activity.
        }

        public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
            // If your preview can change or rotate, take care of those events here.
            // Make sure to stop the preview before resizing or reformatting it.

            if (mHolder.getSurface() == null){
                // preview surface does not exist
                return;
            }

            // stop preview before making changes
            try {
                mCamera.stopPreview();
            } catch (Exception e){
                // ignore: tried to stop a non-existent preview
            }

            // set preview size and make any resize, rotate or
            // reformatting changes here

            // start preview with new settings
            try {
                mCamera.setPreviewDisplay(mHolder);
                mCamera.startPreview();

            } catch (Exception e){
                Log.d(TAG, "Error starting camera preview: " + e.getMessage());
            }
        }
    }

    private void releaseMediaRecorder(){
        if (mediaRecorder != null) {
            mediaRecorder.reset();   // clear recorder configuration
            mediaRecorder.release(); // release the recorder object
            mediaRecorder = null;
            mCamera.lock();           // lock camera for later use
        }
    }

    private void releaseCamera(){
        if (mCamera != null){
            mCamera.release();        // release the camera for other applications
            mCamera = null;
        }
    }
}
