package me.senseiwells.replay.compat.inventory

import me.senseiwells.replay.api.ServerReplayPlugin
import me.senseiwells.replay.api.ServerReplayPluginManager
import me.senseiwells.replay.api.network.RecordablePayload
import me.senseiwells.replay.recorder.chunk.ChunkRecorder
import me.senseiwells.replay.recorder.player.PlayerRecorder
import me.senseiwells.replay.recorder.player.PlayerRecorders
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.common.ClientCommonPacketListener
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer

object ReplayInventoryPlugin : ServerReplayPlugin {
    private const val MOD_ID = "flashback"

    private val EXPERIENCE_TYPE = CustomPacketPayload.Type<ExperiencePayload>(
        ResourceLocation.fromNamespaceAndPath(MOD_ID, "player_experience")
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
            // 记录到玩家录制器
            PlayerRecorders.get(player)?.record(createExperiencePacket(player))

            // 记录到区块录制器
            val dimension = player.level().dimension()
            val chunkPos = player.chunkPosition()
            for (recorder in me.senseiwells.replay.recorder.chunk.ChunkRecorders.containing(dimension, chunkPos)) {
                recorder.record(createExperiencePacket(player))
            }
        }
    }

    override fun onPlayerReplayStart(recorder: PlayerRecorder) {
        val player = recorder.getPlayerOrThrow()
        recorder.record(createExperiencePacket(player))
    }

    override fun onChunkReplayStart(recorder: ChunkRecorder) {
        val server = recorder.level.server
        for (player in server.playerList.players) {
            if (isPlayerInRecordingRange(player, recorder)) {
                recorder.record(createExperiencePacket(player))
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

    /**
     * 经验值数据包负载
     */
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
}