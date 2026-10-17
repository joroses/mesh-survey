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

/** One column per upload field, plus the session ID. A missing value is an empty cell. */
object SurveyCsv {
    const val HEADER =
        "session_id,seq,heard_at_ms,latitude,longitude,accuracy_m,fix_age_ms,sender_node_id,recipient_node_id," +
            "packet_id,rx_snr,rx_rssi,hop_start,hop_limit,relay_byte,ok_to_mqtt,default_key\n"

    private const val PAGE_SIZE = 500

    fun row(m: SurveyMeasurement): String = listOf(
        m.sessionId,
        m.seq,
        m.heardAtMs,
        m.latitude,
        m.longitude,
        m.accuracyM,
        m.fixAgeMs,
        m.senderNodeId,
        m.recipientNodeId,
        m.packetId,
        m.rxSnr,
        m.rxRssi,
        m.hopStart,
        m.hopLimit,
        m.relayByte,
        m.okToMqtt,
        m.defaultKey,
    )
        .joinToString(",", postfix = "\n") { it?.toString().orEmpty() }

    /** Writes a whole session, a page at a time, so a long session never sits in memory at once. */
    suspend fun export(dao: SurveyDao, sessionId: String, write: (String) -> Unit) {
        write(HEADER)
        var afterSeq = 0L
        do {
            val page = dao.measurementsAfter(sessionId, afterSeq, PAGE_SIZE)
            page.forEach { write(row(it)) }
            afterSeq = page.lastOrNull()?.seq ?: afterSeq
        } while (page.size == PAGE_SIZE)
    }
}
