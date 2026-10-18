package com.example.aria.connector;

import android.os.IInterface;
import android.os.IBinder;
import android.os.Binder;
import java.util.List;

public interface IAriaConnector extends IInterface {
    AppResponseContainer execute(AppCommandContainer command);
    List<Capability> getCapabilities();
    ConnectorVersionInfo getVersionInfo();
    String getAppName();
    void registerCallback(IAriaCallback callback);
    void unregisterCallback(IAriaCallback callback);
    String executeLegacy(String commandJson);

    abstract class Stub extends Binder implements IAriaConnector {
        public static IAriaConnector asInterface(IBinder obj) { return null; }
        @Override
        public IBinder asBinder() { return this; }
    }
}
