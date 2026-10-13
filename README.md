# Dorm Camera

กล้องสำหรับผู้ดูแลหอพัก เน้นความเร็ว ความเสถียร และใช้งานง่ายบน Android 8.0 ขึ้นไป

## V0.1.0 — Camera Core

ฐานแรกของ V1:
- Native CameraX Preview เพื่อลดอาการกระพริบและให้ภาพตรงกับกล้องจริงมากที่สุด
- ถ่ายภาพแบบเน้น latency ต่ำ
- สลับกล้องหน้า/หลัง
- เก็บภาพในพื้นที่แอป `Pictures/DormCamera`
- รองรับ Android 8.0+ (minSdk 26)
- GitHub Actions สร้าง Debug APK อัตโนมัติ

## Roadmap V1

1. กล้องนิ่งหน้า/หลัง + บันทึกเร็ว
2. วิดีโอหน้า/หลัง 1080p30 พร้อม fallback 720p
3. GPS metadata สำหรับภาพ และ metadata สำหรับวิดีโอเมื่ออุปกรณ์รองรับ
4. เลือก/สร้าง/เปลี่ยนชื่อ/ล็อกโฟลเดอร์บันทึก
5. Explorer ภายในแอป: thumbnail, preview, rename, delete, multi-select, share
6. ปุ่มภาพล่าสุด + แสดงโฟลเดอร์ที่กำลังใช้งาน
7. Natural Beauty แบบเบาเป็นตัวเลือกภายหลัง โดยไม่กระทบ native preview

## หลักการ

ความเร็ว > ความเสถียร > preview ตรงจริง > หน้า/หลัง > วิดีโอ > GPS > โฟลเดอร์/Explorer > Beauty

ไม่ใส่ Image Inspector, Verify/Repair หรือ AI ตรวจคุณภาพภาพใน V1
