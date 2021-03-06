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
 *   Mar 31, 2022 (Adrian Nembach): created
 */
package org.knime.conda.envbundling.environment.management;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Before;
import org.junit.Test;

/**
 *
 * @author Adrian Nembach, KNIME GmbH, Konstanz, Germany
 */
@SuppressWarnings("javadoc")
public class CondaEnvironmentRegistryTest {

    private static final String TEST_ENV = "org.knime.conda.envbundling.test.env";

    private CondaEnvironmentRegistry m_registry;

    private Path m_envsPath;

    @Before
    public void before() throws IOException {
        var tempFolder = Files.createTempDirectory("micromamba_test");
        m_envsPath = tempFolder.resolve("envs");
        m_registry = new CondaEnvironmentRegistry(tempFolder.resolve("root"), m_envsPath, "win-64");
    }

    @Test
    public void testEnvironmentCreation() throws Exception {
        long startTime = System.currentTimeMillis();
        var environment = createTestEnvironment();
        System.out.println("Environment creation took " + (System.currentTimeMillis() - startTime) + " ms.");
        assertEquals(TEST_ENV, environment.getName());
        var expectedPath = m_envsPath.resolve(TEST_ENV);
        assertEquals(expectedPath, environment.getPath());
    }

    private CondaEnvironment createTestEnvironment() {
        return m_registry.getOrCreateEnvironmentInternal(TEST_ENV);
    }

    @Test
    public void testBackToBackEnvCreation() throws Exception {
        createTestEnvironment();
        long startTime = System.currentTimeMillis();
        createTestEnvironment();
        long endTime = System.currentTimeMillis();
        assertTrue(endTime - startTime < 1);
    }

    @Test
    public void testConcurrentEnvRetrieval() throws Exception {//NOSONAR
        var first = new Thread(() -> createTestEnvironment());
        var second = new Thread(() -> createTestEnvironment());
        first.start();
        // wait a bit for the creation to start
        Thread.sleep(10);
        // try to retrieve the same environment concurrently
        second.start();
        first.join();
        second.join(1);
    }

}
