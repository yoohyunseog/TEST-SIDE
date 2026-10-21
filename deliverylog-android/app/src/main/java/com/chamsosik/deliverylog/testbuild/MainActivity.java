package com.chamsosik.deliverylog.testbuild;

import android.Manifest;
import android.app.*;
import android.os.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.Locale;
import java.util.Date;
import java.text.SimpleDateFormat;

public class MainActivity extends Activity {
  static final String PREF = "deliverylog_test_data";
  android.content.SharedPreferences p;
  LinearLayout content;
  Handler handler = new Handler(Looper.getMainLooper());
  final Runnable ticker = new Runnable() { public void run() { updateSummary(); handler.postDelayed(this, 3000); } };
  TextView summary;
  static final int MEDIA_REQUEST = 34;
  static final int PERMISSIONS_REQUEST = 7;

  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    p = getSharedPreferences(PREF, MODE_PRIVATE);
    ScrollView sc = new ScrollView(this);
    content = new LinearLayout(this);
    content.setOrientation(LinearLayout.VERTICAL);
    content.setPadding(28,24,28,28);
    content.setBackgroundColor(Color.rgb(245,248,251));
    sc.addView(content);
    setContentView(sc);
    title("🛵 배달로그 v2.0.5 GPS 시험판", 23);
    text("기존 앱과 별도 설치 · 기록은 이 시험판 안에 저장됩니다.");
    summary = new TextView(this);
    summary.setTextSize(17);
    summary.setTextColor(Color.rgb(20,39,63));
    summary.setPadding(15,18,15,20);
    content.addView(summary);
    action("📦 픽업 시작", () -> startPickup());
    action("📍 현재 위치 특이점", () -> addNote());
    action("📸 사진·영상 선택", () -> chooseMedia());
    action("✅ 배달 완료 · 수익 등록", () -> completePickup());
    action("💰 정산 (수동 확인)", () -> settle());
    action("📡 GPS 수신 현황", () -> showGpsInfo());
    action("🫥 플로팅 버튼 권한", () -> overlayPermission());
    action("🚨 카메라 좌표 등록", () -> addCamera());
    action("🤖 ChatGPT 프로젝트 설정/열기", () -> chatGpt());
    action("📤 기록 내보내기", () -> export());
    text("GPS 요청 주기: GPS 최소 1.5초 / 네트워크 최소 3초\n실제 수신 빈도는 기기 상태에 따라 달라집니다.\n주행 중에는 버튼을 조작하지 마세요.");
    if(android.os.Build.VERSION.SDK_INT >= 33) {
      requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
          Manifest.permission.POST_NOTIFICATIONS}, PERMISSIONS_REQUEST);
    } else if(android.os.Build.VERSION.SDK_INT >= 23) {
      requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, PERMISSIONS_REQUEST);
    }
  }

  @Override protected void onResume() { super.onResume(); handler.removeCallbacks(ticker); handler.post(ticker); }
  @Override protected void onPause() { handler.removeCallbacks(ticker); super.onPause(); }

  void title(String s,int size) {
    TextView v = new TextView(this);
    v.setText(s); v.setTextSize(size); v.setTextColor(Color.rgb(19,42,70));
    v.setPadding(0,14,0,13); content.addView(v);
  }
  void text(String s) {
    TextView v = new TextView(this); v.setText(s); v.setTextSize(13);
    v.setTextColor(Color.rgb(90,103,117)); v.setPadding(0,5,0,12); content.addView(v);
  }
  void action(String name,Runnable job) {
    Button b = new Button(this);
    b.setAllCaps(false);
    b.setText(name); b.setTextSize(16);
    content.addView(b,new LinearLayout.LayoutParams(-1,-2));
    b.setOnClickListener(v -> job.run());
  }
  String money(long n){ return String.format(Locale.KOREA, "%,d원",n); }
  JSONArray array(String key) {
    try { return new JSONArray(p.getString(key,"[]")); }
    catch(Exception ex){ return new JSONArray(); }
  }
  long unsettled(){ return p.getLong("unsettled",0); }
  void updateSummary(){
    if(summary == null) return;
    JSONArray recent = array("recent");
    long now=System.currentTimeMillis(), prev=0, sum=0; int count=0;
    for(int i=0;i<recent.length();i++) {
      long t=recent.optLong(i,0);
      if(now-t<=60000 && t<=now+2000 && t>0) { count++; if(prev>0) sum+=t-prev; prev=t; }
    }
    long last=p.getLong("lastFixAt",0);
    String gps=last==0 ? "수신 대기" : ((now-last<30000) ? (now-last)/1000+"초 전" : "30초 이상 미수신");
    String txt="미정산 누적 수익: "+money(unsettled())
      +"\n완료 배달: "+array("deliveries").length()+"건"
      +"\n픽업 상태: "+(p.getBoolean("active",false)?"● 픽업~배달 동선 기록 중":"대기")
      +"\nGPS 최근 1분 수신: "+count+"회"
      +"\n평균 수신 간격: "+(count>1?sum/(count-1)+"ms":"측정 중")
      +"\n최근 수신: "+gps
      +"\nGPS/네트워크 누적: "+p.getLong("gpsCallbacks",0)+" / "+p.getLong("netCallbacks",0)+"회"
      +"\n마지막 정확도: "+p.getFloat("accuracy",0)+"m";
    summary.setText(txt);
  }

  void startPickup() {
    if(p.getBoolean("active",false)){toast("이미 픽업 기록 중입니다");return;}
    p.edit().putBoolean("active",true).putLong("pickupAt",System.currentTimeMillis())
      .putString("route","[]").putString("events","[]").apply();
    startTracking();
    toast("픽업 시작. GPS가 잡히면 위치가 기록됩니다.");
    updateSummary();
  }
  void startTracking() {
    if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED &&
       checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)!=PackageManager.PERMISSION_GRANTED){
      toast("위치 권한을 허용하세요. 픽업 시작 기록은 저장되었습니다.");
      return;
    }
    try {
      Intent i=new Intent(this,TrackerService.class);
      if(Build.VERSION.SDK_INT>=26) startForegroundService(i); else startService(i);
    } catch(Exception ex) { toast("위치 서비스를 시작하지 못했습니다: "+ex.getClass().getSimpleName()); }
  }
  void completePickup() {
    if(!p.getBoolean("active",false)){toast("먼저 픽업 시작을 누르세요");return;}
    final EditText input=new EditText(this);
    input.setInputType(2);
    input.setHint("배달 수익 원 단위 (예: 4500)");
    new AlertDialog.Builder(this).setTitle("배달 완료 · 금액 입력")
      .setView(input).setNegativeButton("취소",null)
      .setPositiveButton("완료", (d,w)->{
        long amount;
        try {amount=Long.parseLong(input.getText().toString().trim());}
        catch(Exception e){toast("수익이 숫자가 아닙니다. 배달은 계속 기록 중입니다.");return;}
        if(amount<0 || amount>2000000){toast("금액 범위를 확인하세요. 배달은 계속 기록 중입니다.");return;}
        try {
          JSONObject trip=new JSONObject();
          trip.put("pickupAt",p.getLong("pickupAt",0));
          trip.put("completedAt",System.currentTimeMillis());
          trip.put("amount",amount);
          trip.put("route",array("route"));
          trip.put("events",array("events"));
          JSONArray items=array("deliveries");items.put(trip);
          p.edit().putString("deliveries",items.toString())
           .putLong("unsettled",unsettled()+amount)
           .putBoolean("active",false).remove("pickupAt")
           .remove("route").remove("events").apply();
          toast("배달 완료 · 미정산 수익 "+money(unsettled()));
          updateSummary();
        }catch(JSONException e){toast("저장 실패: "+e.getMessage());}
      }).show();
  }
  void addNote(){
    final EditText input=new EditText(this);input.setHint("특이점 메모");
    new AlertDialog.Builder(this).setTitle("현재 위치에 특이점 저장")
    .setView(input).setNegativeButton("취소",null).setPositiveButton("저장",(d,w)->{
      try{
        JSONObject marker=new JSONObject();
        marker.put("at",System.currentTimeMillis());
        marker.put("note",input.getText().toString());
        if(p.getLong("lastFixAt",0)>System.currentTimeMillis()-20000 && p.getFloat("accuracy",9999)<=150){
          marker.put("lat",Double.longBitsToDouble(p.getLong("lat",0)));
          marker.put("lon",Double.longBitsToDouble(p.getLong("lon",0)));
        } else { marker.put("locationStatus","수신 불량 또는 오래된 좌표"); }
        JSONArray events=array(p.getBoolean("active",false)?"events":"unassignedEvents");
        events.put(marker);
        p.edit().putString(p.getBoolean("active",false)?"events":"unassignedEvents",events.toString()).apply();
        toast("특이점 저장");
      }catch(Exception e){toast("특이점 저장 실패");}
    }).show();
  }
  void chooseMedia(){
    Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");
    i.addCategory(Intent.CATEGORY_OPENABLE);
    i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"image/*","video/*"});
    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    startActivityForResult(i,MEDIA_REQUEST);
  }
  @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
    super.onActivityResult(requestCode,resultCode,data);
    if(requestCode==MEDIA_REQUEST && resultCode==RESULT_OK && data!=null && data.getData()!=null){
      try{
        Uri u=data.getData();
        try{getContentResolver().takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
        JSONArray e=array(p.getBoolean("active",false)?"events":"unassignedEvents");
        JSONObject item=new JSONObject(); item.put("at",System.currentTimeMillis());item.put("mediaUri",u.toString());item.put("note","선택한 사진/영상");
        e.put(item);p.edit().putString(p.getBoolean("active",false)?"events":"unassignedEvents",e.toString()).apply();
        toast("미디어 첨부 경로 저장 (파일 원본은 원래 위치에 유지)");
      }catch(Exception e){toast("미디어 연결 실패");}
    }
  }
  void settle(){
    long amount=unsettled();
    if(amount==0){toast("미정산 금액이 0원입니다");return;}
    new AlertDialog.Builder(this).setTitle("정산 확인")
      .setMessage(money(amount)+"을 정산 처리할까요? 배달 기록은 삭제되지 않습니다.")
      .setNegativeButton("취소",null)
      .setPositiveButton("정산", (d,w)->{
        try{
          JSONArray records=array("settlements");
          JSONObject r=new JSONObject();r.put("at",System.currentTimeMillis());r.put("amount",amount);
          records.put(r);
          p.edit().putString("settlements",records.toString()).putLong("unsettled",0).apply();
          updateSummary();toast("정산 완료 · 과거 기록 유지");
        }catch(Exception ex){toast("정산 실패");}
      }).show();
  }
  void showGpsInfo(){
    new AlertDialog.Builder(this).setTitle("GPS 수신 및 호출 진단")
      .setMessage("GPS 요청 등록: "+p.getLong("gpsRequests",0)+"회\n네트워크 요청 등록: "+p.getLong("netRequests",0)+"회"
      +"\n실제 GPS 수신: "+p.getLong("gpsCallbacks",0)+"회\n실제 네트워크 수신: "+p.getLong("netCallbacks",0)+"회"
      +"\n최근 위치 정확도: "+p.getFloat("accuracy",0)+"m\n의심 GPS 급변: "+p.getLong("rejectedJumps",0)+"회"
      +"\n모의 위치 탐지: "+p.getLong("mockCallbacks",0)+"회\n\n이 통계는 배달로그 앱 자체의 위치 수신만 확인합니다.")
      .setPositiveButton("확인",null).show();
  }
  void overlayPermission(){
    if(Settings.canDrawOverlays(this)){startTracking();toast("플로팅 권한이 있습니다");}
    else startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName())));
  }
  void addCamera(){
    final EditText input=new EditText(this);
    input.setHint("위도,경도  예) 37.123,127.123");input.setSingleLine(true);
    new AlertDialog.Builder(this).setTitle("안전 경고 좌표 등록")
      .setMessage("직접 확인된 카메라 위치만 등록합니다. 방향과 관계없이 반경 300m 안에서 알림이 표시됩니다.")
      .setView(input).setNegativeButton("취소",null)
      .setPositiveButton("저장",(d,w)->{
        String val=input.getText().toString().trim();
        try{
          String[] xy=val.split(","); double lat=Double.parseDouble(xy[0]),lon=Double.parseDouble(xy[1]);
          if(Math.abs(lat)>90||Math.abs(lon)>180)throw new Exception();
          String existing=p.getString("cameras","");
          p.edit().putString("cameras",existing.isEmpty()?val:existing+";"+val).apply();
          toast("카메라 좌표 등록");
        }catch(Exception ex){toast("좌표 형식 오류");}
      }).show();
  }
  void chatGpt(){
    final EditText input=new EditText(this);
    input.setText(p.getString("chatgpt",""));
    input.setHint("https://chatgpt.com/g/.../project");
    new AlertDialog.Builder(this).setTitle("ChatGPT 프로젝트 연결")
      .setView(input).setNegativeButton("취소",null)
      .setNeutralButton("링크 열기",(d,w)->{
        String url=p.getString("chatgpt","");
        try {
          Uri uri=Uri.parse(url);
          if(!"https".equals(uri.getScheme()) || !"chatgpt.com".equals(uri.getHost()))throw new Exception();
          startActivity(new Intent(Intent.ACTION_VIEW,uri));
        }catch(Exception ex){toast("저장된 ChatGPT 주소를 확인하세요");}
      }).setPositiveButton("주소 저장",(d,w)->{
        String url=input.getText().toString().trim();
        try{
          Uri uri=Uri.parse(url);
          if(!"https".equals(uri.getScheme()) || !"chatgpt.com".equals(uri.getHost())) throw new Exception();
          p.edit().putString("chatgpt",url).apply();toast("ChatGPT 프로젝트 링크 저장 완료");
        }catch(Exception ex){toast("chatgpt.com의 HTTPS 주소만 입력하세요");}
      }).show();
  }
  void export(){
    try{
      JSONObject j=new JSONObject();
      j.put("deliveries",array("deliveries"));
      j.put("settlements",array("settlements"));
      j.put("unsettled",unsettled());
      if(p.getBoolean("active",false)){j.put("activePickupRoute",array("route"));}
      Intent share=new Intent(Intent.ACTION_SEND);share.setType("application/json");
      share.putExtra(Intent.EXTRA_TEXT,j.toString(2));
      startActivity(Intent.createChooser(share,"배달 기록 백업 공유"));
    }catch(Exception e){toast("내보내기 실패");}
  }
  void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
}
