# AutoMine - Các Tính Năng Mới

## ✅ Đã thêm vào mod

### 1. 🍎 Tự động ăn (Auto-Eat)
**Mô tả:** Tự động ăn táo vàng khi mất thanh đói trong khi đào.

**Config:**
- `autoEat` (true/false) - Bật/tắt tự động ăn (mặc định: true)
- `autoEatThreshold` (1-10) - Số thanh đói mất trước khi ăn (mặc định: 2)

**Cách dùng:**
1. Bỏ táo vàng hoặc táo vàng enchanted vào hotbar (slot 0-8)
2. Bật tính năng "Tự động ăn" trong menu `/automine`
3. Điều chỉnh ngưỡng "Ăn khi mất (thanh)" để quyết định khi nào ăn
4. Khi đào, bot sẽ tự động chuyển sang táo vàng, ăn, rồi chuyển lại công cụ cũ

**Commands:**
```
/am set autoEat true
/am set autoEat false
/am set autoEatThreshold 2
```

**Lưu ý:**
- Tự động tạm dừng đào khi đang ăn
- Tự động chuyển lại slot cũ sau khi ăn xong
- Hỗ trợ cả Golden Apple và Enchanted Golden Apple

---

### 2. ⛏️ Đánh dấu bằng Xẻng Vàng (Golden Shovel Marking)
**Mô tả:** Dùng xẻng vàng để đánh dấu 2 điểm của vùng cần đào - **hoàn toàn giống lệnh `/sel 1` và `/sel 2`**.

**Config:**
- `goldenShovelMark` (true/false) - Bật/tắt tính năng (mặc định: true)

**Cách dùng:**
1. Cầm xẻng vàng (Golden Shovel)
2. **CHUỘT TRÁI** vào block → Đặt điểm 1 (/sel 1)
3. **CHUỘT PHẢI** vào block → Đặt điểm 2 (/sel 2)
4. Sau khi có 2 điểm, nhấn `/start` hoặc bấm nút trong menu

**Thông báo hiển thị:**
```
[AutoMine] điểm 1 = -133630 -36 -214878
[AutoMine] điểm 2 = -133661 -58 -214946 · vùng 32x23x69 (51072 block) — gõ /start
```
**Giống y hệt khi dùng lệnh `/sel 1` và `/sel 2`!**

**Command:**
```
/am set goldenShovelMark true
/am set goldenShovelMark false
```

**Ưu điểm:**
- **Chuột trái = điểm 1, Chuột phải = điểm 2** - Rất trực quan!
- Không cần gõ command
- Nhanh hơn, tiện hơn khi đánh dấu vùng
- Thấy rõ block đang chọn
- Hiển thị đầy đủ thông tin: tọa độ, kích thước vùng, số block
- Tự động thông báo "gõ /start" khi đã có đủ 2 điểm

---

### 3. 👁️ Đào không cúi đầu + Vét sạch từng tầng
**Mô tả:** 
- Tối ưu thuật toán để bot không cúi đầu xuống liên tục khi đào
- **VÉT SẠCH TỪNG TẦNG** trước khi xuống tầng tiếp theo

**Cách hoạt động:**
1. Đào tâm các mặt 3x3 của tầng 1
2. **Vét sạch tất cả block còn sót lại ở tầng 1**
3. Xuống tầng 2
4. Đào tâm các mặt 3x3 của tầng 2
5. **Vét sạch tất cả block còn sót lại ở tầng 2**
6. Tiếp tục cho đến hết vùng

**Cải tiến:**
- Luôn thử aim trước khi reposition
- Không dừng lại sau mỗi lần đào xong
- Chuyển mượt từ block này sang block khác
- Chỉ cúi đầu khi thực sự cần đặt block
- **Mỗi tầng hoàn thành 100% trước khi xuống tầng tiếp**
- Dễ theo dõi tiến trình hơn

**Thông báo:**
```
[AutoMine] tầng 1 xong tâm — vét sót tầng này
[AutoMine] tầng 1 hoàn thành — xuống tầng 2
[AutoMine] tầng 2 xong tâm — vét sót tầng này
[AutoMine] tầng 2 hoàn thành — xuống tầng 3
...
```

**Không cần config** - Tự động hoạt động!

---

## 📋 Menu GUI

Mở menu bằng phím tắt hoặc command `/automine`:

### Section mới:
**5) Cài đặt tự động ăn:**
- Toggle "Tự động ăn" - Bật/tắt tính năng
- Stepper "Ăn khi mất (thanh)" - Điều chỉnh ngưỡng (1-10 thanh)

**4) Tuỳ chọn:**
- Toggle "Xẻng vàng mark" - Bật/tắt đánh dấu bằng xẻng vàng

---

## 🎮 Commands

### Các command có sẵn:
```
/am set autoEat true/false
/am set autoEatThreshold <số thanh> (1-10)
/am set goldenShovelMark true/false
/am set layerHeight <1-6>
/am set passWidth <1-5>
/am set allowSprint true/false
/am set allowPlace true/false
/am set fillCenter true/false
/am set avoidLava true/false
/am set renderSelection true/false
```

### Các command điều khiển:
```
/automine - Mở menu GUI
/start - Bắt đầu đào
/stop - Dừng đào
/sel 1 - Đặt điểm 1 tại vị trí đứng
/sel 2 - Đặt điểm 2 tại vị trí đứng
```

---

## 📦 File Config

Config được lưu tại: `config/automine.properties`

Ví dụ nội dung:
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

---

## 🚀 Cách sử dụng nhanh

1. **Chuẩn bị:**
   - Bỏ pickaxe vào hotbar
   - Bỏ táo vàng vào hotbar (nếu dùng auto-eat)
   - Bỏ cobblestone/deepslate vào hotbar (nếu cần leo lên)

2. **Đánh dấu vùng:**
   - Cầm xẻng vàng
   - Chuột phải ở góc 1
   - Shift + Chuột phải ở góc 2

3. **Bắt đầu:**
   - Gõ `/start` hoặc mở menu `/automine` và bấm "▶ Bắt đầu đào"

4. **Thưởng thức:**
   - Bot sẽ tự động đào, ăn khi đói, leo lên khi cần
   - Theo dõi tiến trình qua status trên màn hình

---

## 🎯 Tips & Tricks

1. **Tự động ăn thông minh:**
   - Đặt `autoEatThreshold=1` nếu muốn ăn sớm (an toàn hơn)
   - Đặt `autoEatThreshold=4` nếu muốn tiết kiệm táo vàng

2. **Đào nhanh hơn:**
   - Bật "Chạy nhanh" trong menu
   - Bật "Kê block vào tâm" nếu dùng 3x3 pickaxe

3. **An toàn:**
   - Bật "Né dung nham" để tránh chết cháy
   - Luôn có táo vàng dự phòng trong hotbar

4. **Xẻng vàng:**
   - Có thể tắt tính năng nếu muốn dùng xẻng vàng bình thường
   - Command: `/am set goldenShovelMark false`

---

## ⚠️ Lưu ý

- Tất cả config đều được lưu tự động
- Có thể thay đổi config trong khi đang đào (sẽ áp dụng ngay)
- File JAR: `dist-mods/automine-0.1.0.jar`
