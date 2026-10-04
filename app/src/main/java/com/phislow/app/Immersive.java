package com.phislow.app;

import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

/**
 * Hides the system bars so the window really is the whole screen.
 *
 * {@code setSystemUiVisibility} and the {@code SYSTEM_UI_FLAG_*} family it takes are ignored from
 * Android 11 (API 30) on, so the status bar clock stayed drawn over the top of the song list and
 * over the running chart. API 30+ needs an insets controller instead; the legacy flags remain for
 * the API 26-29 range this app still supports.
 */
final class Immersive {
    private Immersive() {}

    private static final int LEGACY_FLAGS = View.SYSTEM_UI_FLAG_FULLSCREEN
        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;

    /**
     * Applies the hidden-bar state. Safe to call from {@code onCreate} and again from
     * {@code onWindowFocusChanged}, because the bars come back whenever the window regains focus.
     */
    static void apply(Window window) {
        if (window == null) return;
        // PhoneWindow.getInsetsController() dereferences its decor view, so asking for the
        // controller before setContentView() builds one throws inside the framework. The decor is
        // also not attached yet at that point, which leaves the controller null anyway - so skip
        // and let onWindowFocusChanged apply the state once the window is real.
        View decor = window.peekDecorView();
        if (decor == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller == null) return;
            controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            return;
        }
        decor.setSystemUiVisibility(LEGACY_FLAGS);
    }
}
