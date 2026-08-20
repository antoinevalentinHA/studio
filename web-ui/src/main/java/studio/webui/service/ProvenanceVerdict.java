/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

/**
 * What STUdio can say about a converted pack and the source it is being asked to stand in for.
 *
 * <p>Three answers, not two, and the third is the important one. A boolean would collapse "these are
 * not the same" into "I could not tell", and those call for different things: the first is a fact
 * worth telling the user, the second is an absence of knowledge — which is the ordinary state of
 * every conversion made before provenance was recorded at all.
 *
 * <p>Only {@link #MATCH} is permission to skip the confirmation, and it is only ever returned when
 * both artefacts were read now and both digests equalled the recorded ones. Everything else — a
 * missing record, an unreadable ledger, a file that moved while being read, an unexpected failure —
 * is {@link #UNKNOWN}, and asks.
 */
public enum ProvenanceVerdict {

    /** Both artefacts were read and both digests equal the ones recorded when the conversion ran. */
    MATCH,

    /** Both artefacts were read, and at least one digest differs from the recorded one. */
    MISMATCH,

    /** No proof could be established. Never to be treated as either of the other two. */
    UNKNOWN
}
