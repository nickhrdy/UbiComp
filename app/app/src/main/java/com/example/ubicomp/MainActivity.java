package com.example.ubicomp;

import org.json.*;
import android.Manifest;
import android.app.Application;
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
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.MotionEvent;
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
import com.google.ar.core.Anchor;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.exceptions.NotTrackingException;
import com.google.ar.core.exceptions.NotYetAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.sceneform.AnchorNode;
import com.google.ar.sceneform.ArSceneView;
import com.google.ar.sceneform.FrameTime;
import com.google.ar.sceneform.HitTestResult;
import com.google.ar.sceneform.Node;
import com.google.ar.sceneform.Scene;
import com.google.ar.sceneform.math.Quaternion;
import com.google.ar.sceneform.math.Vector3;
import com.google.ar.sceneform.rendering.Renderable;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import javax.net.ssl.SSLContext;
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

public class MainActivity extends FragmentActivity implements DownloadCallback<String> {

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

        /**
         * This is the constructor fo the private class PayLoad
         * @param latitude
         * @param longitude
         * @param azimuth
         * @param text
         */
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

    /**
     * Private class to bundle nodes and locations together
     */
    private class NodeBundle {
        AnchorNode node;
        double latitude;
        double longitude;

        public NodeBundle(AnchorNode node, double latitude, double longitude){
            this.node = node;
            this.latitude = latitude;
            this.longitude = longitude;
        }

        public AnchorNode getNode() {
            return node;
        }

        public double getLatitude() {
            return latitude;
        }

        public double getLongitude() {
            return longitude;
        }
    }

    // View Objects
    Button button, receiveButton;
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

    private enum NODE_TYPE {RECEIVED, CREATE}

    //Camera properties
    private String cameraId;
    CameraDevice cameraDevice;
    CameraCaptureSession cameraCaptureSession;
    CaptureRequest.Builder captureRequestBuilder;
    private Size imageDimensions;
    Handler mBackgroundHandler;
    HandlerThread mBackgroundThread;
    private double fetchTimeSeconds = 5;

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

    //key of points
    private JSONObject pointsKey;

    // Network Connectivity
    //      Keep a reference to the NetworkFragment, which owns the AsyncTask object
    //      that is used to execute network ops.
    private NetworkFragment networkFragment;
    //      Boolean telling us whether a download is in progress, so we don't trigger overlapping
    //      downloads with consecutive button clicks.
    private boolean downloading = false;

    //Ar core
    private ArFragment arFragment;
    private ArrayList<Anchor> anchorList = new ArrayList<>();
    private ArrayList<NodeBundle> nodeList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        pointsKey = new JSONObject();
        text = findViewById(R.id.editText);
        button = findViewById(R.id.button);
//        receiveButton = findViewById(R.id.receiveButton);
        textureView = findViewById(R.id.textureView);
        textureView.setSurfaceTextureListener(textureListener);
        arFragment = (ArFragment) getSupportFragmentManager().findFragmentById(R.id.ux_fragment);
        //arFragment.getPlaneDiscoveryController().hide();
        //arFragment.getPlaneDiscoveryController().setInstructionView(null);
        arFragment.getArSceneView().getPlaneRenderer().setEnabled(true);

        // set-up session so the camera auto-focuses
        Session session = null;
        try {
            session = new Session(this);
            Config config = new Config(session);
            config.setFocusMode(Config.FocusMode.AUTO);
            config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
            config.setCloudAnchorMode(Config.CloudAnchorMode.ENABLED);
            config.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL);
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

