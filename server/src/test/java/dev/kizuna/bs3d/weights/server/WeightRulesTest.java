package dev.kizuna.bs3d.weights.server;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.Item;
import net.minecraft.resource.Resource;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WeightRulesTest {
    @BeforeAll
    static void bootstrap() {
        // 物品 ID 和 tag 校验依赖真实原版注册表。
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    @Test
    void itemRulesAndFallbackAndRemoval() {
        var rules = new WeightRules();
        rules.reload(resources(Map.of("stone", rule("1000", 0))));
        assertEquals(1000, rules.unitGrams(new ItemStack(Items.STONE)));
        assertEquals(100, rules.unitGrams(new ItemStack(Items.STICK)));
        rules.reload(resources(Map.of()));
        assertEquals(100, rules.unitGrams(new ItemStack(Items.STONE)));
    }

    @Test
    void priorityThenResourceIdDecidesWinner() {
        var rules = new WeightRules();
        rules.reload(resources(Map.of("z", rule("900", 2), "a", rule("800", 2), "b", rule("700", 1))));
        assertEquals(800, rules.unitGrams(new ItemStack(Items.STONE)));
    }

    @Test
    void realItemTagMatches() {
        // 保存原标签并在 finally 恢复，不让测试标签污染同进程的其他测试。
        var original = new LinkedHashMap<TagKey<Item>, List<RegistryEntry<Item>>>();
        Registries.ITEM.streamTagsAndEntries().forEach(entry ->
                original.put(entry.getFirst(), entry.getSecond().stream().toList()));
        var tags = new LinkedHashMap<>(original);
        tags.put(TagKey.of(RegistryKeys.ITEM, new Identifier("test", "heavy")), List.of(Registries.ITEM.getEntry(Items.STONE)));
        try {
            Registries.ITEM.populateTags(tags);
            var rules = new WeightRules();
            rules.reload(resources(Map.of("tag", "{\"tag\":\"test:heavy\",\"grams\":500}")));
            assertEquals(500, rules.unitGrams(new ItemStack(Items.STONE)));
        } finally {
            Registries.ITEM.populateTags(original);
        }
    }

    @Test
    void invalidReloadKeepsCompletePreviousRules() {
        // 一次重载包含好坏两条规则；坏规则必须阻止整个新目录替换旧目录。
        var rules = new WeightRules();
        rules.reload(resources(Map.of("old", rule("500", 0))));
        for (var grams : List.of("-1", "1.5", "\"100\"", "1000000001", "9223372036854775808")) {
            var failure = assertThrows(IllegalArgumentException.class, () -> rules.reload(resources(Map.of(
                    "good", rule("700", 1), "bad", rule(grams, 0)))));
            assertTrue(failure.getMessage().contains("bad.json"));
            assertEquals(500, rules.unitGrams(new ItemStack(Items.STONE)));
        }
    }

    @Test
    void invalidFieldsRejectedAndZeroAccepted() {
        // 错误选择器和溢出优先级拒绝；零克是合法配置。
        var rules = new WeightRules();
        for (var json : List.of("{\"grams\":1}", "{\"item\":\"test:missing\",\"grams\":1}",
                "{\"item\":\"minecraft:stone\",\"tag\":\"test:x\",\"grams\":1}",
                "{\"item\":\"minecraft:stone\",\"grams\":1,\"priority\":2147483648}",
                "{\"item\":\"minecraft:stone\",\"grams\":1,\"priority\":-2147483649}")) {
            assertThrows(IllegalArgumentException.class, () -> rules.reload(resources(Map.of("bad", json))));
        }
        rules.reload(resources(Map.of("zero", rule("0", 0))));
        assertEquals(0, rules.unitGrams(new ItemStack(Items.STONE)));
    }

    private static String rule(String grams, int priority) {
        return "{\"item\":\"minecraft:stone\",\"grams\":" + grams + ",\"priority\":" + priority + "}";
    }

    private static TestResources resources(Map<String, String> files) {
        // 将每条 JSON 封装为独立可关闭的资源流，不依赖磁盘夹具。
        var resources = new LinkedHashMap<Identifier, Resource>();
        for (var entry : files.entrySet()) {
            var id = new Identifier("test", "bs3d_weights/weights/" + entry.getKey() + ".json");
            byte[] bytes = entry.getValue().getBytes(StandardCharsets.UTF_8);
            resources.put(id, new Resource(null, () -> new ByteArrayInputStream(bytes)));
        }
        return new TestResources(resources);
    }
}
