package com.hoho.android.usbserial.examples;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.RemoteViews;

import androidx.fragment.app.FragmentManager;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.hoho.android.usbserial.bean.TyreInfoBean;
import com.hoho.android.usbserial.bean.TyreInfoCollection;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements FragmentManager.OnBackStackChangedListener {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EventBus.getDefault().register(this);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportFragmentManager().addOnBackStackChangedListener(this);
        if (savedInstanceState == null)
            getSupportFragmentManager().beginTransaction().add(R.id.fragment, new DevicesFragment(), "devices").commit();
        else
            onBackStackChanged();
    }

    @Override
    public void onBackStackChanged() {
        getSupportActionBar().setDisplayHomeAsUpEnabled(getSupportFragmentManager().getBackStackEntryCount()>0);
    }

    @Override
    public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        if("android.hardware.usb.action.USB_DEVICE_ATTACHED".equals(intent.getAction())) {
            TerminalFragment terminal = (TerminalFragment)getSupportFragmentManager().findFragmentByTag("terminal");
            if (terminal != null)
                terminal.status("USB device detected");
        }
        super.onNewIntent(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        EventBus.getDefault().unregister(this);
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onEventReciverSettingInfo(TyreInfoEvent fVar) {
        TyreInfoCollection<Integer, TyreInfoBean> tyreInfoCollection;
        if (fVar != null && (tyreInfoCollection = fVar.getCollection()) != null && (tyreInfoCollection instanceof TyreInfoCollection)) {
            for (Map.Entry<Integer, TyreInfoBean> entry : tyreInfoCollection.entrySet()) {
                updateWidget(entry.getValue());
            }
        }
    }

    private void updateWidget(TyreInfoBean tyreInfoBean){
        Context contextToUpdateWidget = this;
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(contextToUpdateWidget);
        RemoteViews remoteViews = new RemoteViews(contextToUpdateWidget.getPackageName(), R.layout.tpms_widget);
        ComponentName thisWidget = new ComponentName(contextToUpdateWidget, TpmsWidget.class);
        switch (tyreInfoBean.getPosition()){
            case 2:
                remoteViews.setTextViewText(R.id.ivTyreLFTxt,tyreInfoBean.getAirPressure());
                remoteViews.setTextViewText(R.id.ivTyreLFTempTxt,tyreInfoBean.getTemperature());
                break;
            case 4:
                remoteViews.setTextViewText(R.id.ivTyreLBTxt,tyreInfoBean.getAirPressure());
                remoteViews.setTextViewText(R.id.ivTyreLBTempTxt,tyreInfoBean.getTemperature());
                break;
            case 1:
                remoteViews.setTextViewText(R.id.ivTyreRFTxt,tyreInfoBean.getAirPressure());
                remoteViews.setTextViewText(R.id.ivTyreRFTempTxt,tyreInfoBean.getTemperature());
                break;
            case 3:
                remoteViews.setTextViewText(R.id.ivTyreRBTxt,tyreInfoBean.getAirPressure());
                remoteViews.setTextViewText(R.id.ivTyreRBTempTxt,tyreInfoBean.getTemperature());
                break;
        }
        appWidgetManager.updateAppWidget(thisWidget, remoteViews);
    }


}
