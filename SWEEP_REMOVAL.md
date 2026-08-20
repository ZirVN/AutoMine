# Đã Xóa Bỏ Hệ Thống Vét Sót

## Thay Đổi
Đã hoàn toàn loại bỏ hệ thống vét sót trên mỗi tầng theo yêu cầu của bạn.

### Hành Vi Cũ (ĐÃ XÓA)
- Đào tâm các mặt tầng 1 → **Vét sót khắp tầng 1** → Xuống tầng 2
- Bot phải đi khắp nơi để thu thập các block sót lại
- Có thể gây ra việc nhìn lên/xuống khi vét sót

### Hành Vi Mới (HIỆN TẠI)
- Đào tâm các mặt tầng 1 → **XUỐNG TẦNG 2 NGAY**
- Đào tâm các mặt tầng 2 → **XUỐNG TẦNG 3 NGAY**
- Cứ thế cho đến hết tầng cuối → **XONG**

## Code Đã Xóa
1. **Biến sweep**: `sweepTarget`, `sweepY`, `sweepingWholeBox`, `restarts`
2. **Hằng số sweep**: `TICKS_PER_BLOCK`, `NO_HIT_LIMIT`, `MAX_RESTARTS`, `FILL_LIMIT`
3. **Phương thức**: `tickSweep()`, `leftoversOnLayer()`, `finishOrRestart()`, `countRemainingInBox()`
4. **Logic điều kiện**: Tất cả các kiểm tra `sweepingWholeBox` đã bị xóa

## Code Được Đơn Giản Hóa
```java
private void tickPhases(ClientPlayerEntity player, World world) {
    if (!plan.areLayerFacesDone()) {
        tickFaces(world);
        return;
    }
    
    // Tầng này đã đào xong tâm — XUỐNG TẦNG TIẾP NGAY
    int finishedLayer = plan.layerIndex() + 1;
    clearTarget();
    
    if (plan.nextLayer()) {
        message("tầng " + finishedLayer + " xong — xuống tầng " + (plan.layerIndex() + 1));
        note = "";
    } else {
        // Hết tất cả tầng - XONG!
        finish();
    }
}
```

## Build Thành Công
- File JAR: `build\libs\automine-0.1.0.jar`
- Kích thước: 72,395 bytes
- Đã biên dịch thành công, không có lỗi

## Lưu Ý
- Bot chỉ đào tâm các mặt (9 blocks mỗi mặt)
- Các block sót lại sẽ KHÔNG được vét
- Nếu cần vét sạch 100%, bạn cần chạy lại `/start` hoặc điều chỉnh lại selection
- Hành vi này theo đúng yêu cầu: "xóa bỏ cái vét sót đi"

## Cách Test
1. Copy file `build\libs\automine-0.1.0.jar` vào thư mục mods của Minecraft
2. Khởi động lại game
3. Dùng xẻng vàng hoặc `/sel 1` và `/sel 2` để chọn khu vực
4. `/start` để bắt đầu đào
5. Quan sát: Sau khi đào xong tâm tầng 1, bot sẽ xuống tầng 2 ngay lập tức (KHÔNG vét sót)
