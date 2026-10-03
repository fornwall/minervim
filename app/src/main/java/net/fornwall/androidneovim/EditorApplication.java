package net.fornwall.androidneovim;

import android.app.Application;

public final class EditorApplication extends Application {
    private EditorSession session;

    @Override
    public void onCreate() {
        super.onCreate();
        session = new EditorSession(this);
    }

    EditorSession session() {
        return session;
    }
}
