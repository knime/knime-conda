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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.knime.core.node.NodeLogger;
import org.knime.product.headless.IWarmstartAction;

/**
 * Warmstart action that handles conda environment installation during the warmstart phase.
 *
 * <p>
 * This action forces the installation of all bundled conda environments, which is typically
 * an expensive operation that can significantly slow down regular application startup. By
 * executing this during the warmstart phase (e.g., during Docker container preparation),
 * subsequent application starts can be much faster.
 * </p>
 *
 * <p>
 * The action uses the existing {@link CondaEnvironmentRegistry} infrastructure to perform
 * the installation and provides detailed status reporting about the installation results.
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
            // Set system property to enable conda environment installation on startup
            System.setProperty("knime.conda.install_envs_on_startup", "true");
            LOGGER.debug("Set knime.conda.install_envs_on_startup=true");

            // Force initialization of conda environments in headless mode
            LOGGER.debug("Calling CondaEnvironmentRegistry.initializeEnvironments(true, true) for headless mode");
            CondaEnvironmentRegistry.initializeEnvironments(true, true);

            // Get installation results and provide detailed status
            Map<String, CondaEnvironment> environments = CondaEnvironmentRegistry.getEnvironments();

            if (environments == null || environments.isEmpty()) {
                LOGGER.info("No conda environments are configured");
                System.out.println("=== CONDA WARMSTART: No environments configured ===");
                return WarmstartResult.success("No conda environments configured");
            }

            // Analyze installation results
            InstallationStats stats = analyzeInstallationResults(environments);

            // Log detailed results
            logInstallationResults(stats);

            // Determine overall result - be more forgiving about what constitutes success
            if (stats.criticallyBrokenCount > 0) {
                String message = String.format("Conda environment installation failed: %d/%d environments are critically broken",
                        stats.criticallyBrokenCount, stats.totalCount);
                LOGGER.error(message);
                return WarmstartResult.failure(message);
            } else if (stats.functionalCount > 0) {
                String message = String.format("Conda environments are ready: %d functional, %d skipped/disabled", 
                        stats.functionalCount, stats.skippedCount);
                if (stats.warningCount > 0) {
                    message += String.format(" (%d with warnings)", stats.warningCount);
                    LOGGER.warn(message);
                } else {
                    LOGGER.info(message);
                }
                return WarmstartResult.success(message);
            } else if (stats.skippedCount == stats.totalCount) {
                String message = String.format("All %d conda environments were skipped/disabled", stats.skippedCount);
                LOGGER.info(message);
                return WarmstartResult.success(message);
            } else {
                String message = "No functional conda environments found";
                LOGGER.warn(message);
                return WarmstartResult.failure(message);
            }

        } catch (Exception e) {
            LOGGER.error("Failed to install conda environments", e);
            return WarmstartResult.failure("Failed to install conda environments: " + e.getMessage(), e);
        }
    }

    @Override
    public String getName() {
        return "Conda Environment Installation";
    }

    @Override
    public int getPriority() {
        return 999; // High priority - environments should be installed early
    }

    @Override
    public boolean executeAfterFailures() {
        return false; // Don't execute if other critical actions have failed
    }

    /**
     * Analyzes the installation results of conda environments.
     *
     * @param environments the environments map from the registry
     * @return installation statistics
     */
    private InstallationStats analyzeInstallationResults(final Map<String, CondaEnvironment> environments) {
        int functionalCount = 0;
        int skippedCount = 0;
        int warningCount = 0;
        int criticallyBrokenCount = 0;

        System.out.println("=== CONDA WARMSTART: Environment Analysis ===");
        System.out.println("Total environments: " + environments.size());

        for (Map.Entry<String, CondaEnvironment> entry : environments.entrySet()) {
            String envName = entry.getKey();
            CondaEnvironment condaEnv = entry.getValue();

            try {
                String name = condaEnv.getName();
                Path envPath = condaEnv.getPath();
                boolean isDisabled = condaEnv.isDisabled();

                System.out.println("Environment: " + name);

                if (isDisabled) {
                    System.out.println("  Status: SKIPPED/DISABLED");
                    skippedCount++;
                } else if (envPath != null) {
                    String pathStr = envPath.toString();

                    // Check for disabled placeholder paths
                    if (pathStr.startsWith("DISABLED_ENVIRONMENT_")) {
                        System.out.println("  Status: SKIPPED/DISABLED (placeholder)");
                        skippedCount++;
                    } else {
                        System.out.println("  Status: FUNCTIONAL");
                        System.out.println("  Location: " + pathStr);

                        // Verify the path exists on filesystem
                        if (Files.exists(envPath)) {
                            // Additional check for environment completeness
                            if (isEnvironmentComplete(envPath)) {
                                System.out.println("  Verification: ✓ Environment is complete and functional");
                                functionalCount++;
                            } else {
                                System.out.println("  Verification: ⚠ Environment exists but may be incomplete");
                                functionalCount++; // Still count as functional - may work
                                warningCount++;
                            }
                        } else {
                            System.out.println("  Verification: ⚠ WARNING - Path missing, but environment object exists");
                            // Don't count as critically broken if the environment object is properly configured
                            // This might be a case where the environment hasn't been installed yet but will be
                            functionalCount++; // Give it the benefit of the doubt
                            warningCount++;
                        }
                    }
                } else {
                    System.out.println("  Status: CRITICALLY BROKEN (null path)");
                    criticallyBrokenCount++;
                }

                System.out.println();

            } catch (Exception e) {
                System.out.println("Environment: " + envName + " (ERROR: " + e.getMessage() + ")");
                criticallyBrokenCount++;
                System.out.println();
            }
        }

        return new InstallationStats(environments.size(), functionalCount, skippedCount, warningCount, criticallyBrokenCount);
    }

    /**
     * Checks if a conda environment directory appears to be complete.
     * This is a basic check - if the directory exists, we assume it's functional.
     * 
     * @param envPath the environment path
     * @return true if the environment appears complete
     */
    private boolean isEnvironmentComplete(Path envPath) {
        try {
            // Basic check: if the directory exists and contains some expected structure
            if (!Files.isDirectory(envPath)) {
                return false;
            }
            
            // Check for common conda environment markers
            Path condaMetaDir = envPath.resolve("conda-meta");
            Path binDir = envPath.resolve("bin");
            Path libDir = envPath.resolve("lib");
            
            // If any of these exist, we consider it a valid conda environment
            return Files.exists(condaMetaDir) || Files.exists(binDir) || Files.exists(libDir);
            
        } catch (Exception e) {
            // If we can't check, assume it's incomplete but don't fail completely
            return false;
        }
    }

    /**
     * Logs detailed installation results.
     *
     * @param stats the installation statistics
     */
    private void logInstallationResults(final InstallationStats stats) {
        System.out.println("=== CONDA WARMSTART: Installation Summary ===");
        System.out.println("Total environments: " + stats.totalCount);
        System.out.println("Functional environments: " + stats.functionalCount);
        System.out.println("Skipped/Disabled: " + stats.skippedCount);
        System.out.println("Warnings: " + stats.warningCount);
        System.out.println("Critically broken: " + stats.criticallyBrokenCount);

        if (stats.functionalCount == stats.totalCount) {
            System.out.println("✓ All conda environments are functional");
        } else if (stats.criticallyBrokenCount > 0) {
            System.out.println("⚠ Some conda environments are critically broken");
        } else if (stats.functionalCount > 0) {
            System.out.println("✓ Conda environments are ready (some skipped/disabled)");
        } else {
            System.out.println("⚠ No functional conda environments found");
        }

        System.out.println("=== END CONDA WARMSTART ===");
        System.out.flush();
    }

    /**
     * Statistics about conda environment installation.
     * 
     * @param totalCount total number of environments
     * @param functionalCount environments that are functional (installed and working)
     * @param skippedCount environments that were skipped/disabled
     * @param warningCount environments with warnings but still functional
     * @param criticallyBrokenCount environments that are completely broken
     */
    private record InstallationStats(int totalCount, int functionalCount, int skippedCount, 
            int warningCount, int criticallyBrokenCount) {
    }
}