/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

/**
 * What STUdio knows about one conversion it performed: which content went in, and which came out.
 *
 * <p>Both sides are identified by a SHA-256 digest, because nothing else identifies content. The
 * source's name is carried for diagnostics only and is never compared — a source renamed but
 * unchanged still matches, and a different file reusing the name still does not.
 *
 * <p>The artefact's size and modification time are recorded for the same reason: they say something
 * useful in a log, and they may serve a later optimisation. They are <strong>not</strong> a shortcut
 * to a proof. A verification that can conclude "reuse this without asking" recomputes both digests;
 * C7-0b established that equal size and modification time do not mean equal content, and that limit
 * is acceptable for a cache but not for a proof that authorises a silent transfer.
 *
 * <p>{@code targetFormat} and {@code converterVersion} are recorded now and used by nothing yet.
 * Recording them costs two fields and avoids a schema migration the day a conversion-freshness
 * policy is wanted; using them today would apply a policy that has not been decided. Provenance —
 * "this came from that" — is a different question from freshness — "the converter that made it is
 * still the one we would use".
 */
public final class ConversionRecord {

    /** What a digest was taken over: a single file, or a directory tree. */
    public enum Kind {
        FILE, TREE
    }

    private final String sourceName;
    private final Kind sourceKind;
    private final String sourceSha256;
    private final Kind artifactKind;
    private final String artifactSha256;
    private final long artifactSize;
    private final long artifactLastModified;
    private final String targetFormat;
    private final int converterVersion;

    public ConversionRecord(String sourceName, Kind sourceKind, String sourceSha256,
                            Kind artifactKind, String artifactSha256, long artifactSize,
                            long artifactLastModified, String targetFormat, int converterVersion) {
        this.sourceName = sourceName;
        this.sourceKind = sourceKind;
        this.sourceSha256 = sourceSha256;
        this.artifactKind = artifactKind;
        this.artifactSha256 = artifactSha256;
        this.artifactSize = artifactSize;
        this.artifactLastModified = artifactLastModified;
        this.targetFormat = targetFormat;
        this.converterVersion = converterVersion;
    }

    public String getSourceName() {
        return sourceName;
    }

    public Kind getSourceKind() {
        return sourceKind;
    }

    public String getSourceSha256() {
        return sourceSha256;
    }

    public Kind getArtifactKind() {
        return artifactKind;
    }

    public String getArtifactSha256() {
        return artifactSha256;
    }

    public long getArtifactSize() {
        return artifactSize;
    }

    public long getArtifactLastModified() {
        return artifactLastModified;
    }

    public String getTargetFormat() {
        return targetFormat;
    }

    public int getConverterVersion() {
        return converterVersion;
    }
}
