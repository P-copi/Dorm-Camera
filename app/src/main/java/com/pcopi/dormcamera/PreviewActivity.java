package com.pcopi.dormcamera;

import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.appcompat.app.AppCompatActivity;

public class PreviewActivity extends AppCompatActivity {
    public static final String EXTRA_URI = "uri";
    public static final String EXTRA_MIME = "mime";
    public static final String EXTRA_NAME = "name";

    private VideoView videoView;
    private Button playPauseButton;
    private TextView previewStatus;
    private boolean videoPrepared;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_preview);

        TextView title = findViewById(R.id.previewTitle);
        ZoomImageView imageView = findViewById(R.id.previewImage);
        videoView = findViewById(R.id.previewVideo);
        previewStatus = findViewById(R.id.previewStatus);
        playPauseButton = findViewById(R.id.previewPlayPauseButton);
        Button closeButton = findViewById(R.id.previewCloseButton);

        String uriText = getIntent().getStringExtra(EXTRA_URI);
        String mime = getIntent().getStringExtra(EXTRA_MIME);
        String name = getIntent().getStringExtra(EXTRA_NAME);

        title.setText(name != null ? name : "Preview");
        closeButton.setOnClickListener(v -> finish());

        if (uriText == null) {
            Toast.makeText(this, "ไม่พบไฟล์สำหรับ Preview", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        Uri uri = Uri.parse(uriText);
        boolean video = mime != null && mime.startsWith("video/");

        if (video) {
            imageView.setVisibility(View.GONE);
            videoView.setVisibility(View.VISIBLE);
            playPauseButton.setVisibility(View.VISIBLE);
            previewStatus.setText("กำลังเตรียมวิดีโอ...");

            MediaController controller = new MediaController(this);
            controller.setAnchorView(videoView);
            videoView.setMediaController(controller);
            videoView.setVideoURI(uri);

            videoView.setOnPreparedListener(mp -> {
                videoPrepared = true;
                previewStatus.setText("วิดีโอพร้อมเล่น • แตะปุ่มเพื่อเล่น/หยุด");
                playPauseButton.setText("หยุดชั่วคราว");
                videoView.start();
            });

            videoView.setOnCompletionListener(mp -> {
                playPauseButton.setText("เล่นอีกครั้ง");
                previewStatus.setText("เล่นจบแล้ว");
            });

            videoView.setOnErrorListener((mp, what, extra) -> {
                videoPrepared = false;
                playPauseButton.setText("ลองเล่นอีกครั้ง");
                previewStatus.setText("เปิดวิดีโอไม่สำเร็จใน Preview");
                Toast.makeText(
                        this,
                        "เปิดวิดีโอไม่สำเร็จ (" + what + "/" + extra + ")",
                        Toast.LENGTH_LONG
                ).show();
                return true;
            });

            playPauseButton.setOnClickListener(v -> {
                if (!videoPrepared) {
                    previewStatus.setText("กำลังลองเปิดวิดีโออีกครั้ง...");
                    videoView.setVideoURI(uri);
                    videoView.start();
                    return;
                }
                if (videoView.isPlaying()) {
                    videoView.pause();
                    playPauseButton.setText("เล่นต่อ");
                } else {
                    videoView.start();
                    playPauseButton.setText("หยุดชั่วคราว");
                }
            });
        } else {
            videoView.setVisibility(View.GONE);
            playPauseButton.setVisibility(View.GONE);
            imageView.setVisibility(View.VISIBLE);
            previewStatus.setText("บีบนิ้วเพื่อขยาย • ลากเพื่อเลื่อน • แตะสองครั้งเพื่อขยาย/คืนค่า");
            imageView.setImageURI(uri);
            imageView.post(() -> {
                if (imageView.getDrawable() == null) {
                    previewStatus.setText("เปิดภาพไม่สำเร็จ");
                    Toast.makeText(this, "เปิดภาพไม่สำเร็จ", Toast.LENGTH_LONG).show();
                } else {
                    imageView.resetZoom();
                }
            });
        }
    }

    @Override
    protected void onPause() {
        if (videoView != null && videoView.isPlaying()) {
            videoView.pause();
            if (playPauseButton != null) playPauseButton.setText("เล่นต่อ");
        }
        super.onPause();
    }
}
