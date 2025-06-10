package me.senseiwells.replay.compat.inventory

import me.senseiwells.replay.api.ServerReplayPlugin
import me.senseiwells.replay.api.ServerReplayPluginManager
import me.senseiwells.replay.api.network.RecordablePayload
import me.senseiwells.replay.recorder.chunk.ChunkRecorder
import me.senseiwells.replay.recorder.chunk.ChunkRecorders
import me.senseiwells.replay.recorder.player.PlayerRecorder
import me.senseiwells.replay.recorder.player.PlayerRecorders
import net.minecraft.core.NonNullList
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ClientCommonPacketListener
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import java.util.UUID

object ReplayInventoryPlugin : ServerReplayPlugin {
    private const val MOD_ID = "flashback"

    // 物品栏常量，与Inventory类一致
    private const val INVENTORY_SIZE = 36
    private const val ARMOR_SIZE = 4
    private const val OFFHAND_SIZE = 1
    private const val SLOT_OFFHAND = 40

    private val EXPERIENCE_TYPE = CustomPacketPayload.Type<ExperiencePayload>(
        ResourceLocation.fromNamespaceAndPath(MOD_ID, "player_experience")
    )

    private val INVENTORY_TYPE = CustomPacketPayload.Type<InventoryPayload>(
        ResourceLocation.fromNamespaceAndPath(MOD_ID, "player_inventory")
    )

    private val HOTBAR_SLOT_TYPE = CustomPacketPayload.Type<HotbarSlotPayload>(
        ResourceLocation.fromNamespaceAndPath(MOD_ID, "hotbar_slot")
    )

    private val PLAYER_CONTAINER_SLOT_TYPE = CustomPacketPayload.Type<PlayerContainerSlotPayload>(
        ResourceLocation.fromNamespaceAndPath(MOD_ID, "player_container_slot")
    )

    init {
        @Suppress("DEPRECATION")
        ServerReplayPluginManager.registerPlugin(this)
    }

    /**
     * 当玩家经验值变化时调用此方法
     */
    fun onExperienceChange(player: ServerPlayer) {
        player.server.execute {
            PlayerRecorders.get(player)?.record(createExperiencePacket(player))

            val dimension = player.level().dimension()
            val chunkPos = player.chunkPosition()
            for (recorder in ChunkRecorders.containing(dimension, chunkPos)) {
                recorder.record(createExperiencePacket(player))
            }
        }
    }

    /**
     * 当玩家物品栏变化时调用此方法
     */
    fun onInventoryChange(player: ServerPlayer) {
        player.server.execute {
            PlayerRecorders.get(player)?.record(createInventoryPacket(player))

            val dimension = player.level().dimension()
            val chunkPos = player.chunkPosition()
            for (recorder in ChunkRecorders.containing(dimension, chunkPos)) {
                recorder.record(createInventoryPacket(player))
            }
        }
    }

    /**
     * 记录玩家快捷物品栏选择的槽位变化
     */
    fun onHotbarSlotChange(player: ServerPlayer, slot: Int) {
        player.server.execute {
            val packet = createHotbarSlotPacket(player.uuid, slot)

            PlayerRecorders.get(player)?.record(packet)

            val dimension = player.level().dimension()
            val chunkPos = player.chunkPosition()
            for (recorder in ChunkRecorders.containing(dimension, chunkPos)) {
                recorder.record(packet)
            }
        }
    }

    /**
     * 记录玩家容器的特定槽位更新，仅限于玩家自己的容器
     */
    fun onPlayerContainerSlotChange(player: ServerPlayer, containerId: Int, slot: Int, itemStack: ItemStack) {
        player.server.execute {
            val packet = createPlayerContainerSlotPacket(player.uuid, containerId, slot, itemStack)

            PlayerRecorders.get(player)?.record(packet)

            val dimension = player.level().dimension()
            val chunkPos = player.chunkPosition()
            for (recorder in ChunkRecorders.containing(dimension, chunkPos)) {
                recorder.record(packet)
            }
        }
    }

    override fun onPlayerReplayStart(recorder: PlayerRecorder) {
        val player = recorder.getPlayerOrThrow()
        recorder.record(createExperiencePacket(player))
        recorder.record(createInventoryPacket(player))
    }

    override fun onChunkReplayStart(recorder: ChunkRecorder) {
        val server = recorder.level.server
        for (player in server.playerList.players) {
            if (isPlayerInRecordingRange(player, recorder)) {
                recorder.record(createExperiencePacket(player))
                recorder.record(createInventoryPacket(player))
            }
        }
    }

    private fun isPlayerInRecordingRange(player: ServerPlayer, recorder: ChunkRecorder): Boolean {
        val dimension = player.level().dimension()
        if (dimension != recorder.level.dimension()) {
            return false
        }

        val playerChunkPos = player.chunkPosition()
        val centerChunkPos = recorder.getCenterChunk()
        val viewDistance = recorder.getViewDistance()

        val dx = playerChunkPos.x - centerChunkPos.x
        val dz = playerChunkPos.z - centerChunkPos.z

        return dx * dx + dz * dz <= viewDistance * viewDistance
    }

