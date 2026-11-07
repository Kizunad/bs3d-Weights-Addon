package dev.kizuna.bs3d.weights.server;

import dev.kizuna.backpack3d.item.ContainerItems;
import dev.kizuna.backpack3d.item.ItemRegistrations;
import dev.kizuna.backpack3d.item.WearableItems;
import dev.kizuna.backpack3d.protocol.StackCodec;
import dev.kizuna.backpack3d.protocol.Volumes;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.Item;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.SavedInventory;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.WornWearable;
import dev.kizuna.backpack3d.server.InventoryAccess;
import dev.kizuna.backpack3d.service.TransferService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;
import net.minecraft.core.component.DataComponentInitializers;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 实例计重与来源口径回归；夹具克数只用于区分根物品，不是平衡参数。 */
class WearableWeightTest {
    private static final Identifier SUIT = Identifier.parse("weights_test:suit");
    private static final Identifier BASKET = Identifier.parse("bs3d:gu_jia_bei_lou");
    private static final long SUIT_GRAMS = 730;
    private static final long SMALL_GRAMS = 110;

    @BeforeAll
    static void bootstrap() {
        TestRegistries.bootstrap();
        ItemRegistrations.registerText("weights-wearable", """
                version: 1
                items { id: "weights_test:suit" wearable {
                  title: "多层套装" model: "weights_test:suit"
                  slots { layer: "armor_head" bone: "head" }
                  slots { layer: "armor_body" bone: "chest" segment: "body" }
                  slots { layer: "armor_body" bone: "l_upper_arm" segment: "arm" }
                  slots { layer: "armor_legs" bone: "l_thigh" }
                  slots { layer: "armor_feet" bone: "l_foot" }
                  slots { layer: "cape" bone: "chest" }
                } }
                """);
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(TestRegistries.VANILLA)
                .forEach(DataComponentInitializers.PendingComponents::apply);
    }

    @Test
    void multiLayerSuitIsOneRootAndSurvivesSerialization() throws Exception {
        var suit = WearableItems.create(SUIT);
        var saved = saved(suit);
        var restored = SavedInventory.parseFrom(saved.toByteArray());
        var state = calculator().calculate(snapshot(List.of(), restored), ItemStack.EMPTY, TestRegistries.VANILLA);
        assertEquals(SUIT_GRAMS, state.getTotalGrams());
        assertEquals(SUIT_GRAMS, state.getEffectiveGrams());
        assertEquals(0, state.getContainersCount());
    }

    @Test
    void movingFromHotbarToWornAndContainerPreservesOneActualWeight() throws IOException {
        var suit = WearableItems.create(SUIT);
        var calculator = calculator();
        var hotbar = calculator.calculate(snapshot(List.of(suit), TransferService.empty()),
                ItemStack.EMPTY, TestRegistries.VANILLA);
        var worn = calculator.calculate(snapshot(List.of(), saved(suit)), ItemStack.EMPTY, TestRegistries.VANILLA);
        var basket = ContainerItems.create(BASKET);
        var child = Item.newBuilder()
                .setId(WearableItems.identity(suit))
                .setContainer(ContainerItems.identity(basket))
                .setStackNbt(StackCodec.encode(suit, TestRegistries.VANILLA))
                .setBaseSize(Volumes.UNIT)
                .setSize(Volumes.UNIT)
                .build();
        ContainerItems.setContents(basket, List.of(child));
        var packed = calculator.calculate(snapshot(List.of(basket), TransferService.empty()),
                ItemStack.EMPTY, TestRegistries.VANILLA);
        assertEquals(SUIT_GRAMS, hotbar.getTotalGrams());
        assertEquals(0, hotbar.getEffectiveGrams());
        assertEquals(hotbar.getTotalGrams(), worn.getTotalGrams());
        assertEquals(SUIT_GRAMS, worn.getEffectiveGrams());
        assertEquals(SUIT_GRAMS + SMALL_GRAMS, packed.getTotalGrams());
        assertEquals(SUIT_GRAMS, packed.getContainers(0).getContentsGrams());
    }

