package com.hoho.android.usbserial.examples;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RemoteViews;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.hoho.android.usbserial.bean.TyreInfoBean;
import com.hoho.android.usbserial.bean.TyreInfoCollection;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.HexDump;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import org.greenrobot.eventbus.EventBus;

import java.io.IOException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.concurrent.Executors;

public class TerminalFragment extends Fragment implements SerialInputOutputManager.Listener {

    private enum UsbPermission { Unknown, Requested, Granted, Denied }

    private static final String INTENT_ACTION_GRANT_USB = BuildConfig.APPLICATION_ID + ".GRANT_USB";
    private static final int WRITE_WAIT_MILLIS = 2000;
    private static final int READ_WAIT_MILLIS = 2000;

    private int deviceId, portNum, baudRate;
    private boolean withIoManager;

    private final BroadcastReceiver broadcastReceiver;
    private final Handler mainLooper;
    private TextView receiveText;
    private ControlLines controlLines;

    private SerialInputOutputManager usbIoManager;
    private UsbSerialPort usbSerialPort;
    private UsbPermission usbPermission = UsbPermission.Unknown;
    private boolean connected = false;

    private TyreInfoCollection<Integer, TyreInfoBean> tyreInfoCollection;
    private int j;

    public TerminalFragment() {
        broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if(INTENT_ACTION_GRANT_USB.equals(intent.getAction())) {
                    usbPermission = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                            ? UsbPermission.Granted : UsbPermission.Denied;
                    connect();
                }
            }
        };
        mainLooper = new Handler(Looper.getMainLooper());
    }

    /*
     * Lifecycle
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        deviceId = getArguments().getInt("device");
        portNum = getArguments().getInt("port");
        baudRate = getArguments().getInt("baud");
        withIoManager = getArguments().getBoolean("withIoManager");
    }

    @Override
    public void onResume() {
        super.onResume();
        getActivity().registerReceiver(broadcastReceiver, new IntentFilter(INTENT_ACTION_GRANT_USB));

        if(usbPermission == UsbPermission.Unknown || usbPermission == UsbPermission.Granted)
            mainLooper.post(this::connect);
    }

    @Override
    public void onPause() {
        if(connected) {
            status("disconnected");
            disconnect();
        }
        getActivity().unregisterReceiver(broadcastReceiver);
        super.onPause();
    }

    /*
     * UI
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        receiveText = view.findViewById(R.id.receive_text);                          // TextView performance decreases with number of spans
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText)); // set as default color to reduce number of spans
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());
        TextView sendText = view.findViewById(R.id.send_text);
        View sendBtn = view.findViewById(R.id.send_btn);
        sendBtn.setOnClickListener(v -> send(sendText.getText().toString()));
        View receiveBtn = view.findViewById(R.id.receive_btn);
        controlLines = new ControlLines(view);
        if(withIoManager) {
            receiveBtn.setVisibility(View.GONE);
        } else {
            receiveBtn.setOnClickListener(v -> read());
        }
        return view;
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_terminal, menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            receiveText.setText("");
            return true;
        } else if( id == R.id.send_break) {
            if(!connected) {
                Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            } else {
                try {
                    usbSerialPort.setBreak(true);
                    Thread.sleep(100); // should show progress bar instead of blocking UI thread
                    usbSerialPort.setBreak(false);
                    SpannableStringBuilder spn = new SpannableStringBuilder();
                    spn.append("send <break>\n");
                    spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    receiveText.append(spn);
                } catch(UnsupportedOperationException ignored) {
                    Toast.makeText(getActivity(), "BREAK not supported", Toast.LENGTH_SHORT).show();
                } catch(Exception e) {
                    Toast.makeText(getActivity(), "BREAK failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }

    /*
     * Serial
     */
    @Override
    public void onNewData(byte[] data) {
        mainLooper.post(() -> {
            receive(data);
        });
    }

    @Override
    public void onRunError(Exception e) {
        mainLooper.post(() -> {
            status("connection lost: " + e.getMessage());
            disconnect();
        });
    }

    /*
     * Serial + UI
     */
    private void connect() {
        UsbDevice device = null;
        UsbManager usbManager = (UsbManager) getActivity().getSystemService(Context.USB_SERVICE);
        for(UsbDevice v : usbManager.getDeviceList().values())
            if(v.getDeviceId() == deviceId)
                device = v;
        if(device == null) {
            status("connection failed: device not found");
            return;
        }
        UsbSerialDriver driver = UsbSerialProber.getDefaultProber().probeDevice(device);
        if(driver == null) {
            driver = CustomProber.getCustomProber().probeDevice(device);
        }
        if(driver == null) {
            status("connection failed: no driver for device");
            return;
        }
        if(driver.getPorts().size() < portNum) {
            status("connection failed: not enough ports at device");
            return;
        }
        usbSerialPort = driver.getPorts().get(portNum);
        UsbDeviceConnection usbConnection = usbManager.openDevice(driver.getDevice());
        if(usbConnection == null && usbPermission == UsbPermission.Unknown && !usbManager.hasPermission(driver.getDevice())) {
            usbPermission = UsbPermission.Requested;
            PendingIntent usbPermissionIntent = PendingIntent.getBroadcast(getActivity(), 0, new Intent(INTENT_ACTION_GRANT_USB), 0);
            usbManager.requestPermission(driver.getDevice(), usbPermissionIntent);
            return;
        }
        if(usbConnection == null) {
            if (!usbManager.hasPermission(driver.getDevice()))
                status("connection failed: permission denied");
            else
                status("connection failed: open failed");
            return;
        }

        try {
            usbSerialPort.open(usbConnection);
            usbSerialPort.setParameters(baudRate, 8, 1, UsbSerialPort.PARITY_NONE);
            if(withIoManager) {
                usbIoManager = new SerialInputOutputManager(usbSerialPort, this);
                Executors.newSingleThreadExecutor().submit(usbIoManager);
            }
            status("connected:port is " + portNum);
            connected = true;
            controlLines.start();
        } catch (Exception e) {
            status("connection failed: " + e.getMessage());
            disconnect();
        }
    }

    private void disconnect() {
        connected = false;
        controlLines.stop();
        if(usbIoManager != null) {
            usbIoManager.setListener(null);
            usbIoManager.stop();
        }
        usbIoManager = null;
        try {
            usbSerialPort.close();
        } catch (IOException ignored) {}
        usbSerialPort = null;
    }

    private void send(String str) {
        if(!connected) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
        byte[] data = (str + '\n').getBytes();
            SpannableStringBuilder spn = new SpannableStringBuilder();
            spn.append("send ").append(String.valueOf(data.length)).append(" bytes\n");
            spn.append(HexDump.dumpHexString(data)).append("\n");
            spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            receiveText.append(spn);
            usbSerialPort.write(data, WRITE_WAIT_MILLIS);
        } catch (Exception e) {
            onRunError(e);
        }
    }

    private void read() {
        if(!connected) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            byte[] buffer = new byte[8192];
            int len = usbSerialPort.read(buffer, READ_WAIT_MILLIS);
            receive(Arrays.copyOf(buffer, len));
        } catch (IOException e) {
            // when using read with timeout, USB bulkTransfer returns -1 on timeout _and_ errors
            // like connection loss, so there is typically no exception thrown here on error
            status("connection lost: " + e.getMessage());
            disconnect();
        }
    }

    private void receive(byte[] data) {
        SpannableStringBuilder spn = new SpannableStringBuilder();
        spn.append("receive " + data.length + " bytes\n");
        if(data.length > 0) {
            spn.append(HexDump.dumpHexString(data)).append("\n");
            handleTyreData(data);
        }
        receiveText.append(spn);
    }

    private void dumpTyreInfo(TyreInfoBean tyreInfoBean){
        SpannableStringBuilder spn = new SpannableStringBuilder();

        String tyreName = "右后轮";
        if (tyreInfoBean.getPosition() == 2) {
            tyreName = "左前轮";
        } else if (tyreInfoBean.getPosition() == 1) {
            tyreName = "右前轮";
        } else if (tyreInfoBean.getPosition() == 4) {
            tyreName = "左后轮";
        }
        spn.append(tyreName + "--" + tyreInfoBean.getSensorId() + "，压力：" + tyreInfoBean.getAirPressure() + "，温度：" + tyreInfoBean.getTemperature() + "，压力值：" + tyreInfoBean.getAirValue() + "\n");
        //sendMessage(tyreInfoBean);
        receiveText.append(spn);
    }

    private void sendMessage(TyreInfoBean tyreInfoBean){
        Intent updateIntent =new Intent();
        updateIntent.setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
        Bundle extras = new Bundle();
        extras.putInt("tyreIndex",tyreInfoBean.getPosition());
        extras.putString("pressure",tyreInfoBean.getAirPressure());
        extras.putString("temp",tyreInfoBean.getTemperature());
        extras.putIntArray(AppWidgetManager.EXTRA_APPWIDGET_IDS, new int[]{R.layout.tpms_widget});
        updateIntent.putExtras(extras);
        LocalBroadcastManager.getInstance(getActivity()).sendBroadcast(updateIntent);
    }

    private void handleTyreData(byte[] bArr){
        try{
            byte[] bArr2 = new byte[8];
            if (bArr != null && bArr[4] == 99 && bArr.length >= 7) {
                boolean z = true;
                if (bArr[5] == 0) {
                    if (bArr.length >= 15) {
                        System.arraycopy(bArr, 6, bArr2, 0, bArr2.length);
                        postTyreInfo(bArr2, false);
                        if (this.tyreInfoCollection.size() >= this.j) {
                            TyreInfoCollection tyreInfoCollection = new TyreInfoCollection();
                            tyreInfoCollection.putAll(this.tyreInfoCollection);
                            try {
                                Iterator it = tyreInfoCollection.values().iterator();
                                while (true) {
                                    if (!it.hasNext()) {
                                        z = false;
                                        break;
                                    }
                                    TyreInfoBean tyreInfoBean = (TyreInfoBean) it.next();
                                    if (tyreInfoBean != null && tyreInfoBean.getState() != 0) {
                                        break;
                                    }
                                }
                                if (z) {
//                                b((mate.steel.com.t620.usb.a<Boolean>) null);
                                }
                            } catch (Exception e2) {
                                e2.printStackTrace();
                            }
                        EventBus.getDefault().post(new TyreInfoEvent(tyreInfoCollection));
//                        o();
                        }
                    } else if (bArr.length == 8) {
                        this.j = bArr[6] & 255;
//                    EventBus.getDefault().post(new g());
                        this.tyreInfoCollection.clear();
                        if (this.j <= 0) {
                        EventBus.getDefault().post(new TyreInfoEvent(this.tyreInfoCollection));
//                        o();
                        }
                    }
                } else if (bArr[5] != -1 && bArr[5] != -86 && bArr.length >= 14) {
                    System.arraycopy(bArr, 5, bArr2, 0, bArr2.length);
                    if (postTyreInfo(bArr2, true)) {
//                    b((mate.steel.com.t620.usb.a<Boolean>) null);
                    }
                }
            }
        }catch (Exception e){
            receiveText.append(e.getMessage());
        }

    }

    private boolean postTyreInfo(byte[] bArr,boolean z){
        boolean z2;
        synchronized (this) {
            z2 = false;
            if (bArr != null) {
                try {
                    if (bArr.length == 8) {
                        TyreInfoBean g2 = getTyreInfoBean(bArr);
                        this.tyreInfoCollection.put(Integer.valueOf(g2.getPosition()), g2);
                        if (g2.getState() != 0) {
                            z2 = true;
                        }
                        if (z) {
                            TyreInfoCollection tyreInfoCollection = new TyreInfoCollection();
                            tyreInfoCollection.putAll(this.tyreInfoCollection);
                            EventBus.getDefault().post(new TyreInfoEvent(tyreInfoCollection));
//                            o();
                        }
//                        q.a(a, ">>>>>>>>>>>>>>>>>== aredy post tyres info ==<<<<<<<<<<<<<<<<<<<");
                    }
                } catch (Throwable th) {
                    throw th;
                }
            }
        }
        return z2;
    }

    private TyreInfoBean getTyreInfoBean(byte[] bArr) {
        if (bArr == null || bArr.length != 8) {
            return null;
        }
        TyreInfoBean tyreInfoBean = new TyreInfoBean();
        StringBuilder sb = new StringBuilder();
        tyreInfoBean.setPosition(bArr[0] & 255);
        sb.append("0x");
        sb.append(HexDump.a(bArr[1]));
        sb.append(HexDump.a(bArr[2]));
        sb.append(HexDump.a(bArr[3]));
        tyreInfoBean.setSensorId(sb.toString());
        float parseFloat = Float.parseFloat(HexDump.getBigDecimalStr(((float) HexDump.getBigDecimalDou((double) (((bArr[4] << 8) & 768) | (bArr[5] & 255)), 0.025d)) + "", 1));
        tyreInfoBean.setAirValue(parseFloat);
        tyreInfoBean.setAirPressure(HexDump.getBigDecimalStr(parseFloat + "", 1));
        tyreInfoBean.setTemperature("" + ((bArr[6] & 255) + -50));
        tyreInfoBean.setState(bArr[7]);
        dumpTyreInfo(tyreInfoBean);
        return tyreInfoBean;
    }


    void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str+'\n');
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn);
    }

    class ControlLines {
        private static final int refreshInterval = 200; // msec

        private final Runnable runnable;
        private final ToggleButton rtsBtn, ctsBtn, dtrBtn, dsrBtn, cdBtn, riBtn;

        ControlLines(View view) {
            runnable = this::run; // w/o explicit Runnable, a new lambda would be created on each postDelayed, which would not be found again by removeCallbacks

            rtsBtn = view.findViewById(R.id.controlLineRts);
            ctsBtn = view.findViewById(R.id.controlLineCts);
            dtrBtn = view.findViewById(R.id.controlLineDtr);
            dsrBtn = view.findViewById(R.id.controlLineDsr);
            cdBtn = view.findViewById(R.id.controlLineCd);
            riBtn = view.findViewById(R.id.controlLineRi);
            rtsBtn.setOnClickListener(this::toggle);
            dtrBtn.setOnClickListener(this::toggle);
        }

        private void toggle(View v) {
            ToggleButton btn = (ToggleButton) v;
            if (!connected) {
                btn.setChecked(!btn.isChecked());
                Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
                return;
            }
            String ctrl = "";
            try {
                if (btn.equals(rtsBtn)) { ctrl = "RTS"; usbSerialPort.setRTS(btn.isChecked()); }
                if (btn.equals(dtrBtn)) { ctrl = "DTR"; usbSerialPort.setDTR(btn.isChecked()); }
            } catch (IOException e) {
                status("set" + ctrl + "() failed: " + e.getMessage());
            }
        }

        private void run() {
            if (!connected)
                return;
            try {
                EnumSet<UsbSerialPort.ControlLine> controlLines = usbSerialPort.getControlLines();
                rtsBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.RTS));
                ctsBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.CTS));
                dtrBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.DTR));
                dsrBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.DSR));
                cdBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.CD));
                riBtn.setChecked(controlLines.contains(UsbSerialPort.ControlLine.RI));
                mainLooper.postDelayed(runnable, refreshInterval);
            } catch (IOException e) {
                status("getControlLines() failed: " + e.getMessage() + " -> stopped control line refresh");
            }
        }

        void start() {
            if (!connected)
                return;
            try {
                EnumSet<UsbSerialPort.ControlLine> controlLines = usbSerialPort.getSupportedControlLines();
                if (!controlLines.contains(UsbSerialPort.ControlLine.RTS)) rtsBtn.setVisibility(View.INVISIBLE);
                if (!controlLines.contains(UsbSerialPort.ControlLine.CTS)) ctsBtn.setVisibility(View.INVISIBLE);
                if (!controlLines.contains(UsbSerialPort.ControlLine.DTR)) dtrBtn.setVisibility(View.INVISIBLE);
                if (!controlLines.contains(UsbSerialPort.ControlLine.DSR)) dsrBtn.setVisibility(View.INVISIBLE);
                if (!controlLines.contains(UsbSerialPort.ControlLine.CD))   cdBtn.setVisibility(View.INVISIBLE);
                if (!controlLines.contains(UsbSerialPort.ControlLine.RI))   riBtn.setVisibility(View.INVISIBLE);
                run();
            } catch (IOException e) {
                Toast.makeText(getActivity(), "getSupportedControlLines() failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }

        void stop() {
            mainLooper.removeCallbacks(runnable);
            rtsBtn.setChecked(false);
            ctsBtn.setChecked(false);
            dtrBtn.setChecked(false);
            dsrBtn.setChecked(false);
            cdBtn.setChecked(false);
            riBtn.setChecked(false);
        }
    }
}
