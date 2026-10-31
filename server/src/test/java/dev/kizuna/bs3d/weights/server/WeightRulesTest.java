package dev.kizuna.bs3d.weights.server;

import net.minecraft.core.Holder;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.tags.TagKey;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
        TestRegistries.bootstrap();
        // 26.2 只有冻结后的注册表能按数据包流程绑定标签；按原版冻结顺序先把登记期标签绑为空。
        // 冻结后本 JVM 不能再登记物品，构建脚本让每个测试类独占 JVM。
        ((MappedRegistry<Item>) BuiltInRegistries.ITEM).bindAllTagsToEmpty();
        BuiltInRegistries.ITEM.freeze();
    }

    @Test
    void itemRulesAndFallbackAndRemoval() {
        var rules = new WeightRules();
        rules.onResourceManagerReload(resources(Map.of("stone", rule("1000", 0))));
        assertEquals(1000, rules.unitGrams(new ItemStack(Items.STONE)));
        assertEquals(100, rules.unitGrams(new ItemStack(Items.STICK)));
        rules.onResourceManagerReload(resources(Map.of()));
        assertEquals(100, rules.unitGrams(new ItemStack(Items.STONE)));
    }

    @Test
    void priorityThenResourceIdDecidesWinner() {
        var rules = new WeightRules();
        rules.onResourceManagerReload(resources(Map.of("z", rule("900", 2), "a", rule("800", 2), "b", rule("700", 1))));
        assertEquals(800, rules.unitGrams(new ItemStack(Items.STONE)));
    }

    @Test
    void realItemTagMatches() {
        // 保存原标签并在 finally 恢复，不让测试标签污染同进程的其他测试。
        var original = new LinkedHashMap<TagKey<Item>, List<Holder<Item>>>();
        BuiltInRegistries.ITEM.getTags().forEach(named -> original.put(named.key(), named.stream().toList()));
        var tags = new LinkedHashMap<>(original);
        tags.put(TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("test", "heavy")), List.of(BuiltInRegistries.ITEM.wrapAsHolder(Items.STONE)));
        try {
            bindTags(tags);
            var rules = new WeightRules();
            rules.onResourceManagerReload(resources(Map.of("tag", "{\"tag\":\"test:heavy\",\"grams\":500}")));
            assertEquals(500, rules.unitGrams(new ItemStack(Items.STONE)));
        } finally {
            bindTags(original);
        }
    }

    @Test
    void invalidReloadKeepsCompletePreviousRules() {
        // 一次重载包含好坏两条规则；坏规则必须阻止整个新目录替换旧目录。
        var rules = new WeightRules();
        rules.onResourceManagerReload(resources(Map.of("old", rule("500", 0))));
        for (var grams : List.of("-1", "1.5", "\"100\"", "1000000001", "9223372036854775808")) {
            var failure = assertThrows(IllegalArgumentException.class, () -> rules.onResourceManagerReload(resources(Map.of(
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
            assertThrows(IllegalArgumentException.class, () -> rules.onResourceManagerReload(resources(Map.of("bad", json))));
        }
        rules.onResourceManagerReload(resources(Map.of("zero", rule("0", 0))));
        assertEquals(0, rules.unitGrams(new ItemStack(Items.STONE)));
    }

    private static void bindTags(Map<TagKey<Item>, List<Holder<Item>>> tags) {
        // 冻结后的注册表只能走与数据包重载相同的待定标签流程替换整套标签。
        BuiltInRegistries.ITEM.prepareTagReload(new TagLoader.LoadResult<>(Registries.ITEM, tags)).apply();
    }

    private static String rule(String grams, int priority) {
        return "{\"item\":\"minecraft:stone\",\"grams\":" + grams + ",\"priority\":" + priority + "}";
    }

    private static TestResources resources(Map<String, String> files) {
        // 将每条 JSON 封装为独立可关闭的资源流，不依赖磁盘夹具。
        var resources = new LinkedHashMap<Identifier, Resource>();
        for (var entry : files.entrySet()) {
            var id = Identifier.fromNamespaceAndPath("test", "bs3d_weights/weights/" + entry.getKey() + ".json");
            byte[] bytes = entry.getValue().getBytes(StandardCharsets.UTF_8);
            resources.put(id, new Resource(null, () -> new ByteArrayInputStream(bytes)));
        }
        return new TestResources(resources);
    }
}
