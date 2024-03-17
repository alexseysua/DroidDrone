package de.droiddrone.flight;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.PixelFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import static de.droiddrone.common.Logcat.log;

public class Camera {
    private Size targetResolution;
    private Range<Integer> targetFrameRange;
    private CameraDevice camera;
    private CameraManager cameraManager;
    public Size cameraResolution;
    public boolean highFps = false;
    public boolean wideAngle = false;
    public boolean logical = false;
    public boolean frontFacing = false;
    public Range<Integer> frameRate;
    private int cameraFrameCounter;
    private int lastFps;
    private long cameraFrameTimestamp;
    private Surface streamEncoderSurface, recorderSurface;
    private final Context context;
    private final Config config;
    private StreamEncoder streamEncoder;
    private Mp4Recorder mp4Recorder;
    private boolean isOpened;
    private HandlerThread handlerThread;
    private CaptureRequest.Builder mCaptureRequest;
    private CameraCaptureSession mCameraCaptureSession;

    public Camera(Context context, Config config) {
        this.context = context;
        this.config = config;
    }

    public boolean initialize(){
        targetResolution = new Size(config.getCameraResolutionWidth(), config.getCameraResolutionHeight());
        targetFrameRange = new Range<>(config.getCameraFpsMin(), config.getCameraFpsMax());
        frameRate = targetFrameRange;

        cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        String[] cameraIdList;
        try{
            cameraIdList = cameraManager.getCameraIdList();
        }catch (CameraAccessException e){
            log("getCameraIdList CameraAccessException: "+ e);
            return false;
        }
        if (cameraIdList.length == 0){
            log("No camera found.");
            return false;
        }
        for (String cameraId : cameraIdList) {
            log("Camera device found. ID: " + cameraId);
        }
        return getCameraCharacteristics(config.getCameraId());
    }

    public boolean isOpened(String cameraId){
        return isOpened && camera != null && camera.getId().equals(cameraId);
    }

