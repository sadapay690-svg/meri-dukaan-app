package com.bithub.meridukaan;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.webkit.WebViewAssetLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

/**
 * Meri Dukaan — offline shopkeeper POS.
 *
 * The whole app is one HTML file in assets/index.html. It is served through
 * WebViewAssetLoader on https://appassets.androidplatform.net so that the page
 * gets a SECURE origin. That matters for three things:
 *   - localStorage survives reliably (file:// storage is flaky/not persistent)
 *   - navigator.mediaDevices.getUserMedia (barcode scanner) only works on https
 *   - Firebase cloud sync refuses to run on file://
 *
 * LIVE UPDATE
 * -----------
 * A newer copy of that same HTML file can be downloaded from the cloud by the
 * page itself and handed to upSave(). It is written to filesDir/live/index.html
 * and served from the SAME origin under /live/, so localStorage — the
 * shopkeeper's entire ledger — is shared between the bundled and the updated
 * copy. That is the whole point: features can ship without a new APK and
 * without touching anyone's data.
 *
 * A bad update must never brick the app, so upFail is incremented BEFORE the
 * live copy is loaded and only reset once the page reports a healthy boot via
 * bootOk(). Two silent failures and the live copy is thrown away, putting the
 * shopkeeper back on the APK's own copy.
 */
public class MainActivity extends Activity {

  private static final String ORIGIN = "https://appassets.androidplatform.net";
  private static final String HOME = ORIGIN + "/assets/index.html";
  private static final String LIVE = ORIGIN + "/live/index.html";

  /** Below this a "downloaded app" is obviously truncated, not an app. */
  private static final int MIN_APP = 120000;
  /** Two silent boot failures and the downloaded copy is discarded. */
  private static final int MAX_FAIL = 2;

  private static final int REQ_CAM = 11;
  private static final int REQ_FILE = 12;
  private static final int REQ_STORE = 13;
  private static final int REQ_SCAN = 14;

  private WebView web;
  private WebViewAssetLoader loader;
  private PermissionRequest camReq;
  private ValueCallback<Uri[]> filePick;
  private String pendName, pendText;
  private boolean backArmed = false;

