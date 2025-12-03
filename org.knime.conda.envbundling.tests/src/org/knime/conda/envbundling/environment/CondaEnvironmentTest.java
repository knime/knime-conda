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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * Unit tests for {@link CondaEnvironment}.
 *
 * @author Marc Lehner, KNIME GmbH, Zurich, Switzerland
 */
class CondaEnvironmentTest {

    // --------------------------------------------------------------------
    //  Constructor and Basic Properties
    // --------------------------------------------------------------------

    @Test
    void constructor_createsEnvironmentWithProperties() {
        var bundle = FrameworkUtil.getBundle(getClass());
        var path = Paths.get("/test/env/path");
        var name = "test-env";
        var requiresDownload = true;

        var env = new CondaEnvironment(bundle, path, name, requiresDownload);

        assertNotNull(env, "Environment should be created");
        assertEquals(name, env.getName(), "Name should match");
        assertEquals(path, env.getPath(), "Path should match");
        assertEquals(requiresDownload, env.requiresDownload(), "requiresDownload should match");
        assertFalse(env.isDisabled(), "Should not be disabled by default");
    }

    @Test
    void constructor_withDisabledFlag_createsDisabledEnvironment() {
        var bundle = FrameworkUtil.getBundle(getClass());
        var path = Paths.get("/test/env/path");
        var name = "disabled-env";
        var requiresDownload = false;
        var isDisabled = true;

        var env = new CondaEnvironment(bundle, path, name, requiresDownload, isDisabled);

        assertTrue(env.isDisabled(), "Should be disabled");
        assertEquals(name, env.getName(), "Name should match even when disabled");
    }

    // --------------------------------------------------------------------
    //  Disabled Placeholder
    // --------------------------------------------------------------------

    @Test
    void createDisabledPlaceholder_createsDisabledEnvironment() {
        var bundle = FrameworkUtil.getBundle(getClass());
        var name = "placeholder-env";

        var env = CondaEnvironment.createDisabledPlaceholder(bundle, name);

        assertNotNull(env, "Placeholder should be created");
        assertTrue(env.isDisabled(), "Placeholder should be disabled");
        assertEquals(name, env.getName(), "Name should match");
        assertFalse(env.requiresDownload(), "Placeholder should not require download");
        assertTrue(env.getPath().toString().contains("DISABLED_ENVIRONMENT_"), 
            "Path should indicate disabled state");
    }

    @Test
    void createDisabledPlaceholder_pathContainsEnvironmentName() {
        var bundle = FrameworkUtil.getBundle(getClass());
        var name = "my-special-env";

        var env = CondaEnvironment.createDisabledPlaceholder(bundle, name);

        assertTrue(env.getPath().toString().contains(name), 
            "Path should contain environment name");
    }

    // --------------------------------------------------------------------
    //  Equality and HashCode
    // --------------------------------------------------------------------

    @Test
    void equals_sameProperties_returnsTrue() {
        var bundle = FrameworkUtil.getBundle(getClass());
        var path = Paths.get("/test/env/path");
        var name = "test-env";

        var env1 = new CondaEnvironment(bundle, path, name, false);
        var env2 = new CondaEnvironment(bundle, path, name, false);

        assertEquals(env1, env2, "Environments with same properties should be equal");
        assertEquals(env1.hashCode(), env2.hashCode(), "Hash codes should match");
    }

    @Test
    void equals_differentName_returnsFalse() {
        var bundle = FrameworkUtil.getBundle(getClass());
        var path = Paths.get("/test/env/path");

        var env1 = new CondaEnvironment(bundle, path, "env1", false);
        var env2 = new CondaEnvironment(bundle, path, "env2", false);

        assertFalse(env1.equals(env2), "Environments with different names should not be equal");
    }

