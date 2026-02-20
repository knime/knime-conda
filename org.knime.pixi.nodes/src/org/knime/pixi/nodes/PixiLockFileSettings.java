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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Supplier;

import org.knime.conda.envinstall.pixi.PixiBinary;
import org.knime.core.util.PathUtils;
import org.knime.core.webui.node.dialog.defaultdialog.util.updates.StateComputationFailureException;
import org.knime.core.webui.node.dialog.defaultdialog.widget.handler.WidgetHandlerException;
import org.knime.node.parameters.NodeParametersInput;
import org.knime.node.parameters.Widget;
import org.knime.node.parameters.WidgetGroup;
import org.knime.node.parameters.layout.Layout;
import org.knime.node.parameters.updates.Effect;
import org.knime.node.parameters.updates.Effect.EffectType;
import org.knime.node.parameters.updates.EffectPredicate;
import org.knime.node.parameters.updates.EffectPredicateProvider;
import org.knime.node.parameters.updates.ParameterReference;
import org.knime.node.parameters.updates.StateProvider;
import org.knime.node.parameters.updates.ValueProvider;
import org.knime.node.parameters.updates.ValueReference;
import org.knime.node.parameters.widget.text.TextAreaWidget;
import org.knime.pixi.nodes.PythonEnvironmentProviderNodeParameters.LockFileSection;
import org.knime.pixi.nodes.PythonEnvironmentProviderNodeParameters.MainInputSource;
import org.knime.pixi.nodes.PythonEnvironmentProviderNodeParameters.MainInputSourceRef;
import org.knime.pixi.nodes.PythonEnvironmentProviderNodeParameters.PackageArrayRef;
import org.knime.pixi.nodes.PythonEnvironmentProviderNodeParameters.ResolveDependenciesButtonRef;
import org.knime.pixi.nodes.TomlEditor.TomlContentRef;
import org.knime.pixi.nodes.YamlEditor.TomlFromYamlRef;
import org.knime.pixi.port.PixiUtils;

class PixiLockFileSettings implements WidgetGroup {


    ///////////////////////////////////////////////////////////////////////////////////////////////////////
    // Effective TOML content that is used to generate the lock file and passed to the port
    ///////////////////////////////////////////////////////////////////////////////////////////////////////

    @ValueReference(EffectiveTOMLContentRef.class)
    @ValueProvider(EffectiveTOMLContentValueProvider.class)
    String m_effectiveTOMLContent = "";

    interface EffectiveTOMLContentRef extends ParameterReference<String> {
    }

    static final class EffectiveTOMLContentValueProvider implements StateProvider<String> {

        private Supplier<MainInputSource> m_inputSourceSupplier;

        private Supplier<PixiPackageSpec[]> m_packagesSupplier;

        private Supplier<String> m_tomlContentSupplier;

        private Supplier<String> m_tomlFromYamlSupplier;

        @Override
        public void init(final StateProviderInitializer initializer) {
            initializer.computeBeforeOpenDialog();
            m_inputSourceSupplier = initializer.computeFromValueSupplier(MainInputSourceRef.class);
            m_packagesSupplier = initializer.computeFromValueSupplier(PackageArrayRef.class);
            m_tomlContentSupplier = initializer.computeFromValueSupplier(TomlContentRef.class);
            m_tomlFromYamlSupplier = initializer.computeFromValueSupplier(TomlFromYamlRef.class);
        }

        @Override
        public String computeState(final NodeParametersInput context) {
            return getTomlContent(m_inputSourceSupplier.get(), m_packagesSupplier.get(),
                m_tomlContentSupplier.get(), m_tomlFromYamlSupplier.get());
        }

        static String getTomlContent(final MainInputSource inputSource, final PixiPackageSpec[] packages,
            final String tomlContent, final String yamlContentAsTOML) {
            return switch (inputSource) {
                case SIMPLE -> PixiPackageSpec.buildPixiTomlFromPackages(packages);
                case TOML_EDITOR -> tomlContent;
                case YAML_EDITOR -> yamlContentAsTOML;
            };
        }
    }


    ///////////////////////////////////////////////////////////////////////////////////////////////////////
    // TOML content at the time of the last "Resolve dependencies" button click, used to determine if the lock file is up to date
    ///////////////////////////////////////////////////////////////////////////////////////////////////////

    @ValueProvider(TomlForLastButtonClickProvider.class)
    @ValueReference(TomlForLastButtonClickRef.class)
    String m_tomlForLastLockFileGeneration = "";

    interface TomlForLastButtonClickRef extends ParameterReference<String> {
    }

    /** Copies effective TOML on button click */
    static class TomlForLastButtonClickProvider implements StateProvider<String> {

        private Supplier<String> m_effectiveTOMLContentSupplier;

