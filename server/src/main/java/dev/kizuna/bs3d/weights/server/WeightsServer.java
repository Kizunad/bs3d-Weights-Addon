package dev.kizuna.bs3d.weights.server;

import dev.kizuna.bs3d.weights.protocol.WeightMessages;
import dev.kizuna.inventoryui.server.ServerModuleRegistry;
import dev.kizuna.inventoryui.server.fabric.FabricServerModule;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.PackType;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 单机内置服务器和专用服务器注册同一套计算与通信。 */
public final class WeightsServer implements ModInitializer, FabricServerModule {
    private static final WeightRules RULES = new WeightRules();
    private static final Set<WeightSession> SESSIONS = new HashSet<>();
    // 有效负重系数表，相对 Fabric 配置目录。
    private static final String BURDEN_CONFIG = "bs3d_weights/burden.toml";
    private static volatile WeightCalculator calculator;

    @Override
    public void onInitialize() {
        // 数据包属于逻辑服务器；客户端配置不能覆盖服务端规则。
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(RULES);
        // 系数表与 Pocket 槽位范围在启动时确定一次；坏配置在进入世界前直接拒绝。
        var factors = BurdenFactors.load(FabricLoader.getInstance().getConfigDir().resolve(BURDEN_CONFIG));
        calculator = new WeightCalculator(RULES::unitGrams, BurdenSlots.detect(), factors);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (var session : List.copyOf(SESSIONS)) {
                if (session.server() == server) {
                    session.tick();
                }
            }
        });
    }

    @Override
    public void register(MinecraftServer server, ServerModuleRegistry<ServerPlayer> registry) {
        // 工厂只创建会话，订阅请求到达后才读取真实玩家并发送状态。
        registry.register(WeightMessages.MODULE, WeightMessages.capabilities(), connection -> {
            var session = new WeightSession(server, connection, calculator());
            SESSIONS.add(session);
            return session;
        });
    }

    static WeightCalculator calculator() {
        // 会话与 Burden API 共用同一个无状态计算器；初始化前调用属于接线错误。
        var current = calculator;
        if (current == null) {
            throw new IllegalStateException("重量附属尚未初始化");
        }
        return current;
    }

    static void forget(WeightSession session) {
        // 关闭的连接退出 tick 集合，下一次登录创建新的订阅和修订号。
        SESSIONS.remove(session);
    }
}
