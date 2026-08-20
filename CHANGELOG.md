# Changelog - AutoMine

Tất cả các thay đổi quan trọng của mod sẽ được ghi lại ở đây.

---

## [v0.1.0] - 2024

### 🎉 Tính năng mới

#### ⛏️ Xẻng Vàng (Golden Shovel Marking)
- **THÊM:** Chuột TRÁI vào block = `/sel 1` (đặt điểm 1)
- **THÊM:** Chuột PHẢI vào block = `/sel 2` (đặt điểm 2)
- **THÊM:** Config `goldenShovelMark` để bật/tắt tính năng
- **THÊM:** Hiển thị thông báo giống y hệt lệnh `/sel`
- **THÊM:** Hiển thị đầy đủ: tọa độ, kích thước vùng, số block

#### 🍎 Tự động Ăn (Auto-Eat)
- **THÊM:** Tự động ăn táo vàng/enchanted golden apple khi đói
- **THÊM:** Config `autoEat` (true/false) để bật/tắt
- **THÊM:** Config `autoEatThreshold` (1-10) để điều chỉnh ngưỡng ăn
- **THÊM:** Tự động tạm dừng đào khi đang ăn
- **THÊM:** Tự động chuyển lại slot cũ sau khi ăn xong
- **THÊM:** Tìm táo vàng trong hotbar (slot 0-8)

#### 🏗️ Vét Sạch Từng Tầng
- **SỬA:** Đổi logic đào từ "đào hết tất cả tầng → vét sót toàn bộ"
- **THÀNH:** "Đào tầng 1 → Vét tầng 1 → Đào tầng 2 → Vét tầng 2..."
- **THÊM:** Thông báo rõ ràng tiến trình từng tầng
- **CẢI THIỆN:** Dễ theo dõi, không để sót block ở tầng trên

#### 👁️ Không Cúi Đầu
- **SỬA:** Tối ưu thuật toán aim để không cúi đầu liên tục
- **SỬA:** Luôn thử aim trước khi reposition
- **SỬA:** Không dừng lại sau mỗi lần đào xong
- **CẢI THIỆN:** Chuyển mượt từ block này sang block khác

#### 🎨 Menu GUI
- **THÊM:** Toggle "Tự động ăn" trong menu
- **THÊM:** Stepper "Ăn khi mất (thanh)" để điều chỉnh ngưỡng
- **THÊM:** Toggle "Xẻng vàng mark" trong menu
- **CẢI THIỆN:** Sắp xếp lại layout menu cho rõ ràng hơn
- **CẢI THIỆN:** Thanh tiến trình di chuyển xuống để không che nút

#### 🔧 Commands
- **THÊM:** `/am test` - Kiểm tra config xẻng vàng
- **THÊM:** `/am set goldenShovelMark true/false`
- **THÊM:** `/am set autoEat true/false`
- **THÊM:** `/am set autoEatThreshold <1-10>`

### 🐛 Sửa lỗi

#### Xẻng Vàng
- **SỬA:** UseBlockCallback không trigger - thêm check player == mc.player
- **SỬA:** Thêm AttackBlockCallback cho chuột trái
- **SỬA:** Return ActionResult.SUCCESS để cancel break/place block

#### Auto-Eat
- **SỬA:** Dùng getSelectedSlot() và setSelectedSlot() thay vì truy cập trực tiếp
- **SỬA:** Kiểm tra config trước khi chạy
- **SỬA:** Tích hợp với QuarryEngine.pause()/resume()

#### Đào Không Cúi
- **SỬA:** Loại bỏ POST_BREAK delay không cần thiết
- **SỬA:** Không reposition ngay sau khi đào xong
- **SỬA:** Chỉ cúi khi thực sự cần đặt block

#### Vét Sạch Tầng
- **SỬA:** tickPhases() logic để vét sau mỗi tầng
- **SỬA:** tickSweep() để kiểm tra layerBottom/layerTop
- **SỬA:** Chuyển tầng đúng cách sau khi vét xong

### 📚 Tài liệu

- **THÊM:** `README.md` - Hướng dẫn tổng quan
- **THÊM:** `FEATURES.md` - Chi tiết tính năng
- **THÊM:** `TROUBLESHOOTING.md` - Khắc phục lỗi
- **THÊM:** `CHANGELOG.md` - Nhật ký thay đổi

### 🔄 Thay đổi nội bộ

- **THÊM:** `ClientCommands.setCornerAt()` - Helper method chung cho xẻng vàng và lệnh
- **SỬA:** `AutoMineConfig` - Thêm 2 field mới và xử lý trong applyRaw()
- **SỬA:** `QuarryEngine` - Refactor tickPhases() và tickSweep()
- **CẢI THIỆN:** Code comments bằng tiếng Việt để dễ hiểu

---

## [Kế hoạch tương lai]

### Có thể thêm:
- ⏱️ Hiển thị thời gian ước tính hoàn thành
- 📊 Thống kê số lượng từng loại block đã đào
- 🗺️ Chế độ strip mining, branch mining
- 🔋 Tự động sửa pickaxe bằng mending
- 📦 Tự động đổ đồ vào chest khi đầy
- 🛡️ Tự động mặc armor
- ⚔️ Tự động đánh mob
- 🌙 Tự động ngủ khi trời tối

### Đang xem xét:
- 🎯 Chế độ đào theo mỏ (mine veins)
- 🚀 Tối ưu path-finding
- 🎨 Cải thiện giao diện
- 🔊 Thêm âm thanh thông báo
- 📱 Tích hợp với Discord webhook

---

## Ghi chú phiên bản

### Format:
- **THÊM:** Tính năng mới
- **SỬA:** Bug fix hoặc cải thiện
- **XÓA:** Loại bỏ tính năng
- **CẢI THIỆN:** Cải thiện hiệu suất hoặc trải nghiệm

### Quy tắc versioning:
- Major (X.0.0): Thay đổi lớn, có thể không tương thích ngược
- Minor (0.X.0): Tính năng mới, tương thích ngược
- Patch (0.0.X): Bug fix, cải tiến nhỏ

---

**Cập nhật lần cuối:** 2024  
**Phiên bản hiện tại:** v0.1.0
