# AutoMine

> **Tuyên bố bản quyền & Nguồn gốc:**
> - **Tác giả (Creator):** `LovOrLop` (`vuducmanh` - `vdm`)
> - **Nguồn:** Sản phẩm được leak từ **ZirVN** ([github.com/ZirVN/AutoMine](https://github.com/ZirVN/AutoMine))

---

## Các tính năng chính

### 1. Tự động đào mỏ (Quarry Engine)
- **Đào thông minh:** Tối ưu góc nhìn, hạn chế cúi đầu giật lag, tự động reposition và chuyển tầng mượt mà.
- **Vét sạch từng tầng:** Quét sạch 100% block ở tầng hiện tại trước khi hạ xuống tầng tiếp theo.
- **Né dung nham & nước:** Tự phát hiện và né tránh chất lỏng nguy hiểm trong quá trình đào.
- **Tự đặt block / leo cột:** Tự động kê block khi cần di chuyển vượt chướng ngại vật hoặc leo lên.

### 2. Đánh dấu vùng bằng Xẻng Vàng (Golden Shovel)
- **Chuột TRÁI** vào block: Đặt tọa độ **Điểm 1** (`/sel 1`).
- **Chuột PHẢI** vào block: Đặt tọa độ **Điểm 2** (`/sel 2`).
- Hiển thị trực quan khung viền (Selection Box) và kích thước khối cần đào.

### 3. Tự động ăn (Auto-Eat)
- Tự động nhận diện thanh thức ăn và ăn táo vàng (Golden Apple / Enchanted Golden Apple) khi đói.
- Có thể tuỳ chỉnh ngưỡng ăn (1-10 thanh đói).
- Tạm dừng đào khi ăn và tự chuyển lại công cụ sau khi ăn xong.

### 4. Spotify HUD & Điều khiển nhạc
- Tích hợp giao diện **Spotify HUD** trực tiếp trong Minecraft.
- Hiển thị thông tin bài hát đang phát (Tên bài, ca sĩ, album art).
- Tương thích và đồng bộ qua API của Spotify.

### 5. YouTube Screen
- Tích hợp màn hình xem/nghe YouTube ngay bên trong giao diện mod.

### 6. Staff Guard & Fluid Alert
- **Staff Guard:** Tự động cảnh báo hoặc dừng bot khi phát hiện Staff / Quản trị viên trong phạm vi.
- **Fluid Alert:** Báo động tức thì khi có dòng dung nham hoặc nước tràn tới.

---

## Các lệnh cơ bản (Commands)

| Lệnh | Chức năng |
|------|-----------|
| `/automine` hoặc `/am` | Mở menu GUI cài đặt và điều khiển |
| `/start` | Bắt đầu đào theo vùng đã chọn |
| `/stop` | Dừng đào |
| `/pause` / `/resume` | Tạm dừng / Tiếp tục quá trình đào |
| `/sel 1` | Đặt điểm bắt đầu (vị trí đang đứng) |
| `/sel 2` | Đặt điểm kết thúc (vị trí đang đứng) |
| `/sel clear` | Xoá vùng đã chọn |
| `/status` | Xem trạng thái tiến độ hiện tại |

---

## Cài đặt nhanh

1. Tải bản build mod (file `.jar`) hoặc tự build bằng lệnh `./gradlew build`.
2. Đặt file mod vào thư mục `.minecraft/mods`.
3. Yêu cầu cài đặt **Fabric Loader** và **Fabric API** phù hợp với phiên bản game.
4. Khởi động game và sử dụng lệnh `/am` để mở bảng điều khiển.

---

## Lưu ý
- Bản mod được chia sẻ phục vụ mục đích nghiên cứu, học tập và trải nghiệm cá nhân.
- Vui lòng tuân thủ quy định của các máy chủ mà bạn tham gia.