        arFragment.getArSceneView().getScene().addOnUpdateListener(new Scene.OnUpdateListener() {
            @Override
            public void onUpdate(FrameTime frameTime) {
                for (NodeBundle n: nodeList) {

                    //get distance between nodes
                    AnchorNode node = n.getNode();


                    float[] results = {-1,-1,-1};
                    Location.distanceBetween(mLocation.getLatitude(), mLocation.getLongitude(), n.getLatitude(), n.getLongitude(), results);
                    float bearing = results[1];
                    float scaledDistance = (results[0] / 10);

                    //calculate relative position of node to camera
                    Vector3 cameraPosition = arFragment.getArSceneView().getScene().getCamera().getWorldPosition();
                    Vector3 newOffset = new Vector3((float)Math.cos(bearing) * scaledDistance, (float)(cameraPosition.y - 0.5), (float)Math.sin(bearing) * scaledDistance);

                    //set node position and rotation
                    Vector3 finalPosition = Vector3.add(newOffset, cameraPosition);
                    Vector3 direction = Vector3.subtract(cameraPosition, finalPosition);
                    Quaternion lookRotation = Quaternion.lookRotation(direction, Vector3.up());

                    //create new anchor and attach node to it
                    float[] translation = {finalPosition.x, finalPosition.y, finalPosition.z};
                    float[] rotation = {lookRotation.x, lookRotation.y, lookRotation.z, lookRotation.w};
                    Pose pose = new Pose(translation, rotation);

                    try {
                        Anchor newAnchor = arFragment.getArSceneView().getSession().createAnchor(pose);
                        node.setAnchor(newAnchor);

                        if (node.getAnchor() != null) {
                            node.getAnchor().detach();
                        }
                    }
                    catch(NotTrackingException e){
                        Log.e("Tracking", "NOT TRACKING", e);
                    }

                    //set scale based on how far away it is
                    node.setWorldScale(new Vector3((float)Math.max(0.5, 1 - scaledDistance), 1, (float)Math.max(0.5, 1 - scaledDistance)));

                    //change color based on distance to current location
                    ViewRenderable vr = (ViewRenderable) node.getRenderable();
                    View v = vr.getView();
                    if(results[0] < 15) {
                        v.setBackgroundColor(Color.GREEN);
                    }
                    else {
                        v.setBackgroundColor(Color.BLUE);
                    }
                }
            }
        });

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
        (new NetworkReceieveTask()).execute();
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
    private View createView(String s, NODE_TYPE type){
        TextView view = new TextView(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        view.setGravity(1);
        view.setText(s);
        view.setPadding(6, 6, 6, 6);
        view.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        if(type == NODE_TYPE.CREATE) {
            view.setBackgroundColor(Color.MAGENTA);
        }
        else if(type == NODE_TYPE.RECEIVED){
            view.setBackgroundColor(Color.BLUE);
        }
        else{
            Log.wtf("Models", "Unknown model type");
            view.setBackgroundColor(Color.BLACK);
        }
        view.setTextColor(Color.WHITE);
        return view;
    }


    private void takePicture() {

        Image image;
        List<HitResult> hits;
        HitResult hit;
        try {
            Frame frame =  arFragment.getArSceneView().getArFrame();
            DisplayMetrics displayMetrics = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
            hits = frame.hitTest(displayMetrics.heightPixels/2,displayMetrics.widthPixels/2); //get hit result based on phone coord

            if(hits.isEmpty() == true){
                Log.e("Image Capture", "No Planes detected!");
                Toast.makeText(this, "No planes detected!", Toast.LENGTH_SHORT).show();
                return;
            }
            hit = hits.get(0);
            image = frame.acquireCameraImage();

            Log.d("Image Capture", String.valueOf(image.getFormat()));
        } catch (NotYetAvailableException e) {
            e.printStackTrace();
            Log.e("Image Capture", "Resource not yet available!");
            Toast.makeText(this, "Resource not ready. Try again in a few seconds!", Toast.LENGTH_SHORT).show();
            return;
        }
        Log.d("Image Capture", "Image capture successful! Running recognition.");
        runTextRecognition(image, hit);
        image.close(); // close image to save resources
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
    private void runTextRecognition(Image image, HitResult hit) {
        FirebaseVisionImage firebaseVisionImage = FirebaseVisionImage.fromMediaImage(image, ORIENTATIONS.get(90));
        FirebaseVisionTextRecognizer recognizer = FirebaseVision.getInstance()
                .getOnDeviceTextRecognizer();
        recognizer.processImage(firebaseVisionImage)
                .addOnSuccessListener(
                        texts -> processTextRecognitionResult(texts, hit));
    }

    /**
     * Processes Firebase text detection results, POSTs them to the database, and create a node in the world
     * @param texts Firebase processing results
     * @param hitResult HitResult from frame being processed
     */
    private void processTextRecognitionResult(FirebaseVisionText texts, HitResult hitResult) {
        List<FirebaseVisionText.TextBlock> blocks = texts.getTextBlocks();
        String blockText = null;
        for (int i = 0; i < blocks.size(); i++) {
            // Can't ge the confidence because we're not using cloud
            Log.d("Firebase", "word! " + blocks.get(i).getText());
            text.append("\n" + blocks.get(i).getText());
            blockText = blocks.get(i).getText();


            //calculate position of text

            double distance = hitResult.getDistance() / 1000; //distance in km
            float currAzimuth = (float)((azimuth + 90) * Math.PI / 180);

            final double earthRadius = 6378.137;
            double lat1 = ((mLocation.getLatitude() + 90) * Math.PI / 180);
            double lon1 = ((mLocation.getLongitude() + 90) * Math.PI / 180);
            double lat2 = (Math.asin( Math.sin(lat1)*Math.cos(distance/earthRadius) +
                    Math.cos(lat1)*Math.sin(distance/earthRadius)*Math.cos(currAzimuth) ));
            double lon2 = (lon1 + Math.atan2(Math.sin(currAzimuth)*Math.sin(distance/earthRadius)*Math.cos(lat1),
                    Math.cos(distance/earthRadius)-Math.sin(lat1)*Math.sin(lat2)));

            double latitude = (lat2 * 180 / Math.PI);
            double longitude = (lon2 * 180 / Math.PI);

            payload = new Payload(90 - latitude, -90 + longitude, currAzimuth, blockText);
            Log.d("Firebase", "Done processing!");

            new NetworkTask().execute(); // Upload the data to the database
            break;

            /* NOTE: Leave this in case we need it again!
            List<FirebaseVisionText.Line> lines = blocks.get(i).getLines();
            for (int j = 0; j < lines.size(); j++) {
                List<FirebaseVisionText.Element> elements = lines.get(j).getElements();
                for (int k = 0; k < elements.size(); k++) {
                    //Do something
                }
            }*/
        }

        // Quit and notify if text wasn't found
        if(blockText == null){
            Log.w("Firebase", "Text not found!");
            Toast.makeText(this, "No text found!", Toast.LENGTH_SHORT).show();
            return;
        }

        // Build the word as a renderable
        ViewRenderable.builder()
                .setView(this, createView(blockText, NODE_TYPE.CREATE))
                .build()
                .thenAccept(renderable -> addObjectToScene(renderable, hitResult));
    }


    /**
     * Adds a model to the scene
     * @param model model to render
     * @param hit Hit result to mount model to
     */
    private void addObjectToScene(Renderable model, HitResult hit){


        //set node position and rotation
        Pose p = hit.getHitPose();


        //create an anchor from the corresponding hit result and attach the word.


        Vector3 direction = Vector3.subtract(arFragment.getArSceneView().getScene().getCamera().getWorldPosition(), new Vector3(hit.getHitPose().tx(), hit.getHitPose().ty(), hit.getHitPose().tz() ));
        Quaternion lookRotation = Quaternion.lookRotation(direction, Vector3.up());

        float[] translation = {p.tx(), p.ty(), p.tz()};
        float[] rotation = {lookRotation.x, lookRotation.y, lookRotation.z, lookRotation.w};

        p = new Pose(translation, rotation);
        Anchor anchor = arFragment.getArSceneView().getSession().createAnchor(p);
        anchorList.add(anchor);
        AnchorNode anchorNode = new AnchorNode(anchor);
        anchorNode.setRenderable(model);
        anchorNode.setParent(arFragment.getArSceneView().getScene());
        anchorNode.setOnTouchListener(new Node.OnTouchListener() {
            @Override
            public boolean onTouch(HitTestResult hitTestResult, MotionEvent motionEvent) {
                hitTestResult.getNode().setEnabled(false);
                return true;
            }
        });

        //Debug payload
        Log.d("Models", "Adding model to scene from scan");
        Log.d("Models", String.format("Camera world position: %s", arFragment.getArSceneView().getScene().getCamera().getWorldPosition().toString()));
        Log.d("Models", String.format("Anchor isTracking: %s", String.valueOf(anchorNode.isTracking())));
        Log.d("Models", String.format("Anchor pose: %s", anchorNode.getAnchor().getPose().toString()));
    }

    /**
     * This method helps separate anchor points so renderable boxes are not placed on top of each other.
     */
//    public Anchor nudgeAnchor(Anchor anchor) {
//
//        // find anchors close to each other
//        for(int i = 0; i < anchorList.size(); i++){
//            // give tolerance to translation
//            if(!(anchor.getCloudAnchorId().equals(anchorList.get(i))) && (anchor.getPose() anchorList.get(i)) {
//                // do some moving of anchor -- specifically the anchor we are about to place
//                Vector3 blah = new Vector3(anchor.getPose().tx(), anchor.getPose().ty(), anchor.getPose().tz());
//                Log.d("Pre-Blah scale vector", blah.toString());
//                blah.scaled((float) 1.2);
//                Log.d("Post-Blah scale vector", blah.toString());
//
//                float[] temp = {blah.x, blah.y, blah.z};
//                float[] tempTwo = {0,0,0,0};
//
//                Anchor newAnchor = arFragment.getArSceneView().getSession().createAnchor(new Pose(temp, tempTwo));
//
//                // make sure all resources are closed
//                anchorList.remove(anchor);
//                anchorList.add(newAnchor);
//
//                return newAnchor;
//            } else {
//                //skip
//            }
//        }
//
//        return anchor;
//    }

    /**
     * Adds a model to the scene at the given latitude and longitude
     * @param model
     * @param latitude
     * @param longitude
     */
    private void addObjectToScene(Renderable model, double latitude, double longitude, double bearing){
        //TODO: ADD BEARING INTO POSITION CALCULATION TO GET CORRECT ROTATION
        double cameraLatitude = mLocation.getLatitude();
        double cameraLongitude = mLocation.getLongitude();
        Vector3 toObject = new Vector3((float)(cameraLatitude - latitude), 0, (float)(longitude - cameraLongitude));
        Vector3 cameraPosition = arFragment.getArSceneView().getScene().getCamera().getWorldPosition();
        //Vector3 nodePosition = Vector3.add(cameraPosition, toObject.normalized().scaled((float) calculateHaversine(latitude, longitude, cameraLatitude, cameraLongitude)));
        Vector3 nodePosition = toObject.normalized();
        //attemptCalc(latitude, longitude, cameraLatitude, cameraLongitude, calculateHaversine(latitude, longitude, cameraLatitude, cameraLongitude));
        //Vector3 nodePosition = Vector3.add(cameraPosition, toObject);

        //Debug payload
        Log.d("Models", "Adding model to scene based on lat/long");
        Log.d("Models", "Camera world position: "  + cameraPosition.toString());
        Log.d("Models", "Vector from camera to object: " + toObject.toString());
        Log.d("Models", "Normalized cam -> obj vector: " + toObject.normalized().toString());
        //Log.d("Models", String.format("Corrected cam -> obj vector: %s", nodePosition.toString()));

        float[] translation = {nodePosition.x, nodePosition.y, nodePosition.z};
        float[] rotation = {0, 0, 0, 0};

        Pose pose = new Pose(translation, rotation);
        Anchor anchor = arFragment.getArSceneView().getSession().createAnchor(pose);
        anchorList.add(anchor);

        //check for other anchor points in the same 3D area
//        anchor = nudgeAnchor(anchor);

        //AnchorNode anchorNode = new AnchorNode(anchor);
        AnchorNode anchorNode = new AnchorNode();

        nodeList.add(new NodeBundle(anchorNode, latitude, longitude));
        anchorNode.setRenderable(model);
        anchorNode.setParent(arFragment.getArSceneView().getScene());
        anchorNode.setOnTouchListener(new Node.OnTouchListener() {
            @Override
            public boolean onTouch(HitTestResult hitTestResult, MotionEvent motionEvent) {
                hitTestResult.getNode().setEnabled(false);
                return true;
            }
        });
    }

    public double calculateHaversine(double lat1, double long1, double lat2, double long2){
        final double earthRadius = 6378.137;
        double dLat = lat2 * Math.PI / 180 - lat1 * Math.PI / 180;
        double dLong = long2 * Math.PI / 180 - long1 * Math.PI / 180;
        double a = Math.sin(dLat/2) * Math.sin(dLat/2) +
                Math.cos(lat1 * Math.PI / 180) * Math.cos(lat2 * Math.PI / 180) *
                        Math.sin(dLong/2) * Math.sin(dLong/2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1-a));
        double d = earthRadius * c;
        return d * 1000;
    }


    public void attemptCalc(double lat1, double long1, double lat2, double long2, double distance){

        double theta = Math.atan( (long1 - long2) / (lat1 - lat2));
        Vector3 v = new Vector3( (float)(distance * Math.cos(theta)), (float)(distance* Math.sin(theta)), 0);
        Log.d("Models", String.format("Attempted calc: %s", v.toString()));
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

    private class NetworkReceieveTask extends AsyncTask<URL, Integer, JSONObject> {
        @Override
        protected JSONObject doInBackground(URL... urls){
            JSONObject results = receiveData();
            try {
                Thread.sleep((long)(1000 * fetchTimeSeconds));
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            NetworkReceieveTask task2 = new NetworkReceieveTask();
            task2.execute();
            return results;
        }

        @Override
        protected  void onPostExecute(JSONObject json){
            placeNodes(json);
        }
    }

    private final static int INTERVAL = 1000 * 60 * 2; //2 minutes


    @Override
    public void updateFromDownload(String result) {

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
     * @param payload Blob of information to deliver
     */
    void uploadData(Payload payload){

        //For locally testing on Android
        //String ipv4Address = "10.0.2.2";
        //String portNumber = "8888";
        //String postUrl= "https://"+ipv4Address+":"+portNumber+"/points";
        String postUrl= "http://35.245.208.104/api/points";
        MediaType mediaType = MediaType.parse("application/json; charset=utf-8");
        RequestBody postBody = RequestBody.create(mediaType, payload.getJSON().toString());
        postRequest(postUrl, postBody);
    }

    /**
     * Receive points from the Flask server
     * @return Dictionary of objects received from the server
     */
    JSONObject receiveData(){
        OkHttpClient preconfiguredClient = new OkHttpClient();
        OkHttpClient client = trustAllSslClient(preconfiguredClient);
        JSONObject json = null;
        if (mLocation != null) {
            Request request = new Request.Builder()
                    .url(String.format("http://35.245.208.104/api/nearme?latitude=%s&longitude=%s", String.valueOf(mLocation.getLatitude()), String.valueOf(mLocation.getLongitude())))
                    .build();

            try {
                //parse the result
                Response response = client.newCall(request).execute();
                String result = response.body().string();
                json = new JSONObject(result);
                Log.d("Flask", String.format("Fetch success! Objects: %s", json.length()));
            } catch (IOException e) {
                Log.e("Flask", "Error reading response", e);
            } catch (JSONException e) {
                Log.e("Flask", "Error creating JSON object", e);
            } finally {
                return json;
            }
        }
        return null;
    }

    /**
     * Places node into the scene.
     * @param json JSON dictionary of text items to be put into the scene. The application
     *             keeps track of items it's currently showing to avoid showing duplicates.
     */
    private void placeNodes(JSONObject json){
        if (json == null) {
            Log.d("Flask", "No results to return yet!!!");
            return;
        }
        Iterator<String> iterator = json.keys();
        String key;
        JSONObject payload;
        do{
            try {
                key = iterator.next();
                if(pointsKey.has(key) == false) { //Don't add keys that already exist
                    pointsKey.put(key, 0); //Add the record to the internal list
                    Log.d("Flask", "key:" + key);
                    payload = json.getJSONObject(key);

                    String text = payload.getString("text");
                    final double latitude = payload.getDouble("latitude");
                    final double longitude = payload.getDouble("longitude");
                    final double bearing = payload.getDouble("azimuth");
                    StringBuilder sb = (new StringBuilder()).append("latitude: ").append(latitude)
                            .append(" logitude: ").append(longitude)
                            .append(" azimuth: ").append(azimuth);
                    Log.d("PlaceNodes", sb.toString());
                    ViewRenderable.builder()
                            .setView(this, createView(text, NODE_TYPE.RECEIVED))
                            .build()
                            .thenAccept(renderable -> addObjectToScene(renderable, latitude, longitude, bearing));

                }
            }
            catch(JSONException e){
                Log.e("Flask", "Error trying to parse node JSON", e);
            }
        }while(iterator.hasNext());
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

    /**
     *
     */
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
        builder.hostnameVerifier((hostname, session) -> true);
        return builder.build();
    }

    /**
     *
     * @param postUrl
     * @param postBody
     */
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
                try {
                    // Get assigned key from response body
                    final String message = response.body().string();

                    // Add the assigned key to the database
                    if(pointsKey.has(message) == false){
                        pointsKey.put(message, 0);
                    }

                    // In order to access the TextView inside the UI thread, the code is executed inside runOnUiThread()
                    runOnUiThread(() -> Log.d("Network", String.format("Posted object was assigned key: %s", message)));

                } catch (IOException e){
                    Log.e("Network", "Error reading response body", e);
                } catch(JSONException e) {
                    Log.e("Network", "Error adding assigned key to list", e);
                }
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
