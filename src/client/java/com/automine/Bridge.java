package com.automine;

import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Thin jar-resident seam between the mixins (which stay in the jar) and the real
 * logic (which is served from the server as a payload and loaded by a child
 * classloader the mixins cannot see).
 *
 * <p>A mixin in the jar cannot call a payload class by name — the game's
 * classloader never delegates down to the payload loader. So instead the mixins
 * call {@code Bridge}, and the payload registers its implementations here at
 * init. The fields are typed as JDK functional interfaces, which both sides can
 * see, so no direct symbol crosses the boundary.
 */
public final class Bridge {

    /** True while AutoMine is forcing a block break this tick. */
    public static volatile BooleanSupplier forceBreaking = () -> false;

    /** True while AutoEat wants the use key held (arg is the MinecraftClient). */
    public static volatile Predicate<Object> holdingUseKey = mc -> false;

    private Bridge() {}

    public static boolean shouldForceBreaking() {
        return forceBreaking.getAsBoolean();
    }

    public static boolean isHoldingUseKey(Object mc) {
        return holdingUseKey.test(mc);
    }
}
