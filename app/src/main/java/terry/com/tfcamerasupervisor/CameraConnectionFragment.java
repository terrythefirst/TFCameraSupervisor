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

package terry.com.tfcamerasupervisor;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.Fragment;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
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
import android.media.ImageReader;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class CameraConnectionFragment extends Fragment {
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

  int width;
  int height;
  /**
   * {@link android.view.TextureView.SurfaceTextureListener} handles several lifecycle events on a
   * {@link TextureView}.
   */
  private final TextureView.SurfaceTextureListener surfaceTextureListener =
      new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(
                final SurfaceTexture texture, final int width, final int height) {
          CameraConnectionFragment.this.width = width;
          CameraConnectionFragment.this.height = height;
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
        public void onSurfaceTextureUpdated(final SurfaceTexture texture) {}
      };

  /**
   * Callback for Activities to use to initialize their data once the
   * selected preview size is known.
   */
  public interface ConnectionCallback {
    void onPreviewSizeChosen(Size size, int cameraRotation);
  }

  /**
   * ID of the current {@link CameraDevice}.
   */
  private String cameraId;
  private String backCameraId;//后置摄像头ID
  private String frontCameraId;//前置摄像头ID

  CameraCharacteristics characteristics;

    private boolean isCameraFront = false;//当前是否是前置摄像头
    private boolean isLightOn = false;//当前闪光灯是否开启


  private TextureView textureView;
  private ImageView ivSwitchCamera;//切换前后摄像头
  private ImageView ivLightOn;//开关闪光灯
  private ImageView ivClose;//关闭该Activity
  private TextView ivTextView;

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
          final Activity activity = getActivity();
          if (null != activity) {
            activity.finish();
          }
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
  private final OnImageAvailableListener imageListener;

  /** The input size in pixels desired by TensorFlow (width and height of a square bitmap). */
  private final Size inputSize;

  /**
   * The layout identifier to inflate for this Fragment.
   */
  private final int layout;


  private final ConnectionCallback cameraConnectionCallback;

  private CameraConnectionFragment(
      final ConnectionCallback connectionCallback,
      final OnImageAvailableListener imageListener,
      final int layout,
      final Size inputSize) {
    this.cameraConnectionCallback = connectionCallback;
    this.imageListener = imageListener;
    this.layout = layout;
    this.inputSize = inputSize;
  }

  /**
   * Shows a {@link Toast} on the UI thread.
   *
   * @param text The message to show
   */
  private void showToast(final String text) {
    final Activity activity = getActivity();
    if (activity != null) {
      activity.runOnUiThread(
          new Runnable() {
            @Override
            public void run() {
              Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
            }
          });
    }
  }

  // 通过对比得到与宽高比最接近的尺寸（如果有相同尺寸，优先选择，activity我们已经固定了方向，所以这里无需在做判断
  protected static Size getCloselyPreSize(Size[] sizeMap, int surfaceWidth, int surfaceHeight) {
    int ReqTmpWidth;
    int ReqTmpHeight;
    ReqTmpWidth = surfaceHeight;
    ReqTmpHeight = surfaceWidth;
    //先查找preview中是否存在与surfaceview相同宽高的尺寸
    for (Size size : sizeMap) {
      if ((size.getWidth() == ReqTmpWidth) && (size.getHeight() == ReqTmpHeight)) {
        return size;
      }
    }

    // 得到与传入的宽高比最接近的size
    float reqRatio = ((float) ReqTmpWidth) / ReqTmpHeight;
    float curRatio, deltaRatio;
    float deltaRatioMin = Float.MAX_VALUE;
    Size retSize = null;
    for (Size size : sizeMap) {
      curRatio = ((float) size.getWidth()) / size.getHeight();
      deltaRatio = Math.abs(reqRatio - curRatio);
      if (deltaRatio < deltaRatioMin) {
        deltaRatioMin = deltaRatio;
        retSize = size;
      }
    }
    return retSize;
  }

  /**
   * Given {@code choices} of {@code Size}s supported by a camera, chooses the smallest one whose
   * width and height are at least as large as the minimum of both, or an exact match if possible.
   *
   * @param choices The list of sizes that the camera supports for the intended output class
   * @param width The minimum desired width
   * @param height The minimum desired height
   * @return The optimal {@code Size}, or an arbitrary one if none were big enough
   */
  protected static Size chooseOptimalSize(final Size[] choices, final int width, final int height) {
    final int minSize = Math.max(Math.min(width, height), MINIMUM_PREVIEW_SIZE);
    final Size desiredSize = new Size(width, height);

    // Collect the supported resolutions that are at least as big as the preview Surface
    boolean exactSizeFound = false;
    final List<Size> bigEnough = new ArrayList<Size>();
    final List<Size> tooSmall = new ArrayList<Size>();
    for (final Size option : choices) {
      if (option.equals(desiredSize)) {
        // Set the size but don't return yet so that remaining sizes will still be logged.
        exactSizeFound = true;
      }

      if (option.getHeight() >= minSize && option.getWidth() >= minSize) {
        bigEnough.add(option);
      } else {
        tooSmall.add(option);
      }
    }

    LOGGER.i("Desired size: " + desiredSize + ", min size: " + minSize + "x" + minSize);
    LOGGER.i("Valid preview sizes: [" + TextUtils.join(", ", bigEnough) + "]");
    LOGGER.i("Rejected preview sizes: [" + TextUtils.join(", ", tooSmall) + "]");

    if (exactSizeFound) {
      LOGGER.i("Exact size match found.");
      return desiredSize;
    }

    // Pick the smallest of those, assuming we found any
    if (bigEnough.size() > 0) {
      final Size chosenSize = Collections.min(bigEnough, new CompareSizesByArea());
      LOGGER.i("Chosen size: " + chosenSize.getWidth() + "x" + chosenSize.getHeight());
      return chosenSize;
    } else {
      LOGGER.e("Couldn't find any suitable preview size");
      return choices[0];
    }
  }

  public static CameraConnectionFragment newInstance(
      final ConnectionCallback callback,
      final OnImageAvailableListener imageListener,
      final int layout,
      final Size inputSize) {
    return new CameraConnectionFragment(callback, imageListener, layout, inputSize);
  }

  @Override
  public View onCreateView(
          final LayoutInflater inflater, final ViewGroup container, final Bundle savedInstanceState) {
    return inflater.inflate(layout, container, false);
  }

  @Override
  public void onViewCreated(final View view, final Bundle savedInstanceState) {
    textureView = (TextureView) view.findViewById(R.id.texture);
    ivSwitchCamera = view.findViewById(R.id.iv_switchCamera);
    ivLightOn = view.findViewById(R.id.iv_lightOn);
    ivClose = view.findViewById(R.id.iv_close);
    ivTextView = view.findViewById(R.id.results);
    ivTextView.setText("OK");


      ivSwitchCamera.setOnClickListener(clickListener);
      ivLightOn.setOnClickListener(clickListener);
      ivClose.setOnClickListener(clickListener);

    textureView.setOnTouchListener(new View.OnTouchListener() {
      @Override
      public boolean onTouch(View view, MotionEvent event) {
        //两指缩放
        changeZoom(event);
        return true;
      }
    });
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
                getActivity().finish();
            }
        }
    };

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
            openCamera(width,height);
        } else {
            isCameraFront = true;
            //setupCamera(width, height);
            openCamera(width,height);
        }
    }

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
   * *********************************放大或者缩小**********************************
   */
  //手指按下的点为(x1, y1)手指离开屏幕的点为(x2, y2)
  float finger_spacing;
  int zoom_level = 0;
  Rect zoom;

  public void changeZoom(MotionEvent event) {
    try {
      //活动区域宽度和作物区域宽度之比和活动区域高度和作物区域高度之比的最大比率
      float maxZoom = (characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)) * 10;
      Rect m = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);

      int action = event.getAction();
      float current_finger_spacing;
      //判断当前屏幕的手指数
      if (event.getPointerCount() > 1) {
        //计算两个触摸点的距离
        current_finger_spacing = getFingerSpacing(event);

        if (finger_spacing != 0) {
          if (current_finger_spacing > finger_spacing && maxZoom > zoom_level) {
            zoom_level++;

          } else if (current_finger_spacing < finger_spacing && zoom_level > 1) {
            zoom_level--;
          }

          int minW = (int) (m.width() / maxZoom);
          int minH = (int) (m.height() / maxZoom);
          int difW = m.width() - minW;
          int difH = m.height() - minH;
          int cropW = difW / 100 * (int) zoom_level;
          int cropH = difH / 100 * (int) zoom_level;
          cropW -= cropW & 3;
          cropH -= cropH & 3;
          zoom = new Rect(cropW, cropH, m.width() - cropW, m.height() - cropH);
          previewRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, zoom);
        }
        finger_spacing = current_finger_spacing;
      } else {
        if (action == MotionEvent.ACTION_UP) {
          //single touch logic,可做点击聚焦操作
        }
      }

      try {
        captureSession.setRepeatingRequest(previewRequestBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                  @Override
                  public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);
                  }
                },
                null);
      } catch (Exception e) {
        e.printStackTrace();
      }
    } catch (Exception e) {
      throw new RuntimeException("can not access camera.", e);
    }
  }

  //计算两个触摸点的距离
  private float getFingerSpacing(MotionEvent event) {
    float x = event.getX(0) - event.getX(1);
    float y = event.getY(0) - event.getY(1);
    return (float) Math.sqrt(x * x + y * y);
  }

  @Override
  public void onActivityCreated(final Bundle savedInstanceState) {
    super.onActivityCreated(savedInstanceState);
  }

  @Override
  public void onResume() {
    super.onResume();
    startBackgroundThread();

    // When the screen is turned off and turned back on, the SurfaceTexture is already
    // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
    // a camera and start preview from here (otherwise, we wait until the surface is ready in
    // the SurfaceTextureListener).
    if (textureView.isAvailable()) {
      openCamera(textureView.getWidth(), textureView.getHeight());
    } else {
      textureView.setSurfaceTextureListener(surfaceTextureListener);
    }
  }

  @Override
  public void onPause() {
    closeCamera();
    stopBackgroundThread();
    super.onPause();
  }

  public void setCamera(String cameraId) {
    this.cameraId = cameraId;
  }

  /**
   * Sets up member variables related to camera.
   */
  private void setUpCameraOutputs() {
    final Activity activity = getActivity();
    final CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
    try {
      backCameraId = manager.getCameraIdList()[0];//后置摄像头ID
      frontCameraId = manager.getCameraIdList()[1];//前置摄像头ID

      if(isCameraFront){
          cameraId = frontCameraId;
      }else{
          cameraId = backCameraId;
      }
      characteristics = manager.getCameraCharacteristics(cameraId);

      final StreamConfigurationMap map =
          characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);


      // For still image captures, we use the largest available size.
      final Size largest =
          Collections.max(
              Arrays.asList(map.getOutputSizes(ImageFormat.YUV_420_888)),
              new CompareSizesByArea());

      sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

      // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
      // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
      // garbage capture data.
      previewSize =
          getCloselyPreSize(map.getOutputSizes(SurfaceTexture.class),
              inputSize.getWidth(),
              inputSize.getHeight());

      // We fit the aspect ratio of TextureView to the size of preview we picked.
      final int orientation = getResources().getConfiguration().orientation;
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
      ErrorDialog.newInstance(getString(R.string.camera_error))
          .show(getChildFragmentManager(), FRAGMENT_DIALOG);
      throw new RuntimeException(getString(R.string.camera_error));
    }

    cameraConnectionCallback.onPreviewSizeChosen(previewSize, sensorOrientation);
  }

  /**
   * Opens the camera specified by {@link CameraConnectionFragment#cameraId}.
   */
  private void openCamera(final int width, final int height) {
    setUpCameraOutputs();
    configureTransform(width, height);
    final Activity activity = getActivity();
    final CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
    try {
      if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
        throw new RuntimeException("Time out waiting to lock camera opening.");
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
    backgroundThread = new HandlerThread("ImageListener");
    backgroundThread.start();
    backgroundHandler = new Handler(backgroundThread.getLooper());


    UIChangeHandler = new Handler();
  }

  /**
   * Stops the background thread and its {@link Handler}.
   */
  private void stopBackgroundThread() {
    backgroundThread.quitSafely();
    try {
      backgroundThread.join();
      backgroundThread = null;
      backgroundHandler = null;
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
              previewSize.getWidth(), previewSize.getHeight(), ImageFormat.YUV_420_888, 2);

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
   * Configures the necessary {@link android.graphics.Matrix} transformation to `mTextureView`.
   * This method should be called after the camera preview size is determined in
   * setUpCameraOutputs and also the size of `mTextureView` is fixed.
   *
   * @param viewWidth  The width of `mTextureView`
   * @param viewHeight The height of `mTextureView`
   */
  private void configureTransform(final int viewWidth, final int viewHeight) {
    final Activity activity = getActivity();
    if (null == textureView || null == previewSize || null == activity) {
      return;
    }
    final int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
    final Matrix matrix = new Matrix();
    final RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
    final RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
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

  /**
   * Compares two {@code Size}s based on their areas.
   */
  static class CompareSizesByArea implements Comparator<Size> {
    @Override
    public int compare(final Size lhs, final Size rhs) {
      // We cast here to ensure the multiplications won't overflow
      return Long.signum(
          (long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
    }
  }

  /**
   * Shows an error message dialog.
   */
  public static class ErrorDialog extends DialogFragment {
    private static final String ARG_MESSAGE = "message";

    public static ErrorDialog newInstance(final String message) {
      final ErrorDialog dialog = new ErrorDialog();
      final Bundle args = new Bundle();
      args.putString(ARG_MESSAGE, message);
      dialog.setArguments(args);
      return dialog;
    }

    @Override
    public Dialog onCreateDialog(final Bundle savedInstanceState) {
      final Activity activity = getActivity();
      return new AlertDialog.Builder(activity)
          .setMessage(getArguments().getString(ARG_MESSAGE))
          .setPositiveButton(
              android.R.string.ok,
              new DialogInterface.OnClickListener() {
                @Override
                public void onClick(final DialogInterface dialogInterface, final int i) {
                  activity.finish();
                }
              })
          .create();
    }
  }
}