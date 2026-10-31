package dev.kizuna.bs3d.weights.server;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.entity.player.Inventory;
import java.util.Optional;

/**
 * 按原版槽号划分有效负重来源。
 *
 * @param mainEnd 主库存结束槽号（不含）；装了 Pocket 时只到快捷栏后的第一行
 */
record BurdenSlots(int mainEnd) {
    static final String POCKET_MOD_ID = "bs3d_pocket";
    // 与 bs3d_pocket 的 PocketSlots.VISIBLE_MAIN_SLOTS 相同：快捷栏之外只保留一行 9 格。
    static final int POCKET_MAIN_SLOTS = 9;
    static final BurdenSlots VANILLA = new BurdenSlots(Inventory.INVENTORY_SIZE);
    static final BurdenSlots POCKET = new BurdenSlots(Inventory.getSelectionSize() + POCKET_MAIN_SLOTS);

    static BurdenSlots detect() {
        // 只看模组是否加载，不反射读取 Pocket 的类；两种范围都由本类常量给出。
        return FabricLoader.getInstance().isModLoaded(POCKET_MOD_ID) ? POCKET : VANILLA;
    }

    Optional<BurdenSource> source(int slot) {
        // 原版槽号依次为快捷栏、主库存、盔甲、副手；Pocket 已移除的旧主库存槽不属于任何来源。
        if (slot < 0 || slot > Inventory.SLOT_OFFHAND) {
            throw new IllegalArgumentException("槽号超出原版玩家库存：" + slot);
        }
        if (slot < Inventory.getSelectionSize()) {
            return Optional.of(BurdenSource.HOTBAR);
        }
        if (slot < mainEnd) {
            return Optional.of(BurdenSource.MAIN);
        }
        if (slot < Inventory.INVENTORY_SIZE) {
            return Optional.empty();
        }
        if (slot < Inventory.SLOT_OFFHAND) {
            return Optional.of(BurdenSource.ARMOR);
        }
        return Optional.of(BurdenSource.OFFHAND);
    }
}