    @Test
    void equals_differentPath_returnsFalse() {
        var bundle = FrameworkUtil.getBundle(getClass());
        var name = "test-env";

        var env1 = new CondaEnvironment(bundle, Paths.get("/path1"), name, false);
        var env2 = new CondaEnvironment(bundle, Paths.get("/path2"), name, false);

        assertFalse(env1.equals(env2), "Environments with different paths should not be equal");
    }

    @Test
    void equals_differentRequiresDownload_returnsFalse() {
        var bundle = FrameworkUtil.getBundle(getClass());
        var path = Paths.get("/test/env/path");
        var name = "test-env";

        var env1 = new CondaEnvironment(bundle, path, name, false);
        var env2 = new CondaEnvironment(bundle, path, name, true);

        assertFalse(env1.equals(env2), "Environments with different requiresDownload should not be equal");
    }

    @Test
    void equals_differentDisabledStatus_returnsFalse() {
        var bundle = FrameworkUtil.getBundle(getClass());
        var path = Paths.get("/test/env/path");
        var name = "test-env";

        var env1 = new CondaEnvironment(bundle, path, name, false, false);
        var env2 = new CondaEnvironment(bundle, path, name, false, true);

        assertFalse(env1.equals(env2), "Environments with different disabled status should not be equal");
    }

    @Test
    void equals_sameInstance_returnsTrue() {
        var bundle = FrameworkUtil.getBundle(getClass());
        var env = new CondaEnvironment(bundle, Paths.get("/test"), "test-env", false);

        assertTrue(env.equals(env), "Environment should equal itself");
    }

    @Test
    void equals_null_returnsFalse() {
        var bundle = FrameworkUtil.getBundle(getClass());
        var env = new CondaEnvironment(bundle, Paths.get("/test"), "test-env", false);

        assertFalse(env.equals(null), "Environment should not equal null");
    }

    @Test
    void equals_differentType_returnsFalse() {
        var bundle = FrameworkUtil.getBundle(getClass());
        var env = new CondaEnvironment(bundle, Paths.get("/test"), "test-env", false);

        assertFalse(env.equals("not an environment"), "Environment should not equal different type");
    }

    // --------------------------------------------------------------------
    //  Property Accessors
    // --------------------------------------------------------------------

    @Test
    void getName_returnsCorrectName() {
        var bundle = FrameworkUtil.getBundle(getClass());
        var name = "my-environment";
        var env = new CondaEnvironment(bundle, Paths.get("/test"), name, false);

        assertEquals(name, env.getName(), "getName should return correct name");
    }

    @Test
    void getPath_returnsCorrectPath() {
        var bundle = FrameworkUtil.getBundle(getClass());
        var path = Paths.get("/my/custom/path");
        var env = new CondaEnvironment(bundle, path, "test-env", false);

        assertEquals(path, env.getPath(), "getPath should return correct path");
    }

    @Test
    void requiresDownload_returnsCorrectValue() {
        var bundle = FrameworkUtil.getBundle(getClass());
        var path = Paths.get("/test");

        var env1 = new CondaEnvironment(bundle, path, "env1", true);
        var env2 = new CondaEnvironment(bundle, path, "env2", false);

        assertTrue(env1.requiresDownload(), "Should require download when set to true");
        assertFalse(env2.requiresDownload(), "Should not require download when set to false");
    }

    @Test
    void isDisabled_returnsCorrectValue() {
        var bundle = FrameworkUtil.getBundle(getClass());
        var path = Paths.get("/test");

        var env1 = new CondaEnvironment(bundle, path, "env1", false, true);
        var env2 = new CondaEnvironment(bundle, path, "env2", false, false);

        assertTrue(env1.isDisabled(), "Should be disabled when set to true");
        assertFalse(env2.isDisabled(), "Should not be disabled when set to false");
    }

    @Test
    void getBundle_returnsCorrectBundle() {
        var bundle = FrameworkUtil.getBundle(getClass());
        var env = new CondaEnvironment(bundle, Paths.get("/test"), "test-env", false);

        assertEquals(bundle, env.getBundle(), "getBundle should return correct bundle");
    }
}
