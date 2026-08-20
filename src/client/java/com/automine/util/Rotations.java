package com.automine.util;

import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/** Turns the player's view toward a point, capped per tick so it doesn't snap. */
public final class Rotations {

	/**
	 * Lệch dưới ngần này thì coi như ĐÃ NGẮM TRÚNG và không đụng vào góc nhìn nữa.
	 *
	 * <p>Đây là cái van chống "xoay đầu hoài". Bản cũ tick nào gọi cũng ghi lại
	 * yaw/pitch, kể cả khi chỉ lệch vài phần trăm độ: một block cứng phải gặm 30
	 * giây là 600 lần rung đầu liên tiếp không vì lý do gì. Người chơi thật ngắm
	 * trúng rồi thì để yên tay cho tới lúc block vỡ — và anti-bot nhìn đúng chỗ
	 * đó: chuột người luôn có lúc đứng im, chuột bot thì không bao giờ.
	 */
	private static final float AIM_DEADZONE = 0.75F;

	/**
	 * Mục tiêu nằm trong bán kính ngang này thì coi như NGAY DƯỚI (hoặc ngay trên)
	 * chân — chỉ chỉnh pitch, cấm đụng vào yaw.
	 *
	 * <p>Ngắm một ô dưới chân thì hai thành phần ngang chỉ còn vài phần trăm
	 * block, mà {@code atan2} của hai số bé xíu là một góc gần như ngẫu nhiên:
	 * đầu bị quăng sang ngang mấy chục độ để "ngắm" một thứ nằm thẳng dưới mũi
	 * chân. Chính là lỗi mà {@code BlockPlacer.aimStraightDown} và phần quăng exp
	 * đã ghi lại từ trước ("steering yaw toward a point underfoot is the known
	 * spin bug") — nhưng lúc bổ hai nhát tụt tầng thì vẫn dính, vì đường đó đi
	 * qua đây. Cúi mặt xuống là đủ: crosshair rơi đúng ô dưới chân bất kể yaw.
	 *
	 * <p>0.25, và con số này là TÍNH RA chứ không ước lượng. Pitch ngắm vào TÂM
	 * block (sâu 2.12 dưới mắt với ô dưới chân) nhưng tia chạm MẶT TRÊN block
	 * (sâu 1.62), nên điểm chạm vượt quá chân mình thêm 0.764×h — lệch khỏi cột
	 * khi h &gt; 0.283. Bản đầu để 0.4: cả dải 0.283–0.4 vừa bị ghim yaw vừa ngắm
	 * trượt sang cột bên, mà thân sau khi tới nơi còn trớn nên rất hay dừng đúng
	 * dải đó — đứng nhìn sàn 30 giây không bổ được nhát tụt tầng nào. 0.25 nằm
	 * dưới ngưỡng hỏng của cả ô dưới chân (0.283) lẫn ô trần khi leo (0.349);
	 * lệch hơn nữa thì yaw được chỉnh như thường — thân đứng yên nên hướng ngang
	 * lúc đó là số ổn định, quay một nhịp là xong, không phải nhiễu.
	 */
	private static final double UNDERFOOT_RANGE = 0.25;

	private Rotations() {
	}

	/** Yaw in degrees for a horizontal delta. */
	public static float yawTo(double dx, double dz) {
		return (float) Math.toDegrees(Math.atan2(-dx, dz));
	}

	/**
	 * Quay NGANG tới một hướng, không đụng tới pitch — dùng cho việc đi lại.
	 *
	 * <p>Tách khỏi {@link #turnTo} vì đi bộ không cần ngóc/cúi: kéo pitch theo mỗi
	 * lần rẽ là thừa một trục chuyển động mà người chơi không hề làm khi chạy
	 * đường thẳng.
	 *
	 * @return true khi vẫn còn phải quay tiếp ở tick sau (khúc cua chưa xong).
	 */
	public static boolean stepYawTo(ClientPlayerEntity player, float desiredYaw, float maxDegreesPerTick) {
		float diff = MathHelper.wrapDegrees(desiredYaw - player.getYaw());
		if (Math.abs(diff) <= AIM_DEADZONE) {
			return false; // đúng hướng rồi — không ghi gì cả, đầu đứng yên
		}
		player.setYaw(player.getYaw() + MathHelper.clamp(diff, -maxDegreesPerTick, maxDegreesPerTick));
		return Math.abs(diff) > maxDegreesPerTick;
	}

	/** @return true once the player is looking closely enough at {@code target}. */
	public static boolean turnTo(ClientPlayerEntity player, Vec3d target, float maxDegreesPerTick) {
		Vec3d eye = player.getEyePos();
		double dx = target.x - eye.x;
		double dy = target.y - eye.y;
		double dz = target.z - eye.z;
		double horizontal = Math.sqrt(dx * dx + dz * dz);

		float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
		float desiredPitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));

		// Ô ngay dưới/ngay trên chân: yaw là nhiễu thuần tuý — xem {@link #UNDERFOOT_RANGE}.
		boolean underfoot = horizontal < UNDERFOOT_RANGE;
		float yawDiff = underfoot ? 0.0F : MathHelper.wrapDegrees(desiredYaw - player.getYaw());
		float pitchDiff = desiredPitch - player.getPitch();

		// ĐÃ TRÚNG THÌ THÔI ĐỘNG VÀO ĐẦU — xem {@link #AIM_DEADZONE}.
		if (Math.abs(yawDiff) <= AIM_DEADZONE && Math.abs(pitchDiff) <= AIM_DEADZONE) {
			return true;
		}

		if (!underfoot) {
			player.setYaw(player.getYaw() + MathHelper.clamp(yawDiff, -maxDegreesPerTick, maxDegreesPerTick));
		}
		player.setPitch(MathHelper.clamp(
				player.getPitch() + MathHelper.clamp(pitchDiff, -maxDegreesPerTick, maxDegreesPerTick),
				-90.0F, 90.0F));

		return Math.abs(yawDiff) < 2.5F && Math.abs(pitchDiff) < 2.5F;
	}
}