    @Test
    void duplicateAcrossWornInventoryAndCursorRejectsWholeReading() throws IOException {
        var suit = WearableItems.create(SUIT);
        var calculator = calculator();
        var worn = saved(suit);
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(
                snapshot(List.of(suit.copy()), worn), ItemStack.EMPTY, TestRegistries.VANILLA));
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(
                snapshot(List.of(), worn), suit.copy(), TestRegistries.VANILLA));
    }

    @Test
    void newCursorWearableCountsWithoutMutatingItsIdentity() throws IOException {
        var fresh = new ItemStack(BuiltInRegistries.ITEM.getValue(SUIT));
        var before = fresh.copy();
        var state = calculator().calculate(snapshot(List.of(), TransferService.empty()),
                fresh, TestRegistries.VANILLA);
        assertEquals(SUIT_GRAMS, state.getTotalGrams());
        assertEquals(SUIT_GRAMS, state.getEffectiveGrams());
        assertTrue(ItemStack.matches(before, fresh));
        assertThrows(IllegalArgumentException.class, () -> WearableItems.identity(fresh));
    }

    @Test
    void existingCorruptWearableIdentityDoesNotReceiveReplacement() throws IOException {
        var corrupt = WearableItems.create(SUIT);
        CustomData.update(DataComponents.CUSTOM_DATA, corrupt,
                root -> root.put("bs3d_wearable", new CompoundTag()));
        var calculator = calculator();
        assertThrows(IllegalArgumentException.class, () -> calculator.calculate(
                snapshot(List.of(), TransferService.empty()), corrupt, TestRegistries.VANILLA));
    }

    @Test
    void everyVanillaEquipmentSlotExceptOffhandUsesArmorFactor() throws IOException {
        var calculator = calculator();
        for (var entry : Inventory.EQUIPMENT_SLOT_MAPPING.int2ObjectEntrySet()) {
            if (entry.getValue() == EquipmentSlot.OFFHAND) {
                continue;
            }
            var slots = new ArrayList<>(Collections.nCopies(BurdenSlots.PLAYER_SLOTS, ItemStack.EMPTY));
            slots.set(entry.getIntKey(), new ItemStack(Items.IRON_HELMET));
            var state = calculator.calculate(snapshot(slots, TransferService.empty()),
                    ItemStack.EMPTY, TestRegistries.VANILLA);
            assertEquals(BurdenSource.ARMOR, BurdenSlots.VANILLA.source(entry.getIntKey()).orElseThrow());
            assertEquals(SMALL_GRAMS, state.getTotalGrams());
            assertEquals(SMALL_GRAMS, state.getEffectiveGrams());
        }
    }

    @Test
    void wornEquipmentRespectsConfiguredArmorFactorWithoutChangingRealWeight() {
        var factors = BurdenFactors.parse("""
                hotbar = 0
                main = 2
                armor = 0.5
                offhand = 1
                cursor = 1
                worn_containers = 1
                """);
        var calculator = new WeightCalculator(WearableWeightTest::unitGrams, BurdenSlots.VANILLA, factors);
        var suit = WearableItems.create(SUIT);
        var slots = new ArrayList<>(Collections.nCopies(BurdenSlots.PLAYER_SLOTS, ItemStack.EMPTY));
        slots.set(Inventory.SLOT_BODY_ARMOR, new ItemStack(Items.IRON_HORSE_ARMOR));
        slots.set(Inventory.SLOT_SADDLE, new ItemStack(Items.SADDLE));
        var state = calculator.calculate(snapshot(slots, saved(suit)), ItemStack.EMPTY, TestRegistries.VANILLA);
        assertEquals(950, state.getTotalGrams());
        assertEquals(475, state.getEffectiveGrams());
    }

    private static WeightCalculator calculator() throws IOException {
        try (var stream = BurdenFactors.class.getResourceAsStream(BurdenFactors.DEFAULT_RESOURCE)) {
            var factors = BurdenFactors.parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            return new WeightCalculator(WearableWeightTest::unitGrams, BurdenSlots.VANILLA, factors);
        }
    }

    private static long unitGrams(ItemStack stack) {
        if (WearableItems.definition(stack) != null) {
            return SUIT_GRAMS;
        }
        return SMALL_GRAMS;
    }

    private static SavedInventory saved(ItemStack stack) {
        var type = WearableItems.definition(stack);
        var worn = WornWearable.newBuilder()
                .setId(WearableItems.identity(stack))
                .setStackNbt(StackCodec.encode(stack, TestRegistries.VANILLA))
                .setBaseSize(Volumes.UNIT)
                .addAllSlots(type.slots())
                .build();
        return TransferService.empty().toBuilder().addWornWearables(worn).build();
    }

    private static InventoryAccess.Snapshot snapshot(List<ItemStack> slots, SavedInventory worn) {
        return new InventoryAccess.Snapshot(worn, slots, Map.of());
    }
}
