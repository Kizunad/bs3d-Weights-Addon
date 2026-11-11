package dev.kizuna.bs3d.weights.server;

import dev.kizuna.bs3d.weights.protocol.pb.WeightsProtocol.WeightState;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 一名玩家最近一次重量读数。
 *
 * @param tick 读库存时的服务端 tick 计数
 * @param inputs 那次读到的输入
 * @param state 按这份输入算出的完整结果，不含连接身份和修订号
 */
record WeightReading(int tick, WeightInputs inputs, WeightState state) {

    static WeightReading next(WeightReading previous, int now, int intervalTicks, Supplier<WeightInputs> read,
                              Function<WeightInputs, WeightState> calculate) {
        // 间隔内直接复用上次读数，不读库存；到点读一次输入，和上次逐项相同就沿用结果，只记下这次读取的时刻。
        if (previous != null && now - previous.tick < intervalTicks) {
            return previous;
        }
        WeightInputs inputs = read.get();
        if (previous != null && previous.inputs.sameAs(inputs)) {
            return new WeightReading(now, inputs, previous.state);
        }
        return new WeightReading(now, inputs, calculate.apply(inputs));
    }
}
