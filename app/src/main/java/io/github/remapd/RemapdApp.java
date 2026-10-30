package io.github.remapd;

import android.app.Application;

public class RemapdApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Starter.init(this);
    }
}
