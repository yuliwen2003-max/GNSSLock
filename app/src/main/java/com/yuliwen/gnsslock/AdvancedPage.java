package com.yuliwen.gnsslock;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Permission and current-policy dashboard. Editing is confined to the other tabs. */
final class AdvancedPage extends ScrollView {
    interface Actions {
        void onWhitelist(boolean enabled); void onCompatibility(boolean enabled); void onLocationPermission(); void onWriteHelp();
        void onRootCheck(); void onRefresh(); void onStopRoot(); void onRestore();
    }
    private static final int BLUE=0xff245299, INK=0xff1c283b, MUTED=0xff68778c;
    private final TextView applied, receiver, locationPermission, writePermission, rootPermission, capability, feedback, raw;
    private final Switch append;
    private final Spinner policyMode;
    private final Button restore, stop, refresh;
    private boolean rendering, details, modeRendered, displayedWhitelist;
    private ConfigController.Snapshot current;

    AdvancedPage(Context context, Actions actions) {
        super(context); setFillViewport(true); setBackgroundColor(0xfff3f6fb);
        LinearLayout content=column(); content.setPadding(dp(16),dp(8),dp(16),dp(24)); addView(content);
        LinearLayout state=card(content,"当前锁定");
        applied=text("正在读取",15,INK,false);state.addView(applied);
        receiver=text("接收机：未检测",13,MUTED,false);state.addView(receiver);
        refresh=button(state,"刷新状态",actions::onRefresh);
        LinearLayout method=card(content,"应用方式");
        policyMode=new Spinner(context);
        ArrayAdapter<String> modes=new ArrayAdapter<>(context,android.R.layout.simple_spinner_item,
                new String[]{"黑名单","白名单"});
        modes.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        policyMode.setAdapter(modes);policyMode.setContentDescription("名单模式");
        settingRow(method,"名单模式",policyMode);
        policyMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(AdapterView<?> parent,View view,int position,long id){
                if(rendering||!modeRendered||!policyMode.isEnabled())return;
                boolean whitelist=position==1;
                if(whitelist==displayedWhitelist)return;
                displayedWhitelist=whitelist;
                actions.onWhitelist(whitelist);
            }
            @Override public void onNothingSelected(AdapterView<?> parent){}
        });
        append=new Switch(context);append.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);append.setMinHeight(dp(56));
        append.setContentDescription("北斗追加锁定");settingRow(method,"北斗追加锁定",append);
        append.setOnCheckedChangeListener((button,value)->{if(!rendering)actions.onCompatibility(value);});
        LinearLayout access=card(content,"权限");
        locationPermission=permissionRow(access,"定位",actions::onLocationPermission,"授权");
        writePermission=permissionRow(access,"系统设置写入",actions::onWriteHelp,"授权方式");
        rootPermission=permissionRow(access,"Root",actions::onRootCheck,"检测");
        capability=text("",13,MUTED,false);access.addView(capability);
        LinearLayout recovery=card(content,"恢复与诊断");
        restore=button(recovery,"恢复原配置",actions::onRestore);
        stop=button(recovery,"停止追加策略",actions::onStopRoot);
        raw=text("",12,MUTED,false);raw.setTextIsSelectable(true);raw.setVisibility(GONE);
        button(recovery,"原始配置",()->{details=!details;raw.setVisibility(details?VISIBLE:GONE);});recovery.addView(raw);
        button(recovery,"复制诊断",()->{
            ((ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("GNSS LOCK",
                    applied.getText()+"\n"+receiver.getText()+"\n"+locationPermission.getText()+"\n"+writePermission.getText()+"\n"
                            +rootPermission.getText()+"\n"+(current==null?"":current.raw==null?"<absent>":current.raw)));
            setFeedback("诊断已复制",false);
        });
        feedback=text("",13,MUTED,false);content.addView(feedback);
    }
    void render(ConfigController controller,boolean fine,boolean supported) {
        current=controller.snapshot();rendering=true;
        displayedWhitelist=controller.whitelistMode();
        policyMode.setSelection(displayedWhitelist?1:0);
        modeRendered=true;append.setChecked(controller.compatibility());rendering=false;
        boolean busy=controller.isBusy();policyMode.setEnabled(!busy);append.setEnabled(!busy);restore.setEnabled(!busy&&controller.backup()!=null);
        stop.setEnabled(!busy);refresh.setEnabled(!busy);
        if(current==null)applied.setText("读取失败 · "+controller.readError());
        else {
            List<String> lines=new ArrayList<>();
            for(int c=1;c<=7;c++) {
                if(current.policy.isConstellationBlocked(c))lines.add(GnssPolicy.NAMES[c]+" · 已锁定");
                else if(!current.policy.getBlockedSvids(c).isEmpty())lines.add(GnssPolicy.NAMES[c]+" · "+current.policy.getBlockedSvids(c).size()+" 颗已锁定");
            }
            applied.setText((controller.readError().isEmpty()?"":"读取失败，显示上次状态\n")+(lines.isEmpty()?"全部可用":String.join("\n",lines)));
        }
        ConfigController.RootInfo info=controller.rootInfo();
        receiver.setText(info.verified?"北斗接收机："+(info.excluded?"63 / 63 已限制":"0x0000000000000000".equals(info.mask)?"未限制":info.mask):"接收机：未核验");
        locationPermission.setText("定位 · "+(fine?"已授权":"未授权"));
        writePermission.setText("系统设置写入 · "+(controller.canWrite()?"已授权":"未授权"));
        rootPermission.setText("Root · "+(!info.checked?"未检测":info.available?"可用":"未授权"));
        capability.setText("卫星锁定能力 · "+(supported?"支持":"未确认"));
        raw.setText("锁定配置\n"+(current==null?"读取失败":!current.exists?"<键不存在>":current.raw==null?"<null>":current.raw.isEmpty()?"<空>":current.raw)
                +"\n\n接收机\n"+info.mask+"\n"+info.state+(info.error.isEmpty()?"":"\n"+info.error));
    }
    void setFeedback(String message,boolean error){feedback.setText(message);feedback.setTextColor(error?0xffb83232:0xff22735c);}
    private void settingRow(LinearLayout parent,String title,View control){
        LinearLayout row=column();row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);
        TextView label=text(title,16,INK,false);label.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(label,new LinearLayout.LayoutParams(0,-1,1));
        row.addView(control,new LinearLayout.LayoutParams(dp(144),-1));
        parent.addView(row,new LinearLayout.LayoutParams(-1,dp(56)));
    }
    private TextView permissionRow(LinearLayout parent,String title,Runnable action,String label){
        LinearLayout row=column();row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);
        TextView value=text(title,14,INK,false);row.addView(value,new LinearLayout.LayoutParams(0,-2,1));
        Button b=new Button(getContext());b.setText(label);b.setAllCaps(false);b.setTextSize(12);b.setOnClickListener(v->action.run());
        row.addView(b,new LinearLayout.LayoutParams(dp(96),dp(48)));parent.addView(row);return value;
    }
    private LinearLayout column(){LinearLayout v=new LinearLayout(getContext());v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout card(LinearLayout parent,String title){
        LinearLayout v=column();v.setPadding(dp(15),dp(12),dp(15),dp(12));GradientDrawable bg=new GradientDrawable();bg.setColor(Color.WHITE);bg.setCornerRadius(dp(16));v.setBackground(bg);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.topMargin=dp(12);parent.addView(v,lp);v.addView(text(title,16,INK,true));return v;
    }
    private TextView text(String value,int size,int color,boolean bold){TextView v=new TextView(getContext());v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setPadding(0,dp(5),0,dp(5));if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;}
    private Button button(LinearLayout parent,String title,Runnable action){Button b=new Button(getContext());b.setText(title);b.setAllCaps(false);b.setTextSize(14);b.setTextColor(BLUE);b.setOnClickListener(v->action.run());parent.addView(b,new LinearLayout.LayoutParams(-1,dp(50)));return b;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
