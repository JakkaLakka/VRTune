package com.vrtune.optimizer;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Finds this headset's own Wireless-debugging ports via mDNS, the same way Android Studio does.
 * "_adb-tls-connect._tcp" = the debugging port, "_adb-tls-pairing._tcp" = the pairing port
 * (only advertised while the "Pair with pairing code" dialog is open).
 */
final class AdbDiscovery {
    static final String CONNECT = "_adb-tls-connect._tcp";
    static final String PAIRING = "_adb-tls-pairing._tcp";

    /** Blocks up to timeoutMs and returns the port advertised by this device, or -1. Do not call on the main thread. */
    static int findPort(Context ctx, String type, long timeoutMs) {
        NsdManager nsd = (NsdManager) ctx.getApplicationContext().getSystemService(Context.NSD_SERVICE);
        if (nsd == null) return -1;
        Set<String> myAddrs = localAddresses();
        AtomicInteger found = new AtomicInteger(-1);
        CountDownLatch done = new CountDownLatch(1);

        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            @Override public void onStartDiscoveryFailed(String t, int e) { done.countDown(); }
            @Override public void onStopDiscoveryFailed(String t, int e) { }
            @Override public void onDiscoveryStarted(String t) { }
            @Override public void onDiscoveryStopped(String t) { }
            @Override public void onServiceLost(NsdServiceInfo s) { }

            @Override
            public void onServiceFound(NsdServiceInfo service) {
                try {
                    nsd.resolveService(service, new NsdManager.ResolveListener() {
                        @Override public void onResolveFailed(NsdServiceInfo s, int e) { }

                        @Override
                        public void onServiceResolved(NsdServiceInfo s) {
                            InetAddress host = s.getHost();
                            boolean mine = host == null || host.isLoopbackAddress()
                                    || myAddrs.contains(host.getHostAddress());
                            if (mine && s.getPort() > 0 && found.compareAndSet(-1, s.getPort())) done.countDown();
                        }
                    });
                } catch (Exception ignored) {
                    // resolveService can throw if another resolve is in flight; the next found event retries
                }
            }
        };

        try {
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener);
            done.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        } finally {
            try {
                nsd.stopServiceDiscovery(listener);
            } catch (Exception ignored) {
            }
        }
        return found.get();
    }

    static Set<String> localAddresses() {
        Set<String> s = new HashSet<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces()))
                for (InetAddress a : Collections.list(ni.getInetAddresses())) s.add(a.getHostAddress());
        } catch (Exception ignored) {
        }
        return s;
    }
}
