# Glass Home – Apple TV style launcher (Casper 55UGA610, Android 11)

Một launcher nhỏ, vẽ bằng một custom View (không RecyclerView, không thư viện ngoài):
nền đen, dock 4 app, lưới 4 cột cuộn lên khi bấm Xuống, một "kính" highlight di chuyển
kiểu lò xo, đồng hồ + lời chào, 3 nút Inputs / Wi-Fi / Settings. Không blur, không video,
không polling mạng; chỉ vẽ lại khi highlight đang chuyển động hoặc khi sang phút mới.

## 1. Font Outfit (tuỳ chọn)
Tải Outfit (giấy phép OFL) từ Google Fonts, đặt vào `app/src/main/assets/fonts/`:
`Outfit-Light.ttf` và `Outfit-Regular.ttf`. Không có thì app dùng font hệ thống.

## 2. Build
- Cách dễ: mở thư mục này bằng **Android Studio** (cần JDK 17), đợi Sync xong.
- Hoặc cài Gradle, chạy một lần `gradle wrapper --gradle-version 8.7` trong thư mục này.

## 3. Cài vào TV (TV đã `adb connect`)
Chạy `build-install.bat`. Nó build, cài, rồi đặt Glass Home làm màn hình chính.
Launcher gốc **chưa bị tắt**. Chỉ tắt `com.google.android.tvlauncher` sau khi mọi thứ chạy tốt,
và **không bao giờ tắt** `com.realtek.keyintercept` và `com.android.tv.settings` (launcher dự phòng).

## 4. Đổi dock
`set-dock.bat pkg1,pkg2,pkg3,pkg4` (tối đa 4, đúng thứ tự). Lấy tên gói bằng `adb shell pm list packages`.

## 5. Hình nền
Mặc định đen tuyền. Muốn ảnh: đặt `wallpaper.jpg` vào `app/src/main/assets/`, build lại (ảnh được làm tối).

## 6. Quay về launcher gốc
`restore-launcher.bat`. Gỡ hẳn: `adb uninstall com.local.glasshome`.

## 7. Phím điều khiển
Lên/Xuống đi đúng một hàng; Lên từ dock lên thanh trên cùng; hai đầu thanh trên không rơi xuống ô.
Back từ lưới/thanh trên về dock. Home luôn về dock.

## 8. Khi xong việc
Tắt USB debugging và Network debugging trên TV, thu hồi quyền gỡ lỗi USB, khởi động lại,
kiểm tra `nc -vz <ip-tv> 5555` (hoặc `Test-NetConnection <ip-tv> -Port 5555` trên Windows) phải thất bại.
