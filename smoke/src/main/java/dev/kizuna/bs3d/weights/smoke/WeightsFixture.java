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
import net.minecraft.SharedConstants;
import net.minecraft.commands.Commands;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 只存在于 smoke 源集的服务器夹具，不进入生产 JAR。 */
public final class WeightsFixture implements ModInitializer {
    @Override
    public void onInitialize() {
        // 单机与专用服务端共用夹具；数据始终由实际服务器玩家及库存事务创建。
        var weight = new JsonObject();
        weight.addProperty("grams", 375);
        ItemMetadata.register(Items.GOLD_INGOT, "", Map.of(WeightRules.WEIGHT_FIELD, weight));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> seedInventory(handler.player));
        CommandRegistrationCallback.EVENT.register((dispatcher, registries, environment) -> {
            dispatcher.register(Commands.literal("weights-fixture-reload")
                    .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                    .executes(context -> {
                        // 用真实数据包热重载改重量，客户端不会读取此文件。
                        var server = context.getSource().getServer();
                        writeReloadPack(server);
                        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "reload");
                        return 1;
                    }));
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            // 专用夹具在客户端完成后正常停服；单机由 Minecraft 自己保存并关闭。
            if (server.isDedicatedServer()) {
                server.execute(() -> server.halt(false));
            }
        });
    }

    private static void seedInventory(ServerPlayer player) {
        // 仅首次进入隔离世界时建夹具；重开世界必须保留之前的真实提交。
        player.level().getServer().getPlayerList().op(player.nameAndId());
        var account = new PlayerAccount(player);
        if (account.data().getRevision() != 0) {
            return;
        }
        var registries = player.registryAccess();
        var pouch = filled(ContainerItems.POUCH, new ItemStack(Items.STONE), registries);
        var basket = filled(ContainerItems.BASKET, pouch, registries);
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
                        .setStackNbt(StackCodec.encode(worn, registries)))
                .build();
        try {
            account.commit(data, slots);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static void writeReloadPack(MinecraftServer server) {
        // 单机和专服都写入本次世界的数据包目录，不改用户配置或基础规则文件。
        var pack = server.getWorldPath(LevelResource.ROOT).resolve("datapacks/weights-smoke");
        try {
            Files.createDirectories(pack.resolve("data/smoke/bs3d_weights/weights"));
            // 26.x 数据包格式带次版本号，只认 min_format/max_format；取当前游戏的格式，不写死版本。
            var format = SharedConstants.getCurrentVersion().packVersion(PackType.SERVER_DATA);
            var version = "[" + format.major() + "," + format.minor() + "]";
            Files.writeString(pack.resolve("pack.mcmeta"), "{\"pack\":{\"min_format\":" + version
                    + ",\"max_format\":" + version + ",\"description\":\"Weights smoke\"}}\n");
            Files.writeString(pack.resolve("data/smoke/bs3d_weights/weights/stone.json"), """
                    {"item":"minecraft:stone","grams":2000,"priority":100}
                    """);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    private static ItemStack filled(Identifier type, ItemStack child, HolderLookup.Provider registries) {
        // 保留子容器实例身份，普通物品才生成独立条目身份；占格使用 BS3D 定义。
        var container = ContainerItems.create(type);
        var definition = ContainerItems.definition(child);
        var size = definition == null ? Volumes.UNIT : definition.outside();
        var id = definition == null ? UUID.randomUUID().toString() : ContainerItems.identity(child);
        var contents = Item.newBuilder()
                .setId(id)
                .setContainer(ContainerItems.identity(container))
                .setStackNbt(StackCodec.encode(child, registries))
                .setBaseSize(size)
                .setSize(size)
                .build();
        ContainerItems.setContents(container, List.of(contents));
        return container;
    }
}
