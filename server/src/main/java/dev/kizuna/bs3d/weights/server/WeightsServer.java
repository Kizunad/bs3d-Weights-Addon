package dev.kizuna.bs3d.weights.server;

import dev.kizuna.bs3d.weights.protocol.WeightMessages;
import dev.kizuna.inventoryui.server.ServerModuleRegistry;
import dev.kizuna.inventoryui.server.fabric.FabricServerModule;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.resource.ResourceType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 单机内置服务器和专用服务器注册同一套计算与通信。 */
public final class WeightsServer implements ModInitializer, FabricServerModule {
    private static final WeightRules RULES = new WeightRules();
    private static final Set<WeightSession> SESSIONS = new HashSet<>();

    @Override
    public void onInitialize() {
        // 数据包属于逻辑服务器；客户端配置不能覆盖服务端规则。
        ResourceManagerHelper.get(ResourceType.SERVER_DATA).registerReloadListener(RULES);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (var session : List.copyOf(SESSIONS)) {
                if (session.server() == server) {
                    session.tick();
                }
            }
        });
    }

    @Override
    public void register(MinecraftServer server, ServerModuleRegistry<ServerPlayerEntity> registry) {
        // 工厂只创建会话，订阅请求到达后才读取真实玩家并发送状态。
        registry.register(WeightMessages.MODULE, WeightMessages.capabilities(), connection -> {
            var session = new WeightSession(server, connection, new WeightCalculator(RULES::unitGrams));
            SESSIONS.add(session);
            return session;
        });
    }

    static void forget(WeightSession session) {
        // 关闭的连接退出 tick 集合，下一次登录创建新的订阅和修订号。
        SESSIONS.remove(session);
    }
}
