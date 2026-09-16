package com.wnmsolutions.granth;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.VideoView;

import androidx.appcompat.app.AppCompatActivity;

public class SplashActivity extends AppCompatActivity {

    private boolean navigated = false;
    private final Handler safetyHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        VideoView videoView = findViewById(R.id.splashVideo);
        Uri videoUri = Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.splash);
        videoView.setVideoURI(videoUri);

        videoView.setOnCompletionListener(mp -> goToMain());
        videoView.setOnErrorListener((mp, what, extra) -> {
            goToMain();
            return true;
        });

        videoView.start();

        // Safety net in case the video fails to fire a completion/error callback.
        safetyHandler.postDelayed(this::goToMain, 15000);
    }

    private void goToMain() {
        if (navigated) return;
        navigated = true;
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
