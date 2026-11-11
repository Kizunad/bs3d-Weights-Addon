package dev.kizuna.bs3d.weights.server;

import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.Status;
import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.WeightState;
import java.util.OptionalLong;
import net.minecraft.server.level.ServerPlayer;

/** 供其他服务端附属只读查询随身实重与有效负重；Weights 自身不据此限制玩家。 */
public final class Burden {
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
        // 和订阅推送共用同一份读数：每 5 个服务端 tick 最多读一次库存，库存和规则都没变时不重算。
        return WeightReadings.read(player);
    }
}
