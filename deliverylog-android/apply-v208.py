from pathlib import Path
p=Path("deliverylog-classic")
a=p/"app/src/main/assets"
f=a/"app.js"
h=a/"index.html"
css=a/"styles.css"
j=p/"app/src/main/java/com/chamsosik/deliverylog"
def patch(file, old, new):
    s=file.read_text()
    if old not in s: raise Exception("Missing patch anchor "+file.name+":"+old[:50])
    file.write_text(s.replace(old,new,1))
advice = """  const SAFETY = Object.freeze({
    '행인':['보행자와 충분한 거리를 두고 감속하세요.','횡단 가능성이 있으면 안전하게 정차하세요.'],
    '차량':['주변 차량의 사각지대와 급진로 변경에 주의하세요.','차간거리를 확보하고 위험하면 정차하세요.'],
    '차량 불빛 눈부심':['시야가 나빠지면 감속하고 안전한 곳에 정차하세요.','시야가 회복된 뒤 다시 이동하세요.'],
    '차량 불빛 뒤에서 발쪽 비침':['불빛만으로 상대방의 의도를 단정하지 마세요.','급조향을 피하고 안전한 곳에서 상황을 확인하세요.'],
    '자전거':['자전거와 충분한 측면 거리를 유지하세요.','갑작스러운 방향 변경에 대비하고 감속하세요.'],
    '기타':['안전한 곳에 정차해 주위를 확인하세요.','부상·사고는 119, 위협·범죄는 112에 연락하세요.'],
    '도로·주행':['속도를 낮추고 노면과 장애물을 확인하세요.','위험 구간을 피해 안전한 곳에 정차하세요.'],
    '픽업·배달':['정차한 뒤 위치와 이동 경로를 확인하세요.','무리하게 서두르지 말고 안전하게 이동하세요.'],
    '안전·사고':['사람과 차량의 안전을 먼저 확보하세요.','부상·사고 발생 시 119 또는 112에 연락하세요.'],
    '시설·환경':['위험 시설과 환경을 확인하세요.','안전한 곳에서 기록하고 위험 구간은 피하세요.']
  });
  function adviceFor(kind){return SAFETY[kind]||SAFETY['기타'];}
  function showAdvice(kind){
    const panel=$('riskAdvicePanel');if(!panel)return;
    $('riskAdviceTitle').textContent=kind+' · 기록 후 행동 요령';
    const list=$('riskAdviceList');list.replaceChildren();
    adviceFor(kind).forEach(x=>{const li=document.createElement('li');li.textContent=x;list.append(li);});
    panel.hidden=false;
  }
"""
patch(f,"  function quickRisks() {",advice+"  function quickRisks() {")
patch(f,"    record.kind='risk';record.source='one-touch';",
      "    record.kind='risk';record.source='one-touch';")
patch(f,"    if(!persist()) { state.anomalies=state.anomalies.filter(a=>a.id!==record.id);return; }\n    syncUI();",
      "    if(!persist()) { state.anomalies=state.anomalies.filter(a=>a.id!==record.id);return; }\n    syncUI();showAdvice(kind);")
patch(f,"        row.append(label,detail);",
      """        row.append(label,detail);
        const tips=document.createElement('details');const sum=document.createElement('summary');
        sum.textContent='행동 요령 다시 보기';tips.append(sum);
        const list=document.createElement('ul');adviceFor(item.category).forEach(x=>{const li=document.createElement('li');li.textContent=x;list.append(li);});
        tips.append(list);row.append(tips);""")
patch(f,"    if (!loc) { toast('정확한 현재 GPS 위치가 없습니다. 위치를 수신한 뒤 다시 찍어주세요.');return; }",
        "    // 수신이 늦어도 사건 시각을 저장합니다. GPS 위치가 없음을 표시합니다.")
patch(f,"    toast('지금 위치에 특이점 핀을 저장했습니다.');",
        "    toast(loc?'현재 위치 특이점을 저장했습니다.':'GPS 미수신: 시각만 저장했습니다.');")
patch(f,"persist();hideModal('anomalyModal');renderAnomalies();scheduleDraw();toast('특이점 메모를 저장했습니다.');",
        "persist();hideModal('anomalyModal');renderAnomalies();scheduleDraw();showAdvice(a.category);toast('특이점을 저장했습니다. 행동 요령을 확인하세요.');")
patch(f,"    $('riskOpenHistoryBtn').addEventListener('click',()=>showView('risk'));",
        "    $('riskOpenHistoryBtn').addEventListener('click',()=>showView('risk'));\n    $('riskAdviceClose').addEventListener('click',()=>{$('riskAdvicePanel').hidden=true;});")
patch(f,"기본 요청 GPS 최소 1.5초/3m, 네트워크 최소 3초/5m",
        "고정밀 GPS 요청 1초/0m, 네트워크 보조 2초/0m")
