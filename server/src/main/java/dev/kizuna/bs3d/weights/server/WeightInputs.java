package dev.kizuna.bs3d.weights.server;

import dev.kizuna.backpack3d.server.InventoryAccess;
import java.util.List;
import java.util.Map;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;

/**
 * 算一次重量要读的全部东西：库存快照、光标栈、注册表和规则版本。
 *
 * <p>两份输入逐项相同，算出的重量就相同，可以直接沿用上次结果。
 *
 * @param inventory BS3D 给的库存快照，栈都是副本
 * @param cursor 屏幕光标上的栈，存副本，之后玩家再拿放不会改到它
 * @param registries 玩家所在服务器的注册表，按引用比较
 * @param rulesRevision 算这一份时的重量规则版本
 */
record WeightInputs(InventoryAccess.Snapshot inventory, ItemStack cursor, HolderLookup.Provider registries,
                    long rulesRevision) {

    static WeightInputs of(InventoryAccess.Snapshot inventory, ItemStack cursor, HolderLookup.Provider registries,
                           long rulesRevision) {
        // 光标是玩家手里的活栈，留存前先拷一份；快照里的栈 BS3D 已经给的是副本。
        return new WeightInputs(inventory, cursor.copy(), registries, rulesRevision);
    }

    boolean sameAs(WeightInputs other) {
        // 便宜的先比：注册表和规则版本，再比穿戴存档（不可变 protobuf，没变时通常是同一个对象），最后逐格比栈。
        if (registries != other.registries || rulesRevision != other.rulesRevision) {
            return false;
        }
        if (!inventory.equipment().equals(other.inventory.equipment())) {
            return false;
        }
        if (!ItemStack.matches(cursor, other.cursor)) {
            return false;
        }
        return sameStacks(inventory.playerSlots(), other.inventory.playerSlots())
                && sameGround(inventory.ground(), other.inventory.ground());
    }

    private static boolean sameStacks(List<ItemStack> left, List<ItemStack> right) {
        // 同一槽号的栈要物品、数量、组件都相同；容器内容存在组件里，所以盒子、背包里的变化也比得出来。
        if (left.size() != right.size()) {
            return false;
        }
        for (int slot = 0; slot < left.size(); slot++) {
            if (!ItemStack.matches(left.get(slot), right.get(slot))) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameGround(Map<String, ItemStack> left, Map<String, ItemStack> right) {
        // 地面容器按实例 UUID 对上，打开、关闭或走远都会让键集合变化。
        if (!left.keySet().equals(right.keySet())) {
            return false;
        }
        for (var entry : left.entrySet()) {
            if (!ItemStack.matches(entry.getValue(), right.get(entry.getKey()))) {
                return false;
            }
        }
        return true;
    }
}
