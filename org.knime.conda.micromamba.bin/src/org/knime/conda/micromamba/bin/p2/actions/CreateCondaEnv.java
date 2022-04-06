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
 *   Apr 1, 2022 (Adrian Nembach, KNIME GmbH, Konstanz, Germany): created
 */
package org.knime.conda.micromamba.bin.p2.actions;

import java.nio.file.Files;
import java.util.Map;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.equinox.p2.engine.spi.ProvisioningAction;

/**
 * Installs the Conda environment contained in a plugin. Assumes that the plugin contains an env.yml file with the
 * environment definition, and a Conda channel containing all dependencies.
 *
 * In order to create the Conda environment during fragment installation add a p2.inf to the META-INF that contains at
 * least:
 *
 * <pre>
 * instructions.install=org.knime.conda.micromamba.bin.CreateCondaEnv(artifactLocation:${artifact.location});
 * </pre>
 *
 * This requires that the plugin providing this ProvisioningAction as well as the fragment containing the micromamba
 * executable is already installed. If that's not the case, add metaRequirements to ensure that the micromamba fragment is
 * installed before the environment fragment is installed. For windows 64 bit this looks as follows:
 *
 * <pre>
 * metaRequirements.0.namespace=org.eclipse.equinox.p2.iu
 * metaRequirements.0.name=org.knime.conda.micromamba.bin.windows.amd64.cpu
 * metaRequirements.0.range=[4.6.0, 5.0.0)
 * </pre>
 *
 * Also consider removing the Conda environment via the RemoveCondaEnv action when the fragment is uninstalled.
 *
 * @author Adrian Nembach, KNIME GmbH, Konstanz, Germany
 */
public final class CreateCondaEnv extends ProvisioningAction {

    @Override
    public IStatus execute(final Map<String, Object> parameters) {
        EnvironmentConfig config = ActionUtils.createConfig(parameters);
        var status = validateConfig(config);
        if (status != Status.OK_STATUS) {
            return status;
        }
        return new EnvironmentManager(config).createEnvironment();
    }

    private static IStatus validateConfig(final EnvironmentConfig config) {
        if (!Files.exists(config.channel())) {
            return Logger.createError("There is no channel at the expected path '%s'.", config.channel());
        }
        if (!Files.exists(config.environmentDefinition())) {
            return Logger.createError("There is no environment definition at the expected path '%s'.",
                config.environmentDefinition());
        }
        return Status.OK_STATUS;
    }

    @Override
    public IStatus undo(final Map<String, Object> parameters) {
        return new EnvironmentManager(ActionUtils.createConfig(parameters)).removeEnvironment();
    }

}
