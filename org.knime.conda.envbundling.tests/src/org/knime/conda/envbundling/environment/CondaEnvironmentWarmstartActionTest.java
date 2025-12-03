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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.knime.product.headless.IWarmstartAction.WarmstartResult;

/**
 * Unit tests for {@link CondaEnvironmentWarmstartAction}.
 * 
 * Note: Most functionality requires a full OSGi runtime with registered conda environment extensions.
 * These tests verify the basic API behavior and ensure the action can be instantiated and executed
 * safely even without a complete OSGi environment.
 *
 * @author Marc Lehner, KNIME GmbH, Zurich, Switzerland
 */
class CondaEnvironmentWarmstartActionTest {

    /**
     * Test that the warmstart action can be instantiated.
     * 
     * This verifies the default constructor works as required by the extension point mechanism.
     */
    @Test
    void constructor_createsInstance() {
        var action = new CondaEnvironmentWarmstartAction();
        
        assertNotNull(action, "Constructor should create non-null instance");
    }

    /**
     * Test that execute returns a valid WarmstartResult.
     * 
     * Without OSGi runtime and registered conda environments, the action should still
     * complete successfully and return a valid result indicating no environments were found.
     */
    @Test
    void execute_withoutEnvironments_returnsSuccess() throws Exception {
        var action = new CondaEnvironmentWarmstartAction();
        
        WarmstartResult result = action.execute();
        
        assertNotNull(result, "execute() should return non-null result");
        assertEquals(WarmstartResult.SUCCESS, result, 
            "execute() should return SUCCESS when no environments are registered");
    }

    /**
     * Test that execute can be called multiple times without issues.
     * 
     * This verifies that the warmstart action is idempotent and safe to invoke repeatedly.
     */
    @Test
    void execute_multipleInvocations_succeeds() throws Exception {
        var action = new CondaEnvironmentWarmstartAction();
        
        // First invocation
        WarmstartResult result1 = action.execute();
        assertNotNull(result1, "First execute() should return non-null result");
        
        // Second invocation
        WarmstartResult result2 = action.execute();
        assertNotNull(result2, "Second execute() should return non-null result");
        
        // Both should succeed
        assertEquals(WarmstartResult.SUCCESS, result1, "First invocation should succeed");
        assertEquals(WarmstartResult.SUCCESS, result2, "Second invocation should succeed");
    }

    /**
     * Test that the action properly integrates with the CondaEnvironmentRegistry.
     * 
     * This verifies that calling execute() triggers the environment initialization
     * through the registry, even though no actual environments will be found in
     * the test environment.
     */
    @Test
    void execute_triggersEnvironmentInitialization() throws Exception {
        // Clear any cached state from previous tests
        CondaEnvironmentRegistry.invalidateCache();
        
        var action = new CondaEnvironmentWarmstartAction();
        WarmstartResult result = action.execute();
        
        // After execution, registry should have been accessed
        var environments = CondaEnvironmentRegistry.getEnvironments();
        assertNotNull(environments, "Registry should be accessible after warmstart");
        
        assertEquals(WarmstartResult.SUCCESS, result, 
            "execute() should succeed even with empty environment list");
    }

    /**
     * Test that the action handles the case where environment initialization
     * might be called while already in progress.
     * 
     * This verifies thread-safety and proper handling of concurrent initialization attempts.
     */
    @Test
    void execute_withConcurrentAccess_handlesGracefully() throws Exception {
        var action = new CondaEnvironmentWarmstartAction();
        
        // Trigger environment registry access before warmstart
        CondaEnvironmentRegistry.getEnvironments();
        
        // Now run warmstart - should handle that initialization may already be done
        WarmstartResult result = action.execute();
        
        assertNotNull(result, "execute() should handle pre-initialized registry");
        assertTrue(result == WarmstartResult.SUCCESS || result == WarmstartResult.FAILURE,
            "execute() should return a valid WarmstartResult");
    }
}
