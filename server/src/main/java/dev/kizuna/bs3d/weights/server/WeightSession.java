package dev.kizuna.bs3d.weights.server;

import dev.kizuna.bs3d.weights.protocol.WeightMessages;
import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.SubscribeRequest;
import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.WeightState;
import dev.kizuna.inventoryui.server.ServerConnection;
import dev.kizuna.inventoryui.server.ServerModuleSession;
import dev.kizuna.inventoryui.server.ServerRequest;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** 每连接独立订阅、去重和修订号，断线后不保留旧世界数据。 */
final class WeightSession implements ServerModuleSession<ServerPlayer> {
    private final MinecraftServer server;
    private final ServerConnection<ServerPlayer> connection;
    private WeightState previous;
    private long revision;
    private boolean subscribed;

    WeightSession(MinecraftServer server, ServerConnection<ServerPlayer> connection) {
        this.server = server;
        this.connection = connection;
    }

    MinecraftServer server() {
        return server;
    }

    @Override
    public boolean handle(ServerRequest<ServerPlayer> request) {
        // 客户端只能订阅自己；不给选择玩家、发送重量或改规则的消息入口。
        if (!request.message().equals(WeightMessages.SUBSCRIBE)) {
            return false;
        }
        var subscription = request.payload(SubscribeRequest.getDefaultInstance());
        // v1 订阅必须为空；把重量、库存或玩家字段塞进未知字段也不能通过校验。
        if (!subscription.getUnknownFields().asMap().isEmpty()) {
            return false;
        }
        subscribed = true;
        publish(true);
        return true;
    }

    void tick() {
        // 使用服务端 tick，不受客户端帧率影响；暂停的单机不需要更新静止库存。
        // 每 tick 只取共用读数：读数表按间隔才读库存，间隔内拿到的是同一个结果对象，比较不花钱；
        // 读数一刷新就推，变化最多晚一个间隔送到。
        if (!subscribed) {
            return;
        }
        publish(false);
    }

    private void publish(boolean force) {
        // 先取这名玩家的完整读数，再去重并发布；主动订阅必须收到一份快照。
        WeightState next = WeightReadings.read(connection.context());
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
