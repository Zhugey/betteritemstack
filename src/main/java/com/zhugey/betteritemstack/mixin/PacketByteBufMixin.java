package com.zhugey.betteritemstack.mixin;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.PacketByteBuf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * <p>针对 {@link PacketByteBuf} 的 Mixin：把物品在网络包里的<b>数量</b>由字节改为 VarInt。
 *
 * <h2>为什么必须改</h2>
 *
 * <p>本代（1.20 – 1.20.4）原版物品网络序列化是手写的，数量用<b>有符号字节</b>传输：
 * {@code writeItemStack} 里 {@code writeByte(stack.getCount())}，{@code readItemStack} 里
 * {@code int i = readByte()}，即只保留低 8 位。这与本 Mod 的目标直接冲突。用户实测：
 * 背包里 9999 个的物品，一打开箱子（服务端下发 {@code InventoryS2CPacket}）数量就变了——
 * 落在 128 ~ 255 会读成负数，落在 256 整数倍会读成 0，而 {@code ItemStack#isEmpty()} 对
 * {@code count <= 0} 返回 true，于是整格物品直接消失。
 *
 * <h2>改法：只动「数量」这一个指令</h2>
 *
 * <p>写侧把 {@code writeByte(count)} 换成 {@code writeVarInt(count)}（两个变体，见下）；
 * 读侧把 {@code readByte()} 换成 {@code readVarInt()} 并存入 {@link #bis$count}，
 * 再用 {@code @ModifyVariable} 在它被存进局部变量那一步把值换回真实数量。
 *
 * <p><b>为什么不整段复刻方法体</b>（本类初版就是那么写的，实测有坑）：复刻 {@code writeItemStack}
 * 就必然要自己调用 {@code PacketByteBuf#writeNbt}，而该方法在 1.20.2 换过描述符——
 * 中介名 {@code method_10794} 没变，参数类型却从 {@code NbtCompound}（{@code class_2487}）变成
 * {@code NbtElement}（{@code class_2520}）。也就是说<b>同一个中介名在不同 1.20.x 上的描述符可能不同</b>，
 * 按 1.20 编译的复刻代码跑到 1.20.2+ 会直接 {@code NoSuchMethodError}。
 * 只做重定向则原方法体原样保留，其中的调用永远指向运行版本里正确的那一个。
 *
 * <p>读侧之所以要接 {@code @ModifyVariable} 而不是等方法返回后再修：原版拿到被截断的字节后会立刻
 * {@code new ItemStack(item, 截断值)}，若截断值恰好是 0，这个栈就成了空栈、物品信息已丢失，
 * 返回后再改也救不回来。把真实数量在"存局部变量"这一步换回去，构造函数拿到的就是正确值。
 *
 * <h2>写侧为什么是两个变体</h2>
 *
 * <p>1.20.2 给 {@code PacketByteBuf} 补了一批「返回自身」的协变重载，{@code writeItemStack} 里的
 * 写数量调用因此从 {@code writeByte(I)Lio/netty/buffer/ByteBuf;} 变成了
 * {@code writeByte(I)Lnet/minecraft/network/PacketByteBuf;}——<b>同一个方法名、不同返回类型</b>。
 * 而 {@code @Redirect} 的处理函数返回类型必须与目标方法一致，一个 handler 覆盖不了两种形态，
 * 于是拆成两个 {@code require = 0} 的变体：每个版本恰好命中一个，另一个被静默跳过。
 *
 * <p>这带来一个"静默"风险：若将来两个变体都不命中，写侧会无声地退回原版行为。因此
 * 离线校验脚本 `packet_count_check.py` 会逐个版本断言
 * <b>「恰好一个变体命中且调用次数为 1」</b>，把这条从"静默"变成"可验证"。
 * （读侧的 {@code readByte()B} 描述符在全区间稳定，故正常保留 {@code require = 1} 的硬失败。）
 *
 * <h2>兼容性</h2>
 *
 * <p><b>数量 ≤ 127 时新旧编码逐字节相同</b>：VarInt 对 0 ~ 127 就是单个字节，取值与原
 * {@code writeByte} 完全一致。因此对端若没装本 Mod，只要数量没超过 127 就仍能正确解析、不会错位；
 * 只有数量 > 127 时才会不一致——而那本来也只有装了本 Mod 才会出现。
 *
 * <p>覆盖面：凡经过 {@code writeItemStack} / {@code readItemStack} 的物品传输都被处理，包括
 * {@code InventoryS2CPacket}（打开容器时的整包同步）、{@code ScreenHandlerSlotUpdateS2CPacket}
 * （单格增量同步）、{@code CreativeInventoryActionC2SPacket}（创造模式取物）、
 * {@code ClickSlotC2SPacket}（GUI 点击）等。已扫描本代原版全部 222 个网络包类，
 * 除 {@code PacketByteBuf} 外没有任何一处直接把数量写成字节。
 */
@Mixin(PacketByteBuf.class)
public abstract class PacketByteBufMixin {

    private static final String WRITE_ITEM_STACK = "writeItemStack";
    private static final String READ_ITEM_STACK = "readItemStack";

    /** 1.20 – 1.20.1：{@code writeByte} 继承自 Netty 的 {@code ByteBuf}，返回 {@code ByteBuf}。 */
    private static final String WRITE_BYTE_NETTY =
            "Lnet/minecraft/network/PacketByteBuf;writeByte(I)Lio/netty/buffer/ByteBuf;";

    /**
     * 1.20.2 – 1.20.4：{@code PacketByteBuf} 自己的协变重载，返回 {@code PacketByteBuf}。
     *
     * <p><b>这里刻意用 intermediary 名（{@code class_2540}）而不是 Yarn 名</b>：本项目的构建会把
     * 注解里的 Yarn 目标就地重映射为 intermediary（上面那条与读侧那条都已实测改写成
     * {@code Lnet/minecraft/class_2540;…}），但<b>唯独这一条实测未被改写</b>——构建后它的注解值仍是
     * {@code Lnet/minecraft/network/PacketByteBuf;writeByte(I)Lnet/minecraft/network/PacketByteBuf;}，
     * 与运行期的 {@code class_2540} 对不上，于是 {@code require = 0} 会<b>静默跳过</b>，
     * 1.20.2+ 上的修复等于没生效。直接写 intermediary 就没有"改没改写"的不确定性。
     *
     * <p>（与该条的区别：另两条字符串里的 MC 类名只出现在归属类一处，这条在描述符里又出现了一次。
     * 具体重映射规则未查明，因此以"构建后核对注解值"为准——这也是本项目一贯的判据。）
     */
    private static final String WRITE_BYTE_SELF =
            "Lnet/minecraft/class_2540;writeByte(I)Lnet/minecraft/class_2540;";

    /** 读数量的调用，全区间都是同一形态。 */
    private static final String READ_BYTE =
            "Lnet/minecraft/network/PacketByteBuf;readByte()B";

    /** 最近一次读到的真实数量，供 {@link #bis$restoreCount} 使用。 */
    @Unique
    private int bis$count;

    /**
     * 【1.20 – 1.20.1】把写数量由「一个字节」换成 VarInt。
     *
     * @param self  缓冲区自身
     * @param count 原版要写入的数量（即 {@code stack.getCount()}）
     * @return VarInt 写入的返回值（调用点会丢弃，仅为匹配原调用的返回类型）
     */
    @Redirect(method = WRITE_ITEM_STACK, require = 0,
            at = @At(value = "INVOKE", target = WRITE_BYTE_NETTY))
    private ByteBuf bis$writeCountNetty(PacketByteBuf self, int count) {
        return self.writeVarInt(count);
    }

    /**
     * 【1.20.2 – 1.20.4】同上，目标是 {@code PacketByteBuf} 自己声明的协变重载。
     *
     * @param self  缓冲区自身
     * @param count 原版要写入的数量
     * @return VarInt 写入的返回值（调用点会丢弃，仅为匹配原调用的返回类型）
     */
    @Redirect(method = WRITE_ITEM_STACK, require = 0,
            at = @At(value = "INVOKE", target = WRITE_BYTE_SELF))
    private PacketByteBuf bis$writeCountSelf(PacketByteBuf self, int count) {
        return self.writeVarInt(count);
    }

    /**
     * 把读数量由「一个字节」换成 VarInt，并把真实数量暂存起来。
     *
     * <p>返回值仍会被截断成 byte（调用点的局部变量就是 byte 语义），真正生效的还原在
     * {@link #bis$restoreCount}。
     *
     * @param self 缓冲区自身
     * @return 截断后的字节（原调用的返回值语义）
     */
    @Redirect(method = READ_ITEM_STACK, at = @At(value = "INVOKE", target = READ_BYTE))
    private byte bis$readCount(PacketByteBuf self) {
        int count = self.readVarInt();
        ((PacketByteBufMixin) (Object) self).bis$count = count;
        return (byte) count;
    }

    /**
     * 把原版读到的截断值换成真实数量。
     *
     * <p>{@code readItemStack} 里只有一个 int 局部变量（数量），所以 {@code ordinal = 0} 即它。
     *
     * @param truncated 原版读到的截断值（未使用，真实值取自 {@link #bis$count}）
     * @return 真实数量
     */
    @ModifyVariable(method = READ_ITEM_STACK, at = @At("STORE"), ordinal = 0)
    private int bis$restoreCount(int truncated) {
        // 只挡负数（只可能来自异常/恶意包）。**不能**按 global_max 夹取：
        // /bis set 可以把上限调低，那时容器里保留着超过新上限的堆叠是正常状态，
        // 一旦在这里按上限截断，就会重演"调低上限→物品被销毁"的老问题。
        return Math.max(0, this.bis$count);
    }
}