    private fun createExperiencePacket(player: ServerPlayer): Packet<ClientCommonPacketListener> {
        val payload = ExperiencePayload.of { buf ->
            buf.writeUUID(player.uuid)
            buf.writeInt(player.experienceLevel)
            buf.writeFloat(player.experienceProgress)
            buf.writeInt(player.totalExperience)
        }
        return ClientboundCustomPayloadPacket(payload)
    }

    private fun createInventoryPacket(player: ServerPlayer): Packet<ClientCommonPacketListener> {
        val payload = InventoryPayload.of { buf ->
            buf.writeUUID(player.uuid)
            val inventory = player.inventory

            // 容器ID (玩家物品栏为0)
            buf.writeContainerId(0)

            // 状态ID
            buf.writeVarInt(0)

            // 准备物品列表
            val totalSize = INVENTORY_SIZE + ARMOR_SIZE + OFFHAND_SIZE
            val allItems = NonNullList.withSize(totalSize, ItemStack.EMPTY)

            // 复制主物品栏项目
            for (i in 0 until INVENTORY_SIZE) {
                allItems[i] = inventory.items[i].copy()
            }

            // 复制装备栏项目
            for (i in 0 until ARMOR_SIZE) {
                allItems[INVENTORY_SIZE + i] = inventory.armor[i].copy()
            }

            // 复制副手项目
            allItems[INVENTORY_SIZE + ARMOR_SIZE] = inventory.offhand[0].copy()

            // 使用原版 codec 编码物品列表
            //ItemStack.OPTIONAL_LIST_STREAM_CODEC.encode(buf, allItems)

            // 使用原版 codec 编码手持物品
            //ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, inventory.getSelected().copy())

            // 当前选中的槽位
            buf.writeInt(inventory.selected)
        }
        return ClientboundCustomPayloadPacket(payload)
    }

    private fun createHotbarSlotPacket(playerId: UUID, slot: Int): Packet<ClientCommonPacketListener> {
        val payload = HotbarSlotPayload.of { buf ->
            buf.writeUUID(playerId)
            buf.writeInt(slot)
        }
        return ClientboundCustomPayloadPacket(payload)
    }

    private fun createPlayerContainerSlotPacket(
        playerId: UUID,
        containerId: Int,
        slot: Int,
        itemStack: ItemStack
    ): Packet<ClientCommonPacketListener> {
        val payload = PlayerContainerSlotPayload.of { buf ->
            buf.writeUUID(playerId)
            // 容器ID
            buf.writeContainerId(containerId)
            // 状态ID
            buf.writeVarInt(0)
            // 槽位索引
            buf.writeShort(slot)
            // 使用 codec 编码物品
            //ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, itemStack.copy())
        }
        return ClientboundCustomPayloadPacket(payload)
    }

    // Payload类定义...
    class ExperiencePayload private constructor(
        private val writer: (FriendlyByteBuf) -> Unit
    ) : CustomPacketPayload, RecordablePayload {

        override fun shouldRecord(): Boolean {
            return true
        }

        override fun record(buf: FriendlyByteBuf) {
            this.writer.invoke(buf)
        }

        override fun type(): CustomPacketPayload.Type<*> {
            return EXPERIENCE_TYPE
        }

        companion object {
            fun of(writer: (FriendlyByteBuf) -> Unit): ExperiencePayload {
                return ExperiencePayload(writer)
            }
        }
    }

    class InventoryPayload private constructor(
        private val writer: (FriendlyByteBuf) -> Unit
    ) : CustomPacketPayload, RecordablePayload {

        override fun shouldRecord(): Boolean {
            return true
        }

        override fun record(buf: FriendlyByteBuf) {
            this.writer.invoke(buf)
        }

        override fun type(): CustomPacketPayload.Type<*> {
            return INVENTORY_TYPE
        }

        companion object {
            fun of(writer: (FriendlyByteBuf) -> Unit): InventoryPayload {
                return InventoryPayload(writer)
            }
        }
    }

    class HotbarSlotPayload private constructor(
        private val writer: (FriendlyByteBuf) -> Unit
    ) : CustomPacketPayload, RecordablePayload {

        override fun shouldRecord(): Boolean {
            return true
        }

        override fun record(buf: FriendlyByteBuf) {
            this.writer.invoke(buf)
        }

        override fun type(): CustomPacketPayload.Type<*> {
            return HOTBAR_SLOT_TYPE
        }

        companion object {
            fun of(writer: (FriendlyByteBuf) -> Unit): HotbarSlotPayload {
                return HotbarSlotPayload(writer)
            }
        }
    }

    class PlayerContainerSlotPayload private constructor(
        private val writer: (FriendlyByteBuf) -> Unit
    ) : CustomPacketPayload, RecordablePayload {

        override fun shouldRecord(): Boolean {
            return true
        }

        override fun record(buf: FriendlyByteBuf) {
            this.writer.invoke(buf)
        }

        override fun type(): CustomPacketPayload.Type<*> {
            return PLAYER_CONTAINER_SLOT_TYPE
        }

        companion object {
            fun of(writer: (FriendlyByteBuf) -> Unit): PlayerContainerSlotPayload {
                return PlayerContainerSlotPayload(writer)
            }
        }
    }
}