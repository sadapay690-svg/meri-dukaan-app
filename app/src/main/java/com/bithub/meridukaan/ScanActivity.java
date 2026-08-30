package com.bithub.meridukaan;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Native barcode scanner.
 *
 * The HTML app tries navigator.mediaDevices + window.BarcodeDetector, but
 * Android's WebView does not ship the Barcode Detection API, so scanning was
 * effectively dead inside the app. Google's ML Kit model is bundled in the
 * APK (no download, no internet) and reads worn / blurry / curved barcodes far
 * better than a JS decoder on a cheap phone camera.
 *
 * Result is handed back to MainActivity, which calls window.__aScanDone(code).
 */
public class ScanActivity extends ComponentActivity {

  public static final String EXTRA_CODE = "md_code";
  public static final String EXTRA_MANUAL = "md_manual";

  private static final int REQ_CAM = 21;

  private PreviewView preview;
  private TextView hint;
  private BarcodeScanner scanner;
  private ExecutorService exec;
  private Camera camera;
  private boolean torchOn = false;
  private boolean done = false;

  private int dp(int v) {
    return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
  }

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);

    exec = Executors.newSingleThreadExecutor();

    BarcodeScannerOptions opt = new BarcodeScannerOptions.Builder()
        .setBarcodeFormats(
            Barcode.FORMAT_EAN_13,
            Barcode.FORMAT_EAN_8,
            Barcode.FORMAT_UPC_A,
            Barcode.FORMAT_UPC_E,
            Barcode.FORMAT_CODE_128,
            Barcode.FORMAT_CODE_39,
            Barcode.FORMAT_CODE_93,
            Barcode.FORMAT_ITF,
            Barcode.FORMAT_CODABAR,
            Barcode.FORMAT_QR_CODE)
        .build();
    scanner = BarcodeScanning.getClient(opt);

    setContentView(buildUi());

    if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM);
    } else {
      startCamera();
    }
  }

  /* ---------------- UI (built in code, no layout xml) ---------------- */

  private View buildUi() {
    FrameLayout root = new FrameLayout(this);
    root.setBackgroundColor(Color.BLACK);

    preview = new PreviewView(this);
    preview.setLayoutParams(new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    root.addView(preview);

    View line = new View(this);
    line.setBackgroundColor(Color.parseColor("#EF4444"));
    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, dp(2));
    lp.gravity = Gravity.CENTER_VERTICAL;
    lp.leftMargin = dp(34);
    lp.rightMargin = dp(34);
    root.addView(line, lp);

    hint = new TextView(this);
    hint.setText("Barcode ko camera ke saamne rakhein…");
    hint.setTextColor(Color.WHITE);
    hint.setTextSize(15.5f);
    hint.setGravity(Gravity.CENTER);
    hint.setPadding(dp(16), dp(20), dp(16), dp(20));
    hint.setBackgroundColor(Color.parseColor("#B3000000"));
    FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    hp.gravity = Gravity.TOP;
    root.addView(hint, hp);

    LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setBackgroundColor(Color.parseColor("#B3000000"));
    row.setPadding(dp(10), dp(10), dp(10), dp(14));
    row.addView(btn("Band Karo", 1));
    row.addView(btn("🔆 Roshni", 2));
    row.addView(btn("⌨️ Haath se", 3));
    FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    rp.gravity = Gravity.BOTTOM;
    root.addView(row, rp);

    return root;
  }

  private Button btn(String text, final int what) {
    Button b = new Button(this);
    b.setText(text);
    b.setAllCaps(false);
    b.setTextColor(Color.WHITE);
    b.setTextSize(14.5f);
    b.setBackgroundColor(Color.parseColor("#1F2937"));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    p.leftMargin = dp(4);
    p.rightMargin = dp(4);
    b.setLayoutParams(p);
    b.setOnClickListener(new View.OnClickListener() {
      public void onClick(View v) {
        if (what == 1) {
          setResult(RESULT_CANCELED);
          finish();
        } else if (what == 2) {
          toggleTorch();
        } else {
          Intent i = new Intent();
          i.putExtra(EXTRA_MANUAL, true);
          setResult(RESULT_OK, i);
          finish();
        }
      }
    });
    return b;
  }

  private void toggleTorch() {
    if (camera == null || !camera.getCameraInfo().hasFlashUnit()) {
      Toast.makeText(this, "Is phone mein flash nahi", Toast.LENGTH_SHORT).show();
      return;
    }
    torchOn = !torchOn;
    camera.getCameraControl().enableTorch(torchOn);
  }

  /* ---------------- camera ---------------- */

  private void startCamera() {
    final com.google.common.util.concurrent.ListenableFuture<ProcessCameraProvider> fut =
        ProcessCameraProvider.getInstance(this);
    fut.addListener(new Runnable() {
      public void run() {
        try {
          bind(fut.get());
        } catch (Exception e) {
          hint.setText("Camera nahi khula — ⌨️ Haath se likhein");
        }
      }
    }, ContextCompat.getMainExecutor(this));
  }

  private void bind(ProcessCameraProvider provider) {
    Preview pv = new Preview.Builder().build();
    pv.setSurfaceProvider(preview.getSurfaceProvider());

    ImageAnalysis ana = new ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
        .build();
    ana.setAnalyzer(exec, new ImageAnalysis.Analyzer() {
      public void analyze(final ImageProxy proxy) {
        look(proxy);
      }
    });

    provider.unbindAll();
    camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pv, ana);
  }

  /** One camera frame -> ML Kit. Every frame MUST be closed or the feed stalls. */
  private void look(final ImageProxy proxy) {
    if (done) {
      proxy.close();
      return;
    }
    android.media.Image raw = proxy.getImage();
    if (raw == null) {
      proxy.close();
      return;
    }
    InputImage img = InputImage.fromMediaImage(raw, proxy.getImageInfo().getRotationDegrees());
    scanner.process(img)
        .addOnSuccessListener(new OnSuccessListener<List<Barcode>>() {
          public void onSuccess(List<Barcode> found) {
            if (done || found == null || found.isEmpty()) {
              return;
            }
            String v = found.get(0).getRawValue();
            if (v != null && v.trim().length() > 0) {
              hit(v.trim());
            }
          }
        })
        .addOnCompleteListener(new OnCompleteListener<List<Barcode>>() {
          public void onComplete(Task<List<Barcode>> t) {
            proxy.close();
          }
        });
  }

  private void hit(String code) {
    if (done) {
      return;
    }
    done = true;
    try {
      android.os.Vibrator vb = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
      if (vb != null) {
        vb.vibrate(60);
      }
    } catch (Exception e) {
      // vibration is a nicety, never fail on it
    }
    Intent i = new Intent();
    i.putExtra(EXTRA_CODE, code);
    setResult(RESULT_OK, i);
    finish();
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
    if (code == REQ_CAM) {
      if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
        startCamera();
      } else {
        Toast.makeText(this, "Camera ki ijazat nahi mili", Toast.LENGTH_SHORT).show();
        Intent i = new Intent();
        i.putExtra(EXTRA_MANUAL, true);
        setResult(RESULT_OK, i);
        finish();
      }
      return;
    }
    super.onRequestPermissionsResult(code, perms, res);
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    try {
      if (scanner != null) {
        scanner.close();
      }
    } catch (Exception e) {
      // ignore
    }
    if (exec != null) {
      exec.shutdown();
    }
  }
}
