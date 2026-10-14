package dev.kizuna.bs3d.weights.protocol;

import com.google.protobuf.Message;
import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.SubscribeRequest;
import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.WeightState;
import dev.kizuna.inventoryui.protocol.pb.WireProtocol;
import java.util.Map;

/** 复用 Framework wire v2；业务字段唯一来源是 weights.proto。 */
public final class WeightMessages {
    public static final String MODULE = "bs3d_weights:weights";
    public static final String SUBSCRIBE = "bs3d_weights:subscribe";
    public static final String STATE = "bs3d_weights:state";
    public static final int VERSION = 1;

    private WeightMessages() {
    }

    public static Map<String, WireProtocol.Capability> capabilities() {
        return Map.of(
                SUBSCRIBE, capability(SubscribeRequest.getDefaultInstance(), WireProtocol.Direction.CLIENT_TO_SERVER),
                STATE, capability(WeightState.getDefaultInstance(), WireProtocol.Direction.SERVER_TO_CLIENT));
    }

    private static WireProtocol.Capability capability(Message sample, WireProtocol.Direction direction) {
        // 重量是可选扩展；服务端缺少此附属时仍允许基础库存正常协商。
        return WireProtocol.Capability.newBuilder()
                .setModule(MODULE)
                .setVersion(VERSION)
                .setRequired(false)
                .setDirection(direction)
                .setPayloadType(sample.getDescriptorForType().getFullName())
                .build();
    }
}
