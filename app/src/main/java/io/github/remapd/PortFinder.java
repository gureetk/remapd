package io.github.remapd;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Finds the port of this TV's wireless debugging. adbd picks a new port each
 * time wireless debugging starts and announces it on the local network
 * (mDNS) as an "_adb-tls-connect._tcp" service, like adb on a computer finds
 * it. Other devices on the network announce theirs too, so only a service on
 * one of this TV's own addresses counts.
 */
final class PortFinder implements AutoCloseable {
    private static final String TAG = "remapd";
    private static final String SERVICE_TYPE = "_adb-tls-connect._tcp";

    private final NsdManager nsd;
    private final LinkedBlockingQueue<Integer> ports = new LinkedBlockingQueue<>();
    private final ArrayDeque<NsdServiceInfo> toResolve = new ArrayDeque<>();   // guarded by this
    private boolean resolving;   // guarded by this; NsdManager resolves one service at a time
    private boolean discovering;   // guarded by this
    private boolean closed;   // guarded by this
    private volatile boolean failed;

    private final NsdManager.DiscoveryListener discovery = new NsdManager.DiscoveryListener() {
        @Override
        public void onDiscoveryStarted(String serviceType) {
            synchronized (PortFinder.this) {
                discovering = true;
                if (closed) {
                    stopDiscovery();
                }
            }
        }

        @Override
        public void onStartDiscoveryFailed(String serviceType, int errorCode) {
            Log.w(TAG, "mDNS discovery failed to start: " + errorCode);
            failed = true;
        }

        @Override
        public void onDiscoveryStopped(String serviceType) {
        }

        @Override
        public void onStopDiscoveryFailed(String serviceType, int errorCode) {
        }

        @Override
        public void onServiceFound(NsdServiceInfo info) {
            synchronized (PortFinder.this) {
                toResolve.add(info);
                resolveNext();
            }
        }

        @Override
        public void onServiceLost(NsdServiceInfo info) {
        }
    };

    PortFinder(Context context) {
        nsd = context.getSystemService(NsdManager.class);
    }

    void start() {
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
    }

    /** This TV's wireless debugging port, or -1 if none turned up in time. */
    int await(long timeoutMs) throws InterruptedException {
        Integer port = ports.poll(timeoutMs, TimeUnit.MILLISECONDS);
        return port == null ? -1 : port;
    }

    /** Discovery couldn't start, so nothing will turn up. */
    boolean failed() {
        return failed;
    }

    @Override
    public synchronized void close() {
        closed = true;
        toResolve.clear();
        if (discovering) {
            stopDiscovery();
        }
    }

    private void stopDiscovery() {
        discovering = false;
        try {
            nsd.stopServiceDiscovery(discovery);
        } catch (IllegalArgumentException ignored) {   // already stopped
        }
    }

    @SuppressWarnings("deprecation")   // resolveService still works, and its replacement needs Android 14
    private void resolveNext() {
        if (resolving || closed || toResolve.isEmpty()) {
            return;
        }
        resolving = true;
        nsd.resolveService(toResolve.poll(), new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                Log.w(TAG, "couldn't resolve " + info.getServiceName() + ": " + errorCode);
                done();
            }

            @Override
            public void onServiceResolved(NsdServiceInfo info) {
                InetAddress host = info.getHost();
                if (host != null && isLocal(host)) {
                    Log.i(TAG, "wireless debugging is on port " + info.getPort());
                    ports.add(info.getPort());
                }
                done();
            }

            private void done() {
                synchronized (PortFinder.this) {
                    resolving = false;
                    resolveNext();
                }
            }
        });
    }

    /** Whether the address is one of this device's own. */
    static boolean isLocal(InetAddress address) {
        String wanted = strip(address.getHostAddress());
        for (String mine : localAddresses()) {
            if (mine.equalsIgnoreCase(wanted)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> localAddresses() {
        Set<String> out = new HashSet<>();
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(nif.getInetAddresses())) {
                    out.add(strip(a.getHostAddress()));
                }
            }
        } catch (SocketException | NullPointerException e) {
            Log.w(TAG, "can't list network interfaces", e);
        }
        return out;
    }

    /** Drops an IPv6 scope ("fe80::1%wlan0"). */
    private static String strip(String address) {
        int percent = address == null ? -1 : address.indexOf('%');
        return percent >= 0 ? address.substring(0, percent) : String.valueOf(address);
    }
}
