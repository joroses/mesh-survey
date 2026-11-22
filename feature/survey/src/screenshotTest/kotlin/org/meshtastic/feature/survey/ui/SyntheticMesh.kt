/*
 * Copyright (c) 2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
@file:Suppress("MagicNumber")

package org.meshtastic.feature.survey.ui

import org.meshtastic.feature.survey.DirectCell
import org.meshtastic.feature.survey.DirectNeighbours
import org.meshtastic.feature.survey.DirectSender
import org.meshtastic.feature.survey.LinkEvidence
import org.meshtastic.feature.survey.MappedNode
import org.meshtastic.feature.survey.MappedNodes
import org.meshtastic.feature.survey.RelayGuess
import org.meshtastic.feature.survey.RelayStats
import org.meshtastic.feature.survey.ServerAnswer
import org.meshtastic.feature.survey.ServerCandidate
import org.meshtastic.feature.survey.SurveyCell
import org.meshtastic.feature.survey.SurveyFix
import org.meshtastic.feature.survey.SurveyMeasurement
import org.meshtastic.feature.survey.evidenceFor
import org.meshtastic.feature.survey.relayByteOf
import org.meshtastic.feature.survey.withPacket
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.random.Random

/** The moment every scenario is drawn at: 2026-11-21 17:30 UTC, ten hours after the phone booted. */
internal val SNAPSHOT_NOW = SurveyNow(wallMs = 1_795_282_200_000L, elapsedMs = 10 * 3_600_000L)

internal const val SECOND = 1_000L
internal const val MINUTE = 60 * SECOND
internal const val HOUR = 60 * MINUTE
internal const val DAY = 24 * HOUR

/** The survey radio's node number. */
internal const val RADIO_ID = 0x9e7c41a3L

/** The wall time [ms] before [SNAPSHOT_NOW]. */
internal fun ago(ms: Long) = SNAPSHOT_NOW.wallMs - ms

/** A GPS fix taken [age] before [SNAPSHOT_NOW]. */
internal fun fixAged(age: Long, accuracyM: Float? = 5f) =
    SurveyFix(SNAPSHOT_NOW.elapsedMs - age, WALK_START.first, WALK_START.second, accuracyM)

private val WALK_START = 52.20 to 20.70
private val WALK_END = 52.23 to 21.15

/**
 * A made-up mesh beside a made-up drive, run through the recorder's own relay guessing ([evidenceFor], [withPacket]),
 * so its relays carry the guesses, candidates and ruled-out nodes a real session would. [nodeCount] nodes, some sharing
 * a relay byte; about half were heard directly in the weeks before the session and some of the rest are on SigSleuth's
 * map. [packetCount] packets come evenly over [length], the last one [lastAgo] before [SNAPSHOT_NOW], and
 * [answeredShare] of the relay bytes get a SigSleuth answer. The same arguments always make the same mesh.
 */
