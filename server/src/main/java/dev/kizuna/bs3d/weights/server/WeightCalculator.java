package dev.kizuna.bs3d.weights.server;

import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.item.WearableItems;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.server.InventoryAccess;
import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.ContainerWeight;
import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.Status;
import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.WeightState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToLongFunction;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;

/** 从真实库存树自底向上累计，每个根只加入总重一次；有效负重按根所在来源加权。 */
public final class WeightCalculator {
    // 每次计算允许访问的非空物品栈数量，限制损坏库存带来的遍历工作量。
    private static final int MAX_VISITED_STACKS = 4096;
    private final ToLongFunction<ItemStack> unitGrams;
    private final BurdenSlots slots;
    private final BurdenFactors factors;

    WeightCalculator(ToLongFunction<ItemStack> unitGrams, BurdenSlots slots, BurdenFactors factors) {
        this.unitGrams = unitGrams;
        this.slots = slots;
        this.factors = factors;
    }

    public WeightState calculate(InventoryAccess.Snapshot inventory, ItemStack cursor,
                                 HolderLookup.Provider registries) {
        // 每次计算使用独立遍历状态；损坏树必须抛错，不能发布看似正常的部分总重。
        // 容器内容按数据组件保存，解码须用玩家所在服务器的注册表。
        var walk = new Walk(registries);
        carryPlayer(walk, inventory.playerSlots());
        walk.carry(cursor, BurdenSource.CURSOR);
        carryWorn(walk, inventory, registries);
        // 地面容器仅产生明细，其子树沿用地面来源，不能混入随身总重。
        for (var stack : inventory.ground().values()) {
            walk.stack(stack, Location.GROUND, 0);
        }
        walk.containers.sort(Comparator.comparing(ContainerWeight::getContainerId));
        return WeightState.newBuilder()
                .setStatus(Status.OK)
                .setTotalGrams(walk.total)
                .setEffectiveGrams(factors.effectiveGrams(walk.carried))
                .addAllContainers(walk.containers)
                .build();
    }

    private void carryPlayer(Walk walk, List<ItemStack> playerSlots) {
        // 原版槽号决定来源；Pocket 隐藏的旧槽仍保留实重。
        for (int slot = 0; slot < playerSlots.size(); slot++) {
            var stack = playerSlots.get(slot);
            var source = slots.source(slot);
            if (source.isPresent()) {
                walk.carry(stack, source.get());
            } else {
                walk.carry(stack);
            }
        }
    }

    private static void carryWorn(Walk walk, InventoryAccess.Snapshot inventory, HolderLookup.Provider registries) {
        // 保存根代表真实物品，外观目录不参与计重。
        var equipment = inventory.equipment();
        for (var worn : equipment.getWornList()) {
            walk.carry(StackCodec.decode(worn.getStackNbt(), registries), BurdenSource.WORN_CONTAINERS);
        }
        for (var worn : equipment.getWornWearablesList()) {
            // 每个穿搭根只有一件真实栈；骨段、甲层和成套部件都不是额外物品。
            walk.carry(StackCodec.decode(worn.getStackNbt(), registries), BurdenSource.ARMOR);
        }
    }

    private enum Location {
        CARRIED,
        GROUND
    }

    private final class Walk {
        private final HolderLookup.Provider registries;
        private final Set<String> identities = new HashSet<>();
        private final List<ContainerWeight> containers = new ArrayList<>();
        private final Map<BurdenSource, Long> carried = new EnumMap<>(BurdenSource.class);
        private long total;
        private int visitedStacks;

        private Walk(HolderLookup.Provider registries) {
            this.registries = registries;
        }

        private long carry(ItemStack stack) {
            // 随身根只在这里加入实重，嵌套内容已由递归累计到根上。
            long grams = stack(stack, Location.CARRIED, 0);
            total = Math.addExact(total, grams);
            return grams;
        }

        private void carry(ItemStack stack, BurdenSource source) {
            // 同一来源的多个根先合计实重，系数在全部遍历完成后统一套用。
            long grams = carry(stack);
            carried.merge(source, grams, Math::addExact);
        }

        private long stack(ItemStack stack, Location location, int depth) {
            // 空槽不计重；每件非空栈限制工作量与堆叠数量。
            if (stack.isEmpty()) {
                return 0;
            }
            long own = ownGrams(stack);
            if (WearableItems.definition(stack) != null) {
                // 刚生成的栈可能尚未经历 inventoryTick；只初始化本次读取副本，损坏的已有身份仍拒绝。
                var initialized = stack.copy();
                WearableItems.initializeStack(initialized);
                requireIdentity(WearableItems.identity(initialized));
            }
            if (ContainerItems.definition(stack) == null) {
                return own;
            }

            return container(stack, location, depth, own);
        }

        private long ownGrams(ItemStack stack) {
            // 工作量和数值边界在递归展开前统一检查，失败不能返回部分重量。
            visitedStacks++;
            if (visitedStacks > MAX_VISITED_STACKS) {
                throw new IllegalArgumentException("重量计算访问的物品栈数量超过上限");
            }
            if (stack.getCount() > stack.getMaxStackSize()) {
                throw new IllegalArgumentException("物品堆叠数量超过该物品的上限");
            }
            long unit = unitGrams.applyAsLong(stack);
            if (unit < 0 || unit > WeightRule.MAX_UNIT_GRAMS) {
                throw new IllegalArgumentException("单件重量超出范围");
            }
            return Math.multiplyExact(unit, stack.getCount());
        }

        private long container(ItemStack stack, Location location, int depth, long own) {
            // 嵌套上限针对容器层数，最深容器内的普通物品仍须正常计重。
            if (depth > ContainerItems.MAX_NESTING_DEPTH) {
                throw new IllegalArgumentException("容器嵌套层数超过上限");
            }

            String id = ContainerItems.identity(stack);
            requireIdentity(id);
            long contents = 0;
            for (var item : ContainerItems.contents(stack).getItemsList()) {
                var child = StackCodec.decode(item.getStackNbt(), registries);
                contents = Math.addExact(contents, stack(child, location, depth + 1));
            }
            long total = Math.addExact(own, contents);
            containers.add(ContainerWeight.newBuilder()
                    .setContainerId(id)
                    .setTitle(stack.getHoverName().getString())
                    .setContentsGrams(contents)
                    .setTotalGrams(total)
                    .setCarried(location == Location.CARRIED)
                    .build());
            return total;
        }

        private void requireIdentity(String id) {
            // 穿戴、手持、光标和嵌套子树共享身份集合，重复实例不能发布双计结果。
            if (!identities.add(id)) {
                throw new IllegalArgumentException("容器或穿搭实例身份重复：" + id);
            }
            if (identities.size() > ContainerItems.MAX_CONTAINERS) {
                throw new IllegalArgumentException("容器与穿搭数量超过上限");
            }
        }
    }
}
