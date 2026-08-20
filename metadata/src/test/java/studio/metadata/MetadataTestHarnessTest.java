/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.metadata;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves that Maven runs JUnit 5 tests for this module, and nothing else.
 *
 * <p>Until now {@code metadata} had no Java test sources at all. Surefire was already bound to the
 * module and already ran — the build log has always carried the line, printing nothing because there
 * was no test class to find. The parent imports the JUnit BOM and pins surefire 3.2.5, so the only
 * thing missing was the test-scoped dependency. This class is the evidence that the three fit
 * together: without it, a green build here would say nothing about whether a test would have been
 * discovered.
 *
 * <p>Unlike the rest of the project this module compiles to Java 8, which JUnit 5.10 supports; test
 * sources here are held to the same level.
 *
 * <p>It asserts nothing about the application — in particular nothing about {@code
 * DatabaseMetadataService}, whose unclosed readers and writers are the subject of the next change,
 * not this one. It is not a placeholder to be grown into a real test: the first test of anything in
 * this module belongs in its own file, next to what it covers.
 */
class MetadataTestHarnessTest {

    @Test
    void junit5HarnessIsActive() {
        assertTrue(true, "reaching this assertion is the whole point: surefire discovered and ran it");
    }
}
