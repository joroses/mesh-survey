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
package org.meshtastic.feature.survey

import org.meshtastic.core.model.Node

/**
 * An ended session's relay bytes as [replayRelays] rebuilt them, and the nodes its radio had heard directly by then.
 */
data class ReplayedRelays(val relays: Map<Int, RelayStats>, val neighbours: Map<Long, DirectSender>)

/**
 * Rebuilds [session]'s relays list from its saved packets the way [SurveyRecorder] built it while recording: from what
 * its radio heard directly in the [DIRECT_HISTORY] before it started, each packet is guessed ([evidenceFor]) before it
 * joins them. Today's [nodes] and [mapped] stand in for the ones the session had, so a guess can differ from the one
 * shown then, and SigSleuth's answers are not kept on the phone, so there are none.
 */
suspend fun SurveyDao.replayRelays(
    session: SurveySession,
    nodes: Map<Int, Node>,
    mapped: MappedNodes?,
): ReplayedRelays {
    val history = directPlaces(session, session.startedAt - DIRECT_HISTORY.inWholeMilliseconds, session.startedAt)
    val neighbours = DirectNeighbours(history.map { it.toDirectSender() })
    // The recorder's survey node is the radio's signed node number; the session keeps it unsigned.
    val surveyNode = session.receiverNodeId.toInt()
    val relays = mutableMapOf<Int, RelayStats>()
    var afterSeq = 0L
    while (true) {
        val rows = measurementsAfter(session.id, afterSeq, REPLAY_BATCH)
        if (rows.isEmpty()) break
        for (row in rows) {
            val evidence = neighbours.evidenceFor(row, nodes, mapped, surveyNode)
            neighbours.record(row)
            val byte = row.relayByte
            if (byte != null && evidence != null) relays[byte] = relays[byte].withPacket(row, evidence)
        }
        afterSeq = rows.last().seq
    }
    return ReplayedRelays(relays.toMap(), neighbours.all)
}

private const val REPLAY_BATCH = 1_000
