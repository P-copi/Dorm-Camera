package com.pcopi.dormcamera;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Personal tools hub. Kept separate from the proven camera screen so new tools
 * can evolve without disturbing camera capture, video, Explorer or room folders.
 */
public final class ToolsActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));
        root.setBackgroundColor(0xFF050506);

        TextView title = new TextView(this);
        title.setText("เครื่องมือ");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(24f);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(0, 0, 0, dp(4));
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView subtitle = new TextView(this);
        subtitle.setText("เครื่องมือส่วนตัว • แยกจากระบบกล้องหลัก");
        subtitle.setTextColor(0xFFB9BBC2);
        subtitle.setTextSize(13f);
        subtitle.setPadding(0, 0, 0, dp(18));
        root.addView(subtitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        PressFeedbackButton navigator = makeButton("🧭  นำทาง / GPS จากภาพ");
        navigator.setContentDescription("เลือกภาพ อ่านหรือเพิ่ม GPS แล้วเปิดแผนที่หรือนำทาง");
        navigator.setOnClickListener(v -> startActivity(new Intent(this, PhotoNavigatorFixedActivity.class)));
        root.addView(navigator, buttonParams());

        TextView detail = new TextView(this);
        detail.setText("• ภาพมี GPS: เปิดแผนที่หรือนำทางได้ทันที\n"
                + "• ภาพไม่มี GPS: ใช้ตำแหน่งปัจจุบัน ค้นหาชื่อสถานที่ หรือป้อนพิกัดเอง\n"
                + "• V0.2.2 สร้างสำเนาในไฟล์ชั่วคราวและตรวจ GPS ก่อนบันทึกเข้าคลังภาพ");
        detail.setTextColor(0xFFB9BBC2);
        detail.setTextSize(13f);
        detail.setLineSpacing(0f, 1.2f);
        detail.setPadding(dp(8), dp(6), dp(8), dp(10));
        root.addView(detail, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView note = new TextView(this);
        note.setText("V0.2.2 • สำเนาที่เพิ่ม GPS จะบันทึกใน Pictures/DormCamera");
        note.setTextColor(0xFF8F929A);
        note.setTextSize(12f);
        note.setPadding(dp(4), dp(6), dp(4), 0);
        root.addView(note, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(new TextView(this), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        PressFeedbackButton back = makeButton("←  กลับกล้อง");
        back.setOnClickListener(v -> finish());
        root.addView(back, buttonParams());

        setContentView(root);
    }

    private PressFeedbackButton makeButton(String text) {
        PressFeedbackButton button = new PressFeedbackButton(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(0xFFFFFFFF);
        button.setTextSize(16f);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setPadding(dp(18), 0, dp(18), 0);
        button.setBackgroundResource(R.drawable.bg_button_dark);
        button.setBackgroundTintList(null);
        button.setStateListAnimator(null);
        return button;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58));
        params.setMargins(0, 0, 0, dp(10));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
