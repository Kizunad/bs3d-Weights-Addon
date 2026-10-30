package dev.kizuna.bs3d.weights.server;

import org.tomlj.Toml;
import org.tomlj.TomlTable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;

/** burden.toml 的系数表：每个来源的实重乘以系数，四舍五入到整克后相加。 */
final class BurdenFactors {
    /** 随 JAR 提供的默认系数表，首次启动时复制到配置目录。 */
    static final String DEFAULT_RESOURCE = "/bs3d_weights/burden.toml";
    private final Map<BurdenSource, BigDecimal> factors;

    private BurdenFactors(Map<BurdenSource, BigDecimal> factors) {
        this.factors = factors;
    }

    static BurdenFactors load(Path path) {
        // 首次启动复制默认表；之后只读取，不覆盖服主调整过的文件。
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path.getParent());
                try (InputStream source = BurdenFactors.class.getResourceAsStream(DEFAULT_RESOURCE)) {
                    Files.copy(source, path);
                }
            }
            return parse(Files.readString(path, StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new UncheckedIOException("无法读取负重系数 " + path, failure);
        }
    }

    static BurdenFactors parse(String text) {
        // 每个来源都必须显式配置；语法错误、缺键或多余键都拒绝整份表，代码里没有默认系数。
        var table = Toml.parse(text);
        if (table.hasErrors()) {
            throw new IllegalArgumentException("负重系数不是合法 TOML：" + table.errors().get(0).getMessage());
        }
        var factors = new EnumMap<BurdenSource, BigDecimal>(BurdenSource.class);
        var unknown = new HashSet<>(table.keySet());
        for (var source : BurdenSource.values()) {
            factors.put(source, factor(table, source.key()));
            unknown.remove(source.key());
        }
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("负重系数含有未知键：" + unknown);
        }
        return new BurdenFactors(factors);
    }

    private static BigDecimal factor(TomlTable table, String key) {
        // 整数和小数都接受；缺键、非数字、负数、nan 与 inf 都拒绝。
        Object value = table.get(key);
        if (!(value instanceof Long) && !(value instanceof Double)) {
            throw new IllegalArgumentException("负重系数 " + key + " 必须是数字");
        }
        double number = ((Number) value).doubleValue();
        if (!Double.isFinite(number) || number < 0) {
            throw new IllegalArgumentException("负重系数 " + key + " 必须是非负有限数");
        }
        return BigDecimal.valueOf(number);
    }

    long effectiveGrams(Map<BurdenSource, Long> carriedGrams) {
        // 先按来源合计实重再乘系数，每个来源只舍入一次；超出 int64 时抛错，不发布截断值。
        long total = 0;
        for (var entry : carriedGrams.entrySet()) {
            BigDecimal weighted = factors.get(entry.getKey()).multiply(BigDecimal.valueOf(entry.getValue()));
            long rounded = weighted.setScale(0, RoundingMode.HALF_UP).longValueExact();
            total = Math.addExact(total, rounded);
        }
        return total;
    }
}
