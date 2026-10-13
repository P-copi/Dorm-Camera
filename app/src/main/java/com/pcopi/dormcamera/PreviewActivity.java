package com.pcopi.dormcamera;

import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.appcompat.app.AppCompatActivity;

public class PreviewActivity extends AppCompatActivity {
    public static final String EXTRA_URI = "uri";
    public static final String EXTRA_MIME = "mime";
    public static final String EXTRA_NAME = "name";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_preview);

        TextView title = findViewById(R.id.previewTitle);
        ImageView imageView = findViewById(R.id.previewImage);
        VideoView videoView = findViewById(R.id.previewVideo);
        Button closeButton = findViewById(R.id.previewCloseButton);

        String uriText = getIntent().getStringExtra(EXTRA_URI);
        String mime = getIntent().getStringExtra(EXTRA_MIME);
        String name = getIntent().getStringExtra(EXTRA_NAME);

        title.setText(name != null ? name : "Preview");
        closeButton.setOnClickListener(v -> finish());

        if (uriText == null) {
            finish();
            return;
        }

        Uri uri = Uri.parse(uriText);
        boolean video = mime != null && mime.startsWith("video/");
        if (video) {
            imageView.setVisibility(View.GONE);
            videoView.setVisibility(View.VISIBLE);
            MediaController controller = new MediaController(this);
            controller.setAnchorView(videoView);
            videoView.setMediaController(controller);
            videoView.setVideoURI(uri);
            videoView.setOnPreparedListener(mp -> videoView.start());
        } else {
            videoView.setVisibility(View.GONE);
            imageView.setVisibility(View.VISIBLE);
            imageView.setImageURI(uri);
        }
    }
}
