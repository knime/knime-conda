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
 *   Nov 6, 2025 (Marc Lehner): created
 */
package org.knime.conda.envbundling.environment;

import java.util.Map;

import org.knime.core.node.NodeLogger;
import org.knime.product.headless.IWarmstartAction;

/**
 * Warmstart action that handles conda environment installation during the warmstart phase.
 *
 * <p>
 * This action forces the installation of all bundled conda environments, which is typically an expensive operation that
 * can significantly slow down regular application startup. By executing this during the warmstart phase (e.g., during
 * Docker container preparation), subsequent application starts can be much faster.
 * </p>
 *
 * <p>
 * The action uses the existing {@link CondaEnvironmentRegistry} infrastructure to perform the installation and provides
 * detailed status reporting about the installation results.
 * </p>
 *
 * @author Marc Lehner, KNIME AG, Zurich, Switzerland
 * @since 5.9
 */
public class CondaEnvironmentWarmstartAction implements IWarmstartAction {

    private static final NodeLogger LOGGER = NodeLogger.getLogger(CondaEnvironmentWarmstartAction.class);

    /**
     * Default constructor required for extension point instantiation.
     */
    public CondaEnvironmentWarmstartAction() {
        // Default constructor
    }

    @Override
    public WarmstartResult execute() throws Exception {
        LOGGER.info("Starting conda environment installation");

        try {
            // Force initialization of conda environments in headless mode
            LOGGER.debug("Calling CondaEnvironmentRegistry.initializeEnvironments(true) for headless mode");
            CondaEnvironmentRegistry.initializeEnvironments();

            // Get installation results and provide detailed status
            Map<String, CondaEnvironment> environments = CondaEnvironmentRegistry.getEnvironments();

            if (environments == null || environments.isEmpty()) {
                LOGGER.info("No conda environments are found to install");
                return WarmstartResult.success("No conda environments needed to be configured");
            } else {
                for (CondaEnvironment env : environments.values()) {
                    String envName = env.getName();
                    var envPath = env.getPath();
                    LOGGER.info("Warmstart installed conda environment: " + envName + " at " + envPath);
                }
                return WarmstartResult
                    .success("Number of warmstart installed conda environments = " + environments.size());
            }
        } catch (Exception e) {
            LOGGER.error("Failed to install conda environments", e);
            return WarmstartResult.failure("Failed to install conda environments: " + e.getMessage(), e);
        }
    }
}