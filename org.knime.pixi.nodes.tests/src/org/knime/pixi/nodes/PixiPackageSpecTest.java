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
 *   Mar 2, 2026 (marc lehner): created
 */
package org.knime.pixi.nodes;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PixiPackageSpec#formatVersionConstraint(PixiPackageSpec)}.
 */
class PixiPackageSpecTest {

    private static PixiPackageSpec pkg(final String version) {
        return new PixiPackageSpec("somepkg", PixiPackageSpec.PackageSource.CONDA, version);
    }

    @Test
    void nullVersionReturnsWildcard() {
        assertEquals("*", PixiPackageSpec.formatVersionConstraint(pkg(null)));
    }

    @Test
    void emptyVersionReturnsWildcard() {
        assertEquals("*", PixiPackageSpec.formatVersionConstraint(pkg("")));
    }

    @Test
    void blankVersionReturnsWildcard() {
        assertEquals("*", PixiPackageSpec.formatVersionConstraint(pkg("   ")));
    }

    @Test
    void explicitWildcardIsPreserved() {
        assertEquals("*", PixiPackageSpec.formatVersionConstraint(pkg("*")));
    }

    @Test
    void versionWithTrailingWildcardIsPreserved() {
        assertEquals("3.11.*", PixiPackageSpec.formatVersionConstraint(pkg("3.11.*")));
    }

    @Test
    void plainMajorVersionGetsDotStar() {
        assertEquals("3.*", PixiPackageSpec.formatVersionConstraint(pkg("3")));
    }

    @Test
    void plainMinorVersionGetsDotStar() {
        assertEquals("3.11.*", PixiPackageSpec.formatVersionConstraint(pkg("3.11")));
    }

    @Test
    void plainPatchVersionGetsDotStar() {
        assertEquals("3.11.4.*", PixiPackageSpec.formatVersionConstraint(pkg("3.11.4")));
    }

    @Test
    void operatorConstraintIsReturnedAsIs() {
        assertEquals(">=3.9,<4", PixiPackageSpec.formatVersionConstraint(pkg(">=3.9,<4")));
    }

    @Test
    void versionWithBuildStringIsReturnedAsIs() {
        assertEquals("3.11.4-build1", PixiPackageSpec.formatVersionConstraint(pkg("3.11.4-build1")));
    }

    @Test
    void versionWithBuildStringExplicitIsReturnedAsIs() {
        assertEquals("{ version = \">=1.2.3\", build=\"py34_0\" }", PixiPackageSpec.formatVersionConstraint(pkg("{ version = \">=1.2.3\", build=\"py34_0\" }")));
    }

    @Test
    void exactVersionIsReturnedAsIs() {
        assertEquals("==3.11.4", PixiPackageSpec.formatVersionConstraint(pkg("==3.11.4")));
    }

}
