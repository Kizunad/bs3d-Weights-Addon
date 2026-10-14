package dev.kizuna.bs3d.weights.client;

import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.Status;
import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.WeightState;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

/** 只缓存和格式化服务端结果；不依赖客户端物品、NBT 或重量规则。 */
public final class WeightView {
    // 克转换为千克时，十进制小数点左移三位。
    private static final int KILOGRAM_DECIMAL_SCALE = 3;
    private WeightState state = WeightState.getDefaultInstance();

    public void accept(WeightState next) {
        // 同连接旧修订直接忽略；新连接状态由 Framework 的连接代次过滤。
        if (next.getSession().equals(state.getSession()) && next.getRevision() <= state.getRevision()) {
            return;
        }
        if (next.getSession().isBlank() || next.getRevision() <= 0) {
            throw new IllegalArgumentException("重量快照连接身份或修订号无效");
        }
        if (next.getStatus() == Status.UNRECOGNIZED || next.getStatus() == Status.STATUS_UNSPECIFIED) {
            throw new IllegalArgumentException("重量快照缺少有效状态");
        }
        if (next.getTotalGrams() < 0) {
            throw new IllegalArgumentException("随身总重不能为负数");
        }
        // 完整校验明细后才替换缓存；重复身份或错误数值不会覆盖最后有效结果。
        Set<String> identities = new HashSet<>();
        for (var container : next.getContainersList()) {
            if (container.getContainerId().isBlank() || !identities.add(container.getContainerId())) {
                throw new IllegalArgumentException("容器实例身份为空或重复");
            }
            if (container.getContentsGrams() < 0 || container.getTotalGrams() < container.getContentsGrams()) {
                throw new IllegalArgumentException("容器重量不能为负数，总重不能小于内容重量");
            }
        }
        state = next;
    }

    public WeightState state() {
        return state;
    }

    public void clear() {
        state = WeightState.getDefaultInstance();
    }

    public static String kilograms(long grams) {
        // 用十进制移动三位保留克精度，不能经浮点换算把大重量或一克差异舍掉。
        return BigDecimal.valueOf(grams, KILOGRAM_DECIMAL_SCALE)
                .stripTrailingZeros()
                .toPlainString() + " kg";
    }
}
