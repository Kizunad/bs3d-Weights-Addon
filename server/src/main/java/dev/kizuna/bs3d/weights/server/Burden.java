package dev.kizuna.bs3d.weights.server;

import dev.kizuna.backpack3d.server.InventoryAccess;
import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.Status;
import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.WeightState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.OptionalLong;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerPlayer;

/** 供其他服务端附属只读查询随身实重与有效负重；Weights 自身不据此限制玩家。 */
public final class Burden {
    private static final Logger LOGGER = LoggerFactory.getLogger("bs3d_weights");
    // 按玩家实体缓存最近一次结果；重生会换实体，弱引用让下线或重生前的旧实体自然释放。
    private static final Map<ServerPlayer, Reading> READINGS = new WeakHashMap<>();

    private Burden() {
        // 只提供静态查询，不持有可写状态。
    }

    /** 有效负重，单位克；库存损坏或溢出时为空。只能在逻辑服务器主线程调用。 */
    public static OptionalLong effectiveGrams(ServerPlayer player) {
        WeightState state = read(player);
        if (state.getStatus() != Status.OK) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(state.getEffectiveGrams());
    }

    /** 随身实重，单位克，与背包页总览同一算法；库存损坏或溢出时为空。只能在逻辑服务器主线程调用。 */
    public static OptionalLong totalGrams(ServerPlayer player) {
        WeightState state = read(player);
        if (state.getStatus() != Status.OK) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(state.getTotalGrams());
    }

    private static WeightState read(ServerPlayer player) {
        // 与订阅会话同一节奏：每 5 个服务端 tick 最多重算一次，期间的读取都复用上次结果。
        var server = player.getServer();
        if (server == null || !server.isSameThread()) {
            throw new IllegalStateException("负重只能在逻辑服务器主线程读取");
        }
        int now = server.getTickCount();
        Reading previous = READINGS.get(player);
        if (previous != null && now - previous.tick() < WeightSession.UPDATE_INTERVAL_TICKS) {
            return previous.state();
        }
        WeightState next = calculate(player, previous);
        READINGS.put(player, new Reading(now, next));
        return next;
    }

    private static WeightState calculate(ServerPlayer player, Reading previous) {
        // 损坏库存与溢出返回不可用状态，由调用方决定如何回退；连续失败只告警一次。
        try {
            var cursor = player.containerMenu.getCarried();
            return WeightsServer.calculator().calculate(InventoryAccess.snapshot(player), cursor);
        } catch (IllegalArgumentException | ArithmeticException | UncheckedIOException invalid) {
            if (previous == null || previous.state().getStatus() != Status.INVALID_INVENTORY) {
                LOGGER.warn("玩家 {} 的负重暂不可用", player.getUUID(), invalid);
            }
            return WeightState.newBuilder()
                    .setStatus(Status.INVALID_INVENTORY)
                    .build();
        }
    }

    /**
     * @param tick 计算时的服务端 tick 计数
     * @param state 当次完整结果，不含连接身份和修订号
     */
    private record Reading(int tick, WeightState state) {}
}
