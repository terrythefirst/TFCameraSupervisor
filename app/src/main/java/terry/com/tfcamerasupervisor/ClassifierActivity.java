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

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Size;
import android.util.TypedValue;
import android.view.WindowManager;
import android.widget.TextView;


public class ClassifierActivity extends CameraActivity implements OnImageAvailableListener {
  private static final Logger LOGGER = new Logger();

  protected static final boolean SAVE_PREVIEW_BITMAP = true;

  private TextView resultsView;

  private Bitmap rgbFrameBitmap = null;
  private Bitmap croppedBitmap = null;
  private Bitmap cropCopyBitmap = null;

  private long lastProcessingTimeMs;

//  // These are the settings for the original v1 Inception model. If you want to
//  // use a model that's been produced from the TensorFlow for Poets codelab,
//  // you'll need to set IMAGE_SIZE = 299, IMAGE_MEAN = 128, IMAGE_STD = 128,
//  // INPUT_NAME = "Mul", and OUTPUT_NAME = "final_result".
//  // You'll also need to update the MODEL_FILE and LABEL_FILE paths to point to
//  // the ones you produced.
//  //
//  // To use v3 Inception model, strip the DecodeJpeg Op from your retrained
//  // model first:
//  //
//  // python strip_unused.py \
//  // --input_graph=<retrained-pb-file> \
//  // --output_graph=<your-stripped-pb-file> \
//  // --input_node_names="Mul" \
//  // --output_node_names="final_result" \
//  // --input_binary=true
//  private static final int INPUT_SIZE = 224;
//  private static final int IMAGE_MEAN = 117;
//  private static final float IMAGE_STD = 1;
//  private static final String INPUT_NAME = "input";
//  private static final String OUTPUT_NAME = "output";
//
//
//  private static final String MODEL_FILE = "file:///android_asset/tensorflow_inception_graph.pb";
//  private static final String LABEL_FILE =
//          "file:///android_asset/imagenet_comp_graph_label_strings.txt";
//
//
//  private static final boolean MAINTAIN_ASPECT = true;



  private Integer sensorOrientation;
  //private Classifier classifier;
  private Matrix frameToCropTransform;
  private Matrix cropToFrameTransform;



  @Override
  protected int getLayoutId() {
    return R.layout.camera_connection_fragment;
  }

  @Override
  protected Size getDesiredPreviewFrameSize() {
      //return DESIRED_PREVIEW_SIZE;
      WindowManager manager = this.getWindowManager();
      DisplayMetrics outMetrics = new DisplayMetrics();
      manager.getDefaultDisplay().getMetrics(outMetrics);

      return new Size(outMetrics.widthPixels,outMetrics.heightPixels);
  }

  private static final float TEXT_SIZE_DIP = 10;

  @Override
  public void onPreviewSizeChosen(final Size size, final int rotation) {
    final float textSizePx = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, getResources().getDisplayMetrics());

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
    croppedBitmap = Bitmap.createBitmap(TFCameraSupervisorConfig.INPUT_SIZE, TFCameraSupervisorConfig.INPUT_SIZE, Config.ARGB_8888);

    frameToCropTransform = ImageUtils.getTransformationMatrix(
        previewWidth, previewHeight,
            TFCameraSupervisorConfig.INPUT_SIZE,
            TFCameraSupervisorConfig.INPUT_SIZE,
        sensorOrientation, TFCameraSupervisorConfig.MAINTAIN_ASPECT);

    cropToFrameTransform = new Matrix();
    frameToCropTransform.invert(cropToFrameTransform);

//    addCallback(
//        new OverlayView.DrawCallback() {
//          @Override
//          public void drawCallback(final Canvas canvas) {
//            renderDebug(canvas);
//          }
//        });
  }

  @Override
  protected void processImage() {
    runInBackground(
        new Runnable() {
          @Override
          public void run() {
            rgbFrameBitmap.setPixels(getRgbBytes(), 0, previewWidth, 0, 0, previewWidth, previewHeight);
            final Canvas canvas = new Canvas(croppedBitmap);
            canvas.drawBitmap(rgbFrameBitmap, frameToCropTransform, null);

            // For examining the actual TF input.
            if (SAVE_PREVIEW_BITMAP) {
              if(TFCameraSupervisorConfig.lastSaveTimeStamp==-1){
                TFCameraSupervisorConfig.lastSaveTimeStamp = System.currentTimeMillis();
                ImageUtils.saveBitmap(croppedBitmap);
              }else{
                long nowSaveTimeStap = System.currentTimeMillis();
                if(nowSaveTimeStap-TFCameraSupervisorConfig.lastSaveTimeStamp>
                        TFCameraSupervisorConfig.saveInterval){
                  ImageUtils.saveBitmap(croppedBitmap);
                }
              }
            }

//            final long startTime = SystemClock.uptimeMillis();
//            final List<Classifier.Recognition> results = classifier.recognizeImage(croppedBitmap);
//            lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime;
//            LOGGER.i("Detect: %s", results);
            cropCopyBitmap = Bitmap.createBitmap(croppedBitmap);
            if (resultsView == null) {
              resultsView = (TextView) findViewById(R.id.results);
            }
            fragment.UIChangeHandler.post(new Runnable() {
              @Override
              public void run() {
                resultsView.setText(""+System.currentTimeMillis());
              }
            });
            //resultsView.setResults(results);
            //requestRender();
            readyForNextImage();
          }
        });
  }

  @Override
  public void onSetDebug(boolean debug) {
//    classifier.enableStatLogging(debug);
  }

  private void renderDebug(final Canvas canvas) {
    if (!isDebug()) {
      return;
    }
    final Bitmap copy = cropCopyBitmap;
    if (copy != null) {
      final Matrix matrix = new Matrix();
      final float scaleFactor = 2;
      matrix.postScale(scaleFactor, scaleFactor);
      matrix.postTranslate(
          canvas.getWidth() - copy.getWidth() * scaleFactor,
          canvas.getHeight() - copy.getHeight() * scaleFactor);
      canvas.drawBitmap(copy, matrix, new Paint());

//      final Vector<String> lines = new Vector<String>();
//      if (classifier != null) {
//        String statString = classifier.getStatString();
//        String[] statLines = statString.split("\n");
//        for (String line : statLines) {
//          lines.add(line);
//        }
//      }
//
//      lines.add("Frame: " + previewWidth + "x" + previewHeight);
//      lines.add("Crop: " + copy.getWidth() + "x" + copy.getHeight());
//      lines.add("View: " + canvas.getWidth() + "x" + canvas.getHeight());
//      lines.add("Rotation: " + sensorOrientation);
//      lines.add("Inference time: " + lastProcessingTimeMs + "ms");
//
//      borderedText.drawLines(canvas, 10, canvas.getHeight() - 10, lines);
    }
  }
}
