/*
 * Copyright 2016 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package terry.com.tfcamerasupervisor.CameraContents;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Trace;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import terry.com.tfcamerasupervisor.JTUtil;
import terry.com.tfcamerasupervisor.NextActivity;
import terry.com.tfcamerasupervisor.R;
import terry.com.tfcamerasupervisor.TFCameraSupervisorConfig;
import terry.com.tfcamerasupervisor.util.Camera2Util;
import terry.com.tfcamerasupervisor.util.ImageUtils;
import terry.com.tfcamerasupervisor.util.Logger;

public class CameraActivity extends Activity {

    private static final Logger LOGGER = new Logger();
    /**
     * The camera preview size will be chosen to be the smallest frame by pixel size capable of
     * containing a DESIRED_SIZE x DESIRED_SIZE square.
     */
    private static final int MINIMUM_PREVIEW_SIZE = 320;

    /**
     * Conversion from screen rotation to JPEG orientation.
     */
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    private static final String FRAGMENT_DIALOG = "dialog";

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    /**
     * {@link android.view.TextureView.SurfaceTextureListener} handles several lifecycle events on a
     * {@link TextureView}.
     */
    private final TextureView.SurfaceTextureListener surfaceTextureListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(
                        final SurfaceTexture texture, final int width, final int height) {
                    CameraActivity.this.width = width;
                    CameraActivity.this.height = height;
                    openCamera(width, height);
                }

                @Override
                public void onSurfaceTextureSizeChanged(
                        final SurfaceTexture texture, final int width, final int height) {
                    configureTransform(width, height);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(final SurfaceTexture texture) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(final SurfaceTexture texture) {
                }
            };

    /**
     * ID of the current {@link CameraDevice}.
     */
    private String cameraId;
    private String backCameraId;//后置摄像头ID
    private String frontCameraId;//前置摄像头ID

    private TextureView textureView;
    private ImageView ivSwitchCamera;//切换前后摄像头
    private ImageView ivLightOn;//开关闪光灯
    private ImageView ivClose;//关闭该Activity

    /**
     * A {@link CameraCaptureSession } for camera preview.
     */
    private CameraCaptureSession captureSession;

    /**
     * A reference to the opened {@link CameraDevice}.
     */
    private CameraDevice cameraDevice;

    /**
     * The rotation in degrees of the camera sensor from the display.
     */
    private Integer sensorOrientation;

    /**
     * The {@link android.util.Size} of camera preview.
     */
    private Size previewSize;

    int width;
    int height;
    private boolean isCameraFront = false;//当前是否是前置摄像头
    private boolean isLightOn = false;//当前闪光灯是否开启

    /**
     * {@link android.hardware.camera2.CameraDevice.StateCallback}
     * is called when {@link CameraDevice} changes its state.
     */
    private final CameraDevice.StateCallback stateCallback =
            new CameraDevice.StateCallback() {
                @Override
                public void onOpened(final CameraDevice cd) {
                    // This method is called when the camera is opened.  We start camera preview here.
                    cameraOpenCloseLock.release();
                    cameraDevice = cd;
                    createCameraPreviewSession();
                }

                @Override
                public void onDisconnected(final CameraDevice cd) {
                    cameraOpenCloseLock.release();
                    cd.close();
                    cameraDevice = null;
                }

                @Override
                public void onError(final CameraDevice cd, final int error) {
                    cameraOpenCloseLock.release();
                    cd.close();
                    cameraDevice = null;
                    finish();
                }
            };

    /**
     * An additional thread for running tasks that shouldn't block the UI.
     */
    private HandlerThread backgroundThread;

    /**
     * A {@link Handler} for running tasks in the background.
     */
    private Handler backgroundHandler;

    public Handler UIChangeHandler;

    public HandlerThread countdownThread;
    public Handler countdownHandler;

    private Handler handler;
    private HandlerThread handlerThread;

    /**
     * An {@link ImageReader} that handles preview frame capture.
     */
    private ImageReader previewReader;

    /**
     * {@link android.hardware.camera2.CaptureRequest.Builder} for the camera preview
     */
    private CaptureRequest.Builder previewRequestBuilder;

    /**
     * {@link CaptureRequest} generated by {@link #previewRequestBuilder}
     */
    private CaptureRequest previewRequest;

    /**
     * A {@link Semaphore} to prevent the app from exiting before closing the camera.
     */
    private final Semaphore cameraOpenCloseLock = new Semaphore(1);

    /**
     * A {@link OnImageAvailableListener} to receive frames as they are available.
     */
    private final OnImageAvailableListener imageListener = new OnImageAvailableListener() {
        Image nextImage;
        /**
         * Callback for Camera2 API
         */
        @Override
        public void onImageAvailable(final ImageReader reader) {

            //We need wait until we have some size from onPreviewSizeChosen
            if (previewWidth == 0 || previewHeight == 0) {
                return;
            }
            if (rgbBytes == null) {
                rgbBytes = new int[previewWidth * previewHeight];
            }
            try {
                final Image image = reader.acquireNextImage();//reader.acquireLatestImage();

                if (image == null) {
                    return;
                }

                if (isProcessingFrame) {
                    image.close();
                    return;
                }
                isProcessingFrame = true;
                Trace.beginSection("imageAvailable");
//      final Plane[] planes = image.getPlanes();
//      fillBytes(planes, yuvBytes);
//      yRowStride = planes[0].getRowStride();
//      final int uvRowStride = planes[1].getRowStride();
//      final int uvPixelStride = planes[1].getPixelStride();
                nextImage = image;

                imageConverter =
                        new Runnable() {
                            @Override
                            public void run() {
                                //converting to JPEG
                                ByteBuffer buffer = nextImage.getPlanes()[0].getBuffer();
                                byte[] data = new byte[buffer.remaining()];
                                buffer.get(data);
                                rgbFrameBitmap = BitmapFactory.decodeByteArray(data,0,data.length);

//              ImageUtils.convertYUV420ToARGB8888(
//                  yuvBytes[0],
//                  yuvBytes[1],
//                  yuvBytes[2],
//                  previewWidth,
//                  previewHeight,
//                  yRowStride,
//                  uvRowStride,
//                  uvPixelStride,
//                  rgbBytes);
                            }
                        };

                postInferenceCallback =
                        new Runnable() {
                            @Override
                            public void run() {
                                image.close();
                                isProcessingFrame = false;
                            }
                        };

                processImage();
            } catch (final Exception e) {
                LOGGER.e(e, "Exception!");
                Trace.endSection();
                return;
            }
            Trace.endSection();
        }
    };



    private static final int PERMISSIONS_REQUEST = 1;

    private static final String PERMISSION_CAMERA = Manifest.permission.CAMERA;
    private static final String PERMISSION_STORAGE = Manifest.permission.WRITE_EXTERNAL_STORAGE;

    private boolean debug = false;

    private boolean useCamera2API;
    private boolean isProcessingFrame = false;
    private byte[][] yuvBytes = new byte[3][];
    private int[] rgbBytes = null;
    private int yRowStride;

    protected int previewWidth = 0;
    protected int previewHeight = 0;

    private Runnable postInferenceCallback;
    private Runnable imageConverter;

    private TextView resultsView;
    private TextView countdownView;

    private Bitmap rgbFrameBitmap = null;
    private Bitmap croppedBitmap = null;
    private Bitmap cropCopyBitmap = null;

    private long nowCountdownTimeMillis;
    private long startCountdowTimeMillis;

    //private Classifier classifier;
    private Matrix frameToCropTransform;
    private Matrix cropToFrameTransform;

    protected Size getDesiredPreviewFrameSize() {
        WindowManager manager = this.getWindowManager();
        DisplayMetrics outMetrics = new DisplayMetrics();
        manager.getDefaultDisplay().getMetrics(outMetrics);

        return new Size(outMetrics.widthPixels/2,outMetrics.heightPixels/2);
        //return DESIRED_PREVIEW_SIZE;
    }

    private static final float TEXT_SIZE_DIP = 10;


    public void onPreviewSizeChosen(final Size size, final int rotation) {
//    classifier =
//        TensorFlowImageClassifier.create(
//            getAssets(),
//            MODEL_FILE,
//            LABEL_FILE,
//            INPUT_SIZE,
//            IMAGE_MEAN,
//            IMAGE_STD,
//            INPUT_NAME,
//            OUTPUT_NAME);

        previewWidth = size.getWidth();
        previewHeight = size.getHeight();

        sensorOrientation = rotation - getScreenOrientation();
        LOGGER.i("Camera orientation relative to screen canvas: %d", sensorOrientation);

        LOGGER.i("Initializing at size %dx%d", previewWidth, previewHeight);
        rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Config.ARGB_8888);
        croppedBitmap = Bitmap.createBitmap(
                TFCameraSupervisorConfig.INPUT_WIDTH,
                TFCameraSupervisorConfig.INPUT_HEIGHT,
                Config.ARGB_8888);

        frameToCropTransform = ImageUtils.getTransformationMatrix(//得到保存图片的旋转矩阵
                previewWidth, previewHeight,
                TFCameraSupervisorConfig.INPUT_WIDTH, TFCameraSupervisorConfig.INPUT_HEIGHT,
                sensorOrientation, TFCameraSupervisorConfig.MAINTAIN_ASPECT);

        cropToFrameTransform = new Matrix();
        frameToCropTransform.invert(cropToFrameTransform);
    }

    protected void processImage() {//摄像头捕捉到画面后处理图片
        runInBackground(
                new Runnable() {
                    @Override
                    public void run() {
                        generateRgbFrameBitmap();//将Image对象转化为Bitmap
                        final Canvas canvas = new Canvas(croppedBitmap);
                        canvas.drawBitmap(rgbFrameBitmap, frameToCropTransform, null);//将Bitmap旋转

                        if (TFCameraSupervisorConfig.SAVE_PREVIEW_BITMAP) {//保存预览图片
                            JTUtil.JT(croppedBitmap);

                            if(TFCameraSupervisorConfig.lastSaveTimeStamp==-1){
                                TFCameraSupervisorConfig.lastSaveTimeStamp = System.currentTimeMillis();
                                ImageUtils.saveBitmap(croppedBitmap);
                            }else{
                                long nowSaveTimeStap = System.currentTimeMillis();
                                if(nowSaveTimeStap-TFCameraSupervisorConfig.lastSaveTimeStamp >
                                        TFCameraSupervisorConfig.saveInterval){
                                    ImageUtils.saveBitmap(croppedBitmap);
                                }
                            }
                        }

                        //在此运行神经网络的程序

                        cropCopyBitmap = Bitmap.createBitmap(croppedBitmap);
                        if (resultsView == null) {
                            resultsView =findViewById(R.id.results);
                        }
                        if(UIChangeHandler!=null)
                            UIChangeHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    String msg=String.format("%.4f",JTUtil.df);
                                    resultsView.setText(msg);//显示结果
                                }
                            });
                        //resultsView.setResults(results);
                        //requestRender();
                        readyForNextImage();//请求下一张摄像机捕捉的画面
                    }
                });
    }

    @Override
    public synchronized void onStart() {
        LOGGER.d("onStart " + this);
        super.onStart();
    }

    @Override
    public synchronized void onResume() {
        super.onResume();
        LOGGER.d("onResume " + this);
        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        startBackgroundThread();

        if (textureView.isAvailable()) {
            openCamera(textureView.getWidth(), textureView.getHeight());
        } else {
            textureView.setSurfaceTextureListener(surfaceTextureListener);
        }

        if(countdownView==null)
            countdownView = findViewById(R.id.time_countdown);

        countdownHandler.post(new Runnable() {
            @Override
            public void run() {//倒计时跳转线程
                startCountdowTimeMillis = System.currentTimeMillis();

                do{
                    nowCountdownTimeMillis = System.currentTimeMillis();
                    final long countdowTime = TFCameraSupervisorConfig.countdown_time_ms - (nowCountdownTimeMillis-startCountdowTimeMillis);
                    if(UIChangeHandler==null)return;
                    UIChangeHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            countdownView.setText(String.format("%02d:%02d",countdowTime/1000/60%60,countdowTime/1000%60));
                        }
                    });
                    try {
                        Thread.sleep(1000);
                    }catch (Exception e){
                        e.printStackTrace();
                    }
                }while(nowCountdownTimeMillis-startCountdowTimeMillis<TFCameraSupervisorConfig.countdown_time_ms);

                UIChangeHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        closeCamera();//关闭相机

                        Intent intent = new Intent(CameraActivity.this,NextActivity.class);
                        intent.putExtra("msg","传递成功");  // 传递参数，根据需要填写
                        //ClassifierActivity.this.finish();
                        startActivity(intent);//跳转到下一个Activity
                    }
                });

            }
        });
    }

    @Override
    public synchronized void onPause() {
        LOGGER.d("onPause " + this);

        stopBackgroundThread();

        super.onPause();
    }

    @Override
    public synchronized void onStop() {
        LOGGER.d("onStop " + this);
        super.onStop();
    }


    @Override
    public synchronized void onDestroy() {
        LOGGER.d("onDestroy " + this);
        super.onDestroy();
//        if(UIChangeHandler!=null){
//            UIChangeHandler.removeCallbacks(null);
//            UIChangeHandler = null;
//        }

    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        LOGGER.d("onCreate " + this);
        super.onCreate(null);
        //getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        if(hasPermission()){
            setContentView(R.layout.camera_connection_fragment);
        }else{
            requestPermission();
            setContentView(R.layout.camera_connection_fragment);
        }
        //cameraId = chooseCamera();

        textureView = findViewById(R.id.texture);
        ivSwitchCamera = findViewById(R.id.iv_switchCamera);
        ivLightOn = findViewById(R.id.iv_lightOn);
        ivClose = findViewById(R.id.iv_close);

        ivSwitchCamera.setOnClickListener(clickListener);
        ivLightOn.setOnClickListener(clickListener);
        ivClose.setOnClickListener(clickListener);

    }


    View.OnClickListener clickListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            int i = view.getId();
            if (i == R.id.iv_switchCamera) {
                //切换摄像头
                switchCamera();
            } else if (i == R.id.iv_lightOn) {
                //开启关闭闪光灯
                openLight();
            } else if (i == R.id.iv_close) {
                //关闭Activity
                finish();
            }
        }
    };

    /**
     * ***************************************打开和关闭闪光灯****************************************
     */
    public void openLight() {
        if (isLightOn) {
            ivLightOn.setSelected(false);
            isLightOn = false;
            previewRequestBuilder.set(CaptureRequest.FLASH_MODE,
                    CaptureRequest.FLASH_MODE_OFF);
        } else {
            ivLightOn.setSelected(true);
            isLightOn = true;
            previewRequestBuilder.set(CaptureRequest.FLASH_MODE,
                    CaptureRequest.FLASH_MODE_TORCH);
        }

        try {
            if (captureSession != null)
                captureSession.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * **********************************************切换摄像头**************************************
     */
    public void switchCamera() {
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }

        if (isCameraFront) {
            isCameraFront = false;
            //setupCamera(width, height);
            openCamera(width, height);
        } else {
            isCameraFront = true;
            //setupCamera(width, height);
            openCamera(width, height);
        }
    }

    /**
     * Sets up member variables related to camera.
     */
    private void setUpCameraOutputs() {
        final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            backCameraId = manager.getCameraIdList()[0];//后置摄像头ID
            frontCameraId = manager.getCameraIdList()[1];//前置摄像头ID

            if (isCameraFront) {
                cameraId = frontCameraId;
            } else {
                cameraId = backCameraId;
            }


            final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

            final StreamConfigurationMap map =
                    characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);


            //得到传感器的方位
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

            // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
            // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
            // garbage capture data.

            Size inputSize = getDesiredPreviewFrameSize();
            //选择合适的预览尺寸
            previewSize = Camera2Util.getMinPreSize(map.getOutputSizes(ImageFormat.YUV_420_888), inputSize.getWidth(), inputSize.getHeight(), TFCameraSupervisorConfig.PREVIEW_MAX_HEIGHT);
            //Camera2Util.getMinPreSize(map.getOutputSizes(SurfaceTexture.class), width, height, TFCameraSupervisorConfig.PREVIEW_MAX_HEIGHT);
//              chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class),
//                      inputSize.getWidth(),
//                      inputSize.getHeight());

            // We fit the aspect ratio of TextureView to the size of preview we picked.
//      final int orientation = getResources().getConfiguration().orientation;
//      if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
//        textureView.setAspectRatio(previewSize.getWidth(), previewSize.getHeight());
//      } else {
//        textureView.setAspectRatio(previewSize.getHeight(), previewSize.getWidth());
//      }
        } catch (final CameraAccessException e) {
            LOGGER.e(e, "Exception!");
        } catch (final NullPointerException e) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            // TODO(andrewharp): abstract ErrorDialog/RuntimeException handling out into new method and
            // reuse throughout app.
            throw new RuntimeException(getString(R.string.camera_error));
        }

        onPreviewSizeChosen(previewSize, sensorOrientation);
    }

    /**
     * Opens the camera specified by {@link #cameraId}.
     */
    private void openCamera(final int width, final int height) {
        setUpCameraOutputs();
        configureTransform(width, height);
        final CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            if (checkSelfPermission( Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            manager.openCamera(cameraId, stateCallback, backgroundHandler);
        } catch (final CameraAccessException e) {
            LOGGER.e(e, "Exception!");
        } catch (final InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
    }

    /**
     * Closes the current {@link CameraDevice}.
     */
    private void closeCamera() {
        try {
            cameraOpenCloseLock.acquire();
            if (null != captureSession) {
                captureSession.close();
                captureSession = null;
            }
            if (null != cameraDevice) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (null != previewReader) {
                previewReader.close();
                previewReader = null;
            }
        } catch (final InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            cameraOpenCloseLock.release();
        }
    }

    /**
     * Starts a background thread and its {@link Handler}.
     */
    private void startBackgroundThread() {
        handlerThread = new HandlerThread("inference");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());

        backgroundThread = new HandlerThread("ImageListener");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());

        UIChangeHandler = new Handler();

        countdownThread = new HandlerThread("countdown");
        countdownThread.start();
        countdownHandler = new Handler(countdownThread.getLooper());
    }

    /**
     * Stops the background thread and its {@link Handler}.
     */
    private void stopBackgroundThread() {
        handlerThread.quitSafely();
        try {
            handlerThread.join();
            handlerThread = null;
            handler = null;
        } catch (final InterruptedException e) {
            LOGGER.e(e, "Exception!");
        }

        backgroundThread.quitSafely();
        try {
            backgroundThread.join();
            backgroundThread = null;
            backgroundHandler = null;
        } catch (final InterruptedException e) {
            LOGGER.e(e, "Exception!");
        }

        //UIChangeHandler.removeCallbacks(null);
        UIChangeHandler = null;

        countdownThread.quitSafely();
        try {
            countdownThread.join();
            countdownThread = null;
            //countdownHandler.removeCallbacks(null);
            countdownHandler = null;
        } catch (final InterruptedException e) {
            LOGGER.e(e, "Exception!");
        }
    }

    private final CameraCaptureSession.CaptureCallback captureCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureProgressed(
                        final CameraCaptureSession session,
                        final CaptureRequest request,
                        final CaptureResult partialResult) {}

                @Override
                public void onCaptureCompleted(
                        final CameraCaptureSession session,
                        final CaptureRequest request,
                        final TotalCaptureResult result) {}
            };

    /**
     * Creates a new {@link CameraCaptureSession} for camera preview.
     */
    private void createCameraPreviewSession() {
        try {
            final SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;

            // We configure the size of default buffer to be the size of camera preview we want.
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());

            // This is the output Surface we need to start preview.
            final Surface surface = new Surface(texture);

            // We set up a CaptureRequest.Builder with the output Surface.
            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);

            LOGGER.i("Opening camera preview: " + previewSize.getWidth() + "x" + previewSize.getHeight());

            // Create the reader for the preview frames.
            previewReader =
                    ImageReader.newInstance(
                            previewSize.getWidth(), previewSize.getHeight(), ImageFormat.JPEG, 2);


            previewReader.setOnImageAvailableListener(imageListener, backgroundHandler);
            previewRequestBuilder.addTarget(previewReader.getSurface());

            // Here, we create a CameraCaptureSession for camera preview.
            cameraDevice.createCaptureSession(
                    Arrays.asList(surface, previewReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(final CameraCaptureSession cameraCaptureSession) {
                            // The camera is already closed
                            if (null == cameraDevice) {
                                return;
                            }

                            // When the session is ready, we start displaying the preview.
                            captureSession = cameraCaptureSession;
                            try {
                                // Auto focus should be continuous for camera preview.
                                previewRequestBuilder.set(
                                        CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                // Flash is automatically enabled when necessary.
                                previewRequestBuilder.set(
                                        CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);

                                // Finally, we start displaying the camera preview.
                                previewRequest = previewRequestBuilder.build();
                                captureSession.setRepeatingRequest(
                                        previewRequest, captureCallback, backgroundHandler);
                            } catch (final CameraAccessException e) {
                                LOGGER.e(e, "Exception!");
                            }
                        }

                        @Override
                        public void onConfigureFailed(final CameraCaptureSession cameraCaptureSession) {
                            showToast("Failed");
                        }
                    },
                    null);
        } catch (final CameraAccessException e) {
            LOGGER.e(e, "Exception!");
        }
    }

    /**
     * Shows a {@link Toast} on the UI thread.
     *
     * @param text The message to show
     */
    private void showToast(final String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    /**
     * Configures the necessary {@link android.graphics.Matrix} transformation to `mTextureView`.
     * This method should be called after the camera preview size is determined in
     * setUpCameraOutputs and also the size of `mTextureView` is fixed.
     *
     * @param viewWidth  The width of `mTextureView`
     * @param viewHeight The height of `mTextureView`
     */
    private void configureTransform(final int viewWidth, final int viewHeight) {
        if (null == textureView || null == previewSize ) {
            return;
        }
        final int rotation = getWindowManager().getDefaultDisplay().getRotation();
        final Matrix matrix = new Matrix();
        final RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        final RectF bufferRect = new RectF(0, 0, previewSize.getWidth(), previewSize.getHeight());
        final float centerX = viewRect.centerX();
        final float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            final float scale =
                    Math.max(
                            (float) viewHeight / previewSize.getHeight(),
                            (float) viewWidth / previewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        textureView.setTransform(matrix);
    }

    protected void generateRgbFrameBitmap(){
        imageConverter.run();
    }


    protected synchronized void runInBackground(final Runnable r) {
        if (handler != null) {
            handler.post(r);
        }
    }

    @Override
    public void onRequestPermissionsResult(
            final int requestCode, final String[] permissions, final int[] grantResults) {
        if (requestCode == PERMISSIONS_REQUEST) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    && grantResults[1] == PackageManager.PERMISSION_GRANTED) {
                //setFragment();
            } else {
                requestPermission();
            }
        }
    }

    private boolean hasPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return checkSelfPermission(PERMISSION_CAMERA) == PackageManager.PERMISSION_GRANTED &&
                    checkSelfPermission(PERMISSION_STORAGE) == PackageManager.PERMISSION_GRANTED;
        } else {
            return true;
        }
    }

    private void requestPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (shouldShowRequestPermissionRationale(PERMISSION_CAMERA) ||
                    shouldShowRequestPermissionRationale(PERMISSION_STORAGE)) {
                Toast.makeText(this,
                        "Camera AND storage permission are required for this demo", Toast.LENGTH_LONG).show();
            }
            requestPermissions(new String[] {PERMISSION_CAMERA, PERMISSION_STORAGE}, PERMISSIONS_REQUEST);
        }
    }

    // Returns true if the device supports the required hardware level, or better.
    private boolean isHardwareLevelSupported(
            CameraCharacteristics characteristics, int requiredLevel) {
        int deviceLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        if (deviceLevel == CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY) {
            return requiredLevel == deviceLevel;
        }
        // deviceLevel is not LEGACY, can use numerical sort
        return requiredLevel <= deviceLevel;
    }


    public boolean isDebug() {
        return debug;
    }

    public void onSetDebug(final boolean debug) {}

    @Override
    public boolean onKeyDown(final int keyCode, final KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_BUTTON_L1 || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
            debug = !debug;
            //requestRender();
            onSetDebug(debug);
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    protected void readyForNextImage() {
        if (postInferenceCallback != null) {
            postInferenceCallback.run();
        }
    }

    protected int getScreenOrientation() {
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_270:
                return 270;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_90:
                return 90;
            default:
                return 0;
        }
    }

}
