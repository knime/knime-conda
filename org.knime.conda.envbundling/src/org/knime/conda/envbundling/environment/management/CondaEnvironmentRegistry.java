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
 *   Mar 29, 2022 (Adrian Nembach, KNIME GmbH, Konstanz, Germany): created
 */
package org.knime.conda.envbundling.environment.management;

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;

import org.eclipse.core.runtime.Platform;
import org.knime.conda.envbundling.channel.BundledCondaChannelRegistry;
import org.knime.conda.envbundling.environment.CondaEnvironmentDefinition;
import org.knime.conda.envbundling.environment.CondaEnvironmentDefinitionRegistry;
import org.knime.conda.micromamba.bin.MicromambaExecutable;
import org.knime.core.node.NodeLogger;

/**
 * Registry for Conda (TODO or rather micromamba?) environments that are stored in the
 * {@link Platform#getConfigurationLocation()}.
 *
 * @author Adrian Nembach, KNIME GmbH, Konstanz, Germany
 */
public final class CondaEnvironmentRegistry {

    private static final NodeLogger LOGGER = NodeLogger.getLogger(CondaEnvironmentRegistry.class);

    private static final String ROOT = "micromamba_root";

    private static final String ENVS = "micromamba_envs";

    private final Map<String, CondaEnvironment> m_environmentsByName;

    private final Path m_envsPath;

    private final Path m_rootPath;

    private final String m_platform;

    // NOTE: The instance is initialized with the first access
    private static class InstanceHolder {
        private static final CondaEnvironmentRegistry INSTANCE = new CondaEnvironmentRegistry();
    }

    private CondaEnvironmentRegistry() {
        // TODO abstract the location? This would allow to also switch to another location in the future
        // e.g. if we want to run workflows outside of eclipse
        var configAreaPath = getConfigurationAreaPath();
        m_rootPath = configAreaPath.resolve(ROOT);
        m_envsPath = configAreaPath.resolve(ENVS);
        m_environmentsByName = parseEnvironments(m_envsPath)//
            .collect(toMap(CondaEnvironment::getName, Function.identity(), (i, j) -> i, ConcurrentHashMap::new));
        m_platform = getPlatform();
    }

    private static String getPlatform() {
        return "win-64"; // TODO add osx-arm64 the other mac and linux
    }

    private static Path getConfigurationAreaPath() {
        // TODO replace this hack by something that works on all systems
        return Paths.get(Platform.getConfigurationLocation().getURL().getPath().substring(1));
    }

    private static Stream<CondaEnvironment> parseEnvironments(final Path pathToEnvs) {
        try {
            if (Files.exists(pathToEnvs)) {
                return Files.list(pathToEnvs)//
                        .map(CondaEnvironmentRegistry::parseEnvironment);
                // TODO do we need some consistency checks?
            } else {
                return Stream.empty();
            }
        } catch (IOException ex) {
            LOGGER.error("Parsing the installed Conda environments failed.", ex);
            return null; // TODO or an empty map? what kind of behavior do we want?
        }
    }

    private static CondaEnvironment parseEnvironment(final Path pathToEnv) {
        return new CondaEnvironment(pathToEnv, pathToEnv.getFileName().toString());
    }

    private CondaEnvironment getOrCreateEnvironmentInternal(final String name) {
        return m_environmentsByName.computeIfAbsent(name, this::createEnvironment);
    }

    public static CondaEnvironment getOrCreateEnvironment(final String name) {
        return InstanceHolder.INSTANCE.getOrCreateEnvironmentInternal(name);
    }

    private CondaEnvironment createEnvironment(final String name) {
        return CondaEnvironmentDefinitionRegistry.getEnvironmentDefinition(name)//
            .map(this::createEnvironmentFromDefinition)//
            .orElseThrow(() -> new IllegalArgumentException(
                String.format("There is no environment with the name '%s' registered.", name)));
    }

    private CondaEnvironment createEnvironmentFromDefinition(final CondaEnvironmentDefinition definition) {
        var micromamba = MicromambaExecutable.getInstance();
        final var envName = definition.getName();
        final var envPath = m_envsPath.resolve(envName);
        var processBuilder = new ProcessBuilder(// TODO check if we need to encode everything as URL
            micromamba.getPath().toString(), "create", // micromamba create
            "-p", envPath.toString(), // path to the newly created environment
            "-c", getChannelsAsString(), "--override-channels", // only use the local channels
            "-r", m_rootPath.toString(), // set the micromamba root
            "-f", definition.getPathToSpecs(), // set the environemnt definition file
            "--platform", m_platform// set the current platform (needed for Mac arm64)
        );
        try {
            var process = processBuilder.start();
            final var exitCode = process.waitFor();
            if (exitCode != 0) {
                throw createExceptionWithFormat(
                    "The creation of the Conda environment '%s' with the command '%s' failed with the exit code %s",
                    envName, extractCommandAsString(processBuilder), exitCode);
            }
            return new CondaEnvironment(envPath, envName);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Got interrupted while creating a Conda environment.", ex);
        } catch (IOException ex) {
            throw createExceptionWithFormat("Failed to start creation of Conda environment '%s' with command '%s'",
                envName, extractCommandAsString(processBuilder));
        }
    }

    private static RuntimeException createExceptionWithFormat(final String format, final Object... args) {
        return new IllegalStateException(String.format(format, args));
    }

    private static String extractCommandAsString(final ProcessBuilder processBuilder) {
        return processBuilder.command().stream().collect(joining(" "));
    }

    private static String getChannelsAsString() {
        return BundledCondaChannelRegistry.getChannels().stream().collect(joining(" "));
    }
}
