package io.github.remapd;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.SystemClock;
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
 * (mDNS) as an "_adb-tls-connect._tcp" service named "adb-<serial>-<id>",
 * like adb on a computer finds it. Other devices announce theirs too, so only
 * services on one of this TV's own addresses count, and the name that worked
 * last time is looked up first.
 */
final class PortFinder implements AutoCloseable {
    private static final String TAG = "remapd";
    private static final String SERVICE_TYPE = "_adb-tls-connect._tcp";
    private static final long RESOLVE_TIMEOUT_MS = 5000;

    /** A wireless debugging service on this TV. */
    static final class Found {
        final String name;
        final int port;

        Found(String name, int port) {
            this.name = name;
            this.port = port;
        }
    }

    private final Context context;
    private final NsdManager nsd;
    private final String preferredName;
    private final LinkedBlockingQueue<Found> found = new LinkedBlockingQueue<>();

    // Guarded by this. NsdManager resolves one service at a time, so they're queued.
    private final ArrayDeque<NsdServiceInfo> toResolve = new ArrayDeque<>();
    private NsdManager resolver;
    private NsdManager.ResolveListener resolving;   // the current one, or null
    private long resolveStarted;
    private boolean discovering;
    private boolean closed;
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
                if (info.getServiceName().equals(preferredName)) {
                    toResolve.addFirst(info);
                } else {
                    toResolve.addLast(info);
                }
                resolveNext();
            }
        }

        @Override
        public void onServiceLost(NsdServiceInfo info) {
        }
    };

    /** @param preferredName the service that was this TV's last time, or null */
    PortFinder(Context context, String preferredName) {
        this.context = context;
        this.nsd = context.getSystemService(NsdManager.class);
        this.resolver = nsd;
        this.preferredName = preferredName;
    }

    void start() {
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery);
    }

    /** The next service found on this TV, or null if none turned up in time. */
    Found await(long timeoutMs) throws InterruptedException {
        Found f = found.poll(timeoutMs, TimeUnit.MILLISECONDS);
        synchronized (this) {
            giveUpOnSlowResolve();
        }
        return f;
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

    /**
     * A service that doesn't answer (say, a phone that just left the network)
     * would hold up the rest of the queue: skip it after a while. Before
     * Android 14 a resolve can't be cancelled, and the NsdManager can't start
     * another until it ends, so the rest go through a new NsdManager.
     */
    private void giveUpOnSlowResolve() {
        if (resolving == null || SystemClock.elapsedRealtime() - resolveStarted < RESOLVE_TIMEOUT_MS) {
            return;
        }
        Log.w(TAG, "an mDNS lookup is taking too long; skipping it");
        NsdManager.ResolveListener slow = resolving;
        resolving = null;
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                resolver.stopServiceResolution(slow);
            } catch (IllegalArgumentException ignored) {
            }
        } else {
            resolver = context.createConfigurationContext(context.getResources().getConfiguration())
                    .getSystemService(NsdManager.class);
        }
        resolveNext();
    }

    @SuppressWarnings("deprecation")   // resolveService still works, and its replacement needs Android 14
    private void resolveNext() {
        if (resolving != null || closed || toResolve.isEmpty()) {
            return;
        }
        NsdManager.ResolveListener listener = new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                Log.w(TAG, "couldn't look up " + info.getServiceName() + ": " + errorCode);
                done(this);
            }

            @Override
            public void onServiceResolved(NsdServiceInfo info) {
                InetAddress host = info.getHost();
                if (host != null && isLocal(host)) {
                    Log.i(TAG, "wireless debugging: " + info.getServiceName() + " on port " + info.getPort());
                    found.add(new Found(info.getServiceName(), info.getPort()));
                }
                done(this);
            }

            private void done(NsdManager.ResolveListener self) {
                synchronized (PortFinder.this) {
                    if (resolving == self) {   // not one given up on
                        resolving = null;
                        resolveNext();
                    }
                }
            }
        };
        resolving = listener;
        resolveStarted = SystemClock.elapsedRealtime();
        try {
            resolver.resolveService(toResolve.poll(), listener);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "couldn't start an mDNS lookup", e);
            resolving = null;
        }
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
