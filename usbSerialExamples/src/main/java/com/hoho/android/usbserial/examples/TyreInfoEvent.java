package com.hoho.android.usbserial.examples;

import com.hoho.android.usbserial.bean.TyreInfoCollection;

public class TyreInfoEvent {
    private TyreInfoCollection collection;

    public TyreInfoEvent(TyreInfoCollection obj) {
        this.collection = obj;
    }

    public TyreInfoCollection getCollection() {
        return this.collection;
    }

}
