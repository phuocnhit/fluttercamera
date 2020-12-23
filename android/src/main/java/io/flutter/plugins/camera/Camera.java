package io.flutter.plugins.camera;

import static android.view.OrientationEventListener.ORIENTATION_UNKNOWN;
import static io.flutter.plugins.camera.CameraUtils.computeBestPreviewSize;
import static org.opencv.imgproc.Imgproc.cvtColor;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CamcorderProfile;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Build.VERSION;
import android.os.Build.VERSION_CODES;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.OrientationEventListener;
import android.view.Surface;
import androidx.annotation.NonNull;


import com.example.android.camera.utils.YuvToRgbConverter;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;

import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugins.camera.media.MediaRecorderBuilder;
import io.flutter.view.TextureRegistry.SurfaceTextureEntry;
import online.khaothi.mobile.ImageProcesser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Camera {
  private final SurfaceTextureEntry flutterTexture;
  private final CameraManager cameraManager;
  private final OrientationEventListener orientationEventListener;
  private final boolean isFrontFacing;
  private final int sensorOrientation;
  private final String cameraName;
  private final Size captureSize;
  private final Size previewSize;
  private final boolean enableAudio;

  private CameraDevice cameraDevice;
  private CameraCaptureSession cameraCaptureSession;
  private ImageReader pictureImageReader;
  private ImageReader imageStreamReader;
  private DartMessenger dartMessenger;
  private CaptureRequest.Builder captureRequestBuilder;
  private MediaRecorder mediaRecorder;
  private boolean recordingVideo;
  private CamcorderProfile recordingProfile;
  private int currentOrientation = ORIENTATION_UNKNOWN;
  YuvToRgbConverter yuvToRgbConverter;
  int i=0;
  int j = 0;
  // Mirrors camera.dart
  public enum ResolutionPreset {
    low,
    medium,
    high,
    veryHigh,
    ultraHigh,
    max,
  }

  public Camera(
          final Activity activity,
          final SurfaceTextureEntry flutterTexture,
          final DartMessenger dartMessenger,
          final String cameraName,
          final String resolutionPreset,
          final boolean enableAudio)
          throws CameraAccessException {
    if (activity == null) {
      throw new IllegalStateException("No activity available!");
    }
      OpenCVLoader.initDebug();

    this.cameraName = cameraName;
    this.enableAudio = enableAudio;
    this.flutterTexture = flutterTexture;
    this.dartMessenger = dartMessenger;
    this.cameraManager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
    this.yuvToRgbConverter = new YuvToRgbConverter(activity.getApplicationContext());

    orientationEventListener =
            new OrientationEventListener(activity.getApplicationContext()) {
              @Override
              public void onOrientationChanged(int i) {
                if (i == ORIENTATION_UNKNOWN) {
                  return;
                }
                // Convert the raw deg angle to the nearest multiple of 90.
                currentOrientation = (int) Math.round(i / 90.0) * 90;
              }
            };
    orientationEventListener.enable();

    CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraName);
    StreamConfigurationMap streamConfigurationMap =
            characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
    //noinspection ConstantConditions
    sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
    //noinspection ConstantConditions
    isFrontFacing =
            characteristics.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_FRONT;
    ResolutionPreset preset = ResolutionPreset.valueOf(resolutionPreset);
    recordingProfile =
            CameraUtils.getBestAvailableCamcorderProfileForResolutionPreset(cameraName, preset);
    captureSize = new Size(recordingProfile.videoFrameWidth, recordingProfile.videoFrameHeight);
    previewSize = computeBestPreviewSize(cameraName, preset);
  }

  private void prepareMediaRecorder(String outputFilePath) throws IOException {
    if (mediaRecorder != null) {
      mediaRecorder.release();
    }

    mediaRecorder =
            new MediaRecorderBuilder(recordingProfile, outputFilePath)
                    .setEnableAudio(enableAudio)
                    .setMediaOrientation(getMediaOrientation())
                    .build();
  }

  @SuppressLint("MissingPermission")
  public void open(@NonNull final Result result) throws CameraAccessException {
    pictureImageReader =
            ImageReader.newInstance(
                    captureSize.getWidth(), captureSize.getHeight(), ImageFormat.JPEG, 2);

    // Used to steam image byte data to dart side.
    imageStreamReader =
            ImageReader.newInstance(
                    previewSize.getWidth(), previewSize.getHeight(), ImageFormat.YUV_420_888, 2);

    cameraManager.openCamera(
            cameraName,
            new CameraDevice.StateCallback() {
              @Override
              public void onOpened(@NonNull CameraDevice device) {
                cameraDevice = device;
                try {
                  startPreview();
                } catch (CameraAccessException e) {
                  result.error("CameraAccess", e.getMessage(), null);
                  close();
                  return;
                }
                Map<String, Object> reply = new HashMap<>();
                reply.put("textureId", flutterTexture.id());
                reply.put("previewWidth", previewSize.getWidth());
                reply.put("previewHeight", previewSize.getHeight());
                result.success(reply);
              }

              @Override
              public void onClosed(@NonNull CameraDevice camera) {
                dartMessenger.sendCameraClosingEvent();
                super.onClosed(camera);
              }

              @Override
              public void onDisconnected(@NonNull CameraDevice cameraDevice) {
                close();
                dartMessenger.send(DartMessenger.EventType.ERROR, "The camera was disconnected.");
              }

              @Override
              public void onError(@NonNull CameraDevice cameraDevice, int errorCode) {
                close();
                String errorDescription;
                switch (errorCode) {
                  case ERROR_CAMERA_IN_USE:
                    errorDescription = "The camera device is in use already.";
                    break;
                  case ERROR_MAX_CAMERAS_IN_USE:
                    errorDescription = "Max cameras in use";
                    break;
                  case ERROR_CAMERA_DISABLED:
                    errorDescription = "The camera device could not be opened due to a device policy.";
                    break;
                  case ERROR_CAMERA_DEVICE:
                    errorDescription = "The camera device has encountered a fatal error";
                    break;
                  case ERROR_CAMERA_SERVICE:
                    errorDescription = "The camera service has encountered a fatal error.";
                    break;
                  default:
                    errorDescription = "Unknown camera error";
                }
                dartMessenger.send(DartMessenger.EventType.ERROR, errorDescription);
              }
            },
            null);
  }

  private void writeToFile(ByteBuffer buffer, File file) throws IOException {
    try (FileOutputStream outputStream = new FileOutputStream(file)) {
      while (0 < buffer.remaining()) {
        outputStream.getChannel().write(buffer);
      }
    }
  }

  SurfaceTextureEntry getFlutterTexture() {
    return flutterTexture;
  }

  public void takePicture(String filePath, @NonNull final Result result) {
    final File file = new File(filePath);

    if (file.exists()) {
      result.error(
              "fileExists", "File at path '" + filePath + "' already exists. Cannot overwrite.", null);
      return;
    }

    pictureImageReader.setOnImageAvailableListener(
            reader -> {
              try (Image image = reader.acquireLatestImage()) {
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                writeToFile(buffer, file);
                result.success(null);
              } catch (IOException e) {
                result.error("IOError", "Failed saving image", null);
              }
            },
            null);

    try {
      final CaptureRequest.Builder captureBuilder =
              cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
      captureBuilder.addTarget(pictureImageReader.getSurface());
      captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getMediaOrientation());

      cameraCaptureSession.capture(
              captureBuilder.build(),
              new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureFailed(
                        @NonNull CameraCaptureSession session,
                        @NonNull CaptureRequest request,
                        @NonNull CaptureFailure failure) {
                  String reason;
                  switch (failure.getReason()) {
                    case CaptureFailure.REASON_ERROR:
                      reason = "An error happened in the framework";
                      break;
                    case CaptureFailure.REASON_FLUSHED:
                      reason = "The capture has failed due to an abortCaptures() call";
                      break;
                    default:
                      reason = "Unknown reason";
                  }
                  result.error("captureFailure", reason, null);
                }
              },
              null);
    } catch (CameraAccessException e) {
      result.error("cameraAccess", e.getMessage(), null);
    }
  }

  private void createCaptureSession(int templateType, Surface... surfaces)
          throws CameraAccessException {
    createCaptureSession(templateType, null, surfaces);
  }

  private void createCaptureSession(
          int templateType, Runnable onSuccessCallback, Surface... surfaces)
          throws CameraAccessException {
    // Close any existing capture session.
    closeCaptureSession();

    // Create a new capture builder.
    captureRequestBuilder = cameraDevice.createCaptureRequest(templateType);

    // Build Flutter surface to render to
    SurfaceTexture surfaceTexture = flutterTexture.surfaceTexture();
    surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
    Surface flutterSurface = new Surface(surfaceTexture);
    captureRequestBuilder.addTarget(flutterSurface);

    List<Surface> remainingSurfaces = Arrays.asList(surfaces);
    if (templateType != CameraDevice.TEMPLATE_PREVIEW) {
      // If it is not preview mode, add all surfaces as targets.
      for (Surface surface : remainingSurfaces) {

        captureRequestBuilder.addTarget(surface);
      }
    }

    captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, 180);

    // Prepare the callback
    CameraCaptureSession.StateCallback callback =
            new CameraCaptureSession.StateCallback() {
              @Override
              public void onConfigured(@NonNull CameraCaptureSession session) {
                try {
                  if (cameraDevice == null) {
                    dartMessenger.send(
                            DartMessenger.EventType.ERROR, "The camera was closed during configuration.");
                    return;
                  }
                  cameraCaptureSession = session;
                  captureRequestBuilder.set(
                          CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                  cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null);
                  if (onSuccessCallback != null) {
                    onSuccessCallback.run();
                  }
                } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
                  dartMessenger.send(DartMessenger.EventType.ERROR, e.getMessage());
                }
              }

              @Override
              public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                dartMessenger.send(
                        DartMessenger.EventType.ERROR, "Failed to configure camera session.");
              }
            };

    // Start the session
    if (VERSION.SDK_INT >= VERSION_CODES.P) {
      // Collect all surfaces we want to render to.
      List<OutputConfiguration> configs = new ArrayList<>();
      configs.add(new OutputConfiguration(flutterSurface));
      for (Surface surface : remainingSurfaces) {
        configs.add(new OutputConfiguration(surface));
      }
      createCaptureSessionWithSessionConfig(configs, callback);
    } else {
      // Collect all surfaces we want to render to.
      List<Surface> surfaceList = new ArrayList<>();
      surfaceList.add(flutterSurface);
      surfaceList.addAll(remainingSurfaces);
      createCaptureSession(surfaceList, callback);
    }
  }

  @TargetApi(VERSION_CODES.P)
  private void createCaptureSessionWithSessionConfig(
          List<OutputConfiguration> outputConfigs, CameraCaptureSession.StateCallback callback)
          throws CameraAccessException {
    cameraDevice.createCaptureSession(
            new SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    outputConfigs,
                    Executors.newSingleThreadExecutor(),
                    callback));
  }

  @TargetApi(VERSION_CODES.LOLLIPOP)
  @SuppressWarnings("deprecation")
  private void createCaptureSession(
          List<Surface> surfaces, CameraCaptureSession.StateCallback callback)
          throws CameraAccessException {
    cameraDevice.createCaptureSession(surfaces, callback, null);
  }

  public void startVideoRecording(String filePath, Result result) {
    if (new File(filePath).exists()) {
      result.error("fileExists", "File at path '" + filePath + "' already exists.", null);
      return;
    }
    try {
      prepareMediaRecorder(filePath);
      recordingVideo = true;
      createCaptureSession(
              CameraDevice.TEMPLATE_RECORD, () -> mediaRecorder.start(), mediaRecorder.getSurface());
      result.success(null);
    } catch (CameraAccessException | IOException e) {
      result.error("videoRecordingFailed", e.getMessage(), null);
    }
  }

  public void stopVideoRecording(@NonNull final Result result) {
    if (!recordingVideo) {
      result.success(null);
      return;
    }

    try {
      recordingVideo = false;
      mediaRecorder.stop();
      mediaRecorder.reset();
      startPreview();
      result.success(null);
    } catch (CameraAccessException | IllegalStateException e) {
      result.error("videoRecordingFailed", e.getMessage(), null);
    }
  }

  public void pauseVideoRecording(@NonNull final Result result) {
    if (!recordingVideo) {
      result.success(null);
      return;
    }

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        mediaRecorder.pause();
      } else {
        result.error("videoRecordingFailed", "pauseVideoRecording requires Android API +24.", null);
        return;
      }
    } catch (IllegalStateException e) {
      result.error("videoRecordingFailed", e.getMessage(), null);
      return;
    }

    result.success(null);
  }

  public void resumeVideoRecording(@NonNull final Result result) {
    if (!recordingVideo) {
      result.success(null);
      return;
    }

    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        mediaRecorder.resume();
      } else {
        result.error(
                "videoRecordingFailed", "resumeVideoRecording requires Android API +24.", null);
        return;
      }
    } catch (IllegalStateException e) {
      result.error("videoRecordingFailed", e.getMessage(), null);
      return;
    }

    result.success(null);
  }

  public void startPreview() throws CameraAccessException {
    if (pictureImageReader == null || pictureImageReader.getSurface() == null) return;

    createCaptureSession(CameraDevice.TEMPLATE_PREVIEW, pictureImageReader.getSurface());
  }

  public void startPreviewWithImageStream(EventChannel imageStreamChannel)
          throws CameraAccessException {
    createCaptureSession(CameraDevice.TEMPLATE_RECORD, imageStreamReader.getSurface());

    imageStreamChannel.setStreamHandler(
            new EventChannel.StreamHandler() {
              @Override
              public void onListen(Object o, EventChannel.EventSink imageStreamSink) {
                setImageStreamImageAvailableListener(imageStreamSink);
              }

              @Override
              public void onCancel(Object o) {
                imageStreamReader.setOnImageAvailableListener(null, null);
              }
            });
  }
  public void detectorAruco(EventChannel imageStreamChannel)
          throws CameraAccessException {
    createCaptureSession(CameraDevice.TEMPLATE_RECORD, imageStreamReader.getSurface());

    imageStreamChannel.setStreamHandler(
            new EventChannel.StreamHandler() {
              @Override
              public void onListen(Object o, EventChannel.EventSink imageStreamSink) {
                setImageStreamImageAvailableListenernative(imageStreamSink);
              }

              @Override
              public void onCancel(Object o) {
                imageStreamReader.setOnImageAvailableListener(null, null);
              }
            });
  }



  private void setImageStreamImageAvailableListener(final EventChannel.EventSink imageStreamSink) {
    imageStreamReader.setOnImageAvailableListener(
            reader -> {
              Image img = reader.acquireLatestImage();
              if (img == null) return;

              List<Map<String, Object>> planes = new ArrayList<>();
              for (Image.Plane plane : img.getPlanes()) {
                ByteBuffer buffer = plane.getBuffer();

                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes, 0, bytes.length);

                Map<String, Object> planeBuffer = new HashMap<>();
                planeBuffer.put("bytesPerRow", plane.getRowStride());
                planeBuffer.put("bytesPerPixel", plane.getPixelStride());
                planeBuffer.put("bytes", bytes);

                planes.add(planeBuffer);
              }

              Map<String, Object> imageBuffer = new HashMap<>();
              imageBuffer.put("width", img.getWidth());
              imageBuffer.put("height", img.getHeight());
              imageBuffer.put("format", img.getFormat());
              imageBuffer.put("planes", planes);

              imageStreamSink.success(imageBuffer);
              img.close();
            },
            null);

  }
    Boolean _isDetecting = false;

  private void setImageStreamImageAvailableListenernative(final EventChannel.EventSink imageStreamSink) {
    imageStreamReader.setOnImageAvailableListener(

            reader -> {

              Image img = reader.acquireLatestImage();
              if (img == null) return;
              if(Singleton.getInstance().isDetecting){
                img.close();
                return;
              }
              byte[] bytes= YUV_420_888toNV21(img);
              ImageProcessThread a = new ImageProcessThread(bytes, img.getWidth(), img.getHeight(), imageStreamSink);
              Thread b = new Thread(a);
              b.start();

              img.close();
//              YuvImage img2 = new YuvImage(bytes, ImageFormat.NV21, img.getWidth(), img.getHeight(), null);
//
//              ByteArrayOutputStream buffer = new ByteArrayOutputStream();
//              Rect  cropRect = new Rect(0, 0, img.getWidth(), img.getHeight());
//              img2.compressToJpeg(cropRect, 100, buffer);
//              byte[] jpegData = buffer.toByteArray();
//              try {
//                buffer.close();
//              } catch (IOException e) {
//                e.printStackTrace();
//              }
//              Bitmap bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);
////
//              Mat mat = new Mat();
//              Utils.bitmapToMat(bitmap, mat);
//
//              Mat frame= rotate(mat, 90.0);
//                Imgproc.cvtColor(frame, frame, Imgproc.COLOR_RGBA2GRAY);
//
////              Bitmap bmp = Bitmap.createBitmap(img.getHeight(), img.getWidth(), Bitmap.Config.ARGB_8888);
////              Utils.matToBitmap(mat, bmp);
//              ArrayList responses= new ArrayList();
//              Dictionary dictionary = Aruco.getPredefinedDictionary(Aruco.DICT_5X5_250);
//              Mat ids =new  Mat();
//                ArrayList<Mat> corners=new ArrayList<Mat>();
//              Aruco.detectMarkers(frame, dictionary, corners, ids);
//              Aruco.drawDetectedMarkers(frame, corners);
//              int count =(int) ids.size().height;
//              if(count>0) {
//                int a = 0;
//              }
//
//              Map<String, Object> planeBuffer = new HashMap<>();
//              planeBuffer.put("width", img.getWidth());
//              imageStreamSink.success(planeBuffer);
//
//              img.close();
//                _isDetecting = false;
            },
            null);


  }
  Mat rotate(Mat src, Double angle ) {
    Mat dst =new  Mat();
    if (angle == 180.0 || angle == -180.0) {
      Core.flip(src, dst, -1);
    } else if (angle == 90.0 || angle == -270.0) {
      Core.flip(src.t(), dst, 1);
    } else if (angle == 270.0 || angle == -90.0) {
      Core.flip(src.t(), dst, 0);
    }
    return dst;
  }
  private static byte[] YUV_420_888toNV21(Image image) {

    int width = image.getWidth();
    int height = image.getHeight();
    int ySize = width*height;
    int uvSize = width*height/4;

    byte[] nv21 = new byte[ySize + uvSize*2];

    ByteBuffer yBuffer = image.getPlanes()[0].getBuffer(); // Y
    ByteBuffer uBuffer = image.getPlanes()[1].getBuffer(); // U
    ByteBuffer vBuffer = image.getPlanes()[2].getBuffer(); // V

    int rowStride = image.getPlanes()[0].getRowStride();
    assert(image.getPlanes()[0].getPixelStride() == 1);

    int pos = 0;

    if (rowStride == width) { // likely
      yBuffer.get(nv21, 0, ySize);
      pos += ySize;
    }
    else {
      long yBufferPos = -rowStride; // not an actual position
      for (; pos<ySize; pos+=width) {
        yBufferPos += rowStride;
        yBuffer.position((int) yBufferPos);
        yBuffer.get(nv21, pos, width);
      }
    }

    rowStride = image.getPlanes()[2].getRowStride();
    int pixelStride = image.getPlanes()[2].getPixelStride();

    assert(rowStride == image.getPlanes()[1].getRowStride());
    assert(pixelStride == image.getPlanes()[1].getPixelStride());

    if (pixelStride == 2 && rowStride == width && uBuffer.get(0) == vBuffer.get(1)) {
      // maybe V an U planes overlap as per NV21, which means vBuffer[1] is alias of uBuffer[0]
      byte savePixel = vBuffer.get(1);
      try {
        vBuffer.put(1, (byte)~savePixel);
        if (uBuffer.get(0) == (byte)~savePixel) {
          vBuffer.put(1, savePixel);
          vBuffer.position(0);
          uBuffer.position(0);
          vBuffer.get(nv21, ySize, 1);
          uBuffer.get(nv21, ySize + 1, uBuffer.remaining());

          return nv21; // shortcut
        }
      }
      catch (ReadOnlyBufferException ex) {
        // unfortunately, we cannot check if vBuffer and uBuffer overlap
      }

      // unfortunately, the check failed. We must save U and V pixel by pixel
      vBuffer.put(1, savePixel);
    }

    // other optimizations could check if (pixelStride == 1) or (pixelStride == 2),
    // but performance gain would be less significant

    for (int row=0; row<height/2; row++) {
      for (int col=0; col<width/2; col++) {
        int vuPos = col*pixelStride + row*rowStride;
        nv21[pos++] = vBuffer.get(vuPos);
        nv21[pos++] = uBuffer.get(vuPos);
      }
    }

    return nv21;
  }



  private void closeCaptureSession() {
    if (cameraCaptureSession != null) {
      cameraCaptureSession.close();
      cameraCaptureSession = null;
    }
  }

  public void close() {
    closeCaptureSession();

    if (cameraDevice != null) {
      cameraDevice.close();
      cameraDevice = null;
    }
    if (pictureImageReader != null) {
      pictureImageReader.close();
      pictureImageReader = null;
    }
    if (imageStreamReader != null) {
      imageStreamReader.close();
      imageStreamReader = null;
    }
    if (mediaRecorder != null) {
      mediaRecorder.reset();
      mediaRecorder.release();
      mediaRecorder = null;
    }
  }

  public void dispose() {
    close();
    flutterTexture.release();
    orientationEventListener.disable();
  }

  private int getMediaOrientation() {
    final int sensorOrientationOffset =
            (currentOrientation == ORIENTATION_UNKNOWN)
                    ? 0
                    : (isFrontFacing) ? -currentOrientation : currentOrientation;
    return (sensorOrientationOffset + sensorOrientation + 360) % 360;
  }

}
class ImageProcessThread implements  Runnable  {
  byte[] bytes;
  int width;
  int height;
  EventChannel.EventSink imageStreamSink;
    ImageProcessThread(byte[] byteimg, int width, int height, EventChannel.EventSink imageStreamSink) {
        this.bytes = byteimg;
        this.width = width;
        this.height= height;
        this.imageStreamSink = imageStreamSink;
    }

