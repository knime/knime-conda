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
 *   Mar 30, 2022 (Adrian Nembach, KNIME GmbH, Konstanz, Germany): created
 */
package org.knime.conda.envbundling.environment;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

import org.osgi.framework.Bundle;

/**
 * Represents an installed Conda environment.
 *
 * @author Adrian Nembach, KNIME GmbH, Konstanz, Germany
 */
public final class CondaEnvironment {

    private final Bundle m_bundle;

    private final Path m_path;

    private final String m_name;

    private final boolean m_requiresDownload;

    private final boolean m_isDisabled;

    CondaEnvironment(final Bundle bundle, final Path path, final String name, final boolean requiresDownload) {
        this(bundle, path, name, requiresDownload, false);
    }

    CondaEnvironment(final Bundle bundle, final Path path, final String name, final boolean requiresDownload,
        final boolean isDisabled) {
        m_bundle = bundle;
        m_path = path;
        m_name = name;
        m_requiresDownload = requiresDownload;
        m_isDisabled = isDisabled;
    }

    /**
     * @return the bundle that defines the environment
     */
    Bundle getBundle() {
        return m_bundle;
    }

    /**
     * @return the name of the environment
     */
    public String getName() {
        return m_name;
    }

    /**
     * @return the path to the environment
     */
    public Path getPath() {
        return m_path;
    }

    /**
     * @return <code>true</code> if installing the conda environment requires downloading packages from the internet
     * @since 5.1
     */
    boolean requiresDownload() {
        return m_requiresDownload;
    }

    /**
     * @return <code>true</code> if this environment is disabled/unavailable (e.g., permanently skipped by user)
     * @since 5.9
     */
    public boolean isDisabled() {
        return m_isDisabled;
    }

    /**
     * Creates a disabled placeholder environment for environments that are permanently unavailable. This prevents null
     * pointer exceptions when the environment is accessed but clearly indicates that the environment cannot be used.
     * 
     * @param bundle the bundle that defines the environment
     * @param name   the name of the environment
     * @return a disabled CondaEnvironment placeholder
     * @since 5.9
     */
    static CondaEnvironment createDisabledPlaceholder(final Bundle bundle, final String name) {
        // Use a non-existent path to ensure any attempt to use this environment will fail gracefully
        Path placeholderPath = Paths.get("DISABLED_ENVIRONMENT_" + name);
        return new CondaEnvironment(bundle, placeholderPath, name, false, true);
    }

    @Override
    public boolean equals(final Object obj) {
        if (obj == this) {
            return true;
        } else if (obj instanceof CondaEnvironment) {
            var other = (CondaEnvironment)obj; // TODO use pattern matching in Java 17
            return m_bundle.getBundleId() == other.m_bundle.getBundleId() //
                && m_name.equals(other.m_name) //
                && m_path.equals(other.m_path) //
                && m_requiresDownload == other.m_requiresDownload //
                && m_isDisabled == other.m_isDisabled;
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(m_bundle.getBundleId(), m_name, m_path, m_requiresDownload, m_isDisabled);
    }
}