    public boolean openCamera(StreamEncoder streamEncoder, Mp4Recorder mp4Recorder){
        this.streamEncoder = streamEncoder;
        this.mp4Recorder = mp4Recorder;
        if (ActivityCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED){
            return false;
        }
        try{
            if (camera != null) camera.close();
        }catch (Exception e){
            //
        }
        try{
            handlerThread = new HandlerThread("CameraThread");
            handlerThread.start();
            final Handler handler = new Handler(handlerThread.getLooper());
            cameraManager.openCamera(config.getCameraId(), cameraStateCallback, handler);
        }catch (CameraAccessException e){
            log("openCamera CameraAccessException: "+ e);
            return false;
        }
        return true;
    }

    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback(){
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice){
            camera = cameraDevice;
            isOpened = true;
            log("Camera device opened.");
            captureSessionInit();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice){
            log("Camera device disconnected.");
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int i){
            log("Camera device error.");
        }
    };

    public void captureSessionInit() {
        if (streamEncoder == null) return;
        Surface streamEncoderSurface = streamEncoder.initializeVideo();
        if (streamEncoderSurface == null) return;
        this.streamEncoderSurface = streamEncoderSurface;
        ArrayList<Surface> targets = new ArrayList<>();
        targets.add(streamEncoderSurface);
        if (mp4Recorder != null){
            Surface recorderSurface = mp4Recorder.initialize();
            if (recorderSurface != null){
                this.recorderSurface = recorderSurface;
                targets.add(recorderSurface);
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                List<OutputConfiguration> outputConfigurations = new ArrayList<>();
                for (Surface target : targets) {
                    OutputConfiguration outputConfig = new OutputConfiguration(target);
                    outputConfigurations.add(outputConfig);
                }

                final Handler handler = new Handler(handlerThread.getLooper());
                Executor executor = new Executor() {
                    @Override
                    public void execute(Runnable command) {
                        handler.post(command);
                    }
                };
                SessionConfiguration config = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputConfigurations, executor, sessionStateCallback);
                camera.createCaptureSession(config);
            }else{
                List<OutputConfiguration> outputConfigurations = new ArrayList<>();
                for (Surface target : targets) {
                    OutputConfiguration outputConfig = new OutputConfiguration(target);
                    outputConfigurations.add(outputConfig);
                }

                Handler handler = new Handler(handlerThread.getLooper());
                camera.createCaptureSessionByOutputConfigurations(outputConfigurations, sessionStateCallback, handler);
            }
        } catch (CameraAccessException e) {
            log("createCaptureSession CameraAccessException: " + e);
        } catch (IllegalArgumentException e) {
            log("createCaptureSession IllegalArgumentException: " + e);
        }
    }

    public void addRecorderSurface(){
        if (recorderSurface != null){
            mCaptureRequest.addTarget(recorderSurface);
            try{
                mCameraCaptureSession.setRepeatingRequest(mCaptureRequest.build(), captureCallback, new Handler(handlerThread.getLooper()));
            }catch (CameraAccessException e){
                log("addRecorderSurface CameraAccessException: "+ e);
            }
        }
    }

    public void removeRecorderSurface(){
        if (recorderSurface != null) mCaptureRequest.removeTarget(recorderSurface);
        try{
            mCameraCaptureSession.setRepeatingRequest(mCaptureRequest.build(), captureCallback, new Handler(handlerThread.getLooper()));
        }catch (CameraAccessException e){
            log("removeRecorderSurface CameraAccessException: "+ e);
        }
    }

    private final CameraCaptureSession.StateCallback sessionStateCallback = new CameraCaptureSession.StateCallback(){
        @Override
        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession){
            log("CameraCaptureSession - onConfigured");
            CaptureRequest.Builder captureRequest;
            try{
                captureRequest = cameraCaptureSession.getDevice().createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            }catch (CameraAccessException e){
                log("createCaptureRequest CameraAccessException: "+ e);
                return;
            }
            mCaptureRequest = captureRequest;
            mCameraCaptureSession = cameraCaptureSession;
            configureCamera(captureRequest);
            captureRequest.addTarget(streamEncoderSurface);
            try{
                cameraCaptureSession.setRepeatingRequest(captureRequest.build(), captureCallback, new Handler(handlerThread.getLooper()));
                cameraFrameCounter = 0;
                lastFps = 0;
                cameraFrameTimestamp = System.currentTimeMillis();
            }catch (Exception e){
                log("Camera - setRepeatingRequest error: "+ e);
                return;
            }
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession){
            log("CameraCaptureSession - onConfigureFailed");
        }
    };

    private boolean getCameraCharacteristics(String cameraId){
        int width = 0, height = 0;
        CameraCharacteristics characteristics;
        try{
            characteristics = cameraManager.getCameraCharacteristics(cameraId);
        }catch (CameraAccessException e){
            log("getCameraCharacteristics CameraAccessException: "+ e);
            return false;
        }
        highFps = false;
        logical = false;
        wideAngle = false;
        frontFacing = false;
        try {
            int lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);//0 - front, 1 - back, 2 - external
            if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) frontFacing = true;
        }catch (NullPointerException e){
            log("Get camera lens facing error: " + e);
        }
        int[] cameraCapabilities=characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (cameraCapabilities != null) {
            for (int cap : cameraCapabilities) {
                if (cap == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO)
                    highFps = true;
                if (cap == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA)
                    logical = true;
            }
        }

        float[] fLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        float minLength = 999;
        if (fLengths != null) {
            for (float length : fLengths) {
                if (length < minLength) minLength = length;
            }
        }
        if (minLength < 2) wideAngle = true;

        StreamConfigurationMap configurationMap = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (configurationMap != null) {
            if (highFps && frameRate.getUpper() > 30) {
                Size[] sizes = configurationMap.getHighSpeedVideoSizes();
                int minDiff = -1;
                if (sizes != null) {
                    for (Size size : sizes) {
                        int diff = Math.abs(size.getWidth() - targetResolution.getWidth()) + Math.abs(size.getHeight() - targetResolution.getHeight());
                        if (diff < minDiff || minDiff == -1) {
                            minDiff = diff;
                            width = size.getWidth();
                            height = size.getHeight();
                        }
                    }
                    if (((targetResolution.getWidth() - width) + (targetResolution.getHeight() - height)) > 1000)
                        highFps = false;
                }
                Range<Integer>[] fpsRanges = configurationMap.getHighSpeedVideoFpsRanges();
                int maxFps = 0;
                if (fpsRanges != null) {
                    for (Range<Integer> fpsRange : fpsRanges) {
                        if (fpsRange.getUpper() > maxFps) maxFps = fpsRange.getUpper();
                    }
                    if (frameRate.getUpper() > maxFps && maxFps != 0)
                        frameRate = new Range<>(frameRate.getLower(), maxFps);
                }
            }
        }
        if (width != targetResolution.getWidth() || height != targetResolution.getHeight()){
            int[] supportedFormats = null;
            if (configurationMap != null) {
                supportedFormats = configurationMap.getOutputFormats();
            }
            Size[] sizes = null;
            if (supportedFormats != null) {
                for (int format : supportedFormats) {
                    Size[] s = configurationMap.getOutputSizes(format);
                    if (s != null && (sizes == null || s.length > sizes.length)) sizes = s;
                }
            }
            int minDiff = -1;
            if (sizes != null) {
                for (Size size : sizes) {
                    int diff = Math.abs(size.getWidth() - targetResolution.getWidth()) + Math.abs(size.getHeight() - targetResolution.getHeight());
                    if (diff < minDiff || minDiff == -1) {
                        minDiff = diff;
                        width = size.getWidth();
                        height = size.getHeight();
                    }
                }
            }
            Range<Integer>[] fpsRanges=characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            int max = 0;
            if (fpsRanges != null) {
                for (Range<Integer> fpsRange : fpsRanges) {
                    if (fpsRange.getLower() / 2 + fpsRange.getUpper() > max) {
                        max = fpsRange.getLower() / 2 + fpsRange.getUpper();
                        frameRate = new Range<>(fpsRange.getLower(), fpsRange.getUpper());
                    }
                }
            }
        }
        if (frameRate.getLower() <= 0 || frameRate.getLower() > 60 || frameRate.getUpper() <= 0 || frameRate.getUpper() > 240){
            frameRate = targetFrameRange;
        }
        log("Min FPS: "+frameRate.getLower()+", Max FPS: "+frameRate.getUpper());
        cameraResolution = new Size(width, height);
        log("cameraResolution - Width: " + cameraResolution.getWidth() + ", Height: " + cameraResolution.getHeight());
        return true;
    }

    private void configureCamera(CaptureRequest.Builder captureRequest){
        captureRequest.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
        captureRequest.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
        captureRequest.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, frameRate);
        captureRequest.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0.0f);
        captureRequest.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
        captureRequest.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO);
        captureRequest.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_DISABLED);
        captureRequest.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
        captureRequest.set(CaptureRequest.HOT_PIXEL_MODE, CameraMetadata.HOT_PIXEL_MODE_OFF);
        captureRequest.set(CaptureRequest.JPEG_QUALITY, (byte)95);
        captureRequest.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            captureRequest.set(CaptureRequest.STATISTICS_OIS_DATA_MODE, CameraMetadata.STATISTICS_OIS_DATA_MODE_OFF);
        }
        captureRequest.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_FAST);
        captureRequest.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_FAST);
        captureRequest.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF);
        captureRequest.set(CaptureRequest.SHADING_MODE, CameraMetadata.SHADING_MODE_FAST);
        captureRequest.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_OFF);
        captureRequest.set(CaptureRequest.STATISTICS_HOT_PIXEL_MAP_MODE, false);
    }

    public int getFps(){
        long current = System.currentTimeMillis();
        float timeSec = (current - cameraFrameTimestamp) / 1000f;
        if (timeSec < 0.2f && lastFps > 0) return lastFps;
        int fps = Math.round(cameraFrameCounter / timeSec);
        cameraFrameCounter = 0;
        cameraFrameTimestamp = current;
        lastFps = fps;
        return fps;
    }

    private final CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback(){
        @Override
        public void onCaptureStarted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, long timestamp, long frameNumber){
            super.onCaptureStarted(session, request, timestamp, frameNumber);
        }

        @Override
        public void onCaptureProgressed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureResult partialResult){
            super.onCaptureProgressed(session, request, partialResult);
        }

        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result){
            super.onCaptureCompleted(session, request, result);
            cameraFrameCounter++;
        }

        @Override
        public void onCaptureFailed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull CaptureFailure failure){
            super.onCaptureFailed(session, request, failure);
            log("onCaptureFailed. Reason: "+failure.getReason());
        }

        @Override
        public void onCaptureSequenceCompleted(@NonNull CameraCaptureSession session, int sequenceId, long frameNumber){
            super.onCaptureSequenceCompleted(session, sequenceId, frameNumber);
        }

        @Override
        public void onCaptureSequenceAborted(@NonNull CameraCaptureSession session, int sequenceId){
            super.onCaptureSequenceAborted(session, sequenceId);
        }

        @Override
        public void onCaptureBufferLost(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull Surface target, long frameNumber){
            super.onCaptureBufferLost(session, request, target, frameNumber);
        }
    };

    public void close(){
        isOpened = false;
        if (camera != null){
            camera.close();
            log("Camera device closed.");
        }
        if (handlerThread != null){
            handlerThread.quitSafely();
        }
    }
}