        @Override
        public void init(final StateProviderInitializer initializer) {
            initializer.computeOnButtonClick(ResolveDependenciesButtonRef.class);
            m_effectiveTOMLContentSupplier = initializer.getValueSupplier(EffectiveTOMLContentRef.class);
        }

        @Override
        public String computeState(final NodeParametersInput context) {
            return m_effectiveTOMLContentSupplier.get();
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////
    // Lock file content generation and display
    ///////////////////////////////////////////////////////////////////////////////////////////////////////

    @ValueProvider(LockFileProvider.class)
    @Widget(title = "Lock file content", description = "Content of the generated or loaded pixi.lock file.",
        advanced = true)
    @Layout(LockFileSection.class)
    @TextAreaWidget(rows = 10)
    @Effect(predicate = AlwaysTrue.class, type = EffectType.DISABLE)
    String m_pixiLockFileContent = "";

    /**
     * Predicate that always returns true, used to disable read-only fields.
     */
    static final class AlwaysTrue implements EffectPredicateProvider {
        @Override
        public EffectPredicate init(final PredicateInitializer i) {
            return i.always();
        }
    }

    @ValueReference(IsCurrentLockUpToDateWithOtherSettingsRef.class)
    @ValueProvider(IsCurrentLockUpToDateWithOtherSettingsProvider.class)
    boolean m_isCurrentLockUpToDateWithOtherSettings = false;

    interface IsCurrentLockUpToDateWithOtherSettingsRef extends ParameterReference<Boolean> {
    }

    static class IsCurrentLockUpToDateWithOtherSettingsProvider implements StateProvider<Boolean> {

        private Supplier<String> m_effectiveTOMLContentSupplier;

        private Supplier<String> m_tomlForLastButtonClickSupplier;

        @Override
        public void init(final StateProviderInitializer initializer) {
            m_effectiveTOMLContentSupplier = initializer.computeFromValueSupplier(EffectiveTOMLContentRef.class);
            m_tomlForLastButtonClickSupplier =
                initializer.computeFromValueSupplier(TomlForLastButtonClickRef.class);
        }

        @Override
        public Boolean computeState(final NodeParametersInput context) {
            return Objects.equals(m_effectiveTOMLContentSupplier.get(), m_tomlForLastButtonClickSupplier.get());
        }
    }

    /**
     * State provider that generates a lock file from a TOML. Writing the effective TOML content to a temp
     * directory, running `pixi lock`, and reading the generated lock file. This is triggered when the "Resolve
     * dependencies" button is clicked.
     */
    static final class LockFileProvider implements StateProvider<String> {

        private Supplier<String> m_effectiveTOMLContentSupplier;

        @Override
        public void init(final StateProviderInitializer initializer) {
            initializer.computeOnButtonClick(ResolveDependenciesButtonRef.class);
            m_effectiveTOMLContentSupplier = initializer.getValueSupplier(EffectiveTOMLContentRef.class);
        }

        @Override
        public String computeState(final NodeParametersInput context) throws StateComputationFailureException {
            Path projectDir = null;
            try {

                // Always use a fresh temp directory during configuration
                projectDir = PathUtils.createTempDir("pixi-envs-config");

                // Write the TOML manifest to the temp directory
                final Path tomlFilePath = projectDir.resolve("pixi.toml");
                Files.writeString(tomlFilePath, m_effectiveTOMLContentSupplier.get());

                // Run pixi lock to resolve dependencies and generate lock file
                final String[] pixiArgs = {"--color", "never", "--no-progress", "lock"};
                final var callResult = PixiBinary.callPixiWithCancellation(projectDir, null, () -> false, pixiArgs);

                if (callResult.returnCode() != 0) {
                    String errorDetails = PixiUtils.getMessageFromCallResult(callResult);
                    throw new WidgetHandlerException("Pixi lock failed:\n" + errorDetails);
                }

                // Read the generated lock file
                final Path lockFilePath = projectDir.resolve("pixi.lock");
                if (Files.exists(lockFilePath)) {
                    final String lockContent = Files.readString(lockFilePath);
                    PythonEnvironmentProviderNodeParameters.LOGGER.warn("Lock file generated with " + lockContent.length() + " chars");
                    return lockContent;
                } else {
                    throw new WidgetHandlerException("Lock file was not generated at: " + lockFilePath);
                }
            } catch (WidgetHandlerException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new WidgetHandlerException("Lock generation was cancelled");
            } catch (Exception ex) {
                throw new WidgetHandlerException("Failed to generate lock file: " + ex.getMessage());
            } finally {
                // Clean up temp directory
                try {
                    PathUtils.deleteDirectoryIfExists(projectDir);
                } catch (Exception e) {
                    // Best effort cleanup - log but don't fail
                }
            }
        }
    }
}