    public void run() {
        if(Singleton.getInstance().isDetecting){
          return;
        }
        Singleton.getInstance().isDetecting = true;
        YuvImage img2 = new YuvImage(bytes, ImageFormat.NV21, width, height, null);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Rect  cropRect = new Rect(0, 0, width, height);
        img2.compressToJpeg(cropRect, 100, buffer);
        byte[] jpegData = buffer.toByteArray();
      try {
        buffer.close();
      } catch (IOException e) {
        e.printStackTrace();
      }
      Bitmap bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);

        Mat mat = new Mat();
        Utils.bitmapToMat(bitmap, mat);

      ImageProcesser a =new  ImageProcesser();
      List<Object> b = a.getYUV2Mat(mat,false);

      Handler handler = new Handler(Looper.getMainLooper());
      handler.post(() -> {
        imageStreamSink.success(b);
      });
      Singleton.getInstance().isDetecting = false;
    }
}
class Singleton
{
  // static variable single_instance of type Singleton
  private static Singleton single_instance = null;

  // variable of type String
  public Boolean isDetecting;

  // private constructor restricted to this class itself
  private Singleton()
  {
    isDetecting=false;
  }

  // static method to create instance of Singleton class
  public static Singleton getInstance()
  {
    if (single_instance == null)
      single_instance = new Singleton();

    return single_instance;
  }
}