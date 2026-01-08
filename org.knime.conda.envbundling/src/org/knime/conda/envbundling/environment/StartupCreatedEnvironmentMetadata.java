/*
 * ------------------------------------------------------------------------
 *
 *  Copyright by KNIME AG, Zurich, Switzerland
 *  Website: http://www.knime.com; Email: contact@knime.com
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License, Version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, see <http://www.gnu.org/licenses>.
 *
 *  Additional permission under GNU GPL version 3 section 7:
 *
 *  KNIME interoperates with ECLIPSE solely via ECLIPSE's plug-in APIs.
 *  Hence, KNIME and ECLIPSE are both independent programs and are not
 *  derived from each other. Should, however, the interpretation of the
 *  GNU GPL Version 3 ("License") under any applicable laws result in
 *  KNIME and ECLIPSE being a combined program, KNIME AG herewith grants
 *  you the additional permission to use and propagate KNIME together with
 *  ECLIPSE with only the license terms in place for ECLIPSE applying to
 *  ECLIPSE and the GNU GPL Version 3 applying for KNIME, provided the
 *  license terms of ECLIPSE themselves allow for the respective use and
 *  propagation of ECLIPSE together with KNIME.
 *
 *  Additional permission relating to nodes for KNIME that extend the Node
 *  Extension (and in particular that are based on subclasses of NodeModel,
 *  NodeDialog, and NodeView) and that only interoperate with KNIME through
 *  standard APIs ("Nodes"):
 *  Nodes are deemed to be separate and independent programs and to not be
 *  covered works.  Notwithstanding anything to the contrary in the
 *  License, the License does not apply to Nodes, you are not required to
 *  license Nodes under the License, and you are granted a license to
 *  prepare and propagate Nodes, in each case even if such Nodes are
 *  propagated with or for interoperation with KNIME.  The owner of a Node
 *  may freely choose the license terms applicable to such Node, including
 *  when such Node is propagated with or for interoperation with KNIME.
 * ---------------------------------------------------------------------
 *
 * History
 *   Nov 6, 2024 Marc Lehner : created
 */
package org.knime.conda.envbundling.environment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

import org.osgi.framework.Version;

/**
 * Metadata for startup-created conda environments.
 *
 * @author Marc Lehner, KNIME AG, Zurich, Switzerland
 * @since 5.9
 */
final class StartupCreatedEnvironmentMetadata {

    /** The name of the metadata file */
    public static final String METADATA_FILE_NAME = "metadata.properties";

    /** The version of the bundle that created this environment */
    public final String version;

    /** The path where the environment was created */
    public final String creationPath;

    /** Whether the environment failed to install */
    public final boolean failed;

    /** Whether the environment was skipped by user */
    public final boolean skipped;

    private StartupCreatedEnvironmentMetadata(final String version, final String creationPath, final boolean failed,
        final boolean skipped) {
        this.version = version;
        this.creationPath = creationPath;
        this.failed = failed;
        this.skipped = skipped;
    }

    /**
     * Read metadata from the environment root directory.
     *
     * @param environmentRoot the root directory of the environment
     * @return the metadata if it exists
     * @throws IOException if reading fails
     */
    public static Optional<StartupCreatedEnvironmentMetadata> read(final Path environmentRoot) throws IOException {
        Path metadataFile = environmentRoot.resolve(METADATA_FILE_NAME);
        if (!Files.exists(metadataFile)) {
            return Optional.empty();
        }

        Properties props = new Properties();
        try (var inputStream = Files.newInputStream(metadataFile)) {
            props.load(inputStream);
        } catch (IOException e) {
            throw new IOException("Failed to read metadata file: " + metadataFile, e);
        }

        String version = props.getProperty("version", "");
        String creationPath = props.getProperty("creationPath", "");
        boolean failed = Boolean.parseBoolean(props.getProperty("failed", "false"));
        boolean skipped = Boolean.parseBoolean(props.getProperty("skipped", "false"));

        return Optional.of(new StartupCreatedEnvironmentMetadata(version, creationPath, failed, skipped));
    }

    /**
     * Write successful metadata for an environment.
     *
     * @param version the bundle version
     * @param environmentRoot the environment root directory
     * @throws IOException if writing fails
     */
    public static void write(final Version version, final Path environmentRoot) throws IOException {
        Properties props = new Properties();
        props.setProperty("version", version.toString());
        props.setProperty("creationPath", environmentRoot.toAbsolutePath().toString());
        props.setProperty("failed", "false");
        props.setProperty("skipped", "false");

        Path metadataFile = environmentRoot.resolve(METADATA_FILE_NAME);
        Files.createDirectories(environmentRoot);
        try (var outputStream = Files.newOutputStream(metadataFile)) {
            props.store(outputStream, "Startup-created environment metadata");
        } catch (IOException e) {
            throw new IOException("Failed to write metadata file: " + metadataFile, e);
        }
    }

    /**
     * Write failed metadata for an environment.
     *
     * @param version the bundle version
     * @param environmentRoot the environment root directory
     * @throws IOException if writing fails
     */
    public static void writeFailed(final Version version, final Path environmentRoot) throws IOException {
        Properties props = new Properties();
        props.setProperty("version", version.toString());
        props.setProperty("creationPath", environmentRoot.toAbsolutePath().toString());
        props.setProperty("failed", "true");
        props.setProperty("skipped", "false");

        Path metadataFile = environmentRoot.resolve(METADATA_FILE_NAME);
        Files.createDirectories(environmentRoot);
        try (var outputStream = Files.newOutputStream(metadataFile)) {
            props.store(outputStream, "Failed startup-created environment metadata");
        } catch (IOException e) {
            throw new IOException("Failed to write metadata file: " + metadataFile, e);
        }
    }

    /**
     * Write skipped metadata for an environment.
     *
     * @param version the bundle version
     * @param environmentRoot the environment root directory
     * @throws IOException if writing fails
     */
    public static void writeSkipped(final Version version, final Path environmentRoot) throws IOException {
        Properties props = new Properties();
        props.setProperty("version", version.toString());
        props.setProperty("creationPath", environmentRoot.toAbsolutePath().toString());
        props.setProperty("failed", "true");
        props.setProperty("skipped", "true");

        Path metadataFile = environmentRoot.resolve(METADATA_FILE_NAME);
        Files.createDirectories(environmentRoot);
        try (var outputStream = Files.newOutputStream(metadataFile)) {
            props.store(outputStream, "Skipped startup-created environment metadata");
        } catch (IOException e) {
            throw new IOException("Failed to write metadata file: " + metadataFile, e);
        }
    }
}