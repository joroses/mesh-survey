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
package org.meshtastic.core.repository

import org.meshtastic.proto.MeshPacket

/**
 * Hands every mesh packet from the radio to the Android fork's signal survey (feature:survey) before decoding, so it
 * also sees packets the node could not decrypt. Holds one listener, or null while no survey is recording.
 *
 * The listener runs on the inbound radio pipeline: it must only copy fields and return, never wait.
 */
interface SurveyPacketTap {
    var listener: ((packet: MeshPacket, session: RadioSessionContext) -> Unit)?
}
