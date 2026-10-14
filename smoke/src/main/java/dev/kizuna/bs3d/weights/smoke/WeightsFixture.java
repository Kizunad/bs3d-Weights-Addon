package dev.kizuna.bs3d.weights.smoke;

import com.google.gson.JsonObject;
import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.item.ItemMetadata;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.protocol.Volumes;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Item;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Mount;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.WornContainer;
import dev.kizuna.backpack3d.server.PlayerAccount;
import dev.kizuna.bs3d.weights.server.WeightRules;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 只存在于 smoke 源集的服务器夹具，不进入生产 JAR。 */
public final class WeightsFixture implements ModInitializer {
    private static final int ADMIN_PERMISSION_LEVEL = 2;

    @Override
    public void onInitialize() {
        // 单机与专用服务端共用夹具；数据始终由实际服务器玩家及库存事务创建。
        var weight = new JsonObject();
        weight.addProperty("grams", 375);
        ItemMetadata.register(Items.GOLD_INGOT, "", Map.of(WeightRules.WEIGHT_FIELD, weight));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> seedInventory(handler.player));
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> {
            dispatcher.register(CommandManager.literal("weights-fixture-reload")
                    .requires(source -> source.hasPermissionLevel(ADMIN_PERMISSION_LEVEL))
                    .executes(context -> {
                        // 用真实数据包热重载改重量，客户端不会读取此文件。
                        var server = context.getSource().getServer();
                        writeReloadPack(server);
                        server.getCommandManager().executeWithPrefix(server.getCommandSource(), "reload");
                        return 1;
                    }));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            // 专用夹具在客户端完成后正常停服；单机由 Minecraft 自己保存并关闭。
            if (server.isDedicated()) {
                server.execute(() -> server.stop(false));
            }
        });
    }

    private static void seedInventory(ServerPlayerEntity player) {
        // 仅首次进入隔离世界时建夹具；重开世界必须保留之前的真实提交。
        player.getServer().getPlayerManager().addToOperators(player.getGameProfile());
        var account = new PlayerAccount(player);
        if (account.data().getRevision() != 0) {
            return;
        }
        var pouch = filled(ContainerItems.POUCH, new ItemStack(Items.STONE));
        var basket = filled(ContainerItems.BASKET, pouch);
        var slots = account.inventory();
        for (int slot = 0; slot < slots.size(); slot++) {
            slots.set(slot, ItemStack.EMPTY);
        }
        slots.set(0, new ItemStack(Items.STONE, 2));
        slots.set(1, basket);
        slots.set(2, new ItemStack(Items.GOLD_INGOT, 2));
        var worn = ContainerItems.create(ContainerItems.POUCH);
        var data = account.data().toBuilder()
                .setRevision(1)
                .addWorn(WornContainer.newBuilder()
                        .setMount(Mount.WAIST_RIGHT_FRONT)
                        .setStackNbt(StackCodec.encode(worn)))
                .build();
        try {
            account.commit(data, slots);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void writeReloadPack(MinecraftServer server) {
        // 单机和专服都写入本次世界的数据包目录，不改用户配置或基础规则文件。
        var pack = server.getSavePath(WorldSavePath.ROOT).resolve("datapacks/weights-smoke");
        try {
            Files.createDirectories(pack.resolve("data/smoke/bs3d_weights/weights"));
            Files.writeString(pack.resolve("pack.mcmeta"), """
                    {"pack":{"pack_format":15,"description":"Weights smoke"}}
                    """);
            Files.writeString(pack.resolve("data/smoke/bs3d_weights/weights/stone.json"), """
                    {"item":"minecraft:stone","grams":2000,"priority":100}
                    """);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static ItemStack filled(Identifier type, ItemStack child) {
        // 保留子容器实例身份，普通物品才生成独立条目身份；占格使用 BS3D 定义。
        var container = ContainerItems.create(type);
        var definition = ContainerItems.definition(child);
        var size = definition == null ? Volumes.UNIT : definition.outside();
        var id = definition == null ? UUID.randomUUID().toString() : ContainerItems.identity(child);
        var contents = Item.newBuilder()
                .setId(id)
                .setContainer(ContainerItems.identity(container))
                .setStackNbt(StackCodec.encode(child))
                .setBaseSize(size)
                .setSize(size)
                .build();
        ContainerItems.setContents(container, List.of(contents));
        return container;
    }
}
