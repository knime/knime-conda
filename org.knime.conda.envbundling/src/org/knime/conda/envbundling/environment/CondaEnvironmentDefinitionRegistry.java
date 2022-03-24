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
 *   Mar 21, 2022 (benjamin): created
 */
package org.knime.conda.envbundling.environment;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IExtensionPoint;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.Platform;
import org.knime.conda.envbundling.CondaEnvironmentBundlingUtils;
import org.knime.core.node.NodeLogger;

/**
 * A registry for definitions of Conda environments.
 *
 * @author Benjamin Wilhelm, KNIME GmbH, Konstanz, Germany
 */
public final class CondaEnvironmentDefinitionRegistry {

    private static final String EXT_POINT_ID = "org.knime.conda.envbundling.CondaEnvironmentDefinition";

    private static final NodeLogger LOGGER = NodeLogger.getLogger(CondaEnvironmentDefinitionRegistry.class);

    // NOTE: The instance is initialized with the first access
    private static class InstanceHolder {
        private static final CondaEnvironmentDefinitionRegistry INSTANCE = new CondaEnvironmentDefinitionRegistry();
    }

    private final Map<String, PlatformEnvDefinitions> m_envDefinitions;

    private CondaEnvironmentDefinitionRegistry() {
        m_envDefinitions = registerExtensions();
    }

    /**
     * Get the definition of the environment with the given name.
     *
     * @param name the unique name of the environment
     * @return the definition of this environment or {@code Optional.empty()} if no environment definition with the
     *         given name has been registered for the current platform
     */
    public static Optional<CondaEnvironmentDefinition> getEnvironmentDefinition(final String name) {
        return Optional.ofNullable(InstanceHolder.INSTANCE.m_envDefinitions.get(name))
            .flatMap(e -> e.forPlatform(Platform.getOS(), Platform.getOSArch()));
    }

    /**
     * @return all environment definitions for the current platform that are registered at the extension point
     */
    public static Collection<CondaEnvironmentDefinition> getEnvironmentDefinitions() {
        return InstanceHolder.INSTANCE.m_envDefinitions.values().stream()
            .map(e -> e.forPlatform(Platform.getOS(), Platform.getOSArch())) //
            .flatMap(Optional::stream) //
            .collect(Collectors.toList());
    }

    /** Loop through extensions and collect them in a Map */
    private static Map<String, PlatformEnvDefinitions> registerExtensions() {
        final Map<String, PlatformEnvDefinitions> envDefs = new HashMap<>();
        final IExtensionRegistry registry = Platform.getExtensionRegistry();
        final IExtensionPoint point = registry.getExtensionPoint(EXT_POINT_ID);

        for (final IConfigurationElement elem : point.getConfigurationElements()) {
            try {
                extractDefinition(elem).ifPresent(d -> {
                    final String envName = d.m_name;
                    if (envDefs.containsKey(envName)) {
                        LOGGER.errorWithFormat(
                            "An environment with the name '%s' is already registered. Please use an unique environment name.",
                            envName);
                    } else {
                        envDefs.put(envName, d);
                    }
                });
            } catch (final Exception e) {
                LOGGER.error("An exception occurred while registering an extension at extension point '" + EXT_POINT_ID
                    + "'. Creating Conda environments might fail.", e);
            }
        }
        return Collections.unmodifiableMap(envDefs);
    }

    /** Extract the {@link CondaEnvironmentDefinition} from the given configuration element */
    private static Optional<PlatformEnvDefinitions> extractDefinition(final IConfigurationElement element) {
        final String bundleName = element.getDeclaringExtension().getContributor().getName();

        // Extract the name
        final String name = element.getAttribute("name");
        if (name == null || name.isBlank()) {
            LOGGER
                .errorWithFormat("The name of the Conda environment definition defined by the plugin '%s' is missing. "
                    + "Please specify a unique name.", bundleName);
            return Optional.empty();
        }

        // Extract the spec files
        final IConfigurationElement[] specFileElems = element.getChildren("specificationFile");
        final Set<SpecFile> specFiles = new HashSet<>(specFileElems.length);
        for (final IConfigurationElement specFileElem : specFileElems) {
            extractSpecFile(specFileElem, name, bundleName).ifPresent(specFiles::add);
        }
        if (specFiles.isEmpty()) {
            LOGGER.errorWithFormat("No specification files are available for the environment '%s' of the plugin '%s'.",
                name, bundleName);
            return Optional.empty();
        }

        return Optional.of(new PlatformEnvDefinitions(name, specFiles));
    }

    /** Extract a {@link SpecFile} from the given configuration element */
    private static Optional<SpecFile> extractSpecFile(final IConfigurationElement specFileElem, final String name,
        final String bundleName) {
        final String os = specFileElem.getAttribute("os");
        if (os == null || os.isBlank()) {
            LOGGER.errorWithFormat("The 'os' attribute of the Conda environment specification file "
                + "for the environment '%s' of the plugin '%s' is missing.", name, bundleName);
            return Optional.empty();
        }

        final String arch = specFileElem.getAttribute("arch");
        if (arch == null || arch.isBlank()) {
            LOGGER.errorWithFormat("The 'arch' attribute of the Conda environment specification file "
                + "for the environment '%s' of the plugin '%s' for %s is missing.", name, bundleName, os);
            return Optional.empty();
        }

        final String file = specFileElem.getAttribute("file");
        final String pathToSpecs;
        try {
            pathToSpecs = CondaEnvironmentBundlingUtils.getAbsolutePath(Platform.getBundle(bundleName), file);
        } catch (final IOException ex) {
            LOGGER.error(String.format("Could not find the path to the Conda environment specification file "
                + "for the environment '%s' of the plugin '%s' for %s-%s.", name, bundleName, os, arch), ex);
            return Optional.empty();
        }
        return Optional.of(new SpecFile(os, arch, pathToSpecs));
    }

    /** A conda environment specification file that applies to one os and architecture */
    private static class SpecFile {

        private final String m_os;

        private final String m_arch;

        private final String m_pathToSpecs;

        private SpecFile(final String os, final String arch, final String pathToSpecs) {
            m_os = os;
            m_arch = arch;
            m_pathToSpecs = pathToSpecs;
        }

        private boolean fitsPlatform(final String os, final String arch) {
            return m_os.equals(os) && m_arch.equals(arch);
        }
    }

    /** An environment definition for multiple platforms */
    private static class PlatformEnvDefinitions {

        private final String m_name;

        private final Set<SpecFile> m_specFiles;

        public PlatformEnvDefinitions(final String name, final Set<SpecFile> specFiles) {
            m_name = name;
            m_specFiles = specFiles;
        }

        private Optional<SpecFile> specFileForPlatform(final String os, final String arch) {
            return m_specFiles.stream().filter(s -> s.fitsPlatform(os, arch)).findFirst();
        }

        private Optional<CondaEnvironmentDefinition> forPlatform(final String os, final String arch) {
            return specFileForPlatform(os, arch)
                .map(s -> new DefaultCondaEnvironmentDefinition(m_name, s.m_pathToSpecs));
        }
    }

    private static class DefaultCondaEnvironmentDefinition implements CondaEnvironmentDefinition {

        private final String m_name;

        private final String m_pathToSpecs;

        public DefaultCondaEnvironmentDefinition(final String name, final String pathToSpecs) {
            m_name = name;
            m_pathToSpecs = pathToSpecs;
        }

        @Override
        public String getName() {
            return m_name;
        }

        @Override
        public String getPathToSpecs() {
            return m_pathToSpecs;
        }
    }
}
