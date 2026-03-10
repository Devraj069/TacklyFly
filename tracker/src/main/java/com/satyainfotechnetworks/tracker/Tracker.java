package com.satyainfotechnetworks.tracker;

import android.content.Context;
import android.util.Log;
import org.json.JSONObject;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import com.android.installreferrer.api.InstallReferrerClient;
import com.android.installreferrer.api.InstallReferrerStateListener;
import com.android.installreferrer.api.ReferrerDetails;

public class Tracker {
    // --- Constants ---
    public static class Event {
        public static final String PURCHASE = "purchase";
        public static final String AD_REVENUE = "ad_revenue";
        public static final String LOGIN = "login";
        public static final String SIGN_UP = "sign_up";
        public static final String INVITE = "invite";
        public static final String APP_SESSION = "app_session";
        public static final String APP_UNINSTALL = "app_uninstall";
        public static final String CONSENT_UPDATE = "consent_update";
    }

    public interface ConversionListener {
        void onConversionDataSuccess(Map<String, String> conversionData);
        void onConversionDataFail(String error);
    }

    private static final String TAG = "Trackyfly";
    private static String sdkKey;
    private static String deviceId;
    private static String appPackage;
    private static String externalUserId;
    private static boolean debugMode = false;
    private static String backendUrl = "https://trackyfly.satyainfotechnetworks.com/api";
    private static ConversionListener conversionListener;

    public static void init(Context context, String key) {
        init(context, key, null);
    }

    public static void init(Context context, String key, ConversionListener listener) {
        sdkKey = key;
        conversionListener = listener;
        appPackage = context.getPackageName();
        deviceId = android.provider.Settings.Secure.getString(context.getContentResolver(),
                android.provider.Settings.Secure.ANDROID_ID);
        if (debugMode) Log.d(TAG, "Initialized for " + appPackage);
    }

    public static void start(Context context) {
        // Triggers the install/open check
        trackInstall(context);
    }

    public static void setDebug(boolean enabled) {
        debugMode = enabled;
    }

    public static void setBackendUrl(String url) {
        backendUrl = url;
    }

    public static void setUserId(String userId) {
        externalUserId = userId;
    }

    public static void trackPurchase(double amount, String currency) {
        trackEvent(Event.PURCHASE, amount);
    }

    public static void trackAdRevenue(double amount, String source) {
        // Track ad revenue from AdMob, Unity, etc.
        trackEvent(Event.AD_REVENUE, amount);
        if (debugMode) Log.d(TAG, "Ad Revenue Tracked: " + amount + " from " + source);
    }

    public static void setUninstallToken(String fcmToken) {
        // Associating FCM token for uninstall tracking
        trackEvent(Event.APP_UNINSTALL, 0.0);
    }

    public static void setConsent(boolean hasConsent) {
        // DMA / GDPR Compliance
        trackEvent(Event.CONSENT_UPDATE, hasConsent ? 1.0 : 0.0);
    }

    public static void trackSession(long durationSeconds) {
        trackEvent(Event.APP_SESSION, (double) durationSeconds);
    }

    public static void trackEvent(final String eventName) {
        trackEvent(eventName, 0.0);
    }

    public static void trackEvent(final String eventName, final double eventValue) {
        new Thread(() -> {
            try {
                URL url = new URL(backendUrl + "/event");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                JSONObject json = new JSONObject();
                json.put("sdkKey", sdkKey);
                json.put("eventName", eventName);
                json.put("eventValue", eventValue);
                json.put("deviceId", deviceId);
                json.put("eventTimestamp", System.currentTimeMillis());
                if (externalUserId != null) json.put("userId", externalUserId);

                OutputStream os = conn.getOutputStream();
                os.write(json.toString().getBytes());
                os.flush();
                os.close();

                int code = conn.getResponseCode();
                if (debugMode) Log.d(TAG, "Event Sent: " + eventName + " Resp: " + code);
                conn.disconnect();
            } catch (Exception e) {
                if (debugMode) Log.e(TAG, "Event Error: " + e.getMessage());
            }
        }).start();
    }

    public static void trackInstall(final Context context) {
        final InstallReferrerClient referrerClient = InstallReferrerClient.newBuilder(context).build();
        referrerClient.startConnection(new InstallReferrerStateListener() {
            @Override
            public void onInstallReferrerSetupFinished(int responseCode) {
                if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                    try {
                        ReferrerDetails response = referrerClient.getInstallReferrer();
                        String referrerUrl = response.getInstallReferrer();
                        sendInstallData(referrerUrl);
                        referrerClient.endConnection();
                    } catch (Exception e) {
                        if (debugMode) Log.e(TAG, "Referrer Error: " + e.getMessage());
                        sendInstallData(null);
                    }
                } else {
                    sendInstallData(null);
                }
            }

            @Override
            public void onInstallReferrerServiceDisconnected() {}
        });
    }

    private static void sendInstallData(final String referrerUrl) {
        new Thread(() -> {
            try {
                URL url = new URL(backendUrl + "/install");
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);

                JSONObject json = new JSONObject();
                json.put("sdkKey", sdkKey);
                json.put("deviceId", deviceId);
                json.put("installTimestamp", System.currentTimeMillis());

                String clickId = null;
                if (referrerUrl != null && referrerUrl.contains("click_id=")) {
                    clickId = referrerUrl.split("click_id=")[1].split("&")[0];
                    json.put("clickId", clickId);
                }

                OutputStream os = conn.getOutputStream();
                os.write(json.toString().getBytes());
                os.flush();
                os.close();

                int code = conn.getResponseCode();
                if (code == 200 && conversionListener != null) {
                    Map<String, String> data = new HashMap<>();
                    data.put("status", clickId != null ? "Non-Organic" : "Organic");
                    data.put("clickId", clickId != null ? clickId : "none");
                    conversionListener.onConversionDataSuccess(data);
                }
                
                if (debugMode) Log.d(TAG, "Install Sent. Resp: " + code);
                conn.disconnect();
            } catch (Exception e) {
                if (debugMode && conversionListener != null) conversionListener.onConversionDataFail(e.getMessage());
            }
        }).start();
    }
}