  private final Runnable disarm = new Runnable() {
    public void run() { backArmed = false; }
  };

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);

    if (Build.VERSION.SDK_INT >= 21) {
      getWindow().setStatusBarColor(Color.parseColor("#047857"));
    }

    loader = new WebViewAssetLoader.Builder()
        .setDomain("appassets.androidplatform.net")
        .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
        .addPathHandler("/live/", liveHandler())
        .build();

    web = new WebView(this);
    web.setLayoutParams(new ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    web.setBackgroundColor(Color.parseColor("#f6f7f9"));

    WebSettings s = web.getSettings();
    s.setJavaScriptEnabled(true);
    s.setDomStorageEnabled(true);
    s.setDatabaseEnabled(true);
    s.setMediaPlaybackRequiresUserGesture(false);
    s.setSupportZoom(false);
    s.setBuiltInZoomControls(false);
    s.setDisplayZoomControls(false);
    s.setTextZoom(100);
    s.setAllowFileAccess(false);
    s.setAllowContentAccess(false);
    s.setJavaScriptCanOpenWindowsAutomatically(false);
    s.setSupportMultipleWindows(false);
    s.setCacheMode(WebSettings.LOAD_DEFAULT);
    if (Build.VERSION.SDK_INT >= 26) {
      s.setSafeBrowsingEnabled(false);
    }

    web.addJavascriptInterface(new Bridge(), "AND");
    web.setWebViewClient(new Client());
    web.setWebChromeClient(new Chrome());

    setContentView(web);

    if (state == null) {
      web.loadUrl(pickUrl());
    } else {
      web.restoreState(state);
    }
  }

  /* ---------------- live (downloaded) copy of the app ---------------- */

  private SharedPreferences pref() {
    return getSharedPreferences("md_up", MODE_PRIVATE);
  }

  private File liveDir() {
    return new File(getFilesDir(), "live");
  }

  private File liveFile() {
    return new File(liveDir(), "index.html");
  }

  /** Serves filesDir/live under /live/ on the same secure origin as /assets/. */
  private WebViewAssetLoader.PathHandler liveHandler() {
    File d = liveDir();
    if (!d.exists()) {
      d.mkdirs();
    }
    try {
      return new WebViewAssetLoader.InternalStoragePathHandler(this, d);
    } catch (Exception e) {
      /* Should not happen for getFilesDir(), but never let it stop the app. */
      return new WebViewAssetLoader.PathHandler() {
        public WebResourceResponse handle(String path) { return null; }
      };
    }
  }

  /**
   * Which copy runs: the downloaded one when it looks sane and has not been
   * failing, otherwise the one inside the APK.
   */
  private String pickUrl() {
    File f = liveFile();
    if (!f.exists() || f.length() < MIN_APP) {
      return HOME;
    }
    SharedPreferences p = pref();
    int fails = p.getInt("fail", 0);
    if (fails >= MAX_FAIL) {
      dropLive();
      toast("Naya update theek nahi chala — purani app wapas lag gayi");
      return HOME;
    }
    /* Counted as failed until the page itself says it booted fine. */
    p.edit().putInt("fail", fails + 1).apply();
    return LIVE;
  }

  private void dropLive() {
    try {
      File f = liveFile();
      if (f.exists()) {
        f.delete();
      }
    } catch (Exception e) { /* ignore */ }
    pref().edit().putInt("fail", 0).putInt("ver", 0).apply();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    super.onSaveInstanceState(out);
    web.saveState(out);
  }

  /* ---------------- WebView clients ---------------- */

  private class Client extends WebViewClient {

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
      return loader.shouldInterceptRequest(req.getUrl());
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView v, String url) {
      return loader.shouldInterceptRequest(Uri.parse(url));
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
      return route(req.getUrl() == null ? null : req.getUrl().toString());
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView v, String url) {
      return route(url);
    }

    @Override
    public void onPageFinished(WebView v, String url) {
      v.evaluateJavascript(GLUE, null);
    }
  }

  private class Chrome extends WebChromeClient {

    /** Barcode scanner asks for the camera. */
    @Override
    public void onPermissionRequest(final PermissionRequest req) {
      runOnUiThread(new Runnable() {
        public void run() {
          camReq = req;
          if (Build.VERSION.SDK_INT >= 23
              && checkSelfPermission(Manifest.permission.CAMERA)
                 != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAM);
          } else {
            grantCam(true);
          }
        }
      });
    }

    @Override
    public void onPermissionRequestCanceled(PermissionRequest req) {
      camReq = null;
    }

    /** "Restore" screen uses <input type="file">. */
    @Override
    public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb,
                                     FileChooserParams params) {
      if (filePick != null) {
        filePick.onReceiveValue(null);
      }
      filePick = cb;
      Intent i = new Intent(Intent.ACTION_GET_CONTENT);
      i.addCategory(Intent.CATEGORY_OPENABLE);
      i.setType("*/*");
      try {
        startActivityForResult(Intent.createChooser(i, "Backup file chunein"), REQ_FILE);
      } catch (ActivityNotFoundException e) {
        filePick = null;
        toast("File chunne wali app nahi mili");
        return false;
      }
      return true;
    }

    /* Note: the page now draws its own in-app dialogs (ask/askText), so this
       almost never fires. It stays as a safety net for any stray confirm(). */
    @Override
    public boolean onJsConfirm(WebView v, String url, String msg,
                               final android.webkit.JsResult res) {
      new android.app.AlertDialog.Builder(MainActivity.this)
          .setMessage(msg)
          .setCancelable(false)
          .setPositiveButton("Haan", new android.content.DialogInterface.OnClickListener() {
            public void onClick(android.content.DialogInterface d, int w) { res.confirm(); }
          })
          .setNegativeButton("Nahi", new android.content.DialogInterface.OnClickListener() {
            public void onClick(android.content.DialogInterface d, int w) { res.cancel(); }
          })
          .show();
      return true;
    }
  }

  /* ---------------- external links ---------------- */

  /** wa.me / tel: / mailto: etc. must leave the WebView and open the real app. */
  private boolean route(String url) {
    if (url == null || url.startsWith(ORIGIN)) {
      return false;
    }
    if (url.startsWith("http://") || url.startsWith("https://")
        || url.startsWith("tel:") || url.startsWith("mailto:")
        || url.startsWith("sms:") || url.startsWith("whatsapp:")
        || url.startsWith("intent:") || url.startsWith("market:")) {
      try {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
      } catch (Exception e) {
        toast("Ye link kholne wali app nahi mili");
      }
      return true;
    }
    return true;
  }

  /* ---------------- JS bridge ---------------- */

  /** Must stay public: WebView calls these by reflection. */
  public class Bridge {

    /** window.print() -> Android print dialog (also gives "Save as PDF"). */
    @JavascriptInterface
    public void printPage() {
      runOnUiThread(new Runnable() {
        public void run() { doPrint(); }
      });
    }

    /** <a download> with a blob: href -> save into the phone's Downloads folder. */
    @JavascriptInterface
    public void saveFile(final String name, final String text) {
      runOnUiThread(new Runnable() {
        public void run() { askSave(name, text); }
      });
    }

    @JavascriptInterface
    public void note(final String msg) {
      runOnUiThread(new Runnable() {
        public void run() { toast(msg); }
      });
    }

    /** openScan() -> ML Kit scanner screen (WebView has no BarcodeDetector). */
    @JavascriptInterface
    public void scanBarcode() {
      runOnUiThread(new Runnable() {
        public void run() {
          try {
            startActivityForResult(new Intent(MainActivity.this, ScanActivity.class), REQ_SCAN);
          } catch (Exception e) {
            sendCode("");
          }
        }
      });
    }
    /** "App Band Karein" — WebView mein window.close() kaam nahi karta. */
    @JavascriptInterface
    public void exitApp() {
      runOnUiThread(new Runnable() {
        public void run() { finish(); }
      });
    }

    /**
     * waImg() -> bill ki PNG tasveer WhatsApp ko de do.
     * WebView mein navigator.share({files}) nahi chalta, is liye tasveer
     * base64 mein aati hai, cache mein likhi jati hai aur FileProvider ke
     * zariye ACTION_SEND se bheji jati hai. WhatsApp apna contact picker
     * khud kholta hai (tasveer ke sath number pehle se chunna mumkin nahi).
     */
    @JavascriptInterface
    public void shareImage(final String name, final String b64, final String caption) {
      runOnUiThread(new Runnable() {
        public void run() { doShareImage(name, b64, caption); }
      });
    }

    /* ---------------- live update ---------------- */

    /** Build number of the downloaded copy, 0 when running the APK's own copy. */
    @JavascriptInterface
    public int upVer() {
      File f = liveFile();
      if (!f.exists() || f.length() < MIN_APP) {
        return 0;
      }
      return pref().getInt("ver", 0);
    }

    /** "live" = downloaded copy is running, "apk" = the one inside the APK. */
    @JavascriptInterface
    public String upWhere() {
      String u = web == null ? "" : String.valueOf(web.getUrl());
      return u.startsWith(LIVE) ? "live" : "apk";
    }

    /**
     * Store a freshly downloaded copy of the app. Written to a temp file first
     * and renamed, so a half-finished write can never become the live copy.
     */
    @JavascriptInterface
    public boolean upSave(String html, int ver) {
      if (html == null || html.length() < MIN_APP || ver <= 0) {
        return false;
      }
      try {
        File d = liveDir();
        if (!d.exists() && !d.mkdirs()) {
          return false;
        }
        File tmp = new File(d, "next.tmp");
        FileOutputStream fo = new FileOutputStream(tmp);
        fo.write(html.getBytes("UTF-8"));
        fo.flush();
        fo.close();
        File f = liveFile();
        if (f.exists() && !f.delete()) {
          return false;
        }
        if (!tmp.renameTo(f)) {
          tmp.delete();
          return false;
        }
        pref().edit().putInt("ver", ver).putInt("fail", 0).apply();
        return true;
      } catch (Exception e) {
        return false;
      }
    }

    /** Page booted and rendered fine — the copy that is running is trusted. */
    @JavascriptInterface
    public void bootOk() {
      pref().edit().putInt("fail", 0).apply();
    }

    /** Restart into whichever copy should run now. */
    @JavascriptInterface
    public void upApply() {
      runOnUiThread(new Runnable() {
        public void run() {
          web.clearCache(false);
          web.loadUrl(pickUrl());
        }
      });
    }

    /** "Purani app wapas lagao" — throw the downloaded copy away. */
    @JavascriptInterface
    public void upReset() {
      runOnUiThread(new Runnable() {
        public void run() {
          dropLive();
          web.clearCache(false);
          web.loadUrl(HOME);
        }
      });
    }
  }

  private void doShareImage(String name, String b64, String caption) {
    try {
      byte[] bytes = Base64.decode(b64 == null ? "" : b64, Base64.DEFAULT);
      if (bytes.length == 0) {
        toast("Tasveer khali thi");
        return;
      }
      File dir = new File(getCacheDir(), "share");
      if (!dir.exists()) {
        dir.mkdirs();
      }
      String fn = (name == null || name.length() == 0) ? "bill.png"
          : name.replaceAll("[^A-Za-z0-9._-]", "_");
      File f = new File(dir, fn);
      FileOutputStream fo = new FileOutputStream(f);
      fo.write(bytes);
      fo.close();

      Uri u = FileProvider.getUriForFile(this, getPackageName() + ".files", f);
      Intent send = new Intent(Intent.ACTION_SEND);
      send.setType("image/png");
      send.putExtra(Intent.EXTRA_STREAM, u);
      if (caption != null && caption.length() > 0) {
        send.putExtra(Intent.EXTRA_TEXT, caption);
      }
      send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

      /* WhatsApp mojood ho to seedha usi mein khol do, warna sab apps dikha do. */
      String pkg = has("com.whatsapp") ? "com.whatsapp"
          : (has("com.whatsapp.w4b") ? "com.whatsapp.w4b" : null);
      if (pkg != null) {
        try {
          Intent only = new Intent(send);
          only.setPackage(pkg);
          startActivity(only);
          return;
        } catch (Exception e) { /* neeche chooser chal jayega */ }
      }
      startActivity(Intent.createChooser(send, "Parchi bhejein"));
    } catch (Exception e) {
      toast("Tasveer bheji nahi ja saki");
    }
  }

  private boolean has(String pkg) {
    try {
      getPackageManager().getPackageInfo(pkg, 0);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  /** Hand the scanned barcode back to the page (empty string = type it by hand). */
  private void sendCode(String code) {
    String js = "window.__aScanDone&&__aScanDone(" + org.json.JSONObject.quote(code) + ")";
    web.evaluateJavascript(js, null);
  }

  private void doPrint() {
    try {
      PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
      String job = "Meri Dukaan";
      PrintDocumentAdapter ad = web.createPrintDocumentAdapter(job);
      pm.print(job, ad, new PrintAttributes.Builder().build());
    } catch (Exception e) {
      toast("Print nahi ho saka");
    }
  }

  private void askSave(String name, String text) {
    pendName = name;
    pendText = text;
    if (Build.VERSION.SDK_INT < 29 && Build.VERSION.SDK_INT >= 23
        && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
           != PackageManager.PERMISSION_GRANTED) {
      requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORE);
      return;
    }
    saveNow();
  }

  private void saveNow() {
    String name = pendName, text = pendText;
    pendName = null;
    pendText = null;
    if (name == null || text == null) {
      return;
    }
    String mime = name.endsWith(".json") ? "application/json"
        : name.endsWith(".csv") ? "text/csv" : "text/plain";
    try {
      if (Build.VERSION.SDK_INT >= 29) {
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (u == null) {
          throw new Exception("no uri");
        }
        OutputStream os = getContentResolver().openOutputStream(u);
        os.write(text.getBytes("UTF-8"));
        os.close();
      } else {
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!dir.exists()) {
          dir.mkdirs();
        }
        FileOutputStream fo = new FileOutputStream(new File(dir, name));
        fo.write(text.getBytes("UTF-8"));
        fo.close();
      }
      toast(name + " → Downloads folder mein save ho gaya");
    } catch (Exception e) {
      try {
        File f = new File(getExternalFilesDir(null), name);
        FileOutputStream fo = new FileOutputStream(f);
        fo.write(text.getBytes("UTF-8"));
        fo.close();
        toast(name + " save ho gaya (app folder)");
      } catch (Exception e2) {
        toast("File save nahi hui");
      }
    }
  }

  /* ---------------- permissions / results ---------------- */

  private void grantCam(boolean ok) {
    PermissionRequest r = camReq;
    camReq = null;
    if (r == null) {
      return;
    }
    if (ok) {
      r.grant(r.getResources());
    } else {
      r.deny();
      toast("Camera ki ijazat nahi mili — barcode scan band");
    }
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
    boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
    if (code == REQ_CAM) {
      grantCam(ok);
    } else if (code == REQ_STORE) {
      if (ok) {
        saveNow();
      } else {
        toast("File save karne ki ijazat nahi mili");
      }
    } else {
      super.onRequestPermissionsResult(code, perms, res);
    }
  }

  @Override
  protected void onActivityResult(int code, int result, Intent data) {
    if (code == REQ_SCAN) {
      if (result == RESULT_OK && data != null) {
        String bc = data.getStringExtra(ScanActivity.EXTRA_CODE);
        sendCode(bc == null ? "" : bc);
      }
      return;
    }
    if (code == REQ_FILE) {
      Uri[] out = null;
      if (result == RESULT_OK && data != null && data.getData() != null) {
        out = new Uri[]{data.getData()};
      }
      if (filePick != null) {
        filePick.onReceiveValue(out);
        filePick = null;
      }
      return;
    }
    super.onActivityResult(code, result, data);
  }

  /* ---------------- back button ---------------- */

  @Override
  public void onBackPressed() {
    web.evaluateJavascript(
        "(function(){try{return window.__aBack&&__aBack()?1:0}catch(e){return 0}})()",
        new ValueCallback<String>() {
          public void onReceiveValue(String v) {
            if ("1".equals(v)) {
              return;
            }
            if (backArmed) {
              finish();
              return;
            }
            backArmed = true;
            toast("App band karne ke liye dobara Back dabayein");
            web.postDelayed(disarm, 2000);
          }
        });
  }

  private void toast(String m) {
    Toast.makeText(this, m, Toast.LENGTH_SHORT).show();
  }

  /* ---------------- injected glue ----------------
   * The HTML file is untouched. These shims make the browser-only
   * bits behave inside a WebView:
   *   1. window.print()            -> Android print / Save-as-PDF
   *   2. <a download href=blob:>   -> real file in Downloads
   *   3. Back button               -> close sheet, else go Home
   *   4. Urdu Nastaliq font        -> served from the APK's assets
   *   5. openScan()                -> native ML Kit scanner
   *   6. Boot health-check         -> AND.bootOk(), the live copy's lifeline
   */
  private static final String GLUE =
      "(function(){if(!window.AND||window.__aReady)return;window.__aReady=1;"
    + "window.print=function(){try{AND.printPage();}catch(e){}};"
    + "var oc=HTMLAnchorElement.prototype.click;"
    + "HTMLAnchorElement.prototype.click=function(){"
    + "  var d=this.getAttribute('download'),h=this.href||'';"
    + "  if(d&&h.indexOf('blob:')===0){"
    + "    fetch(h).then(function(r){return r.text();})"
    + "      .then(function(t){AND.saveFile(d,t);})"
    + "      .catch(function(){AND.note('File save nahi hui');});"
    + "    return;"
    + "  }"
    + "  return oc.apply(this,arguments);"
    + "};"
    + "window.__aBack=function(){try{"
    + "  if(window.__aDlg&&__aDlg())return true;"
    + "  var o=document.getElementById('ov');"
    + "  if(o&&o.classList&&o.classList.contains('open')){closeSheet();return true;}"
    + "  var sy=document.getElementById('syncScr');"
    + "  if(sy&&!sy.hidden&&typeof closeSync==='function'){closeSync();return true;}"
    + "  var h=document.getElementById('v-home');"
    + "  if(h&&h.hidden&&typeof go==='function'){go('home');return true;}"
    + "}catch(e){}return false;};"
    /* 4. Urdu Nastaliq font — APK ke andar hai, phone mein nahi hota */
    + "try{var fs=document.createElement('style');"
    + "fs.textContent='@font-face{font-family:Noto Nastaliq Urdu;font-style:normal;"
    + "font-weight:400 700;font-display:swap;src:url(/assets/fonts/NotoNastaliqUrdu.ttf)}';"
    + "document.head.appendChild(fs);}catch(e){}"
    /* 5. Barcode scan — WebView mein BarcodeDetector nahi, native ML Kit chalao */
    + "window.__aScanFrom='sale';"
    + "window.__aScanDone=function(c){try{"
    + "  var f=window.__aScanFrom||'sale';"
    + "  if(c&&String(c).trim()){useCode(String(c).trim(),f);}else{openScanManual('');}"
    + "}catch(e){}};"
    + "if(typeof openScan==='function'){window.openScan=function(f){"
    + "  window.__aScanFrom=f||'sale';"
    + "  try{AND.scanBarcode();}catch(e){openScanManual('');}"
    + "};}"
    /* 6. Boot health-check — jab tak app waqai chal na jaye, "theek hai" nahi
     *    kehte. Naya (download kiya hua) app is signal ke bina do baar mein
     *    khud hat jata hai aur purana wapas aa jata hai. */
    + "(function(){var n=0;var t=setInterval(function(){"
    + "  n++;"
    + "  var okk=false;"
    + "  try{okk=(window.__mdBoot===1&&typeof render==='function'"
    + "    &&typeof saveDB==='function'&&typeof db==='object'&&!!db"
    + "    &&!!document.getElementById('v-home'));}catch(e){okk=false;}"
    + "  if(okk){clearInterval(t);try{AND.bootOk();}catch(e){}return;}"
    + "  if(n>25)clearInterval(t);"          /* ~10 second, phir haar maan lo */
    + "},400);})();"
    + "})();";
}
