package com.chamsosik.deliverylog;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationRequest;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONException;

/**
 * GPS + network fallback. Only the WebView's active pickup session is used for
 * a delivery route, so pre-pickup location observations are never part of it.
 * All data stays on the phone; no network upload is performed.
 */
public final class TrackerService extends Service implements LocationListener {
    private LocationManager manager;
    private WindowManager window;
    private TextView floating;
    private long lastWarn;
    private Location lastAccepted;
    private boolean gpsRegistered = false;
    private boolean networkRegistered = false;
    private static final String TRACKER_CHANNEL = "deliverylog_tracker";
    private static final String CAMERA_CHANNEL = "deliverylog_camera_alert_v203";

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(TRACKER_CHANNEL,
                "배달로그 GPS 수신", NotificationManager.IMPORTANCE_LOW));
        Notification note = new Notification.Builder(this, TRACKER_CHANNEL)
                .setContentTitle("배달로그 GPS 준비 중")
                .setContentText("픽업을 누르면 배달 완료까지 동선이 저장됩니다")
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setOngoing(true)
                .build();
        try {
            startForeground(1, note);
        } catch (RuntimeException e) {
            // No permission / restricted launch: do not crash the activity.
            stopSelf();
            return;
        }
        getSharedPreferences(MainActivity.PREFS,0).edit()
                .putBoolean("gps_service_active", true).apply();
        beginLocationUpdates();
        createOverlay();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!gpsRegistered || !networkRegistered) beginLocationUpdates();
        createOverlay();
        return START_STICKY;
    }

    private void beginLocationUpdates() {
        if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        manager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (manager == null) return;
        try {
            if (!gpsRegistered && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                    && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                // Request highest practical accuracy and zero distance threshold while active.
                // Android still controls actual delivery rate and GNSS hardware scheduling.
                if (Build.VERSION.SDK_INT >= 31) {
                    LocationRequest req = new LocationRequest.Builder(1000L)
                            .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY)
                            .setMinUpdateIntervalMillis(500L)
                            .setMinUpdateDistanceMeters(0f).setMaxUpdateDelayMillis(0L).build();
                    manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, req, getMainExecutor(), this);
                } else manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this);
                gpsRegistered = true;
                noteSubscription("gps");
            }
        } catch (RuntimeException ignored) { }
        try {
            // Indoors or between buildings, network location may arrive before GPS.
            if (!networkRegistered && manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                if (Build.VERSION.SDK_INT >= 31) {
                    LocationRequest req = new LocationRequest.Builder(2000L)
                            .setQuality(LocationRequest.QUALITY_BALANCED_POWER_ACCURACY)
                            .setMinUpdateIntervalMillis(1000L)
                            .setMinUpdateDistanceMeters(0f).build();
                    manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, req, getMainExecutor(), this);
                } else manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000L, 0f, this);
                networkRegistered = true;
                noteSubscription("network");
            }
        } catch (RuntimeException ignored) { }
        for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
            try {
                Location cached = manager.getLastKnownLocation(provider);
                if (cached != null && Math.abs(System.currentTimeMillis() - cached.getTime()) <= 10000L)
                    processLocation(cached, false);
            } catch (RuntimeException ignored) { }
        }
    }

    private void createOverlay() {
        if (floating != null || !Settings.canDrawOverlays(this)) return;
        try {
            window = (WindowManager) getSystemService(WINDOW_SERVICE);
            floating = new TextView(this);
            floating.setText("배달로그");
            floating.setTextColor(Color.WHITE);
            floating.setGravity(Gravity.CENTER);
            floating.setBackgroundColor(0xCC19406B);
            float pct = 30f;
            try { pct = Float.parseFloat(getSharedPreferences(MainActivity.PREFS,0)
                    .getString("opacity", "30")); } catch (Exception ignored) { }
            floating.setAlpha(Math.min(0.8f, Math.max(0.15f, pct / 100f)));
            floating.setOnClickListener(v -> {
                Intent i = new Intent(this, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(i);
            });
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(125, 55,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.RIGHT | Gravity.TOP;
            lp.y = 200;
            window.addView(floating, lp);
        } catch (RuntimeException ignored) {
            floating = null;
        }
    }

    @Override public void onLocationChanged(Location loc) {
        processLocation(loc, true);
    }

    // Counts observed callbacks. Request registration is counted separately.
    // A cached getLastKnownLocation reading does not count as a live callback.
    private void processLocation(Location loc, boolean liveCallback) {
        if (loc == null) return;
        if (liveCallback) noteCallback(loc);
        if (!loc.hasAccuracy()) return;
        long now = System.currentTimeMillis();
        if (loc.getTime() <= 0 || now - loc.getTime() > 20000L || loc.getTime() > now + 5000L) return;
        if (loc.getAccuracy() < 0 || loc.getAccuracy() > 1000f) return;
        // Prefer a recent higher-accuracy fix over a jumpy network position.
        if (lastAccepted != null && loc.getTime() <= lastAccepted.getTime()) return;
        if (lastAccepted != null && loc.getProvider().equals(LocationManager.NETWORK_PROVIDER)
                && lastAccepted.getProvider().equals(LocationManager.GPS_PROVIDER)
                && now - lastAccepted.getTime() < 9000L
                && loc.getAccuracy() > lastAccepted.getAccuracy() * 1.5f) return;
        lastAccepted = new Location(loc);
        // Record pickup-to-first-fix delay; never pretend a cached/pre-pickup fix is fresh.
        SharedPreferences metrics = getSharedPreferences(MainActivity.PREFS,0);
        long pickupAt=metrics.getLong("pickup_started_at",0L);
        if (liveCallback && pickupAt>0L && metrics.getLong("gps_pickup_first_fix_delay_ms",-1L)<0L &&
                loc.getAccuracy()<=120f && loc.getTime()>=pickupAt) {
            metrics.edit().putLong("gps_pickup_first_fix_delay_ms", Math.max(0L, System.currentTimeMillis()-pickupAt)).apply();
        }
        if (liveCallback) noteAccepted();
        savePoint(loc);
        // 모의 위치로 단속카메라 경고가 발생하지 않게 제외합니다.
        if (loc.getAccuracy() <= 100f && !loc.isFromMockProvider()) checkCamera(loc);
    }


    /** Local-only GPS diagnostic counts. No coordinates leave the device. */
    private void noteSubscription(String provider) {
        SharedPreferences p = getSharedPreferences(MainActivity.PREFS, 0);
        String key = "gps_subscriptions_" + provider;
        p.edit().putLong(key, p.getLong(key, 0L) + 1)
            .putLong("gps_last_request_ms", System.currentTimeMillis()).apply();
    }

    private void noteCallback(Location loc) {
        SharedPreferences p = getSharedPreferences(MainActivity.PREFS, 0);
        long now = System.currentTimeMillis();
        long total = p.getLong("gps_callbacks_total", 0L) + 1;
        String provider = loc.getProvider();
        boolean gps = LocationManager.GPS_PROVIDER.equals(provider);
        boolean net = LocationManager.NETWORK_PROVIDER.equals(provider);
        JSONArray old;
        try { old = new JSONArray(p.getString("gps_recent_callbacks", "[]")); }
        catch (JSONException e) { old = new JSONArray(); }
        JSONArray fresh = new JSONArray();
        for (int i = 0; i < old.length(); i++) {
            long ts = old.optLong(i, 0);
            if (ts > now - 60000L && ts <= now + 5000L) fresh.put(ts);
        }
        fresh.put(now);
        // Cap the last-minute buffer in case a faulty provider floods callbacks.
        JSONArray bounded = new JSONArray();
        for (int i=Math.max(0, fresh.length()-180); i<fresh.length(); i++) bounded.put(fresh.optLong(i));
        SharedPreferences.Editor e = p.edit().putLong("gps_callbacks_total",total)
                .putLong("gps_last_callback_ms",now)
                .putString("gps_last_provider",provider == null ? "unknown" : provider)
                .putString("gps_recent_callbacks",bounded.toString());
        if(gps) e.putLong("gps_callbacks_gps",p.getLong("gps_callbacks_gps",0L)+1);
        if(net) e.putLong("gps_callbacks_network",p.getLong("gps_callbacks_network",0L)+1);
        e.apply();
    }

    private void noteAccepted() {
        SharedPreferences p = getSharedPreferences(MainActivity.PREFS, 0);
        p.edit().putLong("gps_accepted_total",p.getLong("gps_accepted_total",0L)+1)
                .putLong("gps_last_accepted_ms", System.currentTimeMillis()).apply();
    }

    private void savePoint(Location l) {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS,0);
        String existing = prefs.getString("pending", "");
        String next = existing + l.getLatitude() + "," + l.getLongitude() + ","
                + Math.round(l.getAccuracy()) + "," + l.getTime() + ","
                + (l.isFromMockProvider() ? "1" : "0") + ";";
        // Bound private buffer size during long background runs. The JavaScript
        // importer detects timestamp gaps and never joins missing paths as a line.
        if (next.length() > 450000) {
            int cut = next.indexOf(';', next.length() - 350000);
            if (cut > 0) next = next.substring(cut + 1);
        }
        prefs.edit().putString("pending", next).apply();
    }

    private void checkCamera(Location where) {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS,0);
        String list = prefs.getString("cameras", "");
        if (list == null || list.isEmpty()) return;
        float radius = 300;
        try { radius = Float.parseFloat(prefs.getString("radius", "300")); }
        catch (Exception ignored) { }
        for (String item : list.split(";")) {
            String[] parts = item.split(",");
            if (parts.length != 2) continue;
            try {
                Location camera = new Location("user-registered-camera");
                camera.setLatitude(Double.parseDouble(parts[0]));
                camera.setLongitude(Double.parseDouble(parts[1]));
                if (where.distanceTo(camera) <= radius) { warnCamera(); return; }
            } catch (NumberFormatException ignored) { }
        }
    }

    private void warnCamera() {
        long now = System.currentTimeMillis();
        if (now - lastWarn < 120000L) return;
        lastWarn = now;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null || !nm.areNotificationsEnabled()) return;
        nm.createNotificationChannel(new NotificationChannel(CAMERA_CHANNEL,
                "단속카메라 안전 경고", NotificationManager.IMPORTANCE_HIGH));
        Notification alert = new Notification.Builder(this, CAMERA_CHANNEL)
                .setContentTitle("등록된 단속카메라 지점 주의")
                .setContentText("주변 카메라 등록 위치입니다. 안전속도를 유지하세요.")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setAutoCancel(true)
                .build();
        try { nm.notify(101, alert); } catch (SecurityException ignored) { }
    }

    @Override public void onProviderEnabled(String provider) { beginLocationUpdates(); }
    @Override public void onProviderDisabled(String provider) {
        if (LocationManager.GPS_PROVIDER.equals(provider)) gpsRegistered = false;
        if (LocationManager.NETWORK_PROVIDER.equals(provider)) networkRegistered = false;
    }
    @Override public void onStatusChanged(String provider, int status, Bundle extras) { }
    @Override public android.os.IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        try { if (floating != null && window != null) window.removeView(floating); }
        catch (RuntimeException ignored) { }
        if (manager != null) manager.removeUpdates(this);
        getSharedPreferences(MainActivity.PREFS,0).edit()
                .putBoolean("gps_service_active",false).apply();
        super.onDestroy();
    }
}