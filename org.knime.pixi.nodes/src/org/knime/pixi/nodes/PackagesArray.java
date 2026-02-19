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
 *   Feb 19, 2026 (Marc Lehner): created
 */
package org.knime.pixi.nodes;

import org.knime.node.parameters.NodeParameters;
import org.knime.node.parameters.Widget;
import org.knime.node.parameters.array.ArrayWidget;
import org.knime.node.parameters.updates.EffectPredicate;
import org.knime.node.parameters.updates.EffectPredicateProvider;
import org.knime.node.parameters.updates.ParameterReference;
import org.knime.node.parameters.updates.ValueReference;
import org.knime.node.parameters.updates.EffectPredicateProvider.PredicateInitializer;
import org.knime.pixi.nodes.PixiPackageSpec.PackageSource;
import org.knime.pixi.nodes.PythonEnvironmentProviderNodeParameters.MainInputSource;
import org.knime.pixi.nodes.PythonEnvironmentProviderNodeParameters.MainInputSourceRef;

class PackagesArray implements NodeParameters {

    // Predicate to show/hide simple (array based) package input
    static final class InputIsPackagesArray implements EffectPredicateProvider {
        @Override
        public EffectPredicate init(final PredicateInitializer i) {
            return i.getEnum(MainInputSourceRef.class).isOneOf(MainInputSource.SIMPLE);
        }
    }

    // Simple/Packages input
    @Widget(title = "Packages", description = "Specify the packages to include in the environment")
    @ArrayWidget(elementLayout = ArrayWidget.ElementLayout.HORIZONTAL_SINGLE_LINE, addButtonText = "Add package")
    @ValueReference(PackageArrayRef.class)
    PixiPackageSpec[] m_packages = new PixiPackageSpec[]{ //
        new PixiPackageSpec("python", PackageSource.CONDA, "3.14", "3.14"), //
        new PixiPackageSpec("knime-python-base", PackageSource.CONDA, "5.9", "5.9") //
    };

    interface PackageArrayRef extends ParameterReference<PixiPackageSpec[]> {
    }
}