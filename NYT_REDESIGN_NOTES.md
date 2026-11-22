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
