package com.example.ubicomp;

import org.json.*;
import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
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
import android.hardware.camera2.params.StreamConfigurationMap;
import android.location.Location;
import android.media.Image;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.FragmentActivity;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;


import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.Session;
import com.google.ar.core.exceptions.NotYetAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.sceneform.Node;
import com.google.ar.sceneform.math.Vector3;
import com.google.ar.sceneform.rendering.ViewRenderable;
import com.google.ar.sceneform.ux.ArFragment;
import com.google.firebase.ml.vision.FirebaseVision;
import com.google.firebase.ml.vision.common.FirebaseVisionImage;
import com.google.firebase.ml.vision.text.FirebaseVisionText;
import com.google.firebase.ml.vision.text.FirebaseVisionTextRecognizer;

import java.io.IOException;
import java.net.URL;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.Collections;
import java.util.List;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class MainActivity extends FragmentActivity implements DownloadCallback<String> {//extends AppCompatActivity {

    /**
    * Internal Class to bundle location and text information.
    * timestamp is not included in the payload information, as that will be handled by the server
     */
    private class Payload {
        // Timestamp will be added by the server to maintain consistency
        double latitude;
        double longitude;
        float azimuth;
        String text;


        public Payload(double latitude, double longitude, float azimuth, String text){
            this.latitude = latitude;
            this.longitude = longitude;
            this.azimuth = azimuth;
            this.text = text;
        }

        // Returns a JSON representation of the object
        public JSONObject getJSON(){
            try {
                JSONObject obj = new JSONObject();
                obj.put("latitude", String.valueOf(latitude));
                obj.put("longitude", String.valueOf(longitude));
                obj.put("azimuth", String.valueOf((azimuth)));
                obj.put("text", text);
                return obj;
            }
            catch(JSONException e){
                Log.e("Payload", "Couldn't create JSON object", e);
                return null;
            }
        }
    }

    // View Objects
    Button button;
    TextureView textureView;
    TextView text;

    // Orientation enum
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 0);
        ORIENTATIONS.append(Surface.ROTATION_90, 90);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    //Camera properties
    private String cameraId;
    CameraDevice cameraDevice;
    CameraCaptureSession cameraCaptureSession;
    CaptureRequest.Builder captureRequestBuilder;
    private Size imageDimensions;
    Handler mBackgroundHandler;
    HandlerThread mBackgroundThread;

    private Payload payload; //TODO: SEE IF WE CAN CHANGE CONTROL FLOW SO WE DON'T HAVE THIS GLOBAL

    //Location properties
    private FusedLocationProviderClient fusedLocationProviderClient;
    private Location mLocation;
    private LocationCallback mLocationCallback;
    private LocationRequest mLocationRequest;


    //Sensor properties
    private SensorManager mSensorManager;
    private Sensor mAccelerometer;
    private SensorEventListener mSensorListener;
    private Sensor mMagneticField;
    private float[] mGravity;
    private float[] mGeomagnetic;
    private float azimuth;


    // Network Connectivity
    //      Keep a reference to the NetworkFragment, which owns the AsyncTask object
    //      that is used to execute network ops.
    private NetworkFragment networkFragment;
    //      Boolean telling us whether a download is in progress, so we don't trigger overlapping
    //      downloads with consecutive button clicks.
    private boolean downloading = false;

    //Ar core
    private ArFragment arFragment;
    private ViewRenderable viewRenderable;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        text = findViewById(R.id.editText);
        button = findViewById(R.id.button);
        textureView = findViewById(R.id.textureView);
        textureView.setSurfaceTextureListener(textureListener);
        arFragment = (ArFragment) getSupportFragmentManager().findFragmentById(R.id.ux_fragment);
        arFragment.getPlaneDiscoveryController().hide();
        arFragment.getPlaneDiscoveryController().setInstructionView(null);
        arFragment.getArSceneView().getPlaneRenderer().setEnabled(false);

        // set-up session so the camera auto-focuses
        Session session;
        try {
            session = new Session(this);
            Config config = new Config(session);
            config.setFocusMode(Config.FocusMode.AUTO);
            config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
            session.configure(config);
            arFragment.getArSceneView().setupSession(session);
        } catch (UnavailableArcoreNotInstalledException e) {
            e.printStackTrace();
        } catch (UnavailableApkTooOldException e) {
            e.printStackTrace();
        } catch (UnavailableSdkTooOldException e) {
            e.printStackTrace();
            e.printStackTrace();
        } catch (UnavailableDeviceNotCompatibleException e) {
            e.printStackTrace();
        }

        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this);

        mLocationRequest = new LocationRequest();
        mLocationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        mLocationCallback = new LocationCallback(){
          @Override
          public void onLocationResult(LocationResult locationResult) {
              if(locationResult == null) {
                  return;
              }
              mLocation = locationResult.getLastLocation();
          }
        };

        networkFragment = NetworkFragment.getInstance(getSupportFragmentManager(), "this url does not work >:(");

        mSensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
        mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mMagneticField = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        mSensorListener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent sensorEvent) {
                if (sensorEvent.sensor.getType() == Sensor.TYPE_ACCELEROMETER)
                    mGravity = sensorEvent.values;

                if (sensorEvent.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD)
                    mGeomagnetic = sensorEvent.values;

                if (mGravity != null && mGeomagnetic != null) {
                    float[] R = new float[9];
                    float[] I = new float[9];

                    if (SensorManager.getRotationMatrix(R, I, mGravity, mGeomagnetic)) {

                        // orientation contains azimuth, pitch and roll
                        float[] orientation = new float[3];
                        SensorManager.getOrientation(R, orientation);

                        azimuth = orientation[0];
                    }
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int i) {

            }
        };

        button.setOnClickListener(v -> takePicture() );
    }


    //*******************
    // ARCORE AND CAMERA BULLSHIT
    //*******************


    private void configureTransform(int viewWidth, int viewHeight) {
        if (null == textureView || null == imageDimensions) {
            return;
        }
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, imageDimensions.getHeight(), imageDimensions.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / imageDimensions.getHeight(),
                    (float) viewWidth / imageDimensions.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        textureView.setTransform(matrix);
    }


    TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int i, int i1) {
            try {
                openCamera(i, i1);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
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


    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            try {
                createCameraPreview();
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            cameraDevice.close();
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int i) {
            cameraDevice.close();
            cameraDevice = null;
        }
    };


    private void createCameraPreview() throws CameraAccessException {
        SurfaceTexture texture = textureView.getSurfaceTexture();
        texture.setDefaultBufferSize(imageDimensions.getWidth(), imageDimensions.getHeight());
        Surface surface = new Surface(texture);
        captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        captureRequestBuilder.addTarget(surface);
        cameraDevice.createCaptureSession(Collections.singletonList(surface), new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(@NonNull CameraCaptureSession session) {
                if (cameraDevice == null) {
                    return;
                }

                cameraCaptureSession = session;
                try {
                    updatePreview();
                } catch (CameraAccessException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                Toast.makeText(getApplicationContext(), "Configuration Changed", Toast.LENGTH_LONG).show();
            }
        }, null);
    }


    private void updatePreview() throws CameraAccessException {
        if (cameraDevice == null) {
            return;
        }
        captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, mBackgroundHandler);
    }


    private void openCamera(int width, int height) throws CameraAccessException {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        cameraId = manager.getCameraIdList()[0];
        CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        assert map != null;
        imageDimensions = map.getOutputSizes(SurfaceTexture.class)[0];


        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
                //ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED){
            ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.CAMERA /*, Manifest.permission.ACCESS_COARSE_LOCATION*/, Manifest.permission.ACCESS_FINE_LOCATION}, 101);
            return;
        }
        configureTransform(width, height);
        manager.openCamera(cameraId, stateCallback, null);

    }

    // Create View for ArCore renderables
    private View createView(String s){
        TextView view = new TextView(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        view.setGravity(1);
        view.setText(s);
        view.setPadding(6, 6, 6, 6);
        view.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        view.setBackgroundColor(Color.MAGENTA);
        view.setTextColor(Color.WHITE);
        return view;
    }


    private void takePicture() {

        Image image;
        try {
            Frame frame =  arFragment.getArSceneView().getArFrame();
            image = frame.acquireCameraImage();

            Log.d("Image Capture", String.valueOf(image.getFormat()));
        } catch (NotYetAvailableException e) {
            e.printStackTrace();
            return;
        }
        Log.d("Image Capture", image.toString());
        runTextRecognition(image);
        image.close();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == 101) {
            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                Toast.makeText(getApplicationContext(), "I need dat camera doe...", Toast.LENGTH_LONG).show();
            }
        }

    }

    //*******************
    // FIREBASE
    //*******************

    /**
     * Starts Firebase Processing workflow
     * @param image Image to process
     */
    private void runTextRecognition(Image image) {
        FirebaseVisionImage firebaseVisionImage = FirebaseVisionImage.fromMediaImage(image, ORIENTATIONS.get(90));
        FirebaseVisionTextRecognizer recognizer = FirebaseVision.getInstance()
                .getOnDeviceTextRecognizer();
        recognizer.processImage(firebaseVisionImage)
                .addOnSuccessListener(
                        texts -> processTextRecognitionResult(texts));
    }

    /**
     * Processes Firebase text detection results, POSTs them to the database, and create a node in the world
     * @param texts Firebase processing results
     */
    private void processTextRecognitionResult(FirebaseVisionText texts) {
        List<FirebaseVisionText.TextBlock> blocks = texts.getTextBlocks();
        String s = "Default Text";
        for (int i = 0; i < blocks.size(); i++) {
            // Can't ge the confidence because we're not using cloud
            Log.d("Firebase", "word! " + blocks.get(i).getText());
            text.append("\n" + blocks.get(i).getText());
            s = blocks.get(i).getText();
            payload = new Payload(mLocation.getLatitude(), mLocation.getLongitude(), azimuth, s);
            Log.d("Firebase", "Done processing!");

            new NetworkTask().execute(); // Upload the data to the database
            break;

            /* Leave this in case we need it again!
            List<FirebaseVisionText.Line> lines = blocks.get(i).getLines();
            for (int j = 0; j < lines.size(); j++) {
                List<FirebaseVisionText.Element> elements = lines.get(j).getElements();
                for (int k = 0; k < elements.size(); k++) {
                    //Do something
                }
            }*/
        }

        // Build the word as a renderable
        ViewRenderable.builder()
                .setView(this, createView(s))
                .build()
                .thenAccept(renderable -> viewRenderable = renderable);

        // Set the renderable in the scene
        // TODO: CALCULATE POSITION SO THE RENDERABLE APPEARS IN FRONT OF THE CAMERA
        Node node = new Node();
        node.setParent(arFragment.getArSceneView().getScene());
        node.setRenderable(viewRenderable);
        Vector3 cameraPosition = arFragment.getArSceneView().getScene().getCamera().getWorldPosition();
        node.setWorldPosition(cameraPosition);
    }

    //*******************
    // NETWORK
    //*******************

    /**
     * Task to handle posting information to the server
     */
    private class NetworkTask extends AsyncTask<URL, Integer, Long> {
        @Override
        protected Long doInBackground(URL... urls){
            uploadData(payload);
            return null;
        }
    }

    @Override
    public void updateFromDownload(String result) {
        // TODO fill this in with a UI  update based on the result of the webpage.

        // Update your UI here based on result of download.
        Log.d("UrlResult", "SUCCESS");
        Log.d("UrlResult", result);
    }

    @Override
    public NetworkInfo getActiveNetworkInfo() {
        ConnectivityManager connectivityManager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo networkInfo = connectivityManager.getActiveNetworkInfo();
        return networkInfo;
    }

    @Override
    public void onProgressUpdate(int progressCode, int percentComplete) {
        switch(progressCode) {
            // You can add UI behavior for progress updates here.
            case DownloadCallback.Progress.ERROR:

                break;
            case DownloadCallback.Progress.CONNECT_SUCCESS:

                break;
            case DownloadCallback.Progress.GET_INPUT_STREAM_SUCCESS:

                break;
            case DownloadCallback.Progress.PROCESS_INPUT_STREAM_IN_PROGRESS:

                break;
            case DownloadCallback.Progress.PROCESS_INPUT_STREAM_SUCCESS:

                break;
        }
    }

    @Override
    public void finishDownloading() {
        downloading = false;
        if (networkFragment != null) {
            networkFragment.cancelDownload();
        }
    }

    /**
     * Add a point to the database.
     *     See https://github.com/webrtc/apprtc/issues/586 for details
     * @param payload location to add
     */
    void uploadData(Payload payload){
        try {
            HttpsURLConnection.setDefaultSSLSocketFactory(trustCert().getSocketFactory());
        }
        catch (Exception e) {
            Log.d("Flask", e.getMessage());
        }
        //For locally testing on Android
        //String ipv4Address = "10.0.2.2";
        //String portNumber = "8888";
        //String postUrl= "https://"+ipv4Address+":"+portNumber+"/points";
        String postUrl= "http://35.245.208.104/api/points";
        MediaType mediaType = MediaType.parse("application/json; charset=utf-8");
        RequestBody postBody = RequestBody.create(mediaType, payload.getJSON().toString());
        postRequest(postUrl, postBody);
    }


    private SSLContext trustCert() throws CertificateException,IOException, KeyStoreException,
            NoSuchAlgorithmException, KeyManagementException {
        AssetManager assetManager = getAssets();
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Certificate ca = cf.generateCertificate(assetManager.open("COMODORSADomainValidationSecureServerCA.crt"));

        // Create a KeyStore containing our trusted CAs
        String keyStoreType = KeyStore.getDefaultType();
        KeyStore keyStore = KeyStore.getInstance(keyStoreType);
        keyStore.load(null, null);
        keyStore.setCertificateEntry("ca", ca);

        // Create a TrustManager that trusts the CAs in our KeyStore
        String tmfAlgorithm = TrustManagerFactory.getDefaultAlgorithm();
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(tmfAlgorithm);
        tmf.init(keyStore);

        // Create an SSLContext that uses our TrustManager
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, tmf.getTrustManagers(), null);
        return context;
    }

    /**
     * Debug function to test for server responsiveness
     * @return True if the ping was successful, false otherwise
     */
    private boolean pingServer(){
        Log.d("Network", "Pinging server...");
        Runtime runtime = Runtime.getRuntime();
        try
        {
            // IP address for your local computer, from the android emulator
            // see https://developer.android.com/studio/run/emulator-networking#networkaddresses
            Process  mIpAddrProcess = runtime.exec("/system/bin/ping -c 1 10.0.2.2");
            int mExitValue = mIpAddrProcess.waitFor();
            Log.d("Network", "Ping -- mExitValue: " + mExitValue);

            return (mExitValue == 0);
        }
        catch (InterruptedException ignore)
        {
            Log.e("Network", "pingServer(): ", ignore);
        }
        catch (IOException e)
        {
            Log.e("Network", "pingServer(): ", e);
        }
        return false;
    }

    private void startDownload() {
        if (!downloading && networkFragment != null) {
            // Execute the async download.
            networkFragment.startDownload();
            downloading = true;
        }
    }


    /**
     * Very hacky work-around to trust all ssl cerificates.
     * This is very bad practice and should NOT be used in production.
     * TODO: GET SSL CERTS (PROBS VIA CERTBOT) SO WE DON'T HAVE TO RELY ON THIS
     */
    private static final TrustManager[] trustAllCerts = new TrustManager[] {
        new X509TrustManager() {
            @Override
            public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
            }

            @Override
            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                return new java.security.cert.X509Certificate[]{};
            }
        }
    };


    private static final SSLContext trustAllSslContext;
    static {
        try {
            trustAllSslContext = SSLContext.getInstance("SSL");
            trustAllSslContext.init(null, trustAllCerts, new java.security.SecureRandom());
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new RuntimeException(e);
        }
    }
    private static final SSLSocketFactory trustAllSslSocketFactory = trustAllSslContext.getSocketFactory();

    /**
     * TODO: Replace this with another method that is secure
     * This should not be used in production unless you really don't care
     * about the security. Use at your own risk.
     */
    public static OkHttpClient trustAllSslClient(OkHttpClient client) {
        Log.w("Network", "Using the trustAllSslClient is highly discouraged and should not be used in production!");
        OkHttpClient.Builder builder = client.newBuilder();
        builder.sslSocketFactory(trustAllSslSocketFactory, (X509TrustManager)trustAllCerts[0]);
        builder.hostnameVerifier(new HostnameVerifier() {
            @Override
            public boolean verify(String hostname, SSLSession session) {
                return true;
            }
        });
        return builder.build();
    }


    void postRequest(String postUrl, RequestBody postBody) {

        OkHttpClient preconfiguredClient = new OkHttpClient();
        OkHttpClient client = trustAllSslClient(preconfiguredClient);

        Request request = new Request.Builder()
                .url(postUrl)
                .post(postBody)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, final IOException e) {
                // Cancel the post on failure.
                call.cancel();

                // In order to access the TextView inside the UI thread, the code is executed inside runOnUiThread()
                runOnUiThread(() -> {
                    Log.e("Network", "Request Failed", e);
                });
            }

            @Override
            public void onResponse(Call call, final Response response) {
                // In order to access the TextView inside the UI thread, the code is executed inside runOnUiThread()
                runOnUiThread(() -> Log.e("Network", "Request Successful!"));
            }
        });
    }

    //*******************
    // LIFECYCLE
    //*******************

    private void StartLocationUpdates() {
        fusedLocationProviderClient.requestLocationUpdates(mLocationRequest, mLocationCallback, Looper.getMainLooper());
    }


    private void StopLocationUpdates() {
        fusedLocationProviderClient.removeLocationUpdates(mLocationCallback);
    }

    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("Camera Background");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }


    protected void stopBackgroundThread() throws InterruptedException {
        mBackgroundThread.quitSafely();

        mBackgroundThread.join();

        mBackgroundThread = null;
        mBackgroundHandler = null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        StartLocationUpdates();
        mSensorManager.registerListener(mSensorListener, mAccelerometer, SensorManager.SENSOR_DELAY_NORMAL);
        mSensorManager.registerListener(mSensorListener, mMagneticField, SensorManager.SENSOR_DELAY_NORMAL);
        startBackgroundThread();
        if(textureView.isAvailable()){
            try {
                openCamera(textureView.getWidth(), textureView.getHeight());
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }
        else{
            textureView.setSurfaceTextureListener(textureListener);
        }
    }


    @Override
    protected void onPause() {
        try {
            stopBackgroundThread();
            StopLocationUpdates();
            mSensorManager.unregisterListener(mSensorListener, mAccelerometer);
            mSensorManager.unregisterListener(mSensorListener, mMagneticField);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

}
