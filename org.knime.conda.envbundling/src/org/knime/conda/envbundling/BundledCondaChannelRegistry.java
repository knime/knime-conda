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
package org.knime.conda.envbundling;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.core.runtime.IExtension;
import org.eclipse.core.runtime.IExtensionPoint;
import org.eclipse.core.runtime.IExtensionRegistry;
import org.eclipse.core.runtime.Platform;
import org.knime.core.node.NodeLogger;
import org.osgi.framework.Bundle;

/**
 * A registry for bundled conda channels.
 *
 * @author Benjamin Wilhelm, KNIME GmbH, Konstanz, Germany
 */
public final class BundledCondaChannelRegistry {

    private static final String EXT_POINT_ID = "org.knime.conda.envbundling.BundledString";

    private static final NodeLogger LOGGER = NodeLogger.getLogger(BundledCondaChannelRegistry.class);

    // NOTE: The instance is initialized with the first access
    private static class InstanceHolder {
        private static final BundledCondaChannelRegistry INSTANCE = new BundledCondaChannelRegistry();
    }

    private final Set<String> m_channels;

    private BundledCondaChannelRegistry() {
        m_channels = registerExtensions();
    }

    /**
     * @return A set of paths to all registered Conda channels.
     */
    public static Set<String> getChannels() {
        return InstanceHolder.INSTANCE.m_channels;
    }

    /** Loop through extensions and collect them in a Map */
    private static Set<String> registerExtensions() {
        final Set<String> channels = new HashSet<>();
        final IExtensionRegistry registry = Platform.getExtensionRegistry();
        final IExtensionPoint point = registry.getExtensionPoint(EXT_POINT_ID);

        for (final IExtension ext : point.getExtensions()) {
            try {
                extractChannel(ext).ifPresent(channels::add);
            } catch (final Exception e) {
                LOGGER.error("An exception occurred while registering an extension at extension point '" + EXT_POINT_ID
                    + "'. Creating Conda environments might fail.", e);
            }
        }
        return Collections.unmodifiableSet(channels);
    }

    /** Extract the {@link String} from the given extension */
    private static Optional<String> extractChannel(final IExtension extension) {
        final String bundleName = extension.getContributor().getName();
        final var bundle = Platform.getBundle(bundleName);
        final Bundle[] fragments = Platform.getFragments(bundle);

        if (fragments.length < 1) {

            LOGGER.errorWithFormat(
                "Could not find a platform-specific fragment for the bundled Conda channel in plugin '%s' "
                    + "(operating system: %s, system architecture: %s).",
                bundle, Platform.getOS(), Platform.getOSArch());
            return Optional.empty();
        }
        if (fragments.length > 1) {
            final String usedFragmentName = fragments[0].getSymbolicName();
            final String unusedFragmentNames =
                Arrays.stream(fragments).skip(1).map(Bundle::getSymbolicName).collect(Collectors.joining(", "));
            LOGGER.warnWithFormat(
                "Found %d platform specific fragments for the bundled Conda channel in plugin '%s' "
                    + "(operating system: %s, system architecture: %s). "
                    + "The fragment '%s' will be used. The fragments [%s] will be ignored.",
                fragments.length, bundleName, Platform.getOS(), Platform.getOSArch(), usedFragmentName,
                unusedFragmentNames);
        }
        final String path;
        try {
            path = CondaEnvironmentBundlingUtils.getAbsolutePath(fragments[0], "pkgs/");
        } catch (final IOException ex) {
            LOGGER.error("Could not find the path to the Conda channel for the plugin '" + bundleName + "'.", ex);
            return Optional.empty();
        }

        return Optional.of(path);
    }
}
