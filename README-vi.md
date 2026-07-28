# Launch-On-Boot

Launch-On-Boot mở ứng dụng Android TV đã chọn sau khi thiết bị khởi động. Ứng dụng còn có phần **Hành động nâng cao** để chạy một chuỗi thao tác sau khi ứng dụng đích đã mở.

Tài liệu tiếng Anh: [README.md](README.md).

## Chức năng chính

- Chọn ứng dụng Android TV đã cài để tự mở sau khi khởi động.
- Có thể mở lại sau khi thiết bị thức dậy từ sleep hoặc screensaver.
- Cấu hình thời gian chờ trước và sau khi mở ứng dụng.
- Chạy chuỗi Hành động nâng cao sau khi ứng dụng đích mở.
- Giao diện dùng được bằng remote Android TV; không yêu cầu kéo-thả hoặc cảm ứng.

## Hành động nâng cao

Tính năng này phù hợp với hai mục đích chính:

1. **Hỗ trợ người lớn tuổi xem TV**: tự mở ứng dụng TV, chờ giao diện tải, nhập số kênh và bấm OK.
2. **Dự án kiosk hoặc tự động hóa**: tự mở ứng dụng đã chọn rồi thực hiện một chuỗi thao tác điều hướng, chờ hoặc nhập văn bản theo thứ tự cố định.

Màn hình **Cài đặt nâng cao** chỉ chứa cấu hình chung như trigger, thời gian chờ và kết nối ADB. Chọn nút **Thiết lập chuỗi hành động** để mở màn hình chỉnh sửa riêng.

Trình chỉnh sửa hỗ trợ:

- Phím điều hướng: Lên, Xuống, Trái, Phải, OK.
- Phím số: 0 đến 9.
- Phím điều khiển: Quay lại, Home, Menu, Enter.
- Hành động `WAIT` để chờ.
- Hành động `TEXT` để gửi văn bản.
- Thêm, sửa, xóa, di chuyển lên/xuống, xóa toàn bộ, lưu và chạy thử chuỗi.

Ví dụ tự chọn kênh 1:

```text
CHỜ 1000 ms
PHÍM SỐ 1 — chờ sau 300 ms — lặp 1 lần
```

Chuỗi được lưu nội bộ bằng JSON trong SharedPreferences riêng của ứng dụng. Người dùng thông thường không cần xem hoặc nhập JSON.

## ADB và gửi văn bản

Việc gửi phím hệ thống dùng ADB client tích hợp. Cần bật ADB debugging trên TV và có thể phải xác nhận hộp thoại cấp quyền RSA ở lần kết nối đầu tiên.

`TEXT` chỉ hoạt động khi con trỏ đang nằm trong ô nhập của ứng dụng đích. Backend hiện dùng lệnh `input text`, chỉ hỗ trợ ASCII in được, trừ ký tự `%`. Tiếng Việt có dấu, emoji và xuống dòng vẫn được giữ nguyên trong chuỗi đã lưu nhưng sẽ báo không hỗ trợ khi chạy, thay vì bị thay đổi âm thầm.

Chỉ bật ADB trên mạng đáng tin cậy. Mặc định ứng dụng kết nối tới `127.0.0.1:5555`.

## Lưu ý Android 14

Một số firmware Android 14 chặn ứng dụng mở ứng dụng khác sau boot nếu không có ngoại lệ cho background launch. Launch-On-Boot có thể hướng dẫn cấp quyền **Cho phép hiển thị trên ứng dụng khác**. Đây là quyền đặc biệt do người dùng và firmware quản lý; một số thiết bị có thể tự tắt quyền sau reboot và ứng dụng thông thường không thể tự bật lại.

## Build và test

Dự án dùng Gradle. JDK đi kèm Android Studio đã được kiểm tra là tương thích.

```powershell
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Khi kiểm thử trên TV thật, hãy kiểm tra boot, wake-up, mở ứng dụng đích, cấp quyền ADB, trình chỉnh sửa chuỗi và hủy chuỗi khi đang chờ.
