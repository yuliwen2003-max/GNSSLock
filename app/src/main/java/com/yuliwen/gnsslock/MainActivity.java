package com.yuliwen.gnsslock;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.yuliwen.gnsslock.ui.GeneralBlockPage;
import com.yuliwen.gnsslock.ui.SatelliteBlockPage;
import com.yuliwen.gnsslock.ui.StatusPage;

/** Observations, editor drafts, and committed policy have separate lifetimes. */
public final class MainActivity extends Activity implements ConfigController.Listener {
    private static final int BLUE=0xff245299,MUTED=0xff6b7a90,INK=0xff1c283b;
    private static final String[] TITLES={"定位状态","星座锁定","逐星锁定","高级"};
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ConfigController.Snapshot[] editorBase=new ConfigController.Snapshot[3];
    private final TextView[] navLabels=new TextView[4];
    private final NavIcon[] navIcons=new NavIcon[4];
    private final View[] pages=new View[4];
    private final boolean[] visited=new boolean[4];
    private ConfigController controller;
    private SharedPreferences prefs;
    private LocationManager locationManager;
    private HeadingTracker headingTracker;
    private StatusPage statusPage;
    private GeneralBlockPage generalPage;
    private SatelliteBlockPage satellitePage;
    private AdvancedPage advancedPage;
    private TextView title,connection;
    private Button monitorButton;
    private int selected,applyingEditor=-1,pendingDestination=-1;
    private long seenOutcome,lastSatelliteElapsed;
    private boolean resumed,monitoring,registered,observerRegistered,monitorRequested=true,capabilitySupported,changingTabDialog;
    private String session="等待卫星";
    private int focusConstellation=-1,focusSvid=-1;
    private final ContentObserver observer=new ContentObserver(handler){
        @Override public void onChange(boolean selfChange){controller.refresh();}
    };
    private final Runnable ticker=new Runnable(){@Override public void run(){
        if(!resumed)return;
        long now=android.os.SystemClock.elapsedRealtime();statusPage.tick(now);
        if(lastSatelliteElapsed!=0&&now-lastSatelliteElapsed>10000){satellitePage.setGnssStatus(null);lastSatelliteElapsed=0;}
        handler.postDelayed(this,1000);
    }};
    private final GnssStatus.Callback gnss=new GnssStatus.Callback(){
        @Override public void onStarted(){session="搜索卫星";renderSession();}
        @Override public void onStopped(){session="等待卫星";renderSession();}
        @Override public void onFirstFix(int milliseconds){session="已接收定位";renderSession();}
        @Override public void onSatelliteStatusChanged(GnssStatus status){
            if(!resumed||!monitoring)return;
            lastSatelliteElapsed=android.os.SystemClock.elapsedRealtime();
            statusPage.setGnssStatus(status);satellitePage.setGnssStatus(status);
            int count=0;for(int i=0;i<status.getSatelliteCount();i++)if(status.usedInFix(i))count++;
            session=count>0?"卫星参与解算":"搜索卫星";renderSession();
        }
    };
    private final LocationListener locations=new LocationListener(){
        @Override public void onLocationChanged(Location location){if(resumed&&monitoring){statusPage.setLocation(location);headingTracker.setLocation(location);}}
        @Override public void onProviderEnabled(String provider){session="搜索卫星";renderSession();}
        @Override public void onProviderDisabled(String provider){session="系统定位已关闭";renderSession();}
    };
    @Override public void onCreate(Bundle state){
        super.onCreate(state);prefs=getSharedPreferences("gpslock",MODE_PRIVATE);
        Object retained=getLastNonConfigurationInstance();
        controller=retained instanceof ConfigController?(ConfigController)retained:new ConfigController(this);
        locationManager=getSystemService(LocationManager.class);
        monitorRequested=true;
        if(state!=null){
            selected=state.getInt("tab",0);
            if(retained instanceof ConfigController){seenOutcome=state.getLong("outcome");applyingEditor=state.getInt("applying",-1);pendingDestination=state.getInt("destination",-1);}
        }
        buildInterface();headingTracker=new HeadingTracker(this,(degrees,available)->statusPage.setHeading(degrees,available));
        controller.addListener(this);loadCleanEditors(true);
        if(state!=null){
            Bundle general=state.getBundle("general"),satellite=state.getBundle("satellite");
            if(general!=null)generalPage.restoreState(general);if(satellite!=null)satellitePage.restoreState(satellite);
            Bundle filters=state.getBundle("filters");if(filters!=null)statusPage.restoreState(filters);
            restoreBase(state,1);restoreBase(state,2);
        }
        showTab(Math.max(0,Math.min(3,selected)));onChanged();
        handler.post(()->{ConfigController.Outcome last=controller.lastOutcome();if(last!=null)onFinished(last);});
        if(!hasFine()&&!prefs.getBoolean("asked_location",false))handler.post(this::requestLocation);
    }
    @Override public Object onRetainNonConfigurationInstance(){return controller;}
    @Override protected void onResume(){
        super.onResume();resumed=true;monitorRequested=true;controller.refresh();headingTracker.start();
        try{capabilitySupported=locationManager!=null&&locationManager.getGnssCapabilities().hasSatelliteBlocklist();}catch(Exception ignored){}
        if(!observerRegistered){getContentResolver().registerContentObserver(Settings.Global.getUriFor(GnssPolicy.KEY),false,observer);observerRegistered=true;}
        if(monitorRequested)startMonitor(false);handler.post(ticker);onChanged();
    }
    @Override protected void onPause(){
        resumed=false;handler.removeCallbacks(ticker);stopMonitor();headingTracker.stop();
        if(observerRegistered){getContentResolver().unregisterContentObserver(observer);observerRegistered=false;}
        prefs.edit().putBoolean("monitor_requested",monitorRequested).apply();super.onPause();
    }
    @Override protected void onDestroy(){
        controller.removeListener(this);handler.removeCallbacksAndMessages(null);
        if(!isChangingConfigurations())controller.releaseWhenIdle();super.onDestroy();
    }
    @Override protected void onNewIntent(Intent intent){super.onNewIntent(intent);setIntent(intent);if(intent.getBooleanExtra("monitor",false)){monitorRequested=true;if(resumed)startMonitor(true);}}
    @Override public void onSaveInstanceState(Bundle out){
        out.putInt("tab",selected);out.putLong("outcome",seenOutcome);out.putInt("applying",applyingEditor);out.putInt("destination",pendingDestination);
        Bundle general=new Bundle(),satellite=new Bundle();generalPage.saveState(general);satellitePage.saveState(satellite);
        out.putBundle("general",general);out.putBundle("satellite",satellite);out.putBundle("filters",statusPage.saveState());
        for(int i=1;i<=2;i++)if(editorBase[i]!=null){out.putBoolean("baseExists"+i,editorBase[i].exists);out.putString("baseRaw"+i,editorBase[i].raw);out.putBoolean("hasBase"+i,true);}
        super.onSaveInstanceState(out);
    }
    private void restoreBase(Bundle b,int page){if(b.getBoolean("hasBase"+page))editorBase[page]=new ConfigController.Snapshot(b.getBoolean("baseExists"+page),b.getString("baseRaw"+page));}
    private void buildInterface(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(0xfff3f6fb);
        root.setOnApplyWindowInsetsListener((view,insets)->{
            android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());root.setPadding(bars.left,bars.top,bars.right,bars.bottom);
            WindowInsetsController ic=view.getWindowInsetsController();if(ic!=null){int light=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;ic.setSystemBarsAppearance(light,light);}return insets;
        });
        LinearLayout bar=new LinearLayout(this);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(18),0,dp(12),0);
        LinearLayout words=new LinearLayout(this);words.setOrientation(LinearLayout.VERTICAL);title=text("GNSS LOCK",20,INK,true);connection=text("",11,MUTED,false);words.addView(title);words.addView(connection);
        bar.addView(words,new LinearLayout.LayoutParams(0,-2,1));monitorButton=new Button(this);monitorButton.setAllCaps(false);monitorButton.setTextSize(13);monitorButton.setTextColor(BLUE);
        monitorButton.setOnClickListener(v->{if(!hasFine()){requestLocation();return;}monitorRequested=!monitorRequested;if(monitorRequested)startMonitor(true);else stopMonitor();renderSession();});
        bar.addView(monitorButton,new LinearLayout.LayoutParams(dp(90),dp(46)));root.addView(bar,new LinearLayout.LayoutParams(-1,dp(62)));
        FrameLayout host=new FrameLayout(this);root.addView(host,new LinearLayout.LayoutParams(-1,0,1));
        statusPage=new StatusPage(this);statusPage.setPageListener(name->{if(selected==0)title.setText("定位状态·"+name);});
        statusPage.setSatelliteAction((c,svid)->{focusConstellation=c;focusSvid=svid;attemptTab(2);});
        generalPage=new GeneralBlockPage(this,new GeneralBlockPage.Listener(){public void onDraftChanged(){renderNavigation();}public void onApply(){applyEditor(1);}public void onRefresh(){reloadEditor(1);}});
        satellitePage=new SatelliteBlockPage(this,new SatelliteBlockPage.Listener(){public void onDraftChanged(){renderNavigation();}public void onApply(){applyEditor(2);}public void onRefresh(){reloadEditor(2);}});
        advancedPage=new AdvancedPage(this,new AdvancedPage.Actions(){
            public void onCompatibility(boolean enabled){controller.setCompatibility(enabled);}
            public void onWhitelist(boolean enabled){controller.setWhitelistMode(enabled);}
            public void onLocationPermission(){requestLocation();}public void onWriteHelp(){showWriteHelp();}public void onRootCheck(){controller.inspectRoot();}
            public void onRefresh(){controller.refresh();if(controller.compatibility())controller.inspectRoot();}
            public void onStopRoot(){if(!controller.isBusy())new AlertDialog.Builder(MainActivity.this).setTitle("停止追加策略？").setMessage("将清除北斗追加名单，恢复标准方式。").setNegativeButton("取消",null).setPositiveButton("停止",(d,w)->controller.stopRoot()).show();}
            public void onRestore(){confirmRestore();}
        });
        pages[0]=statusPage;pages[1]=generalPage;pages[2]=satellitePage;pages[3]=advancedPage;
        for(View page:pages)host.addView(page,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout nav=new LinearLayout(this);nav.setBackgroundColor(Color.WHITE);nav.setElevation(dp(6));
        for(int i=0;i<4;i++){
            final int tab=i;LinearLayout item=new LinearLayout(this);item.setOrientation(LinearLayout.VERTICAL);item.setGravity(Gravity.CENTER);
            NavIcon icon=new NavIcon(i);navIcons[i]=icon;item.addView(icon,new LinearLayout.LayoutParams(dp(28),dp(28)));
            TextView label=text(TITLES[i],12,MUTED,false);label.setGravity(Gravity.CENTER);navLabels[i]=label;item.addView(label);item.setContentDescription(TITLES[i]);item.setClickable(true);item.setOnClickListener(v->attemptTab(tab));
            nav.addView(item,new LinearLayout.LayoutParams(0,-1,1));
        }
        root.addView(nav,new LinearLayout.LayoutParams(-1,dp(70)));setContentView(root);
    }
    private void attemptTab(int target){
        if(target==selected)return;if(controller.isBusy()){toast("正在应用，请稍候");return;}if(changingTabDialog)return;
        if(isDirty(selected)){
            changingTabDialog=true;
            AlertDialog dialog=new AlertDialog.Builder(this).setTitle("有未应用的修改").setMessage("应用后切换，或放弃这些修改。")
                    .setNegativeButton("继续编辑",(d,w)->focusConstellation=-1)
                    .setNeutralButton("放弃修改",(d,w)->{loadEditor(selected,controller.snapshot());showTab(target);})
                    .setPositiveButton("应用并切换",(d,w)->{pendingDestination=target;applyEditor(selected);}).create();
            dialog.setOnDismissListener(d->changingTabDialog=false);dialog.show();return;
        }showTab(target);
    }
    private void showTab(int target){
        selected=target;for(int i=0;i<4;i++)pages[i].setVisibility(i==target?View.VISIBLE:View.GONE);title.setText(target==0?"定位状态·"+statusPage.getSubpageTitle():TITLES[target]);
        boolean first=!visited[target];visited[target]=true;
        if(target==1||target==2){boolean fresh=controller.refresh();if(fresh&&!isDirty(target))loadEditor(target,controller.snapshot());else if(!fresh&&!controller.readError().isEmpty())feedback(target,controller.readError(),true);}
        if(target==3){controller.refresh();if(first&&controller.compatibility()&&!controller.isBusy())controller.inspectRoot();}
        if(target==2&&focusConstellation>0){satellitePage.focusSatellite(focusConstellation,focusSvid);focusConstellation=-1;}
        renderNavigation();renderSession();
    }
    private void renderNavigation(){
        for(int i=0;i<4;i++){navLabels[i].setText(TITLES[i]+(isDirty(i)?" ·":""));navLabels[i].setTextColor(i==selected?BLUE:MUTED);navLabels[i].setTypeface(Typeface.DEFAULT,i==selected?Typeface.BOLD:Typeface.NORMAL);navIcons[i].active=i==selected;navIcons[i].invalidate();}
    }
    private boolean isDirty(int page){return page==1?generalPage.isDirty():page==2&&satellitePage.isDirty();}
    private BlockPolicy draft(int page){return page==1?generalPage.getDraft():satellitePage.getDraft();}
    private void feedback(int page,String message,boolean error){if(page==1)generalPage.setFeedback(message,error);else if(page==2)satellitePage.setFeedback(message,error);}
    private void loadEditor(int page,ConfigController.Snapshot value){
        if(value==null)return;
        boolean unchanged=editorBase[page]!=null&&editorBase[page].policy.equals(value.policy)&&!isDirty(page);
        editorBase[page]=value;
        if(!unchanged){if(page==1)generalPage.loadPolicy(value.policy);else satellitePage.loadPolicy(value.policy);}
        renderNavigation();
    }
    private void loadCleanEditors(boolean force){if(controller.snapshot()!=null)for(int i=1;i<=2;i++)if(force||!isDirty(i))loadEditor(i,controller.snapshot());}
    private void reloadEditor(int page){
        Runnable reload=()->{if(controller.refresh()){loadEditor(page,controller.snapshot());feedback(page,"已读取当前名单",false);}else feedback(page,controller.readError(),true);};
        if(isDirty(page))new AlertDialog.Builder(this).setTitle("放弃当前修改？").setNegativeButton("继续编辑",null).setPositiveButton("重新读取",(d,w)->reload.run()).show();else reload.run();
    }
    private void applyEditor(int page){
        if(controller.isBusy()){pendingDestination=-1;toast("正在应用，请稍候");return;}
        if(!isDirty(page)){if(pendingDestination>=0){int next=pendingDestination;pendingDestination=-1;showTab(next);}return;}
        ConfigController.Snapshot latest;try{latest=controller.read();}catch(Exception e){pendingDestination=-1;feedback(page,ConfigController.message(e),true);return;}
        final BlockPolicy policy=draft(page);
        if(editorBase[page]!=null&&!editorBase[page].policy.equals(latest.policy)){
            new AlertDialog.Builder(this).setTitle("系统名单已更新").setMessage("读取最新名单，或用当前选择覆盖。")
                    .setNegativeButton("取消",(d,w)->pendingDestination=-1)
                    .setNeutralButton("读取最新",(d,w)->{loadEditor(page,latest);pendingDestination=-1;})
                    .setPositiveButton("覆盖应用",(d,w)->commitEditor(page,policy,latest))
                    .setOnCancelListener(d->pendingDestination=-1).show();return;
        }commitEditor(page,policy,latest);
    }
    private void commitEditor(int page,BlockPolicy policy,ConfigController.Snapshot expected){
        if(!controller.compatibility()&&policy.requiresBdsAppend()){
            new AlertDialog.Builder(this).setTitle("本机标准方式会保留北斗")
                    .setNegativeButton("取消",(d,w)->pendingDestination=-1)
                    .setNeutralButton("标准方式",(d,w)->startApply(page,policy,expected))
                    .setPositiveButton("追加锁定",(d,w)->{controller.setCompatibility(true);startApply(page,policy,expected);})
                    .setOnCancelListener(d->pendingDestination=-1).show();return;
        }startApply(page,policy,expected);
    }
    private void startApply(int page,BlockPolicy policy,ConfigController.Snapshot expected){applyingEditor=page;feedback(page,"正在应用…",false);controller.apply(policy,expected);}
    @Override public void onChanged(){
        if(generalPage==null)return;
        generalPage.setWhitelistMode(controller.whitelistMode());satellitePage.setWhitelistMode(controller.whitelistMode());
        generalPage.setBusy(controller.isBusy());satellitePage.setBusy(controller.isBusy());if(!controller.isBusy()&&controller.readError().isEmpty())loadCleanEditors(false);
        advancedPage.render(controller,hasFine(),capabilitySupported);renderNavigation();
    }
    @Override public void onFinished(ConfigController.Outcome value){
        if(value.id<=seenOutcome)return;seenOutcome=value.id;advancedPage.setFeedback(value.message,!value.success);
        if(value.success&&value.kind!=ConfigController.Kind.ROOT_STATUS){statusPage.resetObservation();satellitePage.setGnssStatus(null);lastSatelliteElapsed=0;}
        if(value.kind==ConfigController.Kind.APPLY){
            if(value.success){if(applyingEditor>0)loadEditor(applyingEditor,value.snapshot);loadCleanEditors(false);}
            feedback(applyingEditor,value.message,!value.success);applyingEditor=-1;
            if(value.success&&pendingDestination>=0){int next=pendingDestination;pendingDestination=-1;showTab(next);}else pendingDestination=-1;
        }else if(value.success&&value.kind!=ConfigController.Kind.ROOT_STATUS)loadCleanEditors(false);
        if(value.kind!=ConfigController.Kind.ROOT_STATUS||!value.success)toast(value.message);
    }
    private void confirmRestore(){
        if(controller.backup()==null){toast("尚无原配置备份");return;}
        new AlertDialog.Builder(this).setTitle("恢复原配置？").setMessage("将恢复首次修改前的锁定配置。")
                .setNegativeButton("取消",null).setPositiveButton("恢复",(d,w)->controller.restore()).show();
    }
    private void showWriteHelp(){
        String command="adb shell pm grant com.yuliwen.gnsslock android.permission.WRITE_SECURE_SETTINGS";
        new AlertDialog.Builder(this).setTitle(controller.canWrite()?"系统写入权限已授权":"通过电脑授权一次").setMessage(command)
                .setNegativeButton("关闭",null).setPositiveButton("复制命令",(d,w)->{((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("ADB",command));toast("命令已复制");}).show();
    }
    private boolean hasFine(){return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;}
    private void requestLocation(){
        if(hasFine()){monitorRequested=true;startMonitor(false);return;}prefs.edit().putBoolean("asked_location",true).apply();
        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},100);
    }
    private void startMonitor(boolean ask){
        if(!resumed||monitoring||!monitorRequested)return;if(!hasFine()){session="需要定位权限";renderSession();if(ask)requestLocation();return;}
        try{statusPage.resetObservation();satellitePage.setGnssStatus(null);lastSatelliteElapsed=0;registered=locationManager.registerGnssStatusCallback(gnss,handler);if(!registered)throw new IllegalStateException("卫星回调不可用");
            monitoring=true;locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,1000,0,locations,Looper.getMainLooper());session="搜索卫星";
        }catch(Exception error){stopMonitor();session=ConfigController.message(error);}renderSession();
    }
    private void stopMonitor(){
        monitoring=false;if(locationManager!=null)try{locationManager.removeUpdates(locations);if(registered)locationManager.unregisterGnssStatusCallback(gnss);}catch(RuntimeException ignored){}
        registered=false;lastSatelliteElapsed=0;if(satellitePage!=null)satellitePage.setGnssStatus(null);renderSession();
    }
    private void renderSession(){
        if(statusPage==null)return;boolean enabled=locationManager!=null&&locationManager.isLocationEnabled();
        String label=!monitorRequested?"已暂停":!hasFine()?"需要定位权限":!enabled?"系统定位已关闭":session;
        connection.setText(label);monitorButton.setText(!hasFine()?"授权定位":monitorRequested?"暂停":"开始");statusPage.setSessionState(monitoring,enabled,label);
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);if(request==100){if(hasFine()){monitorRequested=true;startMonitor(false);}renderSession();onChanged();}}
    @Override public void onBackPressed(){if(controller.isBusy()){toast("正在应用，请稍候");return;}if(selected!=0){attemptTab(0);return;}if(statusPage.handleBack())return;super.onBackPressed();}
    private TextView text(String value,int size,int color,boolean bold){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(color);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
    private void toast(String message){Toast.makeText(this,message,Toast.LENGTH_SHORT).show();}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private final class NavIcon extends View {
        final int type;boolean active;final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);NavIcon(int type){super(MainActivity.this);this.type=type;}
        @Override protected void onDraw(Canvas canvas){
            float x=getWidth()/2f,y=getHeight()/2f,r=Math.min(x,y)*.72f;paint.setColor(active?BLUE:MUTED);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(2));
            if(type==0){canvas.drawCircle(x,y,r,paint);canvas.drawLine(x,y-r-2,x,y+r+2,paint);canvas.drawLine(x-r-2,y,x+r+2,y,paint);paint.setStyle(Paint.Style.FILL);canvas.drawCircle(x,y,dp(3),paint);}
            else if(type==1){canvas.drawCircle(x,y,r,paint);canvas.drawLine(x-r*.7f,y+r*.7f,x+r*.7f,y-r*.7f,paint);}
            else if(type==2){for(int row=-1;row<=1;row++)for(int col=-1;col<=1;col++)canvas.drawCircle(x+col*r*.8f,y+row*r*.8f,dp(2),paint);}
            else{canvas.drawCircle(x,y,r*.38f,paint);canvas.drawCircle(x,y,r,paint);for(int i=0;i<8;i++){double a=i*Math.PI/4;canvas.drawLine(x+(float)Math.cos(a)*r*.8f,y+(float)Math.sin(a)*r*.8f,x+(float)Math.cos(a)*r*1.15f,y+(float)Math.sin(a)*r*1.15f,paint);}}
        }
    }
}
