package com.hoho.android.usbserial.bean;

import android.content.Context;
import android.content.res.Resources;

import com.hoho.android.usbserial.examples.R;

import java.io.Serializable;

public class TyreInfoBean implements Serializable {
    private String airPressure;
    private float airValue;
    private boolean isAbnormal = false;
    private int position;
    private String sensorId;
    private byte state;
    private String temperature;

    public static boolean isAbnormal(byte b) {
        int i = b & 255;
        return (i == 3 || i == 32 || b == 0) ? false : true;
    }

    public TyreInfoBean() {
    }

    public TyreInfoBean(String str, int i, String str2, String str3, byte b) {
        this.sensorId = str;
        this.position = i;
        this.airPressure = str2;
        this.temperature = str3;
        this.state = b;
    }

    public String getSensorId() {
        return this.sensorId;
    }

    public void setSensorId(String str) {
        this.sensorId = str;
    }

    public int getPosition() {
        return this.position;
    }

    public void setPosition(int i) {
        this.position = i;
    }

    public String getAirPressure() {
        return this.airPressure;
    }

    public void setAirPressure(String str) {
        this.airPressure = str;
    }

    public String getTemperature() {
        return this.temperature;
    }

    public void setTemperature(String str) {
        this.temperature = str;
    }

    public byte getState() {
        return this.state;
    }

    public void setState(byte b) {
        this.state = b;
        this.isAbnormal = isAbnormal(b);
    }

    public float getAirValue() {
        return this.airValue;
    }

    public void setAirValue(float f) {
        this.airValue = f;
    }

    public boolean isAbnormal() {
        return this.isAbnormal;
    }

    public boolean isClearMask() {
        return this.airValue == 0.0f && "-50".equals(this.temperature);
    }

    public boolean isLowPower() {
        return (this.state & 128) != 0;
    }

    public boolean isTyreLongInvalid() {
        return (this.state & 64) != 0;
    }

    public boolean isTyreShortInvalid() {
        return (this.state & 32) != 0;
    }

    public boolean isHightAp() {
        return (this.state & 16) != 0;
    }

    public boolean isLowAp() {
        return (this.state & 8) != 0;
    }

    public boolean isTpAbnormal() {
        return (this.state & 4) != 0;
    }

    public boolean isSlowLeak() {
        return (this.state & 3) == 2;
    }

    public boolean isFastLeak() {
        return (this.state & 3) == 1;
    }

    public boolean isOtherStateException() {
        return (this.state & 159) != 0;
    }

    public String getStateDescri(Context context) {
        StringBuilder sb = new StringBuilder();
        sb.append("");
        if (context != null) {
            Resources resources = context.getResources();
            String string = resources.getString(R.string.descri_tyre_rb);
            if (this.position == 2) {
                string = resources.getString(R.string.descri_tyre_lf);
            } else if (this.position == 1) {
                string = resources.getString(R.string.descri_tyre_rf);
            } else if (this.position == 4) {
                string = resources.getString(R.string.descri_tyre_lb);
            }
            if ((this.state & 16) != 0) {
                sb.append(string);
                sb.append(resources.getString(R.string.descri_state_height_air));
                sb.append(" ");
            }
            if ((this.state & 8) != 0) {
                sb.append(string);
                sb.append(resources.getString(R.string.descri_state_low_air));
                sb.append(" ");
            }
            int i = this.state & 3;
            if (i == 1) {
                sb.append(string);
                sb.append(resources.getString(R.string.descri_state_quick_air_leak));
                sb.append(" ");
            } else if (i == 2) {
                sb.append(string);
                sb.append(resources.getString(R.string.descri_state__air_leak));
                sb.append(" ");
            }
            if ((this.state & 4) != 0) {
                sb.append(string);
                sb.append(resources.getString(R.string.descri_state_height_tp));
                sb.append(" ");
            }
            if ((this.state & 64) != 0) {
                sb.append(string);
                sb.append(resources.getString(R.string.descri_state_tyre_invalid));
                sb.append(" ");
            }
            if ((this.state & 128) != 0) {
                sb.append(string);
                sb.append(resources.getString(R.string.descri_state_low_power));
                sb.append(" ");
            }
        }
        return sb.toString();
    }

}
