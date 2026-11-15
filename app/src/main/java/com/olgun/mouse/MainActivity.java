package com.olgun.mouse;

import android.Manifest;
import android.app.*;
import android.os.*;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private BluetoothAdapter adapter;
    private BluetoothHidDevice hid;
    private BluetoothDevice selected, connected;
    private TextView deviceLabel;
    private Button closeButton, leftButton, rightButton;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private long closeDeadline = 0;
    private int buttons = 0;
    private float lastX,lastY;
    private boolean tracking = false;
    private final Executor executor = Executors.newSingleThreadExecutor();
    private static final byte[] MOUSE_DESCRIPTOR = new byte[] {
        0x05,0x01, 0x09,0x02, (byte)0xA1,0x01, 0x09,0x01, (byte)0xA1,0x00,
        0x05,0x09, 0x19,0x01, 0x29,0x03, 0x15,0x00, 0x25,0x01,
        (byte)0x95,0x03, 0x75,0x01, (byte)0x81,0x02,
        (byte)0x95,0x01, 0x75,0x05, (byte)0x81,0x03,
        0x05,0x01, 0x09,0x30, 0x09,0x31, 0x09,0x38,
        0x15,(byte)0x81, 0x25,0x7F, 0x75,0x08, (byte)0x95,0x03,
        (byte)0x81,0x06, (byte)0xC0, (byte)0xC0
    };
    private final BluetoothHidDevice.Callback callback = new BluetoothHidDevice.Callback() {
        @Override public void onAppStatusChanged(BluetoothDevice plugged, boolean registered) {
            runOnUiThread(() -> { updateStatus(); if (registered && selected != null) hid.connect(selected); });
        }
        @Override public void onConnectionStateChanged(BluetoothDevice device, int state) {
            runOnUiThread(() -> {
                if (state == BluetoothProfile.STATE_CONNECTED) connected = device;
                else if (connected != null && connected.equals(device)) connected = null;
                updateStatus();
            });
        }
    };
    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.rgb(245,245,245));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        adapter = BluetoothAdapter.getDefaultAdapter();
        buildUi();
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT}, 42);
        else startBluetooth();
    }
    private GradientDrawable bg(int color,int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(5));
        if(stroke != 0)d.setStroke(dp(2),stroke);
        return d;
    }
    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}
    private Button button(String text,int color) {
        Button b=new Button(this);b.setText(text);b.setTextColor(Color.BLACK);b.setAllCaps(false);
        b.setTextSize(13);b.setPadding(dp(5),0,dp(5),0);b.setBackground(bg(color,0));return b;
    }
    private void buildUi() {
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(12),dp(35),dp(12),dp(10));
        root.setBackgroundColor(Color.rgb(245,245,245));setContentView(root);
        TextView title=new TextView(this);title.setText("Mouse");title.setGravity(Gravity.CENTER);
        title.setTextSize(23);title.setTypeface(null,Typeface.BOLD);title.setTextColor(Color.WHITE);
        title.setBackgroundColor(Color.BLACK);root.addView(title,new LinearLayout.LayoutParams(-1,dp(65)));
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(56));rp.topMargin=dp(8);root.addView(row,rp);
        Button pair=button("Eşleş",0xFFAADCF6);row.addView(pair,new LinearLayout.LayoutParams(dp(85),dp(44)));
        pair.setOnClickListener(v->chooseDevice());
        deviceLabel=new TextView(this);deviceLabel.setTextSize(12);deviceLabel.setTextColor(Color.DKGRAY);
        deviceLabel.setGravity(Gravity.CENTER_VERTICAL);deviceLabel.setPadding(dp(8),0,dp(4),0);
        row.addView(deviceLabel,new LinearLayout.LayoutParams(0,-1,1));
        closeButton=button("Kapat",0xFFF4AAAA);row.addView(closeButton,new LinearLayout.LayoutParams(dp(83),dp(44)));
        closeButton.setOnClickListener(v->{
            if(SystemClock.uptimeMillis()>closeDeadline){
                closeDeadline=SystemClock.uptimeMillis()+3000;closeButton.setText("Onayla");
                handler.postDelayed(()->{if(SystemClock.uptimeMillis()>=closeDeadline)closeButton.setText("Kapat");},3000);
            } else {
                closeDeadline=0;closeButton.setText("Kapat");
                if(hid!=null && connected!=null)hid.disconnect(connected);
                connected=null;selected=null;getPreferences(0).edit().remove("device").apply();updateStatus();
            }
        });
        View gap=new View(this);root.addView(gap,new LinearLayout.LayoutParams(1,dp(16)));
        LinearLayout pad=new LinearLayout(this);pad.setOrientation(1);pad.setPadding(dp(10),dp(8),dp(10),dp(10));
        pad.setBackground(bg(Color.WHITE,Color.BLACK));
        root.addView(pad,new LinearLayout.LayoutParams(-1,0,1));
        View touch=new View(this);pad.addView(touch,new LinearLayout.LayoutParams(-1,0,1));
        touch.setOnTouchListener((v,e)->{
            switch(e.getActionMasked()){
                case MotionEvent.ACTION_DOWN: lastX=e.getX();lastY=e.getY();tracking=true;return true;
                case MotionEvent.ACTION_MOVE:
                    if(tracking){float dx=e.getX()-lastX,dy=e.getY()-lastY;
                        sendMovement(Math.round(dx*1.3f),Math.round(dy*1.3f));
                        lastX=e.getX();lastY=e.getY();}
                    return true;
                case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:tracking=false;return true;
            }return true;
        });
        LinearLayout labels=new LinearLayout(this);
        TextView ll=label("Sol Buton"),rr=label("Sağ Buton");
        labels.addView(ll,new LinearLayout.LayoutParams(0,dp(23),1));
        labels.addView(rr,new LinearLayout.LayoutParams(0,dp(23),1));pad.addView(labels);
        LinearLayout clicks=new LinearLayout(this);pad.addView(clicks,new LinearLayout.LayoutParams(-1,dp(48)));
        leftButton=button("",0xFFBBBBBB);rightButton=button("",0xFFBBBBBB);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1);lp.rightMargin=dp(6);clicks.addView(leftButton,lp);
        LinearLayout.LayoutParams rlp=new LinearLayout.LayoutParams(0,-1,1);rlp.leftMargin=dp(6);clicks.addView(rightButton,rlp);
        bindClick(leftButton,1);bindClick(rightButton,2);
        TextView footer=label("Muhammed Yusuf OLGUN");footer.setTextColor(0xFFAAAAAA);footer.setTextSize(12);
        LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(-1,dp(45));fp.topMargin=dp(8);root.addView(footer,fp);
        updateStatus();
    }
    private TextView label(String s){TextView t=new TextView(this);t.setText(s);t.setTextSize(13);t.setTextColor(Color.DKGRAY);t.setGravity(Gravity.CENTER);return t;}
    private void bindClick(Button b,int mask){
        b.setOnTouchListener((v,e)->{
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){buttons|=mask;b.setBackground(bg(0xFFE4E4E4,0));report(0,0,0);return true;}
            if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){
                buttons &= ~mask;b.setBackground(bg(0xFFBBBBBB,0));report(0,0,0);return true;
            }return true;
        });
    }
    private void sendMovement(int x,int y){
        while(x!=0||y!=0){int dx=Math.max(-127,Math.min(127,x)),dy=Math.max(-127,Math.min(127,y));
            report(dx,dy,0);x-=dx;y-=dy;}
    }
    private void report(int dx,int dy,int wheel){
        if(hid==null||connected==null)return;
        try{hid.sendReport(connected,0,new byte[]{(byte)buttons,(byte)dx,(byte)dy,(byte)wheel});}
        catch(SecurityException ex){deviceLabel.setText("Bluetooth izni gerekli");}
    }
    private void updateStatus(){
        if(deviceLabel==null)return;
        deviceLabel.setText(connected!=null ? "Bağlı: "+connected.getName() : selected!=null ? "Seçili: "+selected.getName() : "Bağlı değil");
    }
    private void startBluetooth(){
        if(adapter==null){deviceLabel.setText("Bluetooth desteklenmiyor");return;}
        if(!adapter.isEnabled()){startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));return;}
        adapter.getProfileProxy(this,new BluetoothProfile.ServiceListener(){
            @Override public void onServiceConnected(int profile,BluetoothProfile proxy){
                hid=(BluetoothHidDevice)proxy;
                BluetoothHidDeviceAppSdpSettings sdp=new BluetoothHidDeviceAppSdpSettings(
                    "Mouse","Bluetooth Touchpad","Olgun",BluetoothHidDevice.SUBCLASS1_COMBO,MOUSE_DESCRIPTOR);
                hid.registerApp(sdp,null,null,executor,callback);
                String addr=getPreferences(0).getString("device",null);
                if(addr!=null){try{selected=adapter.getRemoteDevice(addr);updateStatus();}catch(IllegalArgumentException ignored){}}
            }
            @Override public void onServiceDisconnected(int profile){hid=null;connected=null;updateStatus();}
        },BluetoothProfile.HID_DEVICE);
    }
    private void chooseDevice(){
        if(adapter==null)return;
        if(Build.VERSION.SDK_INT>=31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},42);return;
        }
        if(!adapter.isEnabled()){startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));return;}
        Set<BluetoothDevice> bonded=adapter.getBondedDevices();
        ArrayList<BluetoothDevice> list=new ArrayList<>(bonded);
        if(list.isEmpty()){
            new AlertDialog.Builder(this).setMessage("Önce Android Bluetooth ayarlarından bilgisayarınızla eşleşin.")
                .setPositiveButton("Bluetooth Ayarları",(d,w)->startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)))
                .setNegativeButton("İptal",null).show();return;
        }
        String[] names=new String[list.size()];
        for(int i=0;i<list.size();i++)names[i]=(list.get(i).getName()==null?"Cihaz":list.get(i).getName())+" ("+list.get(i).getAddress()+")";
        new AlertDialog.Builder(this).setTitle("Eşleşmiş cihaz seç").setItems(names,(d,which)->{
            selected=list.get(which);getPreferences(0).edit().putString("device",selected.getAddress()).apply();
            updateStatus();if(hid!=null)hid.connect(selected);
        }).setNeutralButton("Bluetooth Ayarları",(d,w)->startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)))
          .setNegativeButton("İptal",null).show();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==42&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)startBluetooth();
    }
    @Override protected void onDestroy(){
        if(hid!=null){try{hid.unregisterApp();if(adapter!=null)adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE,hid);}catch(Exception ignored){}}
        super.onDestroy();
    }
}
