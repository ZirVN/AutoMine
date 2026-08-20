# Xử lý lỗi AutoMine

## ❌ Xẻng vàng không hoạt động

### Triệu chứng:
- Cầm xẻng vàng chuột phải vào block nhưng không thấy thông báo
- Gõ `/start` báo "chưa đủ 2 điểm"

### Cách kiểm tra:

**Bước 1: Kiểm tra config**
```
/am test
```
Xem có hiện:
```
[AutoMine] Test xẻng vàng:
[AutoMine]   goldenShovelMark = true
[AutoMine]   Cầm xẻng vàng và chuột phải vào block để test
```

Nếu hiện `goldenShovelMark = false` thì bật lại:
```
/am set goldenShovelMark true
```

**Bước 2: Reload mod**
- Thoát game hoàn toàn (Exit to Desktop)
- Mở lại game
- Thử lại với xẻng vàng

**Bước 3: Kiểm tra file config**
File: `config/automine.properties`

Mở và kiểm tra dòng:
```
goldenShovelMark=true
```

Nếu không có hoặc = false, sửa thành true và save.

**Bước 4: Xóa config cũ và tạo mới**
1. Thoát game
2. Xóa file `config/automine.properties`
3. Mở lại game (mod sẽ tạo config mới với giá trị mặc định)

**Bước 5: Dùng lệnh thay thế**
Nếu xẻng vàng vẫn không hoạt động, dùng lệnh:
```
/sel 1
/sel 2
/start
```

---

## ❌ Auto-eat không hoạt động

### Triệu chứng:
- Đói nhưng không tự động ăn táo vàng

### Cách khắc phục:

**1. Kiểm tra config:**
```
/am set autoEat
```
Phải hiện `autoEat = true`

Nếu false thì bật:
```
/am set autoEat true
```

**2. Kiểm tra táo vàng:**
- Phải có táo vàng (Golden Apple) hoặc táo vàng enchanted trong **hotbar** (slot 0-8)
- Không được để trong inventory

**3. Điều chỉnh ngưỡng:**
Nếu ăn quá sớm hoặc quá muộn:
```
/am set autoEatThreshold 1    # Ăn sớm (khi mất 1 thanh = 2 hunger)
/am set autoEatThreshold 2    # Ăn vừa (khi mất 2 thanh = 4 hunger) - MẶC ĐỊNH
/am set autoEatThreshold 4    # Ăn muộn (khi mất 4 thanh = 8 hunger)
```

**4. Reload:**
- Gõ `/stop` rồi `/start` lại

---

## ❌ Mod không load

### Triệu chứng:
- Không có command `/am` hoặc `/automine`
- Không thấy mod trong mods list

### Cách khắc phục:

**1. Kiểm tra file JAR:**
- File: `dist-mods/automine-0.1.0.jar`
- Copy vào thư mục `mods/` của Minecraft

**2. Kiểm tra Fabric Loader:**
- Mod cần Fabric Loader để chạy
- Download tại: https://fabricmc.net/use/

**3. Kiểm tra phiên bản Minecraft:**
- Mod này build cho Minecraft 1.21.4
- Kiểm tra trong file `gradle.properties`

**4. Xem log lỗi:**
- Mở `logs/latest.log`
- Tìm dòng có `[AutoMine]` hoặc `automine`
- Nếu có lỗi, sẽ hiện ở đây

---

## ❌ Bot cứ cúi đầu xuống

### Đã fix trong phiên bản mới!

Nếu vẫn còn:
1. Tắt "Kê block vào tâm": `/am set fillCenter false`
2. Tắt "Xây trụ leo lên": `/am set allowPlace false`

---

## ❌ Bot không nhận lệnh /start

### Triệu chứng:
```
[AutoMine] chưa đủ 2 điểm — dùng /sel 1 và /sel 2
```

### Cách khắc phục:

**1. Kiểm tra đã đặt 2 điểm chưa:**
```
/sel info
```
Phải hiện:
```
[AutoMine] vùng 32x23x69 (51072 block)
[AutoMine] từ -133630 -36 -214878 đến -133661 -58 -214946
```

**2. Đặt lại 2 điểm:**
```
/sel 1
/sel 2
```
Hoặc dùng xẻng vàng (nếu đã fix)

**3. Mở menu GUI:**
```
/automine
```
Xem có hiển thị "Vùng: <kích thước>" không

---

## 📞 Báo lỗi

Nếu vẫn không được, cung cấp:

1. **Log file:** `logs/latest.log`
2. **Config file:** `config/automine.properties`
3. **Phiên bản:**
   - Minecraft version: `?`
   - Fabric Loader version: `?`
   - AutoMine mod version: `0.1.0`
4. **Mô tả lỗi:** Làm gì, kết quả thế nào, mong đợi gì

---

## ✅ Các command hữu ích

```bash
# Kiểm tra config
/am set

# Test xẻng vàng
/am test

# Xem hướng dẫn
/am help

# Xem trạng thái
/status

# Xem vùng chọn
/sel info

# Xóa vùng chọn
/sel clear

# Mở menu
/automine
```

---

## 🔧 Reset toàn bộ

**Cách 1: Xóa config**
1. Thoát game
2. Xóa `config/automine.properties`
3. Mở lại game

**Cách 2: Reset từng config**
```
/am set layerHeight 3
/am set passWidth 3
/am set allowSprint true
/am set allowPlace true
/am set fillCenter true
/am set avoidLava true
/am set renderSelection true
/am set autoEat true
/am set autoEatThreshold 2
/am set goldenShovelMark true
```
