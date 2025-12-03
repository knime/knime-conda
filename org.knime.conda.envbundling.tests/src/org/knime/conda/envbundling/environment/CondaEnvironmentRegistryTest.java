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
 *   Dec 3, 2025 (Marc Lehner): created
 */
package org.knime.conda.envbundling.environment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CondaEnvironmentRegistry}.
 * 
 * Note: Most methods in CondaEnvironmentRegistry require a full OSGi runtime with extension points.
 * These tests focus on verifying the public API behavior without requiring OSGi infrastructure.
 *
 * @author Marc Lehner, KNIME GmbH, Zurich, Switzerland
 */
class CondaEnvironmentRegistryTest {

    /**
     * Test that getEnvironments returns a non-null map.
     * 
     * Without OSGi runtime and registered extensions, the map will be empty,
     * but it should never return null.
     */
    @Test
    void getEnvironments_returnsNonNullMap() {
        var environments = CondaEnvironmentRegistry.getEnvironments();
        
        assertNotNull(environments, "getEnvironments should never return null");
    }

    /**
     * Test that cache invalidation can be called without throwing exceptions.
     * 
     * This verifies that the cache invalidation mechanism is safe to call
     * even when there's no active OSGi runtime or registered environments.
     */
    @Test
    void invalidateCache_doesNotThrow() {
        // Should be safe to call multiple times
        CondaEnvironmentRegistry.invalidateCache();
        CondaEnvironmentRegistry.invalidateCache();
        
        // Verify getEnvironments still works after invalidation
        var environments = CondaEnvironmentRegistry.getEnvironments();
        assertNotNull(environments, "getEnvironments should work after cache invalidation");
    }

    /**
     * Test that the environment installation progress flag is accessible.
     * 
     * This flag is used to prevent concurrent environment installations.
     * Without actual installation running, it should typically be false.
     */
    @Test
    void isEnvironmentInstallationInProgress_isAccessible() {
        var inProgress = CondaEnvironmentRegistry.isEnvironmentInstallationInProgress();
        
        // Just verify the method is accessible and returns a boolean
        // The actual value depends on whether installation is running
        assertNotNull(inProgress, "Should return a boolean value");
        assertFalse(inProgress, "Should not be in progress in test environment");
    }
}
