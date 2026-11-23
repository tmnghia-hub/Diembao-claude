# Thay đổi: giao diện kiểu NYT + dịch AI (API trước, offline sau)

## Dịch thuật
Thứ tự ưu tiên (`resolveEffectiveTranslationSettings` trong `openai/OpenAIApi.kt`):
1. DeepL (mục "Translation API" trong Cài đặt)
2. Khóa AI tương thích OpenAI (mục tính năng AI/tóm tắt) - dịch bằng chat model
3. Dịch ngoại tuyến (Bergamot / hệ thống)

Nếu API lỗi (mất mạng, hết quota, sai key) -> tự chuyển sang ngoại tuyến, không báo lỗi.
Bài viết đã dịch hiện dòng "Dịch bởi ..." (DeepL / AI (model) / dịch ngoại tuyến).

File chính: `openai/OpenAIApi.kt`, `model/TranslationManager.kt`, `archmodel/SettingsStore.kt`,
`archmodel/Repository.kt`, `ui/compose/components/TranslatedByNote.kt`.

## Giao diện
- `ui/compose/theme/*`: bảng màu giấy/mực, góc vuông, chữ serif cho tiêu đề và nội dung bài.
- `ui/compose/feed/NewsMasthead.kt`: masthead (tên app, đường kẻ đôi, ngày).
- `ui/compose/feed/FlatNewsCard.kt`: item phẳng ngăn cách bằng đường kẻ mảnh.
- Kiểu "Thẻ" (CARD): bài đầu là tin chính cỡ lớn, các bài sau là hàng gọn có ảnh nhỏ bên phải.
- Màu động (Material You) mặc định tắt để thấy bảng màu NYT (bật lại trong Cài đặt).

## Chất lượng dịch bằng AI (cập nhật)
- Prompt dịch kiểu biên tập viên báo chí, có hướng dẫn riêng cho tiếng Việt.
- Bài dài được chia đoạn (~6000 ký tự) để model không bị cắt cụt.
- Bản dịch dự phòng ngoại tuyến không bị lưu cache như bản dịch của API, nên lần mở sau sẽ thử lại API.

## Dịch ngoại tuyến bằng Google ML Kit (cập nhật)
Thứ tự dịch ngoại tuyến: Google ML Kit -> mô hình dịch của hệ thống Android -> Bergamot.
Cần Google Play services; lần đầu dùng mỗi ngôn ngữ app tự tải mô hình (~30 MB) nên cần mạng.
File: `localtranslation/LocalTranslator.kt`, dependency `com.google.mlkit:translate:17.0.3`.

## Cập nhật OTA không mất dữ liệu
- Workflow `Build APK` tự chạy khi push lên `main` và đăng APK vào Releases với link cố định:
  `https://github.com/<tài-khoản>/<repo>/releases/latest/download/diembao-latest.apk`
- APK debug luôn ký bằng `shareddebug.keystore` trong repo -> cài đè giữ nguyên dữ liệu.
- Không gỡ app cũ trước khi cài bản mới.

## Cập nhật ngay trong app
Cài đặt -> Cập nhật ứng dụng -> Kiểm tra cập nhật. App đọc release `latest` trên GitHub, so commit với
commit của bản đang chạy (BuildConfig.BUILD_COMMIT, do workflow truyền vào), tải APK rồi mở trình cài đặt
của Android. Cài đè cùng chữ ký nên giữ nguyên dữ liệu. Chỉ hoạt động với repo Public.
File: `model/AppUpdater.kt`, `ui/compose/settings/AppUpdateSection.kt`, `res/xml/update_file_paths.xml`.
