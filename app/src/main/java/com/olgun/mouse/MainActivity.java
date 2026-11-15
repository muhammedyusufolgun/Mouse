package com.olgun.mouse;

import android.Manifest;
import android.app.*;
import android.os.*;
import android.bluetooth.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
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
    private boolean registered = false;
    private boolean hidConnected = false;
    private String diagnostic = "";
    private boolean shuttingDown = false;
    private int connectAttempts = 0;
    private int lastHidState = BluetoothProfile.STATE_DISCONNECTED;
    private TouchSurface touchSurface;
    private boolean keyboardMode=false;
    private int modifiers=0;
    private LinearLayout keyboardPanel;
    private LinearLayout mousePanel;
    private final Executor executor = Executors.newSingleThreadExecutor();
    private static final byte[] MOUSE_DESCRIPTOR = new byte[] {
        0x05,0x01, 0x09,0x02, (byte)0xA1,0x01, (byte)0x85,0x01, 0x09,0x01, (byte)0xA1,0x00,
        0x05,0x09, 0x19,0x01, 0x29,0x03, 0x15,0x00, 0x25,0x01,
        (byte)0x95,0x03, 0x75,0x01, (byte)0x81,0x02,
        (byte)0x95,0x01, 0x75,0x05, (byte)0x81,0x03,
        0x05,0x01, 0x09,0x30, 0x09,0x31, 0x09,0x38,
        0x15,(byte)0x81, 0x25,0x7F, 0x75,0x08, (byte)0x95,0x03,
        (byte)0x81,0x06, (byte)0xC0, (byte)0xC0
    };
    private static final byte[] KEYBOARD_DESCRIPTOR=new byte[]{
        0x05,0x01,0x09,0x06,(byte)0xA1,0x01,(byte)0x85,0x02,
        0x05,0x07,0x19,(byte)0xE0,0x29,(byte)0xE7,0x15,0x00,0x25,0x01,
        0x75,0x01,(byte)0x95,0x08,(byte)0x81,0x02,
        0x75,0x08,(byte)0x95,0x01,(byte)0x81,0x01,
        0x75,0x08,(byte)0x95,0x06,0x15,0x00,0x25,0x65,
        0x05,0x07,0x19,0x00,0x29,0x65,(byte)0x81,0x00,(byte)0xC0
    };
    private final BluetoothHidDevice.Callback callback = new BluetoothHidDevice.Callback() {
        @Override public void onAppStatusChanged(BluetoothDevice plugged, boolean registered) {
            runOnUiThread(() -> { MainActivity.this.registered = registered; diagnostic = registered ? "HID hazır" : "HID kaydı başarısız"; updateStatus(); if (registered && selected != null && hid != null) connectHid(); });
        }
        @Override public void onConnectionStateChanged(BluetoothDevice device, int state) {
            runOnUiThread(() -> {
                lastHidState=state;
                if (state == BluetoothProfile.STATE_CONNECTED) {connected = device;hidConnected = true;diagnostic = "HID bağlı";connectAttempts = 0;}
                else if(state == BluetoothProfile.STATE_CONNECTING){diagnostic="HID bağlanıyor";}
                else if(state == BluetoothProfile.STATE_DISCONNECTING){diagnostic="HID ayrılıyor";}
                else if(state == BluetoothProfile.STATE_DISCONNECTED){if(connected != null && connected.equals(device))connected=null;hidConnected=false;diagnostic="HID bağlantısı kurulamadı";}
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
        b.setTextSize(13);b.setTypeface(null,Typeface.BOLD);b.setPadding(dp(5),0,dp(5),0);b.setBackground(bg(color,0));return b;
    }
    private void buildUi() {
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(12),dp(42),dp(12),dp(6));
        root.setBackgroundColor(Color.rgb(245,245,245));setContentView(root);
        TextView title=new TextView(this);title.setText("Mouse");title.setGravity(Gravity.CENTER);
        title.setTextSize(23);title.setTypeface(null,Typeface.BOLD);title.setTextColor(Color.WHITE);
        title.setBackgroundColor(Color.BLACK);root.addView(title,new LinearLayout.LayoutParams(-1,dp(32)));
        title.setOnClickListener(v->{keyboardMode=!keyboardMode;title.setText(keyboardMode?"Klavye":"Mouse");
            setRequestedOrientation(keyboardMode?ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE:ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            mousePanel.setVisibility(keyboardMode?View.GONE:View.VISIBLE);
            keyboardPanel.setVisibility(keyboardMode?View.VISIBLE:View.GONE);
        });
        LinearLayout row=new LinearLayout(this);row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,dp(56));rp.topMargin=dp(4);root.addView(row,rp);
        Button pair=button("Eşleş",0xFFAADCF6);row.addView(pair,new LinearLayout.LayoutParams(dp(85),dp(44)));
        pair.setOnClickListener(v->chooseDevice());
        deviceLabel=new TextView(this);deviceLabel.setTextSize(12);deviceLabel.setTextColor(Color.BLACK);deviceLabel.setTypeface(null,Typeface.BOLD);
        deviceLabel.setGravity(Gravity.CENTER_VERTICAL);deviceLabel.setPadding(dp(8),0,dp(4),0);
        row.addView(deviceLabel,new LinearLayout.LayoutParams(0,-1,1));
        closeButton=button("Kapat",0xFFF4AAAA);row.addView(closeButton,new LinearLayout.LayoutParams(dp(83),dp(44)));
        closeButton.setOnClickListener(v->{
            if(SystemClock.uptimeMillis()>closeDeadline){
                closeDeadline=SystemClock.uptimeMillis()+3000;closeButton.setText("Onayla");
                handler.postDelayed(()->{if(SystemClock.uptimeMillis()>=closeDeadline)closeButton.setText("Kapat");},3000);
            } else {
                closeDeadline=0;closeButton.setText("Kapat");
                shuttingDown=true;
                if(hid!=null && connected!=null)hid.disconnect(connected);
                connected=null;hidConnected=false;updateStatus();
                finishAndRemoveTask();
            }
        });
        View gap=new View(this);root.addView(gap,new LinearLayout.LayoutParams(1,dp(5)));
        LinearLayout pad=new LinearLayout(this);pad.setOrientation(1);pad.setPadding(dp(10),dp(8),dp(10),dp(10));
        pad.setBackground(bg(Color.WHITE,Color.BLACK));
        LinearLayout.LayoutParams padParams=new LinearLayout.LayoutParams(-1,0,2);root.addView(pad,padParams);
        mousePanel=pad;
        touchSurface=new TouchSurface(this);View touch=touchSurface;pad.addView(touch,new LinearLayout.LayoutParams(-1,0,1));
        touch.setOnTouchListener((v,e)->{
            switch(e.getActionMasked()){
                case MotionEvent.ACTION_DOWN: lastX=e.getX();lastY=e.getY();tracking=true;return true;
                case MotionEvent.ACTION_MOVE:
                    if(tracking){float dx=e.getX()-lastX,dy=e.getY()-lastY;
                        sendMovement(Math.round(dx*1.3f),Math.round(dy*1.3f));
                        touchSurface.move(dx,dy);
                        lastX=e.getX();lastY=e.getY();}
                    return true;
                case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:tracking=false;return true;
            }return true;
        });
        LinearLayout clicks=new LinearLayout(this);pad.addView(clicks,new LinearLayout.LayoutParams(-1,dp(48)));
        leftButton=button("Sol Buton",0xFFBBBBBB);rightButton=button("Sağ Buton",0xFFBBBBBB);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,1);lp.rightMargin=dp(6);clicks.addView(leftButton,lp);
        LinearLayout.LayoutParams rlp=new LinearLayout.LayoutParams(0,-1,1);rlp.leftMargin=dp(6);clicks.addView(rightButton,rlp);
        bindClick(leftButton,1);bindClick(rightButton,2);
        TextView footer=label("Muhammed Yusuf OLGUN");footer.setTextColor(0xFFAAAAAA);footer.setTextSize(12);
        LinearLayout.LayoutParams fp=new LinearLayout.LayoutParams(-1,dp(18));fp.topMargin=0;root.addView(footer,fp);
        keyboardPanel=new LinearLayout(this);keyboardPanel.setOrientation(1);keyboardPanel.setVisibility(View.GONE);
        root.addView(keyboardPanel,new LinearLayout.LayoutParams(-1,0,2));
        buildKeyboard();
        View remainingSpace=new View(this);root.addView(remainingSpace,new LinearLayout.LayoutParams(1,0,1));
        updateStatus();
    }
    private TextView label(String s){TextView t=new TextView(this);t.setText(s);t.setTextSize(13);t.setTextColor(Color.BLACK);t.setTypeface(null,Typeface.BOLD);t.setGravity(Gravity.CENTER);return t;}
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
        if(hid==null||connected==null||!hidConnected){if(deviceLabel!=null)deviceLabel.setText(registered ? "HID bağlantısı yok: Windows fare olarak bağlanmadı" : "HID kaydı başarısız: cihaz desteğini kontrol edin");return;}
        try{boolean sent=hid.sendReport(connected,1,new byte[]{(byte)buttons,(byte)dx,(byte)dy,(byte)wheel});
            if(!sent)deviceLabel.setText("HID raporu gönderilemedi");}
        catch(SecurityException ex){deviceLabel.setText("Bluetooth izni gerekli");}
    }
    private void updateStatus(){
        if(deviceLabel==null)return;
        deviceLabel.setText(hidConnected && connected!=null ? "HID fare: "+connected.getName() : selected!=null ? (registered ? "HID bekleniyor: " : "HID kaydı yok: ")+selected.getName() : diagnostic.isEmpty() ? "Bağlı değil" : diagnostic);
    }
    private void connectHid(){
        if(shuttingDown || hid==null || selected==null || !registered || hidConnected)return;
        try{
            connectAttempts++;
            boolean accepted=hid.connect(selected);
            diagnostic=accepted ? "HID bağlantısı kuruluyor" : "Windows HID bağlantısını reddetti";
            deviceLabel.setText(diagnostic);
            if(connectAttempts<3)handler.postDelayed(()->{
                if(!hidConnected && registered && !shuttingDown && lastHidState!=BluetoothProfile.STATE_CONNECTING)connectHid();
            },3500);
            else handler.postDelayed(()->{
                if(!hidConnected && !shuttingDown)deviceLabel.setText("HID kurulamadı: Windows'ta cihazı kaldırıp yeniden eşleştirin");
            },3500);
        }catch(SecurityException e){deviceLabel.setText("Bluetooth izni gerekli");}
    }
    private byte[] combinedDescriptor(){
        byte[] combined=Arrays.copyOf(MOUSE_DESCRIPTOR,MOUSE_DESCRIPTOR.length+KEYBOARD_DESCRIPTOR.length);
        System.arraycopy(KEYBOARD_DESCRIPTOR,0,combined,MOUSE_DESCRIPTOR.length,KEYBOARD_DESCRIPTOR.length);
        return combined;
    }
    private void buildKeyboard(){
        String[][] rows={
            {"Esc","F1","F2","F3","F4","F5","F6","F7","F8","F9","F10","F11","F12","Del"},
            {"1","2","3","4","5","6","7","8","9","0","-","=","Back"},
            {"Tab","Q","W","E","R","T","Y","U","I","O","P","[","]","Enter"},
            {"Caps","A","S","D","F","G","H","J","K","L",";","'","\\\\","↑"},
            {"Shift","Z","X","C","V","B","N","M",",",".","/","←","↓","→"},
            {"Ctrl","Alt","Win","Space","AltGr","Home","End","PgUp","PgDn","Ins"}
        };
        for(String[] keys:rows){
            LinearLayout row=new LinearLayout(this);row.setOrientation(0);
            keyboardPanel.addView(row,new LinearLayout.LayoutParams(-1,0,1));
            for(String key:keys){
                Button b=button(key,0xFFE1E1E1);b.setTextSize(11);
                LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-1,key.equals("Space")?5: key.equals("Enter")||key.equals("Shift")?2:1);
                lp.setMargins(dp(1),dp(1),dp(1),dp(1));row.addView(b,lp);
                b.setOnClickListener(v->keyPress(key,b));
            }
        }
    }
    private void keyPress(String key,Button button){
        int modifier=key.equals("Ctrl")?1:key.equals("Shift")?2:key.equals("Alt")?4:key.equals("Win")?8:key.equals("AltGr")?64:0;
        if(modifier!=0){modifiers^=modifier;button.setBackground(bg((modifiers&modifier)!=0?0xFF92C8F0:0xFFE1E1E1,0));return;}
        int code=keyCode(key);
        if(code==0)return;
        if(hid==null||connected==null||!hidConnected){deviceLabel.setText("HID klavye bağlı değil");return;}
        try{
            byte[] press=new byte[]{(byte)modifiers,0,(byte)code,0,0,0,0,0};
            hid.sendReport(connected,2,press);
            handler.postDelayed(()->{
                if(hid!=null&&connected!=null&&hidConnected){
                    try{hid.sendReport(connected,2,new byte[8]);}catch(Exception ignored){}
                }
            },65);
        }catch(Exception e){deviceLabel.setText("Klavye HID raporu başarısız");}
    }
    private int keyCode(String key){
        if(key.length()==1){
            char ch=Character.toUpperCase(key.charAt(0));
            if(ch>='A'&&ch<='Z')return 4+ch-'A';
            if(ch>='1'&&ch<='9')return 30+ch-'1';
            if(ch=='0')return 39;
            switch(ch){case '-':return 45;case '=':return 46;case '[':return 47;case ']':return 48;case '\\\\':return 49;case ';':return 51;case '\'':return 52;case ',':return 54;case '.':return 55;case '/':return 56;}
        }
        if(key.startsWith("F")){try{int n=Integer.parseInt(key.substring(1));if(n>=1&&n<=12)return 57+n;}catch(Exception ignored){}}
        switch(key){
            case "Enter":return 40;case "Esc":return 41;case "Back":return 42;case "Tab":return 43;
            case "Space":return 44;case "Caps":return 57;case "Ins":return 73;case "Home":return 74;
            case "PgUp":return 75;case "Del":return 76;case "End":return 77;case "PgDn":return 78;
            case "→":return 79;case "←":return 80;case "↓":return 81;case "↑":return 82;
        }
        return 0;
    }
    private void startBluetooth(){
        if(adapter==null){deviceLabel.setText("Bluetooth desteklenmiyor");return;}
        if(!adapter.isEnabled()){startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));return;}
        adapter.getProfileProxy(this,new BluetoothProfile.ServiceListener(){
            @Override public void onServiceConnected(int profile,BluetoothProfile proxy){
                hid=(BluetoothHidDevice)proxy;
                BluetoothHidDeviceAppSdpSettings sdp=new BluetoothHidDeviceAppSdpSettings(
                    "Mouse","Bluetooth Touchpad","Olgun",BluetoothHidDevice.SUBCLASS1_COMBO,combinedDescriptor());
                String addr=getPreferences(0).getString("device",null);
                if(addr!=null){try{selected=adapter.getRemoteDevice(addr);updateStatus();}catch(IllegalArgumentException ignored){}}
                boolean started=hid.registerApp(sdp,null,null,executor,callback);
                if(!started){diagnostic="HID kaydı başlatılamadı";updateStatus();deviceLabel.setText(diagnostic);}
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
            connectAttempts=0;updateStatus();if(hid!=null && registered)connectHid();
        }).setNeutralButton("Bluetooth Ayarları",(d,w)->startActivity(new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)))
          .setNegativeButton("İptal",null).show();
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){
        super.onRequestPermissionsResult(request,permissions,results);
        if(request==42&&results.length>0&&results[0]==PackageManager.PERMISSION_GRANTED)startBluetooth();
    }
    private static class TouchSurface extends View {
        private final Paint paint=new Paint(3);
        private float cursorX=-1,cursorY=-1;
        TouchSurface(Context context){super(context);}
        void move(float dx,float dy){
            if(cursorX<0){cursorX=getWidth()/2f;cursorY=getHeight()/2f;}
            cursorX=Math.max(8,Math.min(getWidth()-8,cursorX+dx));
            cursorY=Math.max(8,Math.min(getHeight()-8,cursorY+dy));invalidate();
        }
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);
            if(cursorX<0){cursorX=getWidth()/2f;cursorY=getHeight()/2f;}
            paint.setColor(Color.BLACK);
            canvas.drawCircle(cursorX,cursorY,dpLocal(6),paint);
            paint.setColor(Color.WHITE);
            canvas.drawCircle(cursorX,cursorY,dpLocal(3),paint);
        }
        private float dpLocal(int n){return n*getResources().getDisplayMetrics().density;}
    }
    @Override protected void onDestroy(){
        shuttingDown=true;
        if(hid!=null){try{hid.unregisterApp();if(adapter!=null)adapter.closeProfileProxy(BluetoothProfile.HID_DEVICE,hid);}catch(Exception ignored){}}
        super.onDestroy();
    }
}
