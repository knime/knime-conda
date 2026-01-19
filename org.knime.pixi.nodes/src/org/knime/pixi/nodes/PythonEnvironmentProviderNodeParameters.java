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
 *   Jan 15, 2026 (Marc Lehner): created
 */
package org.knime.pixi.nodes;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.knime.conda.envbundling.environment.CondaEnvironmentRegistry;
import org.knime.conda.envinstall.pixi.PixiBinary;
import org.knime.conda.envinstall.pixi.PixiBinary.CallResult;
import org.knime.conda.envinstall.pixi.PixiBinary.PixiBinaryLocationException;
import org.knime.core.webui.node.dialog.defaultdialog.internal.button.ButtonWidget;
import org.knime.core.webui.node.dialog.defaultdialog.internal.button.CancelableActionHandler;
import org.knime.core.webui.node.dialog.defaultdialog.internal.file.FileReaderWidget;
import org.knime.core.webui.node.dialog.defaultdialog.internal.file.FileSelection;
import org.knime.core.webui.node.dialog.defaultdialog.internal.file.FileSelectionWidget;
import org.knime.core.webui.node.dialog.defaultdialog.internal.file.SingleFileSelectionMode;
import org.knime.core.webui.node.dialog.defaultdialog.widget.handler.WidgetHandlerException;
import org.knime.node.parameters.NodeParameters;
import org.knime.node.parameters.NodeParametersInput;
import org.knime.node.parameters.Widget;
import org.knime.node.parameters.array.ArrayWidget;
import org.knime.node.parameters.layout.After;
import org.knime.node.parameters.layout.Layout;
import org.knime.node.parameters.persistence.Persist;
import org.knime.node.parameters.updates.Effect;
import org.knime.node.parameters.updates.Effect.EffectType;
import org.knime.node.parameters.updates.EffectPredicate;
import org.knime.node.parameters.updates.EffectPredicateProvider;
import org.knime.node.parameters.updates.ParameterReference;
import org.knime.node.parameters.updates.StateProvider;
import org.knime.node.parameters.updates.ValueProvider;
import org.knime.node.parameters.updates.ValueReference;
import org.knime.node.parameters.widget.choices.ChoicesProvider;
import org.knime.node.parameters.widget.choices.Label;
import org.knime.node.parameters.widget.choices.StringChoicesProvider;
import org.knime.node.parameters.widget.choices.ValueSwitchWidget;
import org.knime.node.parameters.widget.message.TextMessage;
import org.knime.node.parameters.widget.message.TextMessage.MessageType;
import org.knime.node.parameters.widget.text.TextAreaWidget;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.core.io.IndentStyle;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.toml.TomlWriter;

/**
 * Node Parameters for the Python Environment Provider node.
 * Combines input methods from array-based, TOML-based, and file reader approaches.
 *
 * @author Marc Lehner, KNIME GmbH, Konstanz, Germany
 * @since 5.10
 */
@SuppressWarnings("restriction")
public class PythonEnvironmentProviderNodeParameters implements NodeParameters {

    // Layout sections
    static final class mainInputSelectionSection {
    }

    @After(mainInputSelectionSection.class)
    static final class simpleInputSection {
    }

    @After(mainInputSelectionSection.class)
    static final class tomlEditorSection {
    }

    @After(simpleInputSection.class)
    @After(tomlEditorSection.class)
    static final class lockFileSection {
    }

    // Input source selection
    enum MainInputSource {
        @Label("Packages")
        SIMPLE,
        @Label("TOML editor")
        TOML_EDITOR
    }

    @Widget(title = "Input source", description = "Choose how to define the Python environment")
    @Layout(mainInputSelectionSection.class)
    @ValueReference(MainInputSourceRef.class)
    @ValueSwitchWidget
    MainInputSource m_mainInputSource = MainInputSource.SIMPLE;

    interface MainInputSourceRef extends ParameterReference<MainInputSource> {
    }

    // Simple/Packages input
    @Widget(title = "Packages", description = "Specify the packages to include in the environment")
    @Layout(simpleInputSection.class)
    @ArrayWidget(elementLayout = ArrayWidget.ElementLayout.HORIZONTAL_SINGLE_LINE, addButtonText = "Add package")
    @ValueReference(PackageArrayRef.class)
    @Effect(predicate = InputIsSimple.class, type = EffectType.SHOW)
    PackageSpec[] m_packages = new PackageSpec[]{
        new PackageSpec("python", PackageSource.CONDA, "3.14"),
        new PackageSpec("knime-python-base", PackageSource.CONDA, "5.9")
    };

