# AutoMine - Minecraft Auto Mining Mod

Mod tự động đào vùng (quarry) cho Minecraft với nhiều tính năng thông minh.

## 🎯 Tính năng chính

### ⛏️ **Xẻng Vàng (Golden Shovel) - CHUỘT TRÁI & PHẢI**
- **Chuột TRÁI** vào block = `/sel 1` (điểm 1)
- **Chuột PHẢI** vào block = `/sel 2` (điểm 2)
- Không cần gõ lệnh, rất tiện!

### 🍎 **Tự động ăn (Auto-Eat)**
- Tự động ăn táo vàng khi đói
- Điều chỉnh được ngưỡng ăn (1-10 thanh đói)
- Tự động tạm dừng đào khi đang ăn

### 🏗️ **Vét sạch từng tầng**
- Đào xong tầng 1 → Vét sạch tầng 1 → Xuống tầng 2
- Không để sót block ở tầng trên
- Dễ theo dõi tiến trình

### 👁️ **Không cúi đầu**
- Tối ưu để bot không cúi đầu liên tục
- Đào mượt mà, chuyên nghiệp

## 📦 Cài đặt

1. Tải file `automine-0.1.0.jar` từ thư mục `build/libs/`
2. Copy vào thư mục `mods/` của Minecraft
3. Cần **Fabric Loader** để chạy
4. Khởi động lại game

## 🎮 Hướng dẫn sử dụng nhanh

### Cách 1: Dùng Xẻng Vàng (Khuyên dùng!)

```
1. Craft xẻng vàng (Golden Shovel)
2. Đứng ở góc thứ nhất
3. CHUỘT TRÁI vào block → Điểm 1
4. Đi sang góc đối diện
5. CHUỘT PHẢI vào block → Điểm 2
6. Gõ /start
```

### Cách 2: Dùng lệnh

```
/sel 1        # Đặt điểm 1 tại chỗ đứng
/sel 2        # Đặt điểm 2 tại chỗ đứng
/start        # Bắt đầu đào
```

### Mở menu:
```
/automine     # Hoặc /am
```

## ⚙️ Config quan trọng

### Xẻng vàng:
```bash
/am set goldenShovelMark true   # Bật
/am set goldenShovelMark false  # Tắt
```

### Tự động ăn:
```bash
/am set autoEat true             # Bật tự động ăn
/am set autoEatThreshold 2       # Ăn khi mất 2 thanh đói (mặc định)
/am set autoEatThreshold 1       # Ăn sớm hơn
/am set autoEatThreshold 4       # Ăn muộn hơn (tiết kiệm)
```

### Kích thước đào:
```bash
/am set layerHeight 3            # Cao mỗi tầng (1-6)
/am set passWidth 3              # Rộng mặt đào (1-5)
```

### Tuỳ chọn:
```bash
/am set allowSprint true         # Chạy nhanh
/am set allowPlace true          # Xây trụ leo lên
/am set avoidLava true           # Né dung nham
/am set renderSelection true     # Hiện khung vùng chọn
```

## 🎯 Tips & Tricks

### 1. Chuẩn bị trước khi đào:
- ✅ Pickaxe tốt (efficiency, unbreaking)
- ✅ Táo vàng trong hotbar (cho auto-eat)
- ✅ Cobblestone/Deepslate trong hotbar (để leo lên)
- ✅ Chọn vùng không quá sâu (tránh bedrock)

### 2. Xẻng vàng không hoạt động?
```bash
/am test                          # Kiểm tra config
/am set goldenShovelMark true     # Bật lại nếu tắt
```

### 3. Đào nhanh hơn:
```bash
/am set allowSprint true          # Bật chạy nhanh
/am set passWidth 5               # Tăng độ rộng mặt đào
```

### 4. An toàn hơn:
```bash
/am set avoidLava true            # Né dung nham
/am set autoEat true              # Bật tự động ăn
/am set autoEatThreshold 1        # Ăn sớm
```

### 5. Tiết kiệm táo vàng:
```bash
/am set autoEatThreshold 4        # Ăn khi mất nhiều hơn
```

## 📋 Các lệnh đầy đủ