patch(f,"    latestGpsStats=stats;\n    showGpsFrequency();",
        "    latestGpsStats=stats;\n    showGpsFrequency();\n    if(Number.isFinite(stats.pickupFirstFixMs)) $('gpsFreqDetail').textContent+=' | 픽업 첫 유효 GPS '+(stats.pickupFirstFixMs>=0?(stats.pickupFirstFixMs/1000).toFixed(1)+'초':'대기');")
patch(h,'        <button class="risk-history-link" id="riskOpenHistoryBtn"',"""        <section id="riskAdvicePanel" class="risk-advice" hidden role="status" aria-live="polite">
        <div class="risk-advice-head"><strong id="riskAdviceTitle">행동 요령</strong><button id="riskAdviceClose" type="button" aria-label="닫기">×</button></div>
        <ol id="riskAdviceList"></ol><small>안전하게 정차한 상태에서 확인하세요.</small>
        </section>
        <button class="risk-history-link" id="riskOpenHistoryBtn" """)
with css.open("a") as fp: fp.write("\n.risk-advice{padding:12px;margin:12px 0;border:1px solid #b88b6c;border-radius:12px;color:#ffe8d6;background:#242a3b}.risk-advice[hidden]{display:none}.risk-advice-head{display:flex;justify-content:space-between}.risk-advice-head button{color:#fff;background:transparent;border:0;min-width:40px;min-height:40px;font-size:21px}.risk-advice ol{font-size:13px;line-height:1.6;padding-left:20px}.risk-advice small{color:#d9b6a0}.risk-history-entry details{padding:8px;margin:9px 0;border-radius:8px;background:#26324b;color:#ffe0ca;font-size:12px}.risk-history-entry details li{margin:5px 0}\n")
service=j/"TrackerService.java"
patch(service,"import android.location.LocationManager;","import android.location.LocationManager;\nimport android.location.LocationRequest;")
patch(service,"manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1500L, 3f, this);",
  """if(android.os.Build.VERSION.SDK_INT>=31){
                    LocationRequest req=new LocationRequest.Builder(1000L)
                        .setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).setMinUpdateIntervalMillis(500L)
                        .setMinUpdateDistanceMeters(0f).setMaxUpdateDelayMillis(0L).build();
                    manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,req,getMainExecutor(),this);
                }else manager.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000L,0f,this);""")
patch(service,"manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 3000L, 5f, this);",
  """if(android.os.Build.VERSION.SDK_INT>=31){
                    LocationRequest req=new LocationRequest.Builder(2000L)
                        .setQuality(LocationRequest.QUALITY_BALANCED_POWER_ACCURACY).setMinUpdateIntervalMillis(1000L)
                        .setMinUpdateDistanceMeters(0f).build();
                    manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,req,getMainExecutor(),this);
                }else manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,2000L,0f,this);""")
patch(service,"        lastAccepted = new Location(loc);\n        if (liveCallback) noteAccepted();",
"""        lastAccepted = new Location(loc);
        SharedPreferences prefs=getSharedPreferences(MainActivity.PREFS,0);
        long pickup=prefs.getLong("pickup_started_at",0L);
        if(liveCallback && pickup>0 && prefs.getLong("pickup_first_fix_ms",-1L)<0
           && loc.getTime()>=pickup && loc.getAccuracy()<=120f)
           prefs.edit().putLong("pickup_first_fix_ms",Math.max(0L,System.currentTimeMillis()-pickup)).apply();
        if (liveCallback) noteAccepted();""")
main=j/"MainActivity.java"
patch(main,"    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results) {",
"""    @Override protected void onResume() {
        super.onResume();
        // GNSS pre-warm only while the screen is visible (no hidden always-on recording).
        safeStartTracker();
    }
    @Override protected void onStop() {
        super.onStop();
        SharedPreferences prefs=getSharedPreferences(PREFS,0);
        if(prefs.getLong("pickup_started_at",0L)<=0 && !prefs.getBoolean("manual_tracking",false))
            stopService(new Intent(this,TrackerService.class));
    }
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] results) {""")
patch(main,"            safeStartTracker();return;",
           "            getSharedPreferences(PREFS,0).edit().putBoolean(\"manual_tracking\",true).apply();\n            safeStartTracker();return;")
patch(main,"            stopService(new Intent(this,TrackerService.class));return;",
           "            getSharedPreferences(PREFS,0).edit().putBoolean(\"manual_tracking\",false).apply();\n            if(getSharedPreferences(PREFS,0).getLong(\"pickup_started_at\",0L)<=0)stopService(new Intent(this,TrackerService.class));return;")
patch(main,'.putLong("pickup_started_at",System.currentTimeMillis()).apply();',
        '.putLong("pickup_started_at",System.currentTimeMillis()).putLong("pickup_first_fix_ms",-1L).apply();')
patch(main,'obj.put("gpsRequestedMinMs",1500);',
        'obj.put("gpsRequestedMinMs",1000);\n            obj.put("pickupFirstFixMs",p.getLong("pickup_first_fix_ms",-1L));')
patch(main,'obj.put("networkRequestedMinMs",3000);','obj.put("networkRequestedMinMs",2000);')
print("v2.0.8 GPS + advice patch successful")