    interface PackageArrayRef extends ParameterReference<PackageSpec[]> {
    }

    static final class InputIsSimple implements EffectPredicateProvider {
        @Override
        public EffectPredicate init(final PredicateInitializer i) {
            return i.getEnum(MainInputSourceRef.class).isOneOf(MainInputSource.SIMPLE);
        }
    }

    // TOML editor input
    @Widget(title = "Environment specification (pixi.toml)", description = """
            Content of the pixi.toml manifest file that describes the environment.
            """)
    @Layout(tomlEditorSection.class)
    @TextAreaWidget(rows = 20)
    @ValueReference(TomlContentRef.class)
    @Effect(predicate = InputIsToml.class, type = EffectType.SHOW)
    String m_pixiTomlContent = """
            [project]
            channels = ["knime", "conda-forge"]
            platforms = ["win-64", "linux-64", "osx-64", "osx-arm64"]

            [dependencies]
            python = "3.14.*"
            knime-python-base = "5.9.*"
            """;

    interface TomlContentRef extends ParameterReference<String> {
    }

    static final class InputIsToml implements EffectPredicateProvider {
        @Override
        public EffectPredicate init(final PredicateInitializer i) {
            return i.getEnum(MainInputSourceRef.class).isOneOf(MainInputSource.TOML_EDITOR);
        }
    }

    // Lock file generation
    @Widget(title = "Check compatibility",
        description = "Click to check whether this environment can be constructed on all selected operating systems")
    @Layout(lockFileSection.class)
    @ButtonWidget(actionHandler = PixiLockActionHandler.class, updateHandler = PixiLockUpdateHandler.class)
    @ValueReference(ButtonFieldRef.class)
    String m_pixiLockButton;

    interface ButtonFieldRef extends ParameterReference<String> {
    }

    // Hidden field that stores lock file content from button, reset to empty when input changes
    @ValueReference(PixiLockFileRef.class)
    @ValueProvider(ResetLockFileProvider.class)
    String m_pixiLockFileContent = "";

    interface PixiLockFileRef extends ParameterReference<String> {
    }

    @TextMessage(ValidationMessageProvider.class)
    @Layout(lockFileSection.class)
    Void m_validationMessage;

    // Helper methods
    String getPixiLockFileContent() {
        return m_pixiLockFileContent;
    }

    String getPixiTomlFileContent() throws IOException {
        switch (m_mainInputSource) {
            case SIMPLE:
                System.out.println("Building pixi.toml from packages");
                return buildPixiTomlFromPackages(m_packages);
            case TOML_EDITOR:
                System.out.println("Using pixi.toml from editor");
                return m_pixiTomlContent;
            default:
                System.out.println("Unknown input source: " + m_mainInputSource);
                throw new IOException("Unknown input source: " + m_mainInputSource);
        }
    }

    // Package specification
    enum PackageSource {
        @Label("Conda")
        CONDA,
        @Label("Pip")
        PIP
    }

    static final class PackageSpec implements NodeParameters {
        @Widget(title = "Package name", description = "The name of the package")
        String m_packageName = "";

        @Widget(title = "Source", description = "Package source (Conda or Pip)")
        PackageSource m_source = PackageSource.CONDA;

        @Widget(title = "Version", description = "Package version constraint")
        String m_version = "";

        PackageSpec() {
        }

        PackageSpec(final String name, final PackageSource source, final String version) {
            m_packageName = name;
            m_source = source;
            m_version = version;
        }
    }

    // Button action handler for lock file generation
    static final class PixiLockActionHandler extends CancelableActionHandler<String, TomlContentGetter> {

