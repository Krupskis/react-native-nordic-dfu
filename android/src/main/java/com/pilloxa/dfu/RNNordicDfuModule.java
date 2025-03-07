
package com.pilloxa.dfu;

import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import androidx.annotation.Nullable;
import android.util.Log;
import com.facebook.react.bridge.*;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.modules.core.RCTNativeAppEventEmitter;
import no.nordicsemi.android.dfu.*;

public class RNNordicDfuModule extends ReactContextBaseJavaModule implements LifecycleEventListener {

    private final String dfuStateEvent = "DFUStateChanged";
    private final String progressEvent = "DFUProgress";
    private static final String name = "RNNordicDfu";
    public static final String LOG_TAG = name;
    private final ReactApplicationContext reactContext;
    private Promise mPromise = null;

    public RNNordicDfuModule(ReactApplicationContext reactContext) {
        super(reactContext);
        reactContext.addLifecycleEventListener(this);
        this.reactContext = reactContext;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            DfuServiceInitiator.createDfuNotificationChannel(reactContext);
        }
    }

    @ReactMethod
    public void startDFU(String address, String name, String filePath, int packetReceiptNotificationParameter, ReadableMap options, Promise promise) {
        mPromise = promise;
        final DfuServiceInitiator starter = new DfuServiceInitiator(address)
                .setKeepBond(false);
                
        if (options.hasKey("retries")) {
          int retries = options.getInt("retries");
          starter.setNumberOfRetries(retries);
        }

        starter.disableMtuRequest();
        // if (options.hasKey("maxMtu")) {
        //   int mtu = options.getInt("maxMtu");
        //   starter.setMtu(mtu);
        // }
        if (name != null) {
            starter.setDeviceName(name);
        }
          // mimic behavior of iOSDFULibrary when packetReceiptNotificationParameter is set to `0` - see: https://github.com/NordicSemiconductor/IOS-Pods-DFU-Library/blob/master/iOSDFULibrary/Classes/Implementation/DFUServiceInitiator.swift#L115
        if (packetReceiptNotificationParameter > 0) {
          starter.setPacketsReceiptNotificationsEnabled(true);
          starter.setPacketsReceiptNotificationsValue(packetReceiptNotificationParameter);
        } else {
          starter.setPacketsReceiptNotificationsValue(1);
        }
        starter.setUnsafeExperimentalButtonlessServiceInSecureDfuEnabled(true);
        if (filePath.endsWith(".bin") || filePath.endsWith(".hex")) {
            starter.setBinOrHex(DfuBaseService.TYPE_APPLICATION, filePath).setInitFile(null, null);
        } else {
            starter.setZip(filePath);
        }
      
        final DfuServiceController controller = starter.start(this.reactContext, DfuService.class);
    }

    @Override
    public String getName() {
        return name;
    }

    @ReactMethod
    public void addListener(String eventName) {
      // Keep: Required for RN built in Event Emitter Calls.
    }

     @ReactMethod
    public void removeListeners(int count) {
      // Keep: Required for RN built in Event Emitter Calls.
    }

    private void sendEvent(String eventName, @Nullable WritableMap params) {
        getReactApplicationContext()
                .getJSModule(RCTNativeAppEventEmitter.class)
                .emit(eventName, params);
    }

    private void sendStateUpdate(String state, String deviceAddress) {
        WritableMap map = new WritableNativeMap();
        Log.d(LOG_TAG, "State: " + state);
        map.putString("state", state);
        map.putString("deviceAddress", deviceAddress);
        sendEvent(dfuStateEvent, map);
    }


    @Override
    public void onHostResume() {
        DfuServiceListenerHelper.registerProgressListener(this.reactContext, mDfuProgressListener);

    }

    @Override
    public void onHostPause() {
    }

    @Override
    public void onHostDestroy() {
        DfuServiceListenerHelper.unregisterProgressListener(this.reactContext, mDfuProgressListener);

    }


    /**
     * The progress listener receives events from the DFU Service.
     * If is registered in onCreate() and unregistered in onDestroy() so methods here may also be called
     * when the screen is locked or the app went to the background. This is because the UI needs to have the
     * correct information after user comes back to the activity and this information can't be read from the service
     * as it might have been killed already (DFU completed or finished with error).
     */
    private final DfuProgressListener mDfuProgressListener = new DfuProgressListenerAdapter() {
        @Override
        public void onDeviceConnecting(final String deviceAddress) {
            sendStateUpdate("CONNECTING", deviceAddress);
        }

        @Override
        public void onDfuProcessStarting(final String deviceAddress) {
            sendStateUpdate("DFU_PROCESS_STARTING", deviceAddress);
        }

        @Override
        public void onEnablingDfuMode(final String deviceAddress) {
            sendStateUpdate("ENABLING_DFU_MODE", deviceAddress);
        }

        @Override
        public void onFirmwareValidating(final String deviceAddress) {
            sendStateUpdate("FIRMWARE_VALIDATING", deviceAddress);
        }

        @Override
        public void onDeviceDisconnecting(final String deviceAddress) {
            sendStateUpdate("DEVICE_DISCONNECTING", deviceAddress);
        }

        @Override
        public void onDfuCompleted(final String deviceAddress) {
            if (mPromise != null) {
                WritableMap map = new WritableNativeMap();
                map.putString("deviceAddress", deviceAddress);
                mPromise.resolve(map);
                mPromise = null;
            }
            sendStateUpdate("DFU_COMPLETED", deviceAddress);


            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {

                    // if this activity is still open and upload process was completed, cancel the notification
                    final NotificationManager manager = (NotificationManager) reactContext.getSystemService(Context.NOTIFICATION_SERVICE);
                    manager.cancel(DfuService.NOTIFICATION_ID);
                }
            }, 200);

        }

        @Override
        public void onDfuAborted(final String deviceAddress) {
            sendStateUpdate("DFU_ABORTED", deviceAddress);
            if (mPromise != null) {
                mPromise.reject("2", "DFU ABORTED");
                mPromise = null;
            }

        }

        @Override
        public void onProgressChanged(final String deviceAddress, final int percent, final float speed, final float avgSpeed, final int currentPart, final int partsTotal) {
            WritableMap map = new WritableNativeMap();
            map.putString("deviceAddress", deviceAddress);
            map.putInt("percent", percent);
            map.putDouble("speed", speed);
            map.putDouble("avgSpeed", avgSpeed);
            map.putInt("currentPart", currentPart);
            map.putInt("partsTotal", partsTotal);
            sendEvent(progressEvent, map);

        }

        @Override
        public void onError(final String deviceAddress, final int error, final int errorType, final String message) {
            sendStateUpdate("DFU_FAILED", deviceAddress);
            if (mPromise != null) {
                mPromise.reject(Integer.toString(error), message);
                mPromise = null;
            }
        }
    };
}
