package com.chamsosik.deliverylog.testbuild;
import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.location.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.TextView;
import org.json.*;

public class TrackerService extends Service implements LocationListener {
  LocationManager lm; Location prev;
  WindowManager wm; TextView floating;
  static final String CHANNEL="deliverylog_gps_test", WARN="deliverylog_camera_test";
  long lastCamera=0;
  android.content.SharedPreferences p;
  public void onCreate(){
    super.onCreate(); p=getSharedPreferences(MainActivity.PREF,0);
    NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
    nm.createNotificationChannel(new NotificationChannel(CHANNEL,"배달로그 위치 기록",NotificationManager.IMPORTANCE_LOW));
    nm.createNotificationChannel(new NotificationChannel(WARN,"단속카메라 안전 경고",NotificationManager.IMPORTANCE_HIGH));
    Notification n=new Notification.Builder(this,CHANNEL).setContentTitle("배달로그 GPS 실행 중")
      .setContentText("픽업 시작 후 배달 완료까지 위치를 저장합니다").setSmallIcon(android.R.drawable.ic_menu_mylocation).build();
    try{startForeground(42,n);}catch(Exception e){stopSelf();return;}
    subscribe();overlay();
  }
  @Override public int onStartCommand(Intent intent,int flags,int id){ subscribe();overlay();return START_STICKY; }
  void subscribe(){
    if(lm==null)lm=(LocationManager)getSystemService(LOCATION_SERVICE);
    if(lm==null)return;
    try{
      if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
         && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)){
        lm.removeUpdates(this);
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,1500,3,this);
        p.edit().putLong("gpsRequests",p.getLong("gpsRequests",0)+1).apply();
      }
    }catch(Exception ignored){}
    try{
      if((checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
          checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED)
         && lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)){
        lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,3000,5,this);
        p.edit().putLong("netRequests",p.getLong("netRequests",0)+1).apply();
      }
    }catch(Exception ignored){}
  }
  @Override public void onLocationChanged(Location l){
    long now=System.currentTimeMillis();
    String provider=l.getProvider();
    boolean gps=LocationManager.GPS_PROVIDER.equals(provider);
    String counter=gps?"gpsCallbacks":"netCallbacks";
    android.content.SharedPreferences.Editor ed=p.edit().putLong(counter,p.getLong(counter,0)+1);
    JSONArray recent=getArray("recent"),filtered=new JSONArray();
    for(int i=0;i<recent.length();i++){long t=recent.optLong(i);if(t>now-60000 && t<now+3000)filtered.put(t);}
    filtered.put(now);
    ed.putString("recent",filtered.toString()).apply();
    if(android.os.Build.VERSION.SDK_INT>=31?l.isMock():l.isFromMockProvider()){
      p.edit().putLong("mockCallbacks",p.getLong("mockCallbacks",0)+1).apply();return;
    }
    if(!l.hasAccuracy()||l.getAccuracy()>180||Math.abs(now-l.getTime())>20000)return;
    if(prev!=null) {
      long dt=l.getTime()-prev.getTime();
      if(dt<=0)return;
      if(dt<15000 && prev.distanceTo(l)/(dt/1000f)>55){
        p.edit().putLong("rejectedJumps",p.getLong("rejectedJumps",0)+1).apply();return;
      }
    }
    prev=l;
    ed=p.edit().putLong("lastFixAt",now).putFloat("accuracy",l.getAccuracy())
       .putLong("lat",Double.doubleToRawLongBits(l.getLatitude()))
       .putLong("lon",Double.doubleToRawLongBits(l.getLongitude()));
    if(p.getBoolean("active",false)){
      JSONArray route=getArray("route");
      try{
        JSONObject x=new JSONObject();x.put("lat",l.getLatitude());x.put("lon",l.getLongitude());
        x.put("at",l.getTime());x.put("accuracy",l.getAccuracy());route.put(x);
        ed.putString("route",route.toString());
      }catch(Exception ignored){}
    }
    ed.apply();camera(l);
  }
  JSONArray getArray(String name){try{return new JSONArray(p.getString(name,"[]"));}catch(Exception e){return new JSONArray();}}
  void camera(Location l){
    String all=p.getString("cameras","");
    if(all.isEmpty() || System.currentTimeMillis()-lastCamera<120000)return;
    for(String s:all.split(";")){
      try{
        String[] pt=s.split(",");
        Location c=new Location("user");c.setLatitude(Double.parseDouble(pt[0]));c.setLongitude(Double.parseDouble(pt[1]));
        if(l.distanceTo(c)<=300){
          lastCamera=System.currentTimeMillis();
          Notification n=new Notification.Builder(this,WARN).setContentTitle("등록된 단속카메라 지점 주의")
            .setContentText("카메라 등록 위치 주변입니다. 안전속도를 유지하세요.")
            .setSmallIcon(android.R.drawable.ic_dialog_alert).setAutoCancel(true).build();
          ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(99,n);
          return;
        }
      }catch(Exception ignored){}
    }
  }
  void overlay(){
    if(floating!=null||!Settings.canDrawOverlays(this))return;
    try{
      wm=(WindowManager)getSystemService(WINDOW_SERVICE);
      floating=new TextView(this);floating.setText("배달로그");floating.setTextColor(Color.WHITE);
      floating.setGravity(Gravity.CENTER);floating.setBackgroundColor(0xCC205080);floating.setAlpha(.3f);
      floating.setOnClickListener(v->{
        Intent i=new Intent(this,MainActivity.class);i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(i);
      });
      WindowManager.LayoutParams lp=new WindowManager.LayoutParams(140,70,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT);
      lp.gravity=Gravity.RIGHT|Gravity.TOP;lp.y=240;
      wm.addView(floating,lp);
    }catch(Exception e){floating=null;}
  }
  @Override public void onProviderEnabled(String provider){subscribe();}
  @Override public void onProviderDisabled(String provider){}
  @Override public android.os.IBinder onBind(Intent i){return null;}
  @Override public void onDestroy(){if(lm!=null)lm.removeUpdates(this);if(floating!=null&&wm!=null)try{wm.removeView(floating);}catch(Exception ignored){} super.onDestroy();}
}