        @Override
        protected String invoke(final TomlContentGetter deps, final NodeParametersInput context)
            throws WidgetHandlerException {
            final String tomlContent = deps.getTomlContent();
            System.out.println("[PixiLockActionHandler] Button clicked - running pixi lock...");
            if (tomlContent == null || tomlContent.isBlank()) {
                throw new WidgetHandlerException("No manifest content provided");
            }

            try {
                final Path projectDir = PixiUtils.resolveProjectDirectory(tomlContent, null);
                final Path pixiHome = projectDir.resolve(".pixi-home");
                Files.createDirectories(pixiHome);

                final Map<String, String> extraEnv = Map.of("PIXI_HOME", pixiHome.toString());
                final String[] pixiArgs = {"--color", "never", "--no-progress", "lock"};

                System.out.println("[PixiLockActionHandler] Running pixi lock in: " + projectDir);
                final CallResult callResult = PixiBinary.callPixi(projectDir, extraEnv, pixiArgs);

                if (callResult.returnCode() != 0) {
                    String errorDetails = PixiUtils.getMessageFromCallResult(callResult);
                    System.out.println("[PixiLockActionHandler] Pixi lock failed");
                    throw new WidgetHandlerException("Pixi lock failed:\n" + errorDetails);
                }

                // Read the generated lock file
                final Path lockFilePath = projectDir.resolve("pixi.lock");
                if (Files.exists(lockFilePath)) {
                    final String lockContent = Files.readString(lockFilePath);
                    System.out.println("[PixiLockActionHandler] Lock file generated (" + lockContent.length() + " bytes)");
                    return lockContent;
                } else {
                    throw new WidgetHandlerException("Lock file was not generated at: " + lockFilePath);
                }
            } catch (IOException | PixiBinaryLocationException e) {
                throw new WidgetHandlerException("Failed to generate lock file: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new WidgetHandlerException("Lock generation was interrupted: " + e.getMessage());
            }
        }

        @Override
        protected String getButtonText(final States state) {
            return switch (state) {
                case READY -> "Lock Environment";
                case CANCEL -> "Cancel";
                case DONE -> "Re-lock Environment";
            };
        }

        @Override
        protected boolean isMultiUse() {
            return true; // Allow re-locking
        }

        private static String getMessageFromCallResult(final CallResult callResult) {
            final String stdout = callResult.stdout() == null ? "" : callResult.stdout();
            final String stderr = callResult.stderr() == null ? "" : callResult.stderr();
            return "Exit code " + callResult.returnCode() + "\n"
                + (stderr.isBlank() ? "" : "stderr:\n" + stderr + "\n")
                + (stdout.isBlank() ? "" : "stdout:\n" + stdout);
        }
    }

    static final class TomlContentGetter {
        @ValueReference(MainInputSourceRef.class)
        MainInputSource m_mainInputSource;

        @ValueReference(PackageArrayRef.class)
        PackageSpec[] m_packages;

        @ValueReference(TomlContentRef.class)
        String m_pixiTomlContent;

        String getTomlContent() {
            System.out.println("[TomlContentGetter] Getting TOML content for: " + m_mainInputSource);
            switch (m_mainInputSource) {
                case SIMPLE:
                    System.out.println("[TomlContentGetter] Building TOML from " + m_packages.length + " packages");
                    String result = buildPixiTomlFromPackages(m_packages);
                    System.out.println("[TomlContentGetter] Generated TOML (" + result.length() + " chars):");
                    System.out.println(result);
                    return result;
                case TOML_EDITOR:
                    System.out.println("[TomlContentGetter] Using TOML from editor (" + m_pixiTomlContent.length() + " chars)");
                    System.out.println(m_pixiTomlContent);
                    return m_pixiTomlContent;
                default:
                    System.out.println("[TomlContentGetter] Unknown input source: " + m_mainInputSource);
                    return "";
            }
        }
    }

    static final class PixiLockUpdateHandler extends CancelableActionHandler.UpdateHandler<String, TomlContentGetter> {
    }

    /**
     * Resets lock file to empty when any input content changes.
     */
    static final class ResetLockFileProvider implements StateProvider<String> {

        private Supplier<MainInputSource> m_inputSourceSupplier;
        private Supplier<PackageSpec[]> m_packagesSupplier;
        private Supplier<String> m_tomlContentSupplier;
        private Supplier<String> m_buttonFieldSupplier;

        private MainInputSource m_lastInputSource;
        private PackageSpec[] m_lastPackages;
        private String m_lastTomlContent;

        @Override
        public void init(final StateProviderInitializer initializer) {
            System.out.println("[ResetLockFileProvider] Initializing...");
            initializer.computeOnValueChange(MainInputSourceRef.class);
            initializer.computeOnValueChange(PackageArrayRef.class);
            initializer.computeOnValueChange(TomlContentRef.class);
            initializer.computeOnValueChange(ButtonFieldRef.class);

            m_inputSourceSupplier = initializer.getValueSupplier(MainInputSourceRef.class);
            m_packagesSupplier = initializer.getValueSupplier(PackageArrayRef.class);
            m_tomlContentSupplier = initializer.getValueSupplier(TomlContentRef.class);
            m_buttonFieldSupplier = initializer.getValueSupplier(ButtonFieldRef.class);
        }

        @Override
        public String computeState(final NodeParametersInput context) {
            final MainInputSource currentInputSource = m_inputSourceSupplier.get();
            final PackageSpec[] currentPackages = m_packagesSupplier.get();
            final String currentTomlContent = m_tomlContentSupplier.get();
            final String buttonValue = m_buttonFieldSupplier.get();

            System.out.println("[ResetLockFileProvider] Computing state for: " + currentInputSource);
            System.out.println("[ResetLockFileProvider] Button value: " + (buttonValue != null ? buttonValue.length() + " chars" : "null"));

            // Check if any relevant content changed
            boolean contentChanged = false;

            if (m_lastInputSource != null && currentInputSource != m_lastInputSource) {
                System.out.println("[ResetLockFileProvider] Input source changed: " + m_lastInputSource + " -> " + currentInputSource);
                contentChanged = true;
            }

            if (currentInputSource == MainInputSource.SIMPLE &&
                m_lastPackages != null && !java.util.Arrays.equals(m_lastPackages, currentPackages)) {
                System.out.println("[ResetLockFileProvider] Packages changed");
                contentChanged = true;
            }

            if (currentInputSource == MainInputSource.TOML_EDITOR &&
                m_lastTomlContent != null && !m_lastTomlContent.equals(currentTomlContent)) {
                System.out.println("[ResetLockFileProvider] TOML content changed");
                contentChanged = true;
            }

            // Update last values
            m_lastInputSource = currentInputSource;
            m_lastPackages = currentPackages;
            m_lastTomlContent = currentTomlContent;

            // If content changed, reset lock file; otherwise return button value
            if (contentChanged) {
                System.out.println("[ResetLockFileProvider] Content changed - resetting lock file");
                return "";
            }
            System.out.println("[ResetLockFileProvider] Content unchanged - keeping lock file");
            return buttonValue != null ? buttonValue : "";
        }
    }

    // Validation message provider
    static final class ValidationMessageProvider implements StateProvider<Optional<TextMessage.Message>> {

        private Supplier<String> m_lockContentSupplier;

        @Override
        public void init(final StateProviderInitializer initializer) {
            initializer.computeOnValueChange(PixiLockFileRef.class);
            m_lockContentSupplier = initializer.getValueSupplier(PixiLockFileRef.class);
        }

        @Override
        public Optional<TextMessage.Message> computeState(final NodeParametersInput context) {
            final String lockContent = m_lockContentSupplier.get();
            System.out.println("[ValidationMessageProvider] Lock file content: " + 
                (lockContent == null ? "null" : lockContent.length() + " chars"));

            if (lockContent == null || lockContent.isBlank()) {
                System.out.println("[ValidationMessageProvider] No lock file - showing info message");
                return Optional.of(new TextMessage.Message("Lock file status",
                    "No lock file generated yet. Click 'Check compatibility' to validate the environment.",
                    MessageType.INFO));
            }

            System.out.println("[ValidationMessageProvider] Lock file present - showing success message");
            // If lock file exists and is not empty, it's valid
            return Optional.of(new TextMessage.Message("Environment validated",
                "Environment validated successfully. Lock file generated.",
                MessageType.SUCCESS));
        }
    }

    // Helper method to build TOML from packages
    private static String buildPixiTomlFromPackages(final PackageSpec[] packages) {
        final CommentedConfig config = CommentedConfig.inMemory();

        final Config projectConfig = Config.inMemory();
        projectConfig.set("name", "myenv");
        projectConfig.set("version", "0.1.0");
        projectConfig.set("description", "Environment created from package list");
        projectConfig.set("channels", List.of("knime", "conda-forge"));
        projectConfig.set("platforms", List.of("win-64", "linux-64", "osx-64", "osx-arm64"));
        config.set("project", projectConfig);

        final Config dependencies = Config.inMemory();
        final Config pipDeps = Config.inMemory();

        for (PackageSpec pkg : packages) {
            if (pkg.m_source == PackageSource.CONDA) {
                dependencies.set(pkg.m_packageName, pkg.m_version + ".*");
            } else {
                pipDeps.set(pkg.m_packageName, pkg.m_version + ".*");
            }
        }

        config.set("dependencies", dependencies);
        if (!pipDeps.isEmpty()) {
            config.set("pypi-dependencies", pipDeps);
        }

        final TomlWriter writer = new TomlWriter();
        writer.setIndent(IndentStyle.SPACES_2);
        final StringWriter stringWriter = new StringWriter();
        writer.write(config, stringWriter);
        return stringWriter.toString();
    }
}
