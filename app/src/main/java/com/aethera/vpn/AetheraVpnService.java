package com.aethera.vpn;

import android.content.Intent;
import android.net.VpnService;
import android.os.IBinder;
import android.util.Log;

public class AetheraVpnService extends VpnService {

    public static final String ACTION_CONNECT =
            "com.aethera.vpn.CONNECT";

    public static final String ACTION_DISCONNECT =
            "com.aethera.vpn.DISCONNECT";

    private static final String TAG = "AetheraVPN";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        if (intent == null || intent.getAction() == null) {
            return START_NOT_STICKY;
        }

        String action = intent.getAction();

        if (ACTION_CONNECT.equals(action)) {

            /*
             * Authorized VPN backend goes here.
             *
             * A real WireGuard/OpenVPN configuration is required
             * before creating and routing an actual tunnel.
             *
             * We intentionally do NOT add a fake 0.0.0.0/0 route,
             * because that would break internet traffic without
             * a real VPN transport.
             */

            Log.i(TAG, "VPN permission ready; server config required.");

        } else if (ACTION_DISCONNECT.equals(action)) {

            Log.i(TAG, "VPN disconnect requested.");
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    @Override
    public void onRevoke() {
        stopSelf();
        super.onRevoke();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return super.onBind(intent);
    }
}
