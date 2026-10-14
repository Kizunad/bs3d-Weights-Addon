package dev.kizuna.bs3d.weights.server;

import dev.kizuna.bs3d.weights.protocol.WeightMessages;
import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.Status;
import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.SubscribeRequest;
import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.WeightState;
import dev.kizuna.inventoryui.protocol.pb.WireProtocol.Envelope;
import dev.kizuna.inventoryui.protocol.pb.WireProtocol.Kind;
import dev.kizuna.inventoryui.server.ServerModuleRegistry;
import dev.kizuna.inventoryui.server.ServerProtocol;
import dev.kizuna.inventoryui.server.ServerRouter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WeightAuthorityTest {
    @Test
    void forgedClientWeightIsRejectedBeforeBusinessDelivery() {
        // 绕过客户端发送检查，把伪造的零重量直接送进真实服务端路由。
        var handled = new AtomicInteger();
        var replies = new ArrayList<Envelope>();
        var registry = new ServerModuleRegistry<String>();
        registry.register(WeightMessages.MODULE, WeightMessages.capabilities(), connection -> request -> {
            handled.incrementAndGet();
            return true;
        });
        registry.freeze();
        var router = new ServerRouter<>(registry, () -> "authenticated-player", replies::add,
                (message, failure) -> {
                    throw new AssertionError(message, failure);
                });
        router.receive(Envelope.newBuilder()
                .setProtocol(ServerProtocol.VERSION)
                .setKind(Kind.HELLO)
                .putAllCapabilities(WeightMessages.capabilities())
                .build()
                .toByteArray());
        var forged = WeightState.newBuilder().setStatus(Status.OK).setTotalGrams(0).build();
        var request = Envelope.newBuilder()
                .setProtocol(ServerProtocol.VERSION)
                .setKind(Kind.REQUEST)
                .setModule(WeightMessages.MODULE)
                .setMessage(WeightMessages.STATE)
                .setVersion(WeightMessages.VERSION)
                .setRequest(UUID.randomUUID().toString())
                .setData(forged.toByteString());
        router.receive(request.build().toByteArray());
        assertEquals("INVALID_DIRECTION", replies.get(replies.size() - 1).getStatus());
        assertEquals(0, handled.get());

        // 拒绝伪造后，合法订阅仍可交付，连接没有被错误请求破坏。
        router.receive(request
                .setMessage(WeightMessages.SUBSCRIBE)
                .setRequest(UUID.randomUUID().toString())
                .setData(SubscribeRequest.getDefaultInstance().toByteString())
                .build()
                .toByteArray());
        assertEquals("ACCEPTED", replies.get(replies.size() - 1).getStatus());
        assertEquals(1, handled.get());
        router.close();
    }
}
