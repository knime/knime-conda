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

import static java.util.stream.Collectors.toMap;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Stream;

import org.eclipse.core.runtime.Platform;

/**
 * Registry for Conda (TODO or rather micromamba?) environments that are stored in the
 * {@link Platform#getConfigurationLocation()}.
 *
 * @author Adrian Nembach, KNIME GmbH, Konstanz, Germany
 */
public final class CondaEnvironmentRegistry {

    private static final String ROOT = "micromamba_root";

    private static final String ENVS = "micromamba_envs";

    private final Map<String, Environment> m_environmentsByName;

    private final Path m_rootPath;

    private CondaEnvironmentRegistry() throws IOException {
        // TODO abstract the location? This would allow to also switch to another location in the future
        // e.g. if we want to run workflows outside of eclipse
        var configAreaPath = getConfigurationAreaPath();
        m_rootPath = configAreaPath.resolve(ROOT);
        m_environmentsByName = parseEnvironments(configAreaPath.resolve(ENVS))//
            .collect(toMap(Environment::getName, Function.identity(), (i, j) -> i, ConcurrentHashMap::new));
    }

    private static Path getConfigurationAreaPath() {
        try {
            return Paths.get(Platform.getConfigurationLocation().getURL().toURI());
        } catch (URISyntaxException ex) {
            throw new IllegalStateException("Can't create Path to configuration area.", ex);
        }
    }

    private static Stream<Environment> parseEnvironments(final Path pathToEnvs) throws IOException {
        return Files.list(pathToEnvs)//
            .map(CondaEnvironmentRegistry::parseEnvironment);
        // TODO do we need some consistency checks?
    }

    private static Environment parseEnvironment(final Path pathToEnv) {
        return new Environment(pathToEnv, pathToEnv.getFileName().toString());
    }

    private static final class Environment {

        private final Path m_path;

        private final String m_name;

        Environment(final Path path, final String name) {
            m_path = path;
            m_name = name;
        }

        String getName() {
            return m_name;
        }
    }
}
