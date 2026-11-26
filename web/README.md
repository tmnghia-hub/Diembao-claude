# Trang web đọc tin (GitHub Pages)

GitHub Actions tải tin từ `feeds.opml` mỗi 3 giờ, dịch tiêu đề và tóm tắt bằng Gemini, rồi đăng
một trang HTML tĩnh kiểu báo giấy. Chỉ tiêu đề và tóm tắt ngắn, không đăng toàn văn bài.

## Cài đặt (một lần)
1. Settings -> Pages -> Source: **GitHub Actions**.
2. Settings -> Secrets and variables -> Actions -> **Secrets**:
   - `GEMINI_API_KEY`: khóa Gemini (tạo ở aistudio.google.com/apikey)
   - `SITE_PASSWORD`: mật khẩu mở trang, dài ít nhất 10 ký tự
3. Actions -> **Build web reader** -> Run workflow.

## Tùy chọn (Settings -> Secrets and variables -> Actions -> Variables)
| Variable | Ý nghĩa | Mặc định |
|---|---|---|
| `SITE_TITLE` | Tên trên masthead | Điểm Báo |
| `TARGET_LANGUAGE` | Ngôn ngữ dịch | Vietnamese |
| `TRANSLATION_STYLE` | Văn phong dịch (mô tả bằng tiếng Anh) | văn phong tạp chí khoa học |
| `GEMINI_MODEL` | Model Gemini | gemini-flash-latest |
| `REQUIRE_PASSWORD` | `false` = đăng công khai không mật khẩu | true |

Đổi nguồn tin: thay `web/feeds.opml` rồi push.
