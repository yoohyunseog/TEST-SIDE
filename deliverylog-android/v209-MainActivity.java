package com.chamsosik.deliverylog;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.webkit.WebSettings;
import android.webkit.JavascriptInterface;
import android.widget.Toast;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

/** Android wrapper for the offline delivery dashboard. No external data service. */
public final class MainActivity extends Activity {
    WebView webview;
    ValueCallback<Uri[]> callback;
    static final String PREFS = "deliverylog_native";
    private static final int SAVE_DOCUMENT = 2107;
    private String exportContents;
    private boolean exportWaiting = false;

    // Files are saved only to a location the user explicitly chooses.
    final class ClipboardBridge {
        @JavascriptInterface public boolean copyText(final String value) {
            if (value==null || value.length()>2_500_000) return false;
            try {
                runOnUiThread(() -> {
                    try {
                        android.content.ClipboardManager manager = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        if (manager!=null) manager.setPrimaryClip(android.content.ClipData.newPlainText("배달로그 동선·특이점",value));
                    } catch (Exception ignored) { }
                });
                return true;
            } catch (Exception e) { return false; }
        }
    }
    final class FileExportBridge {
        @JavascriptInterface public void saveFile(final String filename, final String mimeType, final String contents) {
            runOnUiThread(() -> showSavePicker(filename, mimeType, contents));
        }
    }
    void showSavePicker(String filename,String mimeType,String contents) {
        if(exportWaiting) { Toast.makeText(this,"먼저 진행 중인 파일 저장을 완료하세요.",Toast.LENGTH_LONG).show();return; }
        if(contents==null || contents.length()>25_000_000) {
            Toast.makeText(this,"파일 크기를 확인하세요.",Toast.LENGTH_LONG).show();return;
        }
        String safeName=filename==null?"deliverylog.geojson":filename.replaceAll("[^a-zA-Z0-9가-힣._-]","_");
        if(safeName.isEmpty()) safeName="deliverylog.geojson";
        String type;
        if(safeName.endsWith(".geojson"))type="application/geo+json";
        else if(safeName.endsWith(".json"))type="application/json";
        else if(safeName.endsWith(".csv"))type="text/csv";
        else {Toast.makeText(this,"지원하지 않는 파일 확장자입니다.",Toast.LENGTH_LONG).show();return;}
        exportContents=contents;
        exportWaiting=true;
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(type);
        intent.putExtra(Intent.EXTRA_TITLE,safeName);
        try {startActivityForResult(intent,SAVE_DOCUMENT);} catch(Exception e) {
            exportWaiting=false;exportContents=null;
            Toast.makeText(this,"파일 저장 창을 열 수 없습니다.",Toast.LENGTH_LONG).show();
        }
    }


    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webview = new WebView(this);
        WebSettings s = webview.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setGeolocationEnabled(true);
        s.setAllowFileAccess(true);
        webview.addJavascriptInterface(new FileExportBridge(),"DeliveryFileExport");
        webview.addJavascriptInterface(new ClipboardBridge(),"DeliveryClipboard");
        webview.setWebChromeClient(new DeliveryChrome(this));
        webview.setWebViewClient(new ChatGPTWebClient(this));
        setContentView(webview);
        webview.loadUrl("file:///android_asset/index.html");
        if(android.os.Build.VERSION.SDK_INT>=33) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.POST_NOTIFICATIONS},7);
        } else {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION},7);
        }
    }
    @Override protected void onResume() {
        super.onResume();
        // Warm GNSS only while this screen is visible, before the pickup tap.
        safeStartTracker();
    }
    @Override protected void onStop() {
        super.onStop();
        SharedPreferences prefs=getSharedPreferences(PREFS,0);
        if (prefs.getLong("pickup_started_at",0L)<=0L && !prefs.getBoolean("manual_tracking",false))
            stopService(new Intent(this,TrackerService.class));
    }
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results) {
        super.onRequestPermissionsResult(requestCode,permissions,results);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            { safeStartTracker(); }
    }
    void safeStartTracker() {
        try {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
                startForegroundService(new Intent(this, TrackerService.class));
        } catch (RuntimeException ignored) { /* permission or service startup restriction */ }
    }
    void onCommand(String url) {
        if ("deliverylog://start".equals(url)) {
            getSharedPreferences(PREFS,0).edit().putBoolean("manual_tracking",true).apply();
            safeStartTracker();return;
        }
        if ("deliverylog://stop".equals(url)) {
            getSharedPreferences(PREFS,0).edit().putBoolean("manual_tracking",false).apply();
            if (getSharedPreferences(PREFS,0).getLong("pickup_started_at",0L)<=0L) stopService(new Intent(this,TrackerService.class));return;
        }
        if ("deliverylog://overlay".equals(url)) {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
              Uri.parse("package:"+getPackageName())));return;
        }
        if ("deliverylog://poll".equals(url)) {flushPending();return;}
        if (url.startsWith("deliverylog://pickup-start")) {
            getSharedPreferences(PREFS,0).edit().putLong("pickup_started_at",System.currentTimeMillis())
                .putLong("gps_pickup_first_fix_delay_ms",-1L).apply();
            safeStartTracker();return;
        }
        if ("deliverylog://pickup-stop".equals(url)) {
            getSharedPreferences(PREFS,0).edit().remove("pickup_started_at").apply();return;
        }
        if (url.startsWith("deliverylog://cameras?")) {
            Uri uri=Uri.parse(url);
            SharedPreferences.Editor ed=getSharedPreferences(PREFS,0).edit();
            ed.putString("cameras",uri.getQueryParameter("points"));
            ed.putString("radius",uri.getQueryParameter("radius"));
            ed.putString("opacity",uri.getQueryParameter("opacity"));
            ed.putString("voice",uri.getQueryParameter("voice"));
            ed.apply();
        }
    }
    // App-owned location telemetry, not Android-wide application surveillance.
    JSONObject gpsFrequencySnapshot() {
        SharedPreferences p = getSharedPreferences(PREFS,0);
        JSONObject obj = new JSONObject();
        try {
            long now = System.currentTimeMillis();
            JSONArray stored;
            try { stored = new JSONArray(p.getString("gps_recent_callbacks","[]")); }
            catch (JSONException ex) { stored = new JSONArray(); }
            JSONArray recent = new JSONArray();
            for (int i=0; i<stored.length(); i++) {
                long t = stored.optLong(i,0);
                if(t>now-60000 && t<=now+5000) recent.put(t);
            }
            obj.put("active", p.getBoolean("gps_service_active",false));
            obj.put("serviceActive", p.getBoolean("gps_service_active",false));
            obj.put("pickupFirstFixMs",p.getLong("gps_pickup_first_fix_delay_ms",-1L));
            obj.put("receivedTotal",p.getLong("gps_callbacks_total",0));
            obj.put("gpsTotal",p.getLong("gps_callbacks_gps",0));
            obj.put("networkTotal",p.getLong("gps_callbacks_network",0));
            obj.put("acceptedTotal",p.getLong("gps_accepted_total",0));
            obj.put("lastReceivedAt",p.getLong("gps_last_callback_ms",0));
            obj.put("lastAcceptedAt",p.getLong("gps_last_accepted_ms",0));
            obj.put("lastProvider",p.getString("gps_last_provider",""));
            obj.put("gpsSubscriptions",p.getLong("gps_subscriptions_gps",0));
            obj.put("networkSubscriptions",p.getLong("gps_subscriptions_network",0));
            obj.put("recentMs",recent);
            obj.put("gpsRequestedMinMs",1000);
            obj.put("networkRequestedMinMs",2000);
        } catch (JSONException ignored) { }
        return obj;
    }

    void flushPending() {
        SharedPreferences prefs=getSharedPreferences(PREFS,0);
        String csv=prefs.getString("pending","");
        if (!csv.isEmpty()) {
            prefs.edit().putString("pending","").apply();
            // Payload is produced from native Location numeric fields only.
            webview.evaluateJavascript("window.deliveryNativeBatch('"+csv+"');",null);
        }
        webview.evaluateJavascript("window.deliveryNativeStats("+gpsFrequencySnapshot().toString()+");",null);
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==SAVE_DOCUMENT) {
            final String pending=exportContents;
            exportWaiting=false;exportContents=null;
            if(resultCode==RESULT_OK && data!=null && data.getData()!=null && pending!=null) {
                final Uri uri=data.getData();
                new Thread(() -> {
                    boolean ok=false;
                    try(OutputStream stream=getContentResolver().openOutputStream(uri,"w")) {
                        if(stream==null)throw new java.io.IOException("OutputStream null");
                        stream.write(pending.getBytes(StandardCharsets.UTF_8));
                        stream.flush();ok=true;
                    }catch(Exception ignored){}
                    final boolean saved=ok;
                    runOnUiThread(() -> Toast.makeText(this,saved?"GPS 기록 파일이 저장되었습니다.":"파일 저장에 실패했습니다.",Toast.LENGTH_LONG).show());
                }).start();
            }
            return;
        }
        if(callback!=null) {
            callback.onReceiveValue(android.webkit.WebChromeClient.FileChooserParams.parseResult(resultCode,data));
            callback=null;
        }
    }
}