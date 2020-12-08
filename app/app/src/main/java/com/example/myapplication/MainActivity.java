package com.example.myapplication;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static java.lang.Float.parseFloat;

public class MainActivity extends AppCompatActivity implements SensorEventListener, View.OnClickListener{

    // layout variables
    private TextureView textureView;
    private TextView accelerometerSensorDataTextView;
    private TextView magnetometerSensorDataTextView;
    private TextView angleDataTextView;
    private TextView distanceDataTextView;
    private TextView currentHeightTextView;

    //Check state orientation of output image
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static{
        ORIENTATIONS.append(Surface.ROTATION_0,90);
        ORIENTATIONS.append(Surface.ROTATION_90,0);
        ORIENTATIONS.append(Surface.ROTATION_180,270);
        ORIENTATIONS.append(Surface.ROTATION_270,180);
    }

    // camera variables
    private String cameraId;
    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSessions;
    private CaptureRequest.Builder captureRequestBuilder;
    private Size imageDimension;
    private static final int REQUEST_CAMERA_PERMISSION = 200;

    // background thread
    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;

    // sensor variables
    private SensorManager sensorManager;
    private Sensor accelerometerSensor;
    private Sensor magnetometerSensor;

    // distance variables
    private float[] gravity;
    private float[] geoMagnetic;
    private float azimuth;
    private float pitch;
    private float roll;
    private float height = 1.4f;
    private float distance;
    private boolean is_meters = true;
    private String measure_unit = "m";

    /***************************************************************************
     *  Camera helper functions
     ***************************************************************************/

    CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            createCameraPreview();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            cameraDevice.close();
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int i) {
            cameraDevice.close();
            cameraDevice=null;
        }
    };

    private void createCameraPreview() {
        try{
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert  texture != null;
            texture.setDefaultBufferSize(imageDimension.getWidth(),imageDimension.getHeight());
            Surface surface = new Surface(texture);
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(surface);
            cameraDevice.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    if(cameraDevice == null)
                        return;
                    cameraCaptureSessions = cameraCaptureSession;
                    updatePreview();
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    Toast.makeText(MainActivity.this, "Changed", Toast.LENGTH_SHORT).show();
                }
            },null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void updatePreview() {
        if(cameraDevice == null)
            Toast.makeText(this, "Error", Toast.LENGTH_SHORT).show();
        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO);
        try{
            cameraCaptureSessions.setRepeatingRequest(captureRequestBuilder.build(),null,mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void openCamera() {
        CameraManager manager = (CameraManager)getSystemService(Context.CAMERA_SERVICE);
        try{
            cameraId = manager.getCameraIdList()[0];
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            assert map != null;
            imageDimension = map.getOutputSizes(SurfaceTexture.class)[0];
            //Check realtime permission if run higher API 23
            if(ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            {
                ActivityCompat.requestPermissions(this,new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                },REQUEST_CAMERA_PERMISSION);
                return;
            }
            manager.openCamera(cameraId,stateCallback,null);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }



    TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int i, int i1) {
            openCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int i, int i1) {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {

        }
    };

    private void stopBackgroundThread() {
        mBackgroundThread.quitSafely();
        try{
            mBackgroundThread.join();
            mBackgroundThread= null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("Camera Background");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    /***************************************************************************
     *  Sensor related listeners
     ***************************************************************************/

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

                // 57.29578 is 1 radian degree
                azimuth = 57.29578F * orientation[0];
                pitch = 57.29578F * orientation[1];
                roll = 57.29578F * orientation[2];

                angleDataTextView.setText(getResources().getString(R.string.angle_data_azimuth_pitch_roll, azimuth, pitch, roll));

                // compute distance
                if(is_meters) {
                    distance = Math.abs((float) (height * Math.tan(pitch * Math.PI / 180)));
                    measure_unit = "m";
                    distanceDataTextView.setText(getResources().getString(R.string.distance_data, distance, measure_unit));
                    currentHeightTextView.setText(getResources().getString(R.string.current_height, height, measure_unit));
                }
                else {
                    distance = Math.abs((float) (3.28 * height * Math.tan(pitch * Math.PI / 180)));
                    measure_unit = "Ft";
                    distanceDataTextView.setText(getResources().getString(R.string.distance_data, distance, measure_unit));
                    currentHeightTextView.setText(getResources().getString(R.string.current_height, 3.28 * height, measure_unit));
                }

                // display current height
//                currentHeightTextView.setText(getResources().getString(R.string.current_height, height, measure_unit));
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // do nothing, but function is required because class implements SensorEventListener
    }

    /***************************************************************************
     *  General listeners
     ***************************************************************************/

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // camera
        textureView = (TextureView)findViewById(R.id.textureView);
        //From Java 1.4 , you can use keyword 'assert' to check expression true or false
        assert textureView != null;
        textureView.setSurfaceTextureListener(textureListener);

        // distance

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

        // Angles and distances
        angleDataTextView = (TextView) findViewById(R.id.angle_data_azimuth_pitch_roll);
        distanceDataTextView = (TextView) findViewById(R.id.distance_data);
        currentHeightTextView = (TextView) findViewById(R.id.height_data);

        // Unit conversion and height set button
        final Button convert_distance_button = findViewById(R.id.convert_distance_button);
        final Button set_height_button = findViewById(R.id.set_height_button);
        convert_distance_button.setOnClickListener(this);
        set_height_button.setOnClickListener(this);
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
    protected void onResume() {
        super.onResume();
        startBackgroundThread();
        if(textureView.isAvailable())
            openCamera();
        else
            textureView.setSurfaceTextureListener(textureListener);
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopBackgroundThread();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if(requestCode == REQUEST_CAMERA_PERMISSION)
        {
            if(grantResults[0] != PackageManager.PERMISSION_GRANTED)
            {
                Toast.makeText(this, "You can't use camera without permission", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.convert_distance_button:
                is_meters = !is_meters;
                break;
            case R.id.set_height_button:
                EditText editHeight = (EditText) findViewById(R.id.editHeight);
                height = parseFloat(editHeight.getText().toString());
                currentHeightTextView.setText(getResources().getString(R.string.current_height, height, measure_unit));
            default:
                break;
        }
    }
}
