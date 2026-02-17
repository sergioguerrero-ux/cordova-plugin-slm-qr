package com.slm.qr;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Base64;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

public class SLMQR extends CordovaPlugin {

    private static final String TAG = "SLMQR";
    private static final int CAMERA_PERMISSION_REQUEST = 200;
    private static final int CAMERA_PERMISSION_PREVIEW = 201;

    private CallbackContext scanCallback;
    private String pendingScanMode;
    private JSONObject pendingScanOptions;

    // Embedded preview
    private FrameLayout embeddedContainer;
    private ProcessCameraProvider embeddedCameraProvider;
    private CallbackContext detectedCallback;
    private CallbackContext pendingPreviewCallback;
    private JSONObject pendingPreviewOptions;
    private String lastDetectedValue;
    private long lastDetectedTime = 0;

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
        Log.d(TAG, "======== execute() ========");
        Log.d(TAG, "  action: " + action);
        Log.d(TAG, "  args: " + args.toString());

        switch (action) {
            case "scanQR":
                Log.d(TAG, "  -> scanQR mode");
                pendingScanMode = "qr";
                pendingScanOptions = args.optJSONObject(0);
                scanCallback = callbackContext;
                startScan();
                return true;
            case "scanBarcode":
                Log.d(TAG, "  -> scanBarcode mode");
                pendingScanMode = "barcode";
                pendingScanOptions = args.optJSONObject(0);
                scanCallback = callbackContext;
                startScan();
                return true;
            case "generateQR":
                Log.d(TAG, "  -> generateQR");
                String data = args.optString(0, "");
                JSONObject options = args.optJSONObject(1);
                generateQR(data, options != null ? options : new JSONObject(), callbackContext);
                return true;
            case "openQRPreview":
                Log.d(TAG, "  -> openQRPreview");
                pendingPreviewOptions = args.optJSONObject(0);
                pendingPreviewCallback = callbackContext;
                if (!hasCameraPermission()) {
                    Log.d(TAG, "  No camera permission, requesting...");
                    cordova.requestPermission(this, CAMERA_PERMISSION_PREVIEW, Manifest.permission.CAMERA);
                } else {
                    Log.d(TAG, "  Camera permission OK, opening preview...");
                    openQRPreview(pendingPreviewOptions, callbackContext);
                }
                return true;
            case "closeQRPreview":
                Log.d(TAG, "  -> closeQRPreview");
                closeQRPreview(callbackContext);
                return true;
            case "onQRDetected":
                Log.d(TAG, "  -> onQRDetected (registering callback)");
                detectedCallback = callbackContext;
                return true;
            default:
                Log.w(TAG, "  -> UNKNOWN action: " + action);
                return false;
        }
    }

    // ============================================
    // Scan
    // ============================================

    private void startScan() {
        Log.d(TAG, "startScan() called");
        if (!hasCameraPermission()) {
            Log.d(TAG, "  No camera permission, requesting...");
            cordova.requestPermission(this, CAMERA_PERMISSION_REQUEST, Manifest.permission.CAMERA);
            return;
        }
        Log.d(TAG, "  Camera permission OK, opening scanner...");
        openScannerActivity();
    }

    private boolean hasCameraPermission() {
        boolean has = ContextCompat.checkSelfPermission(cordova.getActivity(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
        Log.d(TAG, "hasCameraPermission() = " + has);
        return has;
    }

    @Override
    public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException {
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        Log.d(TAG, "onRequestPermissionResult() requestCode=" + requestCode + " granted=" + granted);

        if (requestCode == CAMERA_PERMISSION_REQUEST) {
            if (granted) {
                Log.d(TAG, "  Camera scan permission granted, opening scanner");
                openScannerActivity();
            } else if (scanCallback != null) {
                Log.w(TAG, "  Camera scan permission DENIED");
                scanCallback.error("Permiso de camara denegado");
                scanCallback = null;
            }
        } else if (requestCode == CAMERA_PERMISSION_PREVIEW) {
            if (granted && pendingPreviewCallback != null) {
                Log.d(TAG, "  Camera preview permission granted, opening preview");
                openQRPreview(pendingPreviewOptions, pendingPreviewCallback);
            } else if (pendingPreviewCallback != null) {
                Log.w(TAG, "  Camera preview permission DENIED");
                pendingPreviewCallback.error("Permiso de camara denegado");
                pendingPreviewCallback = null;
            }
        }
    }

    private void openScannerActivity() {
        Log.d(TAG, "======== openScannerActivity() ========");
        final Activity activity = cordova.getActivity();
        Log.d(TAG, "  activity: " + activity);
        Log.d(TAG, "  activity class: " + activity.getClass().getName());
        Log.d(TAG, "  activity isLifecycleOwner: " + (activity instanceof LifecycleOwner));

        final JSONObject options = pendingScanOptions != null ? pendingScanOptions : new JSONObject();
        final String mode = pendingScanMode != null ? pendingScanMode : "qr";

        final String template = options.optString("template", "simple");
        final boolean flashlight = options.optBoolean("flashlight", true);
        final boolean vibrate = options.optBoolean("vibrate", true);
        final boolean useFrontCamera = "front".equals(options.optString("camera", "back"));
        final String title = options.optString("title", mode.equals("qr") ? "Escanea el codigo QR" : "Escanea el codigo de barras");

        Log.d(TAG, "  mode: " + mode);
        Log.d(TAG, "  template: " + template);
        Log.d(TAG, "  flashlight: " + flashlight);
        Log.d(TAG, "  vibrate: " + vibrate);
        Log.d(TAG, "  useFrontCamera: " + useFrontCamera);
        Log.d(TAG, "  title: " + title);

        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "  [UI thread] Creating fullscreen scanner...");
                // Create fullscreen FrameLayout
                final FrameLayout container = new FrameLayout(activity);
                container.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                ));
                container.setBackgroundColor(Color.BLACK);

                // Camera preview
                final PreviewView previewView = new PreviewView(activity);
                previewView.setLayoutParams(new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                ));
                container.addView(previewView);

                // Overlay
                final View overlayView = createOverlay(activity, template, title);
                container.addView(overlayView);

                // Cancel button
                Button cancelBtn = new Button(activity);
                cancelBtn.setText("Cancelar");
                cancelBtn.setTextColor(Color.WHITE);
                cancelBtn.setBackgroundColor(Color.TRANSPARENT);
                cancelBtn.setTextSize(16);
                FrameLayout.LayoutParams cancelParams = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
                cancelParams.gravity = Gravity.BOTTOM | Gravity.START;
                cancelParams.setMargins(40, 0, 0, 100);
                cancelBtn.setLayoutParams(cancelParams);
                container.addView(cancelBtn);

                // Flash button
                final Button flashBtn;
                if (flashlight) {
                    flashBtn = new Button(activity);
                    flashBtn.setText("Flash");
                    flashBtn.setTextColor(Color.WHITE);
                    flashBtn.setBackgroundColor(Color.TRANSPARENT);
                    flashBtn.setTextSize(16);
                    FrameLayout.LayoutParams flashParams = new FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                    );
                    flashParams.gravity = Gravity.BOTTOM | Gravity.END;
                    flashParams.setMargins(0, 0, 40, 100);
                    flashBtn.setLayoutParams(flashParams);
                    container.addView(flashBtn);
                } else {
                    flashBtn = null;
                }

                // Add to DecorView (on top of InAppBrowser)
                Log.d(TAG, "  [UI thread] Adding container to DecorView...");
                ViewGroup decorView = (ViewGroup) activity.getWindow().getDecorView();
                Log.d(TAG, "  [UI thread] DecorView: " + decorView);
                Log.d(TAG, "  [UI thread] DecorView childCount: " + decorView.getChildCount());
                decorView.addView(container, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                ));
                Log.d(TAG, "  [UI thread] Container added! DecorView childCount now: " + decorView.getChildCount());
                Log.d(TAG, "  [UI thread] Container visibility: " + container.getVisibility());
                Log.d(TAG, "  [UI thread] Container dimensions: " + container.getWidth() + "x" + container.getHeight());

                // Setup CameraX
                Log.d(TAG, "  [UI thread] Setting up CameraX...");
                final boolean[] hasDetected = {false};
                ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                        ProcessCameraProvider.getInstance(activity);

                Log.d(TAG, "  [UI thread] CameraProvider future obtained, adding listener...");
                cameraProviderFuture.addListener(() -> {
                    try {
                        Log.d(TAG, "  [CameraX listener] Getting camera provider...");
                        ProcessCameraProvider cameraProvider = cameraProviderFuture.get();
                        Log.d(TAG, "  [CameraX listener] Camera provider obtained: " + cameraProvider);

                        Preview preview = new Preview.Builder().build();
                        preview.setSurfaceProvider(previewView.getSurfaceProvider());
                        Log.d(TAG, "  [CameraX listener] Preview built, surface provider set");

                        CameraSelector cameraSelector = useFrontCamera
                                ? CameraSelector.DEFAULT_FRONT_CAMERA
                                : CameraSelector.DEFAULT_BACK_CAMERA;

                        // Barcode scanner options
                        BarcodeScannerOptions.Builder scannerBuilder = new BarcodeScannerOptions.Builder();
                        if ("qr".equals(mode)) {
                            scannerBuilder.setBarcodeFormats(Barcode.FORMAT_QR_CODE);
                        } else {
                            scannerBuilder.setBarcodeFormats(
                                    Barcode.FORMAT_EAN_8,
                                    Barcode.FORMAT_EAN_13,
                                    Barcode.FORMAT_UPC_A,
                                    Barcode.FORMAT_UPC_E,
                                    Barcode.FORMAT_CODE_39,
                                    Barcode.FORMAT_CODE_93,
                                    Barcode.FORMAT_CODE_128,
                                    Barcode.FORMAT_PDF417,
                                    Barcode.FORMAT_AZTEC,
                                    Barcode.FORMAT_ITF,
                                    Barcode.FORMAT_DATA_MATRIX
                            );
                        }

                        BarcodeScanner scanner = BarcodeScanning.getClient(scannerBuilder.build());
                        Log.d(TAG, "  [CameraX listener] BarcodeScanner created");

                        ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                                .setTargetResolution(new Size(1280, 720))
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build();
                        Log.d(TAG, "  [CameraX listener] ImageAnalysis built");

                        imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(activity), imageProxy -> {
                            if (hasDetected[0]) {
                                imageProxy.close();
                                return;
                            }

                            @SuppressWarnings("UnsafeOptInUsageError")
                            android.media.Image mediaImage = imageProxy.getImage();
                            if (mediaImage == null) {
                                imageProxy.close();
                                return;
                            }

                            InputImage image = InputImage.fromMediaImage(mediaImage, imageProxy.getImageInfo().getRotationDegrees());
                            scanner.process(image)
                                    .addOnSuccessListener(barcodes -> {
                                        if (!barcodes.isEmpty() && !hasDetected[0]) {
                                            hasDetected[0] = true;
                                            Barcode barcode = barcodes.get(0);

                                            // Vibrate
                                            if (vibrate) {
                                                Vibrator v = (Vibrator) activity.getSystemService(Activity.VIBRATOR_SERVICE);
                                                if (v != null) {
                                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                        v.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE));
                                                    } else {
                                                        v.vibrate(100);
                                                    }
                                                }
                                            }

                                            // Build result
                                            JSONObject result = new JSONObject();
                                            try {
                                                result.put("text", barcode.getRawValue());
                                                result.put("format", formatToString(barcode.getFormat()));

                                                if ("qr".equals(mode)) {
                                                    result.put("template", template);
                                                    if (barcode.getRawBytes() != null) {
                                                        result.put("rawBytes", Base64.encodeToString(barcode.getRawBytes(), Base64.NO_WRAP));
                                                    }
                                                }
                                            } catch (JSONException e) {
                                                Log.e(TAG, "JSON error: " + e.getMessage());
                                            }

                                            cameraProvider.unbindAll();
                                            activity.runOnUiThread(() -> {
                                                ((ViewGroup) container.getParent()).removeView(container);
                                            });

                                            if (scanCallback != null) {
                                                scanCallback.success(result);
                                                scanCallback = null;
                                            }
                                        }
                                        imageProxy.close();
                                    })
                                    .addOnFailureListener(e -> {
                                        Log.e(TAG, "Barcode scan error: " + e.getMessage());
                                        imageProxy.close();
                                    });
                        });

                        Log.d(TAG, "  [CameraX listener] Binding to lifecycle...");
                        Camera camera = cameraProvider.bindToLifecycle(
                                (LifecycleOwner) activity, cameraSelector, preview, imageAnalysis);
                        Log.d(TAG, "  [CameraX listener] Camera bound successfully! camera=" + camera);

                        // Flash toggle
                        if (flashBtn != null && camera.getCameraInfo().hasFlashUnit()) {
                            flashBtn.setOnClickListener(v -> {
                                boolean isOn = camera.getCameraInfo().getTorchState().getValue() != null
                                        && camera.getCameraInfo().getTorchState().getValue() == 1;
                                camera.getCameraControl().enableTorch(!isOn);
                            });
                        }

                        // Cancel
                        cancelBtn.setOnClickListener(v -> {
                            hasDetected[0] = true;
                            cameraProvider.unbindAll();
                            ((ViewGroup) container.getParent()).removeView(container);
                            if (scanCallback != null) {
                                scanCallback.error("Escaneo cancelado por el usuario");
                                scanCallback = null;
                            }
                        });

                    } catch (Exception e) {
                        Log.e(TAG, "  [CameraX listener] EXCEPTION: " + e.getClass().getName() + ": " + e.getMessage());
                        Log.e(TAG, "  [CameraX listener] Stack trace:", e);
                        if (scanCallback != null) {
                            scanCallback.error("Error al iniciar camara: " + e.getMessage());
                            scanCallback = null;
                        }
                    }
                }, ContextCompat.getMainExecutor(activity));
            }
        });
    }

    private View createOverlay(Activity activity, String template, String title) {
        FrameLayout overlay = new FrameLayout(activity);
        overlay.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        if ("fullscreen".equals(template)) {
            // No overlay for fullscreen
            return overlay;
        }

        // Title
        TextView titleView = new TextView(activity);
        titleView.setText(title);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(18);
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        titleParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        titleParams.topMargin = dpToPx(activity, 120);
        titleView.setLayoutParams(titleParams);
        overlay.addView(titleView);

        // Custom overlay drawn via ScanOverlayView
        ScanOverlayView scanOverlay = new ScanOverlayView(activity, template);
        scanOverlay.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        overlay.addView(scanOverlay);

        return overlay;
    }

    private int dpToPx(Activity activity, int dp) {
        float density = activity.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    // ============================================
    // Embedded QR Preview
    // ============================================

    private void openQRPreview(JSONObject options, CallbackContext callbackContext) {
        Log.d(TAG, "======== openQRPreview() ========");
        closeEmbeddedPreview();

        if (options == null) options = new JSONObject();
        final Activity activity = cordova.getActivity();
        final float density = activity.getResources().getDisplayMetrics().density;

        final int xPx = Math.round((float) options.optDouble("x", 0) * density);
        final int yPx = Math.round((float) options.optDouble("y", 0) * density);
        final int wPx = Math.round((float) options.optDouble("width",
                activity.getResources().getDisplayMetrics().widthPixels / density) * density);
        final int hPx = Math.round((float) options.optDouble("height", 300) * density);
        final boolean useFrontCamera = "front".equals(options.optString("camera", "back"));

        Log.d(TAG, "  density=" + density + " x=" + xPx + " y=" + yPx + " w=" + wPx + " h=" + hPx);
        Log.d(TAG, "  useFrontCamera=" + useFrontCamera);

        activity.runOnUiThread(() -> {
            Log.d(TAG, "  [UI thread] Creating embedded preview container...");
            FrameLayout container = new FrameLayout(activity);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(wPx, hPx);
            params.leftMargin = xPx;
            params.topMargin = yPx;
            container.setLayoutParams(params);
            container.setBackgroundColor(Color.BLACK);

            PreviewView previewView = new PreviewView(activity);
            previewView.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            ));
            container.addView(previewView);

            ViewGroup decorView = (ViewGroup) activity.getWindow().getDecorView();
            Log.d(TAG, "  [UI thread] Adding to DecorView...");
            decorView.addView(container);
            embeddedContainer = container;
            Log.d(TAG, "  [UI thread] Container added to DecorView");

            ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(activity);
            Log.d(TAG, "  [UI thread] CameraProvider future obtained, adding listener...");
            future.addListener(() -> {
                try {
                    Log.d(TAG, "  [Preview CameraX] Getting camera provider...");
                    ProcessCameraProvider cameraProvider = future.get();
                    embeddedCameraProvider = cameraProvider;
                    Log.d(TAG, "  [Preview CameraX] Camera provider obtained");

                    Preview preview = new Preview.Builder().build();
                    preview.setSurfaceProvider(previewView.getSurfaceProvider());
                    Log.d(TAG, "  [Preview CameraX] Preview built, surface provider set");

                    CameraSelector selector = useFrontCamera
                            ? CameraSelector.DEFAULT_FRONT_CAMERA
                            : CameraSelector.DEFAULT_BACK_CAMERA;

                    BarcodeScannerOptions scannerOpts = new BarcodeScannerOptions.Builder()
                            .setBarcodeFormats(
                                    Barcode.FORMAT_QR_CODE,
                                    Barcode.FORMAT_EAN_8, Barcode.FORMAT_EAN_13,
                                    Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
                                    Barcode.FORMAT_CODE_39, Barcode.FORMAT_CODE_93,
                                    Barcode.FORMAT_CODE_128, Barcode.FORMAT_PDF417,
                                    Barcode.FORMAT_AZTEC, Barcode.FORMAT_ITF,
                                    Barcode.FORMAT_DATA_MATRIX
                            ).build();

                    BarcodeScanner scanner = BarcodeScanning.getClient(scannerOpts);

                    ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                            .setTargetResolution(new Size(1280, 720))
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build();

                    imageAnalysis.setAnalyzer(ContextCompat.getMainExecutor(activity), imageProxy -> {
                        @SuppressWarnings("UnsafeOptInUsageError")
                        android.media.Image mediaImage = imageProxy.getImage();
                        if (mediaImage == null) { imageProxy.close(); return; }

                        InputImage image = InputImage.fromMediaImage(mediaImage,
                                imageProxy.getImageInfo().getRotationDegrees());

                        scanner.process(image)
                                .addOnSuccessListener(barcodes -> {
                                    if (!barcodes.isEmpty() && detectedCallback != null) {
                                        Barcode barcode = barcodes.get(0);
                                        String value = barcode.getRawValue();
                                        long now = System.currentTimeMillis();

                                        if (value != null && (!value.equals(lastDetectedValue) || (now - lastDetectedTime) > 2000)) {
                                            lastDetectedValue = value;
                                            lastDetectedTime = now;

                                            Vibrator v = (Vibrator) activity.getSystemService(Activity.VIBRATOR_SERVICE);
                                            if (v != null) {
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                    v.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE));
                                                } else {
                                                    v.vibrate(100);
                                                }
                                            }

                                            JSONObject result = new JSONObject();
                                            try {
                                                result.put("text", value);
                                                result.put("format", formatToString(barcode.getFormat()));
                                                if (barcode.getRawBytes() != null) {
                                                    result.put("rawBytes", Base64.encodeToString(barcode.getRawBytes(), Base64.NO_WRAP));
                                                }
                                            } catch (JSONException e) {
                                                Log.e(TAG, "JSON error: " + e.getMessage());
                                            }

                                            PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, result);
                                            pluginResult.setKeepCallback(true);
                                            detectedCallback.sendPluginResult(pluginResult);
                                        }
                                    }
                                    imageProxy.close();
                                })
                                .addOnFailureListener(e -> imageProxy.close());
                    });

                    Log.d(TAG, "  [Preview CameraX] Binding to lifecycle...");
                    cameraProvider.bindToLifecycle((LifecycleOwner) activity, selector, preview, imageAnalysis);
                    Log.d(TAG, "  [Preview CameraX] Camera bound successfully!");

                    JSONObject result = new JSONObject();
                    result.put("opened", true);
                    callbackContext.success(result);

                } catch (Exception e) {
                    Log.e(TAG, "  [Preview CameraX] EXCEPTION: " + e.getClass().getName() + ": " + e.getMessage());
                    Log.e(TAG, "  [Preview CameraX] Stack trace:", e);
                    callbackContext.error("Error iniciando camara: " + e.getMessage());
                }
            }, ContextCompat.getMainExecutor(activity));
        });
    }

    private void closeQRPreview(CallbackContext callbackContext) {
        cordova.getActivity().runOnUiThread(() -> {
            closeEmbeddedPreview();
            try {
                JSONObject result = new JSONObject();
                result.put("closed", true);
                callbackContext.success(result);
            } catch (JSONException e) {
                callbackContext.error(e.getMessage());
            }
        });
    }

    private void closeEmbeddedPreview() {
        if (embeddedCameraProvider != null) {
            embeddedCameraProvider.unbindAll();
            embeddedCameraProvider = null;
        }
        if (embeddedContainer != null && embeddedContainer.getParent() != null) {
            ((ViewGroup) embeddedContainer.getParent()).removeView(embeddedContainer);
            embeddedContainer = null;
        }
        lastDetectedValue = null;
        lastDetectedTime = 0;
    }

    // ============================================
    // Generate QR
    // ============================================

    private void generateQR(final String data, final JSONObject options, final CallbackContext callbackContext) {
        cordova.getThreadPool().execute(() -> {
            try {
                int size = options.optInt("size", 300);
                String colorHex = options.optString("color", "#000000");
                String bgColorHex = options.optString("backgroundColor", "#FFFFFF");
                String logoBase64 = options.optString("logo", null);
                String errorCorrectionStr = options.optString("errorCorrection", "M");

                ErrorCorrectionLevel ecLevel;
                switch (errorCorrectionStr) {
                    case "L": ecLevel = ErrorCorrectionLevel.L; break;
                    case "Q": ecLevel = ErrorCorrectionLevel.Q; break;
                    case "H": ecLevel = ErrorCorrectionLevel.H; break;
                    default: ecLevel = ErrorCorrectionLevel.M; break;
                }

                Map<EncodeHintType, Object> hints = new HashMap<>();
                hints.put(EncodeHintType.ERROR_CORRECTION, ecLevel);
                hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
                hints.put(EncodeHintType.MARGIN, 1);

                BitMatrix bitMatrix = new MultiFormatWriter().encode(data, BarcodeFormat.QR_CODE, size, size, hints);

                int fgColor = Color.parseColor(colorHex);
                int bgColor = Color.parseColor(bgColorHex);

                Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                for (int x = 0; x < size; x++) {
                    for (int y = 0; y < size; y++) {
                        bitmap.setPixel(x, y, bitMatrix.get(x, y) ? fgColor : bgColor);
                    }
                }

                // Add logo if provided
                if (logoBase64 != null && !logoBase64.isEmpty()) {
                    byte[] logoBytes = Base64.decode(logoBase64, Base64.DEFAULT);
                    Bitmap logoBitmap = android.graphics.BitmapFactory.decodeByteArray(logoBytes, 0, logoBytes.length);
                    if (logoBitmap != null) {
                        Canvas canvas = new Canvas(bitmap);
                        int logoSize = size / 4;
                        int logoX = (size - logoSize) / 2;
                        int logoY = (size - logoSize) / 2;

                        // White background for logo
                        Paint bgPaint = new Paint();
                        bgPaint.setColor(Color.WHITE);
                        int padding = 4;
                        canvas.drawRect(logoX - padding, logoY - padding, logoX + logoSize + padding, logoY + logoSize + padding, bgPaint);

                        Bitmap scaledLogo = Bitmap.createScaledBitmap(logoBitmap, logoSize, logoSize, true);
                        canvas.drawBitmap(scaledLogo, logoX, logoY, null);
                        scaledLogo.recycle();
                        logoBitmap.recycle();
                    }
                }

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
                String base64Image = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
                bitmap.recycle();

                JSONObject result = new JSONObject();
                result.put("base64Image", base64Image);
                result.put("format", "png");
                callbackContext.success(result);

            } catch (Exception e) {
                Log.e(TAG, "generateQR error: " + e.getMessage());
                callbackContext.error("Error generando QR: " + e.getMessage());
            }
        });
    }

    // ============================================
    // Helpers
    // ============================================

    private String formatToString(int format) {
        switch (format) {
            case Barcode.FORMAT_QR_CODE: return "QR_CODE";
            case Barcode.FORMAT_EAN_8: return "EAN_8";
            case Barcode.FORMAT_EAN_13: return "EAN_13";
            case Barcode.FORMAT_UPC_A: return "UPC_A";
            case Barcode.FORMAT_UPC_E: return "UPC_E";
            case Barcode.FORMAT_CODE_39: return "CODE_39";
            case Barcode.FORMAT_CODE_93: return "CODE_93";
            case Barcode.FORMAT_CODE_128: return "CODE_128";
            case Barcode.FORMAT_PDF417: return "PDF_417";
            case Barcode.FORMAT_AZTEC: return "AZTEC";
            case Barcode.FORMAT_ITF: return "ITF";
            case Barcode.FORMAT_DATA_MATRIX: return "DATA_MATRIX";
            default: return "UNKNOWN";
        }
    }

    // ============================================
    // Scan Overlay View (custom drawing)
    // ============================================

    private static class ScanOverlayView extends View {
        private final String template;
        private final Paint darkPaint;
        private final Paint cornerPaint;
        private final Paint borderPaint;
        private float animAlpha = 1.0f;
        private boolean animGoingDown = true;

        ScanOverlayView(Activity activity, String template) {
            super(activity);
            this.template = template;

            darkPaint = new Paint();
            darkPaint.setColor(Color.parseColor("#B3000000")); // 70% black
            darkPaint.setStyle(Paint.Style.FILL);

            cornerPaint = new Paint();
            cornerPaint.setColor(Color.parseColor("#00BFFF")); // Cyan accent
            cornerPaint.setStyle(Paint.Style.STROKE);
            cornerPaint.setStrokeWidth(6);
            cornerPaint.setStrokeCap(Paint.Cap.ROUND);
            cornerPaint.setAntiAlias(true);

            borderPaint = new Paint();
            borderPaint.setColor(Color.parseColor("#80FFFFFF"));
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(3);
            borderPaint.setAntiAlias(true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            int w = getWidth();
            int h = getHeight();

            if ("credential".equals(template)) {
                drawCredentialOverlay(canvas, w, h);
            } else {
                drawSimpleOverlay(canvas, w, h);
            }

            // Animate corners
            if (animGoingDown) {
                animAlpha -= 0.02f;
                if (animAlpha <= 0.3f) animGoingDown = false;
            } else {
                animAlpha += 0.02f;
                if (animAlpha >= 1.0f) animGoingDown = true;
            }
            cornerPaint.setAlpha((int) (animAlpha * 255));
            postInvalidateDelayed(30);
        }

        private void drawCredentialOverlay(Canvas canvas, int w, int h) {
            float cardWidth = w * 0.85f;
            float cardHeight = cardWidth / 1.586f;
            float cardX = (w - cardWidth) / 2;
            float cardY = (h - cardHeight) / 2;

            // Dark area (four rectangles around card)
            canvas.drawRect(0, 0, w, cardY, darkPaint); // top
            canvas.drawRect(0, cardY + cardHeight, w, h, darkPaint); // bottom
            canvas.drawRect(0, cardY, cardX, cardY + cardHeight, darkPaint); // left
            canvas.drawRect(cardX + cardWidth, cardY, w, cardY + cardHeight, darkPaint); // right

            // Card border
            RectF cardRect = new RectF(cardX, cardY, cardX + cardWidth, cardY + cardHeight);
            canvas.drawRoundRect(cardRect, 24, 24, borderPaint);

            // QR zone inside card (right side)
            float qrSize = cardHeight * 0.65f;
            float qrX = cardX + cardWidth - qrSize - cardWidth * 0.08f;
            float qrY = cardY + (cardHeight - qrSize) / 2;

            drawCorners(canvas, qrX, qrY, qrSize, qrSize);
        }

        private void drawSimpleOverlay(Canvas canvas, int w, int h) {
            float scanSize = Math.min(w, h) * 0.65f;
            float scanX = (w - scanSize) / 2;
            float scanY = (h - scanSize) / 2;

            // Dark area
            canvas.drawRect(0, 0, w, scanY, darkPaint);
            canvas.drawRect(0, scanY + scanSize, w, h, darkPaint);
            canvas.drawRect(0, scanY, scanX, scanY + scanSize, darkPaint);
            canvas.drawRect(scanX + scanSize, scanY, w, scanY + scanSize, darkPaint);

            drawCorners(canvas, scanX, scanY, scanSize, scanSize);
        }

        private void drawCorners(Canvas canvas, float x, float y, float width, float height) {
            float len = 48;

            // Top-left
            canvas.drawLine(x, y, x + len, y, cornerPaint);
            canvas.drawLine(x, y, x, y + len, cornerPaint);

            // Top-right
            canvas.drawLine(x + width - len, y, x + width, y, cornerPaint);
            canvas.drawLine(x + width, y, x + width, y + len, cornerPaint);

            // Bottom-left
            canvas.drawLine(x, y + height, x + len, y + height, cornerPaint);
            canvas.drawLine(x, y + height - len, x, y + height, cornerPaint);

            // Bottom-right
            canvas.drawLine(x + width - len, y + height, x + width, y + height, cornerPaint);
            canvas.drawLine(x + width, y + height - len, x + width, y + height, cornerPaint);
        }
    }
}