### Điều khiển:
```bash
/automine      # Mở menu
/am            # Mở menu (ngắn gọn)
/sel 1         # Đặt điểm 1
/sel 2         # Đặt điểm 2
/sel info      # Xem thông tin vùng chọn
/sel clear     # Xóa vùng chọn
/start         # Bắt đầu đào
/stop          # Dừng đào
/pause         # Tạm dừng
/resume        # Tiếp tục
/status        # Xem trạng thái
```

### Config:
```bash
/am set                           # Xem tất cả config
/am set <key>                     # Xem giá trị của key
/am set <key> <value>             # Đặt giá trị mới
/am test                          # Test xẻng vàng
/am help                          # Hướng dẫn
```

## 🔧 Config keys:

| Key | Giá trị | Mô tả |
|-----|---------|-------|
| `layerHeight` | 1-6 | Cao mỗi tầng (mặc định: 3) |
| `passWidth` | 1-5 | Rộng mặt đào (mặc định: 3) |
| `allowSprint` | true/false | Chạy nhanh (mặc định: true) |
| `allowPlace` | true/false | Xây trụ leo lên (mặc định: true) |
| `fillCenter` | true/false | Kê block vào tâm (mặc định: true) |
| `avoidLava` | true/false | Né dung nham (mặc định: true) |
| `renderSelection` | true/false | Hiện khung (mặc định: true) |
| `autoEat` | true/false | Tự động ăn (mặc định: true) |
| `autoEatThreshold` | 1-10 | Ngưỡng ăn (mặc định: 2) |
| `goldenShovelMark` | true/false | Xẻng vàng mark (mặc định: true) |

## ❓ FAQ

### Q: Xẻng vàng không hoạt động?
A: Gõ `/am test` để kiểm tra. Nếu `goldenShovelMark = false` thì bật lại bằng `/am set goldenShovelMark true`

### Q: Tự động ăn không hoạt động?
A: Phải có táo vàng trong **hotbar** (slot 0-8), không phải trong inventory.

### Q: Bot cứ cúi đầu xuống?
A: Phiên bản mới đã fix! Nếu vẫn còn, thử tắt:
```bash
/am set fillCenter false
/am set allowPlace false
```

### Q: Làm sao biết đang đào tầng nào?
A: Xem dòng status trên màn hình hoặc gõ `/status`

### Q: Bot bị kẹt?
A: Gõ `/stop` rồi `/start` lại. Hoặc `/pause` rồi `/resume`.

### Q: Thay đổi kích thước vùng giữa chừng?
A: Phải `/stop`, đánh dấu lại 2 điểm, rồi `/start` mới.

## 📂 File config

File: `config/automine.properties`

```properties
layerHeight=3
passWidth=3
allowSprint=true
allowPlace=true
fillCenter=true
avoidLava=true
reachDistance=4.5
renderSelection=true
autoEat=true
autoEatThreshold=2
goldenShovelMark=true
```

Có thể edit trực tiếp file này (khi game tắt) hoặc dùng lệnh `/am set`.

## 🐛 Báo lỗi

Nếu gặp lỗi, cung cấp:
1. File `logs/latest.log`
2. File `config/automine.properties`
3. Phiên bản Minecraft & Fabric Loader
4. Mô tả lỗi chi tiết

## 📚 Tài liệu thêm

- [FEATURES.md](FEATURES.md) - Chi tiết tính năng
- [TROUBLESHOOTING.md](TROUBLESHOOTING.md) - Khắc phục lỗi

## ✨ Những gì mới trong phiên bản này

### v0.1.0
- ✅ **Xẻng vàng:** Chuột TRÁI = điểm 1, Chuột PHẢI = điểm 2
- ✅ **Tự động ăn:** Tự động ăn táo vàng khi đói
- ✅ **Vét sạch từng tầng:** Hoàn thành 100% tầng trước khi xuống tầng tiếp
- ✅ **Không cúi đầu:** Tối ưu thuật toán đào
- ✅ **Menu GUI:** Thêm toggle và stepper cho các tính năng mới
- ✅ **Test command:** `/am test` để kiểm tra config

## 📄 License

MIT License - Tự do sử dụng và chỉnh sửa

---

**Chúc đào vui vẻ!** ⛏️💎