@Suppress("LongParameterList")
internal class SyntheticMesh(
    seed: Int,
    nodeCount: Int,
    val packetCount: Int,
    length: Long,
    sessionId: String,
    lastAgo: Long = 4 * SECOND,
    noFixShare: Double = 0.05,
    answeredShare: Double = 0.6,
) {
    private val random = Random(seed)
    private val nodes = makeNodes(nodeCount)

    /** Short names of most nodes; the rest show as their node number. */
    val names: Map<Long, String> =
        nodes.filter { random.nextDouble() > UNNAMED_SHARE }.associate { it.id to shortName() }

    private val history = nodes.mapNotNull { historyOf(it) }
    private val heardBefore = history.mapTo(mutableSetOf()) { it.nodeId }

    /** SigSleuth's relay node list: nodes this radio never heard directly, placed on its map. */
    val mapped =
        MappedNodes(
            fetchedAtMs = ago(length + HOUR),
            nodes
                .filter { it.id !in heardBefore && random.nextDouble() < MAPPED_SHARE }
                .map { node ->
                    MappedNode(
                        node.id,
                        names[node.id],
                        node.latitude,
                        node.longitude,
                        estimated = random.nextDouble() < 0.3,
                        reachMeters = if (random.nextBoolean()) random.nextDouble(3_000.0, 25_000.0) else null,
                    )
                },
        )

    val relays: Map<Int, RelayStats>
    val neighbours: Map<Long, DirectSender>
    val last: SurveyMeasurement?
    val directCount: Int

    init {
        val directNeighbours = DirectNeighbours(history)
        val built = mutableMapOf<Int, RelayStats>()
        val truth = mutableMapOf<Int, Long>()
        val step = length / packetCount.coerceAtLeast(1)
        val firstAt = ago(lastAgo) - step * (packetCount - 1)
        var lastRow: SurveyMeasurement? = null
        var direct = 0
        repeat(packetCount) { index ->
            val (row, transmitter) = packet(index, firstAt + step * index, index.toDouble() / packetCount, sessionId)
            val heard =
                if (index < packetCount - 1 && random.nextDouble() < noFixShare) {
                    row.copy(
                        latitude = null,
                        longitude = null,
                        accuracyM = null,
                        fixAgeMs = null,
                        cellX = null,
                        cellY = null,
                    )
                } else {
                    row
                }
            val evidence = directNeighbours.evidenceFor(heard, emptyMap(), mapped, RADIO_ID.toInt())
            directNeighbours.record(heard)
            val byte = heard.relayByte
            if (byte != null && evidence != null) {
                built[byte] = built[byte].withPacket(heard, evidence)
                truth[byte] = transmitter
            }
            if (evidence?.guess is RelayGuess.Direct) direct++
            lastRow = heard
        }
        relays = built.mapValues { (byte, relay) ->
            if (random.nextDouble() < answeredShare) {
                relay.copy(server = answer(byte, relay, truth.getValue(byte)))
            } else {
                relay
            }
        }
        neighbours = directNeighbours.all
        last = lastRow
        directCount = direct
    }

    /** The relay bytes with the most packets first, as the list shows them. */
    fun busiest(count: Int): Set<Int> =
        relays.entries.sortedByDescending { it.value.packets }.take(count).mapTo(mutableSetOf()) { it.key }

    private data class Node(val id: Long, val latitude: Double, val longitude: Double)

    private fun makeNodes(count: Int): List<Node> {
        val made = mutableListOf<Node>()
        repeat(count) { index ->
            // Every ninth node shares the byte of the one before it, so some relay bytes stay ambiguous.
            val id =
                if (index % 9 == 8) {
                    (random.nextLong(0x100000L, 0xFFFFFFL) shl 8) or (made.last().id and 0xFF)
                } else {
                    random.nextLong(0x1000_0000L, 0x1_0000_0000L)
                }
            val along = random.nextDouble(-0.1, 1.1)
            val (latitude, longitude) = walk(along)
            made += Node(id, latitude + random.nextDouble(-0.12, 0.12), longitude + random.nextDouble(-0.05, 0.05))
        }
        return made
    }

    private fun shortName(): String = String(CharArray(4) { NAME_LETTERS[random.nextInt(NAME_LETTERS.length)] })

    /** Heard directly before the session, from places on the drive near it, if near enough and lucky. */
    private fun historyOf(node: Node): DirectSender? {
        val along = (0..20).map { it / 20.0 }.minBy { km(walk(it), node) }
        if (km(walk(along), node) > HEARD_BEFORE_KM || random.nextDouble() > 0.55) return null
        val lastDirect = ago(random.nextLong(3 * HOUR, 20 * DAY))
        val cells =
            List(random.nextInt(1, 4)) {
                val (latitude, longitude) = walk((along + random.nextDouble(-0.03, 0.03)).coerceIn(0.0, 1.0))
                DirectCell.of(latitude, longitude)
            }
        return DirectSender(
            node.id,
            lastDirect,
            places = cells.associateWith { lastDirect - random.nextLong(0, 5 * DAY) },
            signals = cells.associateWith { random.nextDouble(-125.0, -85.0) },
        )
    }

    /** One packet heard [along] the drive, and the node that sent it on the air. */
    private fun packet(index: Int, heardAtMs: Long, along: Double, sessionId: String): Pair<SurveyMeasurement, Long> {
        val here = walk(along)
        val sender = nodes[random.nextInt(nodes.size)]
        val direct = km(here, sender) < DIRECT_KM && random.nextDouble() < 0.7
        val transmitter = if (direct) sender else nearbyRelay(here, sender)
        val distance = km(here, transmitter)
        // Received power falls with distance and terrain; below the noise floor RSSI stays there and SNR goes negative.
        val power = -85 - 35 * log10(distance + 0.05) + random.nextDouble(-10.0, 10.0)
        val rssi = maxOf(power, NOISE_FLOOR + random.nextDouble(-3.0, 3.0)).roundToInt()
        // LoRa's SNR flattens out for strong signals and follows the power below the noise floor.
        val clean = if (power >= -115) (power + 115) / 3 + 1 else power + 114
        val snr = (clean + random.nextDouble(-1.5, 1.5)).coerceIn(-20.0, 12.0)
        val cell = SurveyCell.of(here.first, here.second)
        val hopStart = 3
        return SurveyMeasurement(
            seq = index + 1L,
            sessionId = sessionId,
            heardAtMs = heardAtMs,
            latitude = here.first,
            longitude = here.second,
            accuracyM = random.nextInt(3, 16).toFloat(),
            fixAgeMs = random.nextLong(200, 2_000),
            senderNodeId = sender.id,
            recipientNodeId = BROADCAST,
            packetId = random.nextLong(1, 0xFFFF_FFFFL),
            rxSnr = (snr * 4).roundToInt() / 4f,
            rxRssi = rssi,
            hopStart = hopStart,
            hopLimit = if (direct) hopStart else random.nextInt(0, hopStart),
            relayByte = relayByteOf(transmitter.id),
            cellX = cell.x,
            cellY = cell.y,
        ) to transmitter.id
    }

    /** A relay near [here], the nearest likelier, never the sender. */
    private fun nearbyRelay(here: Pair<Double, Double>, sender: Node): Node {
        val near = nodes.filter { it != sender }.sortedBy { km(here, it) }.take(NEAR_RELAYS)
        var pick = 0
        while (pick < near.lastIndex && random.nextDouble() < 0.45) pick++
        return near[pick]
    }

    /** SigSleuth's answer for an earlier packet of this relay byte, mostly naming the node that really sent it. */
    private fun answer(byte: Int, relay: RelayStats, transmitter: Long): ServerAnswer {
        val back = random.nextLong(0, relay.packets.coerceAtMost(20).toLong())
        val seq = (relay.last.seq - back * 7).coerceAtLeast(1)
        val heardAtMs = relay.last.heardAtMs - back * 3 * MINUTE
        if (relay.guess is RelayGuess.Direct) {
            return ServerAnswer(seq, heardAtMs, byte, transmitter, "direct", names[transmitter])
        }
        val how = HOWS[random.nextInt(HOWS.size)]
        if (how == "unknown") {
            val candidates =
                relay.candidates.take(random.nextInt(0, 3)).map {
                    ServerCandidate(it.nodeId, names[it.nodeId], random.nextDouble(0.5, 18.0))
                }
            return ServerAnswer(seq, heardAtMs, byte, null, how, null, candidates)
        }
        // Now and then SigSleuth names another node with the byte than the phone's guess.
        val named = relay.candidates.firstOrNull()?.nodeId?.takeIf { random.nextDouble() < 0.2 } ?: transmitter
        val evidence =
            if (how == "survey_link") {
                LinkEvidence(
                    km = random.nextDouble(0.05, 3.0),
                    offsetMs = random.nextLong(-3 * DAY, 2 * HOUR),
                    mine = random.nextBoolean(),
                    rssi = random.nextInt(-125, -70),
                )
            } else {
                null
            }
        return ServerAnswer(seq, heardAtMs, byte, named, how, names[named], evidence = evidence)
    }

    private companion object {
        const val UNNAMED_SHARE = 0.12
        const val MAPPED_SHARE = 0.5
        const val HEARD_BEFORE_KM = 12.0
        const val DIRECT_KM = 5.0
        const val NEAR_RELAYS = 6
        const val NOISE_FLOOR = -120.0
        const val BROADCAST = 0xFFFF_FFFFL
        const val NAME_LETTERS = "ABCDEFGHJKLMNPRSTUVWXYZ0123456789"
        val HOWS =
            listOf("survey_link", "survey_link", "gateway_link", "sender_link", "reported", "only_candidate", "unknown")

        fun walk(along: Double): Pair<Double, Double> = Pair(
            WALK_START.first + (WALK_END.first - WALK_START.first) * along,
            WALK_START.second + (WALK_END.second - WALK_START.second) * along,
        )

        fun km(here: Pair<Double, Double>, node: Node): Double {
            val north = (node.latitude - here.first) * KM_PER_DEGREE
            val east = (node.longitude - here.second) * KM_PER_DEGREE * cos(here.first * PI / 180)
            return hypot(north, east)
        }

        const val KM_PER_DEGREE = 111.2
    }
}
