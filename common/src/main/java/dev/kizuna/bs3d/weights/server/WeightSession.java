package dev.kizuna.bs3d.weights.server;

import dev.kizuna.backpack3d.server.InventoryAccess;
import dev.kizuna.bs3d.weights.protocol.WeightMessages;
import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.Status;
import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.SubscribeRequest;
import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.WeightState;
import dev.kizuna.inventoryui.server.ServerConnection;
import dev.kizuna.inventoryui.server.ServerModuleSession;
import dev.kizuna.inventoryui.server.ServerRequest;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import java.io.UncheckedIOException;

/** 每连接独立订阅、去重和修订号，断线后不保留旧世界数据。 */
final class WeightSession implements ServerModuleSession<ServerPlayerEntity> {
    private static final Logger LOGGER = LoggerFactory.getLogger("bs3d_weights");
    // 20 TPS 下最多每 250 ms 检查一次；未变化的结果不发包。
    private static final int UPDATE_INTERVAL_TICKS = 5;
    private final MinecraftServer server;
    private final ServerConnection<ServerPlayerEntity> connection;
    private final WeightCalculator calculator;
    private WeightState previous;
    private long revision;
    private int elapsedTicks;
    private boolean subscribed;

    WeightSession(MinecraftServer server, ServerConnection<ServerPlayerEntity> connection, WeightCalculator calculator) {
        this.server = server;
        this.connection = connection;
        this.calculator = calculator;
    }

    MinecraftServer server() {
        return server;
    }

    @Override
    public boolean handle(ServerRequest<ServerPlayerEntity> request) {
        // 客户端只能订阅自己；不给选择玩家、发送重量或改规则的消息入口。
        if (!request.message().equals(WeightMessages.SUBSCRIBE)) {
            return false;
        }
        request.payload(SubscribeRequest.getDefaultInstance());
        subscribed = true;
        publish(true);
        return true;
    }

    void tick() {
        // 使用服务端 tick，不受客户端帧率影响；暂停的单机不需要更新静止库存。
        if (!subscribed) {
            return;
        }
        elapsedTicks++;
        if (elapsedTicks < UPDATE_INTERVAL_TICKS) {
            return;
        }
        elapsedTicks = 0;
        publish(false);
    }

    private void publish(boolean force) {
        // 先完整计算，再去重并发布；主动订阅必须收到一份当前快照。
        var player = connection.context();
        WeightState next;
        try {
            // 屏幕光标也是玩家携带的真实栈，拿起原版物品时总重不能临时消失。
            next = calculator.calculate(InventoryAccess.snapshot(player), player.currentScreenHandler.getCursorStack());
        } catch (IllegalArgumentException | ArithmeticException | UncheckedIOException invalid) {
            next = WeightState.newBuilder().setStatus(Status.INVALID_INVENTORY).build();
            if (previous == null || previous.getStatus() != Status.INVALID_INVENTORY) {
                LOGGER.warn("玩家 {} 的重量暂不可用", player.getUuid(), invalid);
            }
        }
        if (!force && next.equals(previous)) {
            return;
        }
        previous = next;
        revision++;
        var message = next.toBuilder()
                .setSession(connection.id())
                .setRevision(revision)
                .build();
        connection.send(WeightMessages.STATE, message);
    }

    @Override
    public void close() {
        // Framework 在逻辑服务器线程关闭会话，保证停止世界后不再推送。
        subscribed = false;
        WeightsServer.forget(this);
    }
}
