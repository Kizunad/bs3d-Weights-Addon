package dev.kizuna.bs3d.weights.server;

import dev.kizuna.backpack3d.item.ContainerItems;
import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;

/**
 * 单元测试没有服务器实例：引导原版注册表，登记 BS3D 容器物品，并补上 26.2 服务器数据重载时才做的物品组件绑定。
 *
 * <p>容器内容编解码使用原版内置注册表 {@link #VANILLA}。
 */
final class TestRegistries {
    static final HolderLookup.Provider VANILLA;

    static {
        // 原版查找表依赖已引导的内置注册表；放在类初始化里，任何测试首次引用都按此顺序。
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        VANILLA = VanillaRegistries.createLookup();
    }

    private TestRegistries() {}

    static void bootstrap() {
        // 物品默认组件（堆叠上限等）在绑定后才可读，登记 BS3D 物品后须重新绑定一次。
        ContainerItems.initialize();
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS
                .build(VANILLA)
                .forEach(DataComponentInitializers.PendingComponents::apply);
    }
}
