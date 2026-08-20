/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package studio.webui.service;

import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Which build of STUdio is running.
 *
 * <p>The value comes from {@code evergreen.properties}, where the build substitutes
 * {@code ${project.version}} through Maven resource filtering — the same file and the same value the
 * application already reports through its version endpoint. Reading it here rather than declaring a
 * constant means the two can never disagree, and that nothing has to be remembered at release time.
 *
 * <p>Read directly from the classpath rather than through the Vert.x config retriever that serves
 * the endpoint: that one is asynchronous and needs a {@code Vertx} instance, which is a great deal
 * of machinery for one string wanted synchronously while recording a conversion.
 */
final class ApplicationVersion {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApplicationVersion.class);

    private static final String PROPERTIES_RESOURCE = "/evergreen.properties";
    private static final String VERSION_KEY = "version";

    /** What is recorded when the build did not stamp a version, rather than a plausible-looking one. */
    static final String UNKNOWN = "unknown";

    private ApplicationVersion() {
    }

    /**
     * The running application's version, or {@link #UNKNOWN}.
     *
     * <p>Never throws and never invents. A conversion recorded with {@code unknown} is still a
     * perfectly good record: this field is diagnostic, and no decision is taken on it.
     */
    static String current() {
        try (InputStream properties = ApplicationVersion.class.getResourceAsStream(PROPERTIES_RESOURCE)) {
            if (properties == null) {
                LOGGER.warn("No " + PROPERTIES_RESOURCE + " on the classpath, recording the "
                        + "converter version as " + UNKNOWN);
                return UNKNOWN;
            }
            Properties parsed = new Properties();
            parsed.load(properties);
            String version = parsed.getProperty(VERSION_KEY);
            return version == null || version.trim().isEmpty() ? UNKNOWN : version.trim();
        } catch (IOException e) {
            LOGGER.warn("Failed to read " + PROPERTIES_RESOURCE + ", recording the converter "
                    + "version as " + UNKNOWN, e);
            return UNKNOWN;
        }
    }
}
