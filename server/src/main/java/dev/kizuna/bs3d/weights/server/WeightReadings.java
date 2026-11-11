package dev.kizuna.bs3d.weights.server;

import dev.kizuna.backpack3d.server.InventoryAccess;
import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.Status;
import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.WeightState;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 每名玩家一份重量读数，订阅会话和 {@link Burden} 共用，同一玩家在一个间隔里只读一次库存。
 *
 * <p>到点读库存时，库存、光标、地面容器和规则都没变就沿用上次结果，不再遍历容器树。
 */
final class WeightReadings {
    private static final Logger LOGGER = LoggerFactory.getLogger("bs3d_weights");
    // 20 TPS 下最多每 250 ms 读一次库存；订阅推送和 Burden 查询沿用同一间隔。
    static final int UPDATE_INTERVAL_TICKS = 5;
    // 按玩家实体保存最近一次读数；重生会换实体，弱引用让下线或重生前的旧实体自然释放。
    private static final Map<ServerPlayer, WeightReading> READINGS = new WeakHashMap<>();

    private WeightReadings() {
        // 只提供静态读取，状态在读数表里。
    }

    static WeightState read(ServerPlayer player) {
        // 库存快照和读数表都只在逻辑服务器主线程访问。
        var server = player.level().getServer();
        if (!server.isSameThread()) {
            throw new IllegalStateException("重量只能在逻辑服务器主线程读取");
        }
        WeightReading previous = READINGS.get(player);
        WeightReading next = WeightReading.next(previous, server.getTickCount(), UPDATE_INTERVAL_TICKS,
                () -> inputs(player), inputs -> calculate(player, inputs, previous));
        if (next != previous) {
            READINGS.put(player, next);
        }
        return next.state();
    }

    private static WeightInputs inputs(ServerPlayer player) {
        // 屏幕光标也是玩家携带的真实栈，拿起原版物品时总重不能临时消失。
        return WeightInputs.of(InventoryAccess.snapshot(player), player.containerMenu.getCarried(),
                player.registryAccess(), WeightsServer.rulesRevision());
    }

    private static WeightState calculate(ServerPlayer player, WeightInputs inputs, WeightReading previous) {
        // 损坏库存与溢出返回不可用状态，由调用方决定如何回退；连续不可用只告警一次。
        // 不可用的结果也随输入留存，库存没变时不再重走一遍损坏的树。
        try {
            return WeightsServer.calculator().calculate(inputs.inventory(), inputs.cursor(), inputs.registries());
        } catch (IllegalArgumentException | ArithmeticException | UncheckedIOException invalid) {
            if (previous == null || previous.state().getStatus() != Status.INVALID_INVENTORY) {
                LOGGER.warn("玩家 {} 的重量暂不可用", player.getUUID(), invalid);
            }
            return WeightState.newBuilder()
                    .setStatus(Status.INVALID_INVENTORY)
                    .build();
        }
    }
}
