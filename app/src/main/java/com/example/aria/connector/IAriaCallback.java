package com.example.aria.connector;

import android.os.IInterface;
import android.os.IBinder;
import java.util.List;

public interface IAriaCallback extends IInterface {
    void onEvent(EcosystemEventContainer event);
    void onCapabilitiesChanged(List<Capability> capabilities);

    abstract class Stub implements IAriaCallback {
        public static IAriaCallback asInterface(IBinder obj) { return null; }
    }
}
