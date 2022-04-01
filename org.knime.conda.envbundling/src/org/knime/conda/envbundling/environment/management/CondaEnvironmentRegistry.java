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
import java.lang.ProcessBuilder.Redirect;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;

import org.eclipse.core.runtime.Platform;
import org.knime.conda.envbundling.channel.BundledCondaChannelRegistry;
import org.knime.conda.envbundling.environment.CondaEnvironmentDefinition;
import org.knime.conda.envbundling.environment.CondaEnvironmentDefinitionRegistry;
import org.knime.conda.micromamba.bin.MicromambaExecutable;
import org.knime.core.node.NodeLogger;
import org.knime.core.util.FileUtil;

/**
 * Registry for Conda (TODO or rather micromamba?) environments that are stored in the
 * {@link Platform#getConfigurationLocation()}.
 *
 * @author Adrian Nembach, KNIME GmbH, Konstanz, Germany
 */
public final class CondaEnvironmentRegistry {

    private static final NodeLogger LOGGER = NodeLogger.getLogger(CondaEnvironmentRegistry.class);

    private static final String PLUGIN = "org.knime.conda.envbundling";

    private static final String ROOT = "root";

    private static final String ENVS = "envs";

    private final ConcurrentHashMap<String, CondaEnvironment> m_environmentsByName;

    // For environments in this set it is guaranteed that getOrCreate returns immediately.
    private final Set<String> m_currentlyExistingEnvNames = ConcurrentHashMap.newKeySet();

    private final Path m_envsPath;

    private final Path m_rootPath;

    private final String m_platform;

    // NOTE: The instance is initialized with the first access
    private static class InstanceHolder {

        private static final CondaEnvironmentRegistry INSTANCE = createInstance();

        private static final CondaEnvironmentRegistry createInstance() {
            var configAreaPath = getConfigurationAreaPath();
            var pluginPath = configAreaPath.resolve(PLUGIN);
            var rootPath = pluginPath.resolve(ROOT);
            var envsPath = pluginPath.resolve(ENVS);
            return new CondaEnvironmentRegistry(rootPath, envsPath, getPlatform());
        }

        private static Path getConfigurationAreaPath() {
            try {
                return FileUtil.resolveToPath(Platform.getConfigurationLocation().getURL());
            } catch (IOException | URISyntaxException ex) {
                // TODO instead fall back to temporary directory?
                throw new IllegalStateException("Failed to create path to configuration area.", ex);
            }
        }

        private static String getPlatform() {
            var os = getOS();
            var arch = getArch();
            return os + "-" + arch;
        }

        private static String getOS() {
            var os = Platform.getOS();
            // TODO use switch with pattern matching in Java 17
            if (Platform.OS_WIN32.equals(os)) {
                return "win";
            } else if (Platform.OS_LINUX.equals(os)) {
                return "linux";
            } else if (Platform.OS_MACOSX.equals(os)) {
                return "osx";
            } else {
                throw new IllegalStateException("Unsupported OS: " + os);
            }
        }

        private static String getArch() {
            var arch = Platform.getOSArch();
            // TODO use switch with pattern matching in Java 17
            if (Platform.ARCH_X86_64.equals(arch)) {
                return "64";
            } else if ("aarch64".equals(arch)) { // use constant in Java 17
                return "arm64";
            } else {
                throw new IllegalStateException("Unsupported arch: " + arch);
            }
        }

    }

    CondaEnvironmentRegistry(final Path rootPath, final Path envsPath, final String platform) {
        m_rootPath = rootPath;
        m_envsPath = envsPath;
        try {
            Files.createDirectories(m_rootPath);
            Files.createDirectories(m_envsPath);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to create the folders in the configuration area.", ex);
        }
        m_environmentsByName = parseEnvironments(m_envsPath)//
            .collect(toMap(CondaEnvironment::getName, Function.identity(), (i, j) -> i, ConcurrentHashMap::new));
        m_currentlyExistingEnvNames.addAll(m_environmentsByName.keySet());
        m_platform = platform;
    }


    private static Stream<CondaEnvironment> parseEnvironments(final Path pathToEnvs) {
        try {
            if (Files.exists(pathToEnvs)) {
                return Files.list(pathToEnvs)//
                    .map(CondaEnvironmentRegistry::parseEnvironment);
                // TODO do we need some consistency checks?
            }
        } catch (IOException ex) {
            LOGGER.error("Parsing the installed Conda environments failed.", ex);
        }
        return Stream.empty();
    }

    private static CondaEnvironment parseEnvironment(final Path pathToEnv) {
        return new CondaEnvironment(pathToEnv, pathToEnv.getFileName().toString());
    }

    CondaEnvironment getOrCreateEnvironmentInternal(final String name) {
        var environment = m_environmentsByName.computeIfAbsent(name, this::createEnvironment);
        m_currentlyExistingEnvNames.add(environment.getName());
        return environment;
    }

    /**
     * Retrieves the environment with the provided name, creating it if necessary.
     * This method blocks until the environment is created.
     * @param name of the required environment
     * @return the environment for the provided name
     * @throws IllegalArgumentException if there is no definition available for name
     * @throws IllegalStateException if environment creation fails
     */
    public static CondaEnvironment getOrCreateEnvironment(final String name) {
        return InstanceHolder.INSTANCE.getOrCreateEnvironmentInternal(name);
    }

    /**
     * Indicates whether the environment with the provided name currently exists.
     * If this method returns true, {@link #getOrCreateEnvironment(String)} will return immediately
     * @param name of the required environment
     * @return true if the environment already exists, false otherwise
     */
    public static boolean environmentExists(final String name) {
        return InstanceHolder.INSTANCE.m_currentlyExistingEnvNames.contains(name);
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
        var processBuilder = new ProcessBuilder(//
            quote(micromamba.getPath().toString()), "create", // micromamba create
            "-p", quote(envPath.toString()), // path to the newly created environment
            "-c", getChannelsAsString(), "--override-channels", // only use the local channels
            "-r", quote(m_rootPath.toString()), // set the micromamba root
            "-f", quote(definition.getPathToSpecs()), // set the environemnt definition file
            "--platform", m_platform, // set the current platform (needed for Mac arm64)
            "-y"// don't ask for permission
        );
        processBuilder.redirectOutput(Redirect.INHERIT);
        processBuilder.redirectError(Redirect.INHERIT);
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
            throw createExceptionWithFormat(ex, "Failed to start creation of Conda environment '%s' with command '%s'",
                envName, extractCommandAsString(processBuilder));
        }
    }

    private static String quote(final String string) {
        return "\"" + string + "\"";
    }

    private static String encodeAsUrl(final String localPath) {
        return Path.of(localPath).toUri().toString();
    }

    private static RuntimeException createExceptionWithFormat(final String format, final Object... args) {
        return new IllegalStateException(String.format(format, args));
    }

    private static RuntimeException createExceptionWithFormat(final Exception cause, final String format,
        final Object... args) {
        return new IllegalStateException(String.format(format, args), cause);
    }

    private static String extractCommandAsString(final ProcessBuilder processBuilder) {
        return processBuilder.command().stream().collect(joining(" "));
    }

    private static String getChannelsAsString() {
        return BundledCondaChannelRegistry.getChannels().stream()//
            .map(CondaEnvironmentRegistry::encodeAsUrl)//
            .collect(joining(" "));
    }
}
