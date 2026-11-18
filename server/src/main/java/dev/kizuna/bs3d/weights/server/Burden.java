package dev.kizuna.bs3d.weights.server;

import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.Status;
import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.WeightState;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** 供其他服务端附属只读查询随身实重与有效负重；Weights 自身不据此限制玩家。 */
public final class Burden {
    private Burden() {
        // 只提供静态查询，不持有可写状态。
    }

    /**
     * 即时读取整份随身根清单，返回实重、有效负重及容器明细，单位为整数克；不缓存结果。
     *
     * <p>调用方必须先看状态是 {@code OK} 再读克数；无效时的默认零不代表空身。
     * 损坏、重复 BS3D 实例、超限或溢出返回 {@code INVALID_INVENTORY}，不返回部分重量。
     * 普通物品没有实例身份，调用方必须保证每件实物只列一个根，且不另列容器内容。
     *
     * <p>只在该世界的逻辑服务器主线程调用，注册表取自该世界；调用期间不得改清单或栈。
     * 读取副本，不修改或留存输入；使用 Weights 当前规则和同一份来源系数，不识别玩家或物种。
     *
     * @param level 这份真实或拟提交库存所属的服务端世界
     * @param roots 完整根清单，空槽和空列表有效，列表及其元素不得为 null
     * @throws IllegalStateException 非服务器主线程调用，或 Weights 尚未初始化
     * @throws NullPointerException 世界、清单或根元素为 null
     */
    public static WeightState measure(ServerLevel level, List<CarriedStack> roots) {
        // 接线错误先报错，不让空库存绕过线程和初始化约束。
        Objects.requireNonNull(level, "level");
        if (!level.getServer().isSameThread()) {
            throw new IllegalStateException("重量只能在逻辑服务器主线程读取");
        }
        var calculator = WeightsServer.calculator();
        return calculator.measure(roots, level.registryAccess());
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
