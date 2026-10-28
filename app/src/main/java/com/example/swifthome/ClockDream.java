package com.example.swifthome;

import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextClock;

import java.util.Random;

/** Offline clock screensaver. Black background, dim text, moves every minute to avoid screen burn-in. */
public class ClockDream extends DreamService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private FrameLayout root;
    private TextClock clock;

    private final Runnable shift = new Runnable() {
        @Override
        public void run() {
            int w = root.getWidth(), h = root.getHeight();
            int cw = clock.getWidth(), ch = clock.getHeight();
            if (w > cw && h > ch) {
                clock.setTranslationX(rnd.nextInt(w - cw));
                clock.setTranslationY(rnd.nextInt(h - ch));
            }
            handler.postDelayed(this, 60000);
        }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        clock = new TextClock(this);
        clock.setFormat12Hour("h:mm a");
        clock.setFormat24Hour("HH:mm");
        clock.setTextSize(96);
        clock.setTextColor(0xFFAAAAAA);
        root.addView(clock, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(root);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        handler.postDelayed(shift, 1000);
    }

    @Override
    public void onDreamingStopped() {
        handler.removeCallbacks(shift);
        super.onDreamingStopped();
    }
}
