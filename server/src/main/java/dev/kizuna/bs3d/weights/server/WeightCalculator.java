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
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;

/**
 * 从真实库存树自底向上累计，每个根只加入总重一次；有效负重按根所在来源加权。
 *
 * <p>BS3D 容器和原版容器（潜影盒、收纳袋等把物品存在组件里的物品）的内容都递归计入所在物品。
 */
public final class WeightCalculator {
    // 每次计算允许访问的非空物品栈数量，限制损坏库存带来的遍历工作量。
    private static final int MAX_VISITED_STACKS = 4096;
    // 一段连续原版容器里最深一层的序号。原版规则下最外层是潜影盒（序号 0），潜影盒不能装潜影盒、收纳袋不能装潜影盒；
    // 收纳袋套收纳袋时，里层在外层占 1/16 加上它自己的内容，外层最多装满 1，所以最多再套 17 层收纳袋（序号 1–17）。
    private static final int MAX_BOX_NESTING_DEPTH = 17;
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
            walk.stack(stack, Location.GROUND, Nesting.ROOT);
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

    /**
     * 当前物品所在路径上，紧挨着的 BS3D 容器层数和原版容器层数。
     *
     * <p>两种容器各自只限制连续的一段：BS3D 只在自己的容器树里限层数，放进潜影盒的 BS3D 背包拿出来就是新的根；
     * 原版规则只管潜影盒和收纳袋之间怎么套。所以跨进另一种容器时，另一种的层数从零数起。
     *
     * @param containers 外面紧挨着的 BS3D 容器层数，根上为 0
     * @param boxes 外面紧挨着的原版容器层数，根上为 0
     */
    private record Nesting(int containers, int boxes) {
        private static final Nesting ROOT = new Nesting(0, 0);

        private Nesting insideContainer() {
            return new Nesting(containers + 1, 0);
        }

        private Nesting insideBox() {
            return new Nesting(0, boxes + 1);
        }
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
            long grams = stack(stack, Location.CARRIED, Nesting.ROOT);
            total = Math.addExact(total, grams);
            return grams;
        }

        private void carry(ItemStack stack, BurdenSource source) {
            // 同一来源的多个根先合计实重，系数在全部遍历完成后统一套用。
            long grams = carry(stack);
            carried.merge(source, grams, Math::addExact);
        }

        private long stack(ItemStack stack, Location location, Nesting nesting) {
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
            if (ContainerItems.definition(stack) != null) {
                return container(stack, location, nesting, own);
            }
            List<ItemStackTemplate> boxed = boxContents(stack);
            if (boxed.isEmpty()) {
                return own;
            }
            return box(stack, boxed, location, nesting, own);
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

        private long container(ItemStack stack, Location location, Nesting nesting, long own) {
            // 嵌套上限针对容器层数，最深容器内的普通物品仍须正常计重。
            if (nesting.containers() > ContainerItems.MAX_NESTING_DEPTH) {
                throw new IllegalArgumentException("容器嵌套层数超过上限");
            }

            String id = ContainerItems.identity(stack);
            requireIdentity(id);
            long contents = 0;
            for (var item : ContainerItems.contents(stack).getItemsList()) {
                var child = StackCodec.decode(item.getStackNbt(), registries);
                contents = Math.addExact(contents, stack(child, location, nesting.insideContainer()));
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

        private long box(ItemStack stack, List<ItemStackTemplate> boxed, Location location, Nesting nesting,
                         long own) {
            // 原版容器没有实例身份，也不进明细；内容只算进装着它的那件物品，再随它进所在的根或 BS3D 容器。
            if (nesting.boxes() > MAX_BOX_NESTING_DEPTH) {
                throw new IllegalArgumentException("潜影盒、收纳袋嵌套层数超过上限");
            }
            long contents = 0;
            // 一堆里每件都装着同一份内容，按件逐一展开：内容按件数计重，里面的 BS3D 实例也照样查重复。
            for (int copy = 0; copy < stack.getCount(); copy++) {
                for (var item : boxed) {
                    contents = Math.addExact(contents, stack(item.create(), location, nesting.insideBox()));
                }
            }
            return Math.addExact(own, contents);
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

    private static List<ItemStackTemplate> boxContents(ItemStack stack) {
        // 按原版组件判断，不按物品类型：十七种潜影盒、各色收纳袋，以及命令给出的带内容箱子、饰纹陶罐都在内。
        // 两个组件都带时两份都算，不让一份藏在另一份后面。
        List<ItemStackTemplate> items = new ArrayList<>();
        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null) {
            items.addAll(bundle.items());
        }
        ItemContainerContents container = stack.get(DataComponents.CONTAINER);
        if (container != null) {
            container.nonEmptyItems().forEach(items::add);
        }
        return items;
    }
}
