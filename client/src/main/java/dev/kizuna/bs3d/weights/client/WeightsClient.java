package dev.kizuna.bs3d.weights.client;

import dev.kizuna.backpack3d.client.ContainerDetails;
import dev.kizuna.bs3d.weights.protocol.WeightMessages;
import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.Status;
import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.SubscribeRequest;
import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.WeightState;
import dev.kizuna.inventoryui.client.ClientBindings;
import dev.kizuna.inventoryui.client.ClientModule;
import dev.kizuna.inventoryui.client.ClientRuntime;
import dev.kizuna.inventoryui.protocol.UiMessageRouter;
import dev.kizuna.inventoryui.protocol.UiWire;
import dev.kizuna.inventoryui.registry.FrameworkCatalog;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import java.util.Set;

/** 附属只提供背包页文字，不新增厚面板，不恢复被隐藏的原版 HUD。 */
public final class WeightsClient implements ClientModule, ContainerDetails.Provider {
    private final WeightView view = new WeightView();
    private String status = "等待重量同步";
    private long generation;

    @Override
    public FrameworkCatalog.Module definition() {
        return FrameworkCatalog.Module.of(WeightMessages.MODULE, Set.of("bs3d:inventory"));
    }

    @Override
    public void register(ClientBindings bindings) {
        // 两个方向都登记可选契约；缺少附属服务端不会阻断 BS3D 基础库存。
        var subscribe = new UiMessageRouter.Contract<>(WeightMessages.MODULE, WeightMessages.SUBSCRIBE,
                WeightMessages.VERSION, UiMessageRouter.Direction.CLIENT_TO_SERVER, SubscribeRequest.class, null);
        var state = new UiMessageRouter.Contract<>(WeightMessages.MODULE, WeightMessages.STATE,
                WeightMessages.VERSION, UiMessageRouter.Direction.SERVER_TO_CLIENT, WeightState.class, view::accept);
        bindings.message(new UiWire.Contract<>(subscribe, UiWire.Codec.protobuf(SubscribeRequest.getDefaultInstance()), false));
        bindings.message(new UiWire.Contract<>(state, UiWire.Codec.protobuf(WeightState.getDefaultInstance()), false));
        ContainerDetails.register(new Identifier("bs3d_weights", "weights"), this);
    }

    @Override
    public void protocolReady(ClientRuntime runtime) {
        // Framework 在客户端线程完成请求；重连之前的异步结果不能覆盖新世界的显示。
        long current = generation;
        runtime.protocol().request(WeightMessages.SUBSCRIBE, SubscribeRequest.getDefaultInstance(), System.currentTimeMillis())
                .thenAccept(result -> {
                    if (current != generation || result.accepted()) {
                        return;
                    }
                    if (result.code().equals("NOT_READY")) {
                        status = "服务器未启用重量附属";
                    } else {
                        status = "重量同步失败：" + result.code();
                    }
                });
    }

    @Override
    public void disconnected(ClientRuntime runtime) {
        // 退出服务器和单机世界都清空重量，不能把上一个角色的重量带入下一局。
        generation++;
        view.clear();
        status = "等待重量同步";
    }

    public WeightState state() {
        return view.state();
    }

    @Override
    public Text summary() {
        // 未同步或计算失败时不显示 0 kg，避免把未知重量误认为空背包。
        return switch (view.state().getStatus()) {
            case OK -> Text.literal("随身重量 " + WeightView.kilograms(view.state().getTotalGrams()));
            case INVALID_INVENTORY -> Text.literal("重量暂不可用：库存数据异常");
            default -> Text.literal(status);
        };
    }

    @Override
    public Text container(String id) {
        // 容器消失时完整快照会移除其条目，客户端不保留过期重量。
        if (view.state().getStatus() == Status.OK) {
            for (var container : view.state().getContainersList()) {
                if (container.getContainerId().equals(id)) {
                    return Text.literal(WeightView.kilograms(container.getTotalGrams()));
                }
            }
        }
        return Text.empty();
    }
}
