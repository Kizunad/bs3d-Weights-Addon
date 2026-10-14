package dev.kizuna.bs3d.weights.server;

import com.google.gson.JsonParser;
import dev.kizuna.backpack3d.item.ItemMetadata;
import dev.kizuna.backpack3d.protocol.pb.VolumeProtocol.SavedInventory;
import dev.kizuna.backpack3d.server.InventoryAccess;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RegisteredWeightsTest {
    private static List<Item> invalidItems;

    @BeforeAll
    static void bootstrap() {
        // 不覆盖其他测试使用的原版条目，每个注册一次的物品代表一种配置。
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        invalidItems = List.of(Items.COAL, Items.CHARCOAL, Items.COPPER_INGOT, Items.IRON_NUGGET,
                Items.GOLD_NUGGET, Items.QUARTZ, Items.AMETHYST_SHARD);
        register(Items.GOLD_INGOT, "{\"grams\":375}");
        register(Items.NETHER_STAR, "{\"grams\":0}");
        register(Items.DIAMOND, "{\"grams\":1000000000}");
        var invalid = List.of("{}", "{\"grams\":-1}", "{\"grams\":1.5}", "{\"grams\":\"100\"}",
                "{\"grams\":1000000001}", "{\"grams\":9223372036854775808}", "{\"grams\":null}");
        for (int index = 0; index < invalidItems.size(); index++) {
            register(invalidItems.get(index), invalid.get(index));
        }
        ItemMetadata.register(Items.EMERALD, "", Map.of(
                new Identifier("another_addon", "weight"),
                JsonParser.parseString("{\"grams\":999}").getAsJsonObject()));
    }

    @Test
    void registeredWeightWinsOverDataPackAndFeedsServerCalculation() {
        var rules = new WeightRules();
        rules.reload(rule("minecraft:gold_ingot", "900"));
        var stack = new ItemStack(Items.GOLD_INGOT, 3);
        var inventory = new InventoryAccess.Snapshot(
                SavedInventory.getDefaultInstance(), List.of(stack), Map.of());
        var state = new WeightCalculator(rules::unitGrams).calculate(inventory, ItemStack.EMPTY);
        assertEquals(1125, state.getTotalGrams());
    }

    @Test
    void zeroAndMaximumAreValidRegisteredWeights() {
        var rules = new WeightRules();
        assertEquals(0, rules.unitGrams(new ItemStack(Items.NETHER_STAR)));
        assertEquals(1_000_000_000L, rules.unitGrams(new ItemStack(Items.DIAMOND)));
    }

    @Test
    void invalidRegisteredFieldsWarnAndFallBack() {
        var rules = new WeightRules();
        rules.reload(rule("minecraft:coal", "80"));
        try (var warnings = new Warnings()) {
            for (var item : invalidItems) {
                long expected = item == Items.COAL ? 80 : WeightRules.DEFAULT_UNIT_GRAMS;
                assertEquals(expected, rules.unitGrams(new ItemStack(item)));
                assertEquals(expected, rules.unitGrams(new ItemStack(item)));
            }
            assertEquals(invalidItems.size(), warnings.messages.size());
            assertTrue(warnings.messages.stream().allMatch(message ->
                    message.contains("bs3d_weights:weight") && message.contains("无效")));
        }
    }

    @Test
    void missingFieldWarnsOnceUntilSuccessfulReloadAndUsesDefault() {
        var rules = new WeightRules();
        try (var warnings = new Warnings()) {
            // 同名但不同命名空间的附属字段不能冒充本附属重量，空栈不触发告警。
            assertEquals(0, rules.unitGrams(ItemStack.EMPTY));
            assertEquals(100, rules.unitGrams(new ItemStack(Items.EMERALD)));
            assertEquals(100, rules.unitGrams(new ItemStack(Items.EMERALD)));
            assertEquals(1, warnings.messages.size());
            assertTrue(warnings.messages.get(0).contains("minecraft:emerald"));
            assertTrue(warnings.messages.get(0).contains("未注册 optional 字段 bs3d_weights:weight"));

            assertThrows(IllegalArgumentException.class,
                    () -> rules.reload(rule("minecraft:emerald", "-1")));
            assertEquals(100, rules.unitGrams(new ItemStack(Items.EMERALD)));
            assertEquals(1, warnings.messages.size());

            rules.reload(rule("minecraft:emerald", "700"));
            assertEquals(700, rules.unitGrams(new ItemStack(Items.EMERALD)));
            assertEquals(2, warnings.messages.size());
        }
    }

    @Test
    void stackNbtCannotOverrideServerRegistrationOrFallback() {
        var rules = new WeightRules();
        for (var item : List.of(Items.GOLD_INGOT, Items.EMERALD)) {
            var stack = new ItemStack(item);
            var forged = new NbtCompound();
            forged.putLong("grams", 0);
            stack.getOrCreateSubNbt("optional").put("bs3d_weights:weight", forged);
            assertEquals(item == Items.GOLD_INGOT ? 375 : 100, rules.unitGrams(stack));
        }
    }

    private static void register(Item item, String json) {
        ItemMetadata.register(item, "", Map.of(WeightRules.WEIGHT_FIELD,
                JsonParser.parseString(json).getAsJsonObject()));
    }

    private static TestResources rule(String item, String grams) {
        var id = new Identifier("test", "bs3d_weights/weights/registered.json");
        byte[] bytes = ("{\"item\":\"" + item + "\",\"grams\":" + grams + "}")
                .getBytes(StandardCharsets.UTF_8);
        return new TestResources(Map.of(id, new Resource(null, () -> new ByteArrayInputStream(bytes))));
    }

    private static final class Warnings extends AbstractAppender implements AutoCloseable {
        private final Logger logger = (Logger) LogManager.getLogger(WeightRules.class);
        private final List<String> messages = new ArrayList<>();

        Warnings() {
            super("registered-weight-warnings", null, null, false, Property.EMPTY_ARRAY);
            // 捕获生产 logger 的真实 WARN，关闭时恢复，不能把日志次数断言替换为内部缓存检查。
            start();
            logger.addAppender(this);
        }

        @Override
        public void append(LogEvent event) {
            // 忽略该 logger 的非警告事件，断言只针对缺字段和无效字段的用户诊断。
            if (event.getLevel() == Level.WARN) {
                messages.add(event.getMessage().getFormattedMessage());
            }
        }

        @Override
        public void close() {
            logger.removeAppender(this);
            stop();
        }
    }
}
