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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
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
import org.knime.core.node.InvalidSettingsException;
import org.knime.core.node.NodeLogger;
import org.knime.core.node.NodeSettingsRO;
import org.knime.core.node.NodeSettingsWO;
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
import org.knime.node.parameters.persistence.NodeParametersPersistor;
import org.knime.node.parameters.persistence.Persist;
import org.knime.node.parameters.persistence.Persistor;
import org.knime.node.parameters.updates.Effect;
import org.knime.node.parameters.updates.Effect.EffectType;
import org.knime.node.parameters.updates.EffectPredicate;
import org.knime.node.parameters.updates.EffectPredicateProvider;
import org.knime.node.parameters.updates.ParameterReference;
import org.knime.node.parameters.updates.StateProvider;
import org.knime.node.parameters.updates.StateProvider.StateProviderInitializer;
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

    @After(mainInputSelectionSection.class)
    static final class tomlFileSection {
    }

    @After(mainInputSelectionSection.class)
    static final class bundledEnvironmentSection {
    }

    @After(mainInputSelectionSection.class)
    static final class yamlEditorSection {
    }

    @After(simpleInputSection.class)
    @After(tomlEditorSection.class)
    @After(tomlFileSection.class)
    @After(bundledEnvironmentSection.class)
    @After(yamlEditorSection.class)
    static final class lockFileSection {
    }

    // Input source selection
    enum MainInputSource {
        @Label("Packages")
        SIMPLE,
        @Label("TOML editor")
        TOML_EDITOR,
        @Label("TOML file")
        TOML_FILE,
        @Label("YAML editor")
        YAML_EDITOR,
        @Label("Bundled environment")
        BUNDLING_ENVIRONMENT
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
        new PackageSpec("python", PackageSource.CONDA, "3.14", "3.14"),
        new PackageSpec("knime-python-base", PackageSource.CONDA, "5.9", "5.9")
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

    // TOML file input
    @Widget(title = "pixi.toml file",
        description = "Select the pixi.toml file to read the environment specification from.")
    @FileSelectionWidget(value = SingleFileSelectionMode.FILE)
    @FileReaderWidget(fileExtensions = {"toml"})
    @Layout(tomlFileSection.class)
    @ValueReference(TomlFileRef.class)
    @Effect(predicate = InputIsTomlFile.class, type = EffectType.SHOW)
    FileSelection m_tomlFile = new FileSelection();

    interface TomlFileRef extends ParameterReference<FileSelection> {
    }

    interface BundledEnvironmentRef extends ParameterReference<String> {
    }

    static final class InputIsTomlFile implements EffectPredicateProvider {
        @Override
        public EffectPredicate init(final PredicateInitializer i) {
            return i.getEnum(MainInputSourceRef.class).isOneOf(MainInputSource.TOML_FILE);
        }
    }

    // Bundled environment input
    @Widget(title = "Bundled environment",
        description = "Select a pixi environment from the bundled environments directory.")
    @ChoicesProvider(BundledPixiEnvironmentChoicesProvider.class)
    @Layout(bundledEnvironmentSection.class)
    @Effect(predicate = InputIsBundledEnvironment.class, type = EffectType.SHOW)
    String m_bundledEnvironment;

    // YAML editor input
    @Widget(title = "Environment specification (conda environment.yaml)", description = """
            Content of the conda environment.yaml file that describes the environment.
            This will be imported into pixi using `pixi init --import` and converted to a pixi.toml manifest.
            The environment will automatically be configured to work on all major platforms (win-64, linux-64, osx-64, osx-arm64).
            """)
    @Layout(yamlEditorSection.class)
    @TextAreaWidget(rows = 20)
    @ValueReference(YamlContentRef.class)
    @Effect(predicate = InputIsYaml.class, type = EffectType.SHOW)
    String m_envYamlContent = """
            name: myenv
            channels:
              - knime
              - conda-forge
            dependencies:
              - python=3.14.*
              - knime-python-base=5.9.*
            """;

    interface YamlContentRef extends ParameterReference<String> {
    }

    static final class InputIsYaml implements EffectPredicateProvider {
        @Override
        public EffectPredicate init(final PredicateInitializer i) {
            return i.getEnum(MainInputSourceRef.class).isOneOf(MainInputSource.YAML_EDITOR);
        }
    }

    static final class InputIsBundledEnvironment implements EffectPredicateProvider {
        @Override
        public EffectPredicate init(final PredicateInitializer i) {
            return i.getEnum(MainInputSourceRef.class).isOneOf(MainInputSource.BUNDLING_ENVIRONMENT);
        }
    }

    /**
     * Provider that lists all pixi environments from the bundling folder that contain a pixi.toml file.
     */
    static final class BundledPixiEnvironmentChoicesProvider implements StringChoicesProvider {

        private static final NodeLogger LOGGER =
            NodeLogger.getLogger(BundledPixiEnvironmentChoicesProvider.class);

        @Override
        public List<String> choices(final NodeParametersInput context) {
            try {
                // Get the bundling root directory (same logic as BundlingRoot)
                Path bundlingRoot = getBundlingRootPath();
                System.out.println("[BundledPixiEnvironmentChoicesProvider] Scanning bundling root: " + bundlingRoot);

                if (!Files.exists(bundlingRoot)) {
                    System.out.println("[BundledPixiEnvironmentChoicesProvider] Bundling root does not exist");
                    return List.of();
                }

                // List all subdirectories in bundling/ that contain pixi.toml
                // Structure: bundling/<some_name>/pixi.toml
                var environments = Files.list(bundlingRoot)
                    .filter(Files::isDirectory)
                    .filter(dir -> {
                        Path tomlPath = dir.resolve("pixi.toml");
                        boolean hasToml = Files.exists(tomlPath);
                        System.out.println("[BundledPixiEnvironmentChoicesProvider] Checking " + dir.getFileName() + ": pixi.toml " + (hasToml ? "found" : "not found"));
                        return hasToml;
                    })
                    .map(dir -> dir.getFileName().toString())
                    .sorted()
                    .collect(Collectors.toList());

                System.out.println("[BundledPixiEnvironmentChoicesProvider] Found " + environments.size() + " environments");
                return environments;

            } catch (Exception e) {
                LOGGER.error("Failed to list bundled pixi environments: " + e.getMessage(), e);
                System.out.println("[BundledPixiEnvironmentChoicesProvider] Error: " + e.getMessage());
                e.printStackTrace();
                return List.of();
            }
        }
    }

    // Lock file generation
    @Widget(title = "Check compatibility",
        description = "Click to check whether this environment can be constructed on all selected operating systems")
    @Layout(lockFileSection.class)
    @ButtonWidget(actionHandler = PixiLockActionHandler.class, updateHandler = PixiLockUpdateHandler.class)
    @ValueReference(ButtonFieldRef.class)
    String m_pixiLockButton;

    @Widget(title = "Update dependencies",
        description = "Click to update all dependencies to their latest compatible versions and update the lock file")
    @Layout(lockFileSection.class)
    @ButtonWidget(actionHandler = PixiUpdateActionHandler.class, updateHandler = PixiUpdateUpdateHandler.class)
    @ValueReference(ButtonFieldRef.class)
    String m_pixiUpdateButton;

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

    // Read-only display of lock file content (advanced setting)
    @Widget(title = "Lock file content",
        description = "Content of the generated or loaded pixi.lock file.", advanced = true)
    @Layout(lockFileSection.class)
    @TextAreaWidget(rows = 10)
    @Persistor(DoNotPersist.class)
    @ValueProvider(LockContentDisplayProvider.class)
    String m_lockFileDisplay = "";

    static final class LockContentDisplayProvider implements StateProvider<String> {
        private Supplier<String> m_lockContentSupplier;

        @Override
        public void init(final StateProviderInitializer initializer) {
            initializer.computeOnValueChange(PixiLockFileRef.class);
            m_lockContentSupplier = initializer.getValueSupplier(PixiLockFileRef.class);
        }

        @Override
        public String computeState(final NodeParametersInput context) {
            final String lockContent = m_lockContentSupplier.get();
            return (lockContent != null) ? lockContent : "";
        }
    }

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
            case TOML_FILE:
                System.out.println("Reading pixi.toml from file");
                if (m_tomlFile == null || m_tomlFile.m_path == null) {
                    throw new IOException("No TOML file selected");
                }
                Path tomlPath = Path.of(m_tomlFile.m_path.getPath());
                if (!Files.exists(tomlPath)) {
                    throw new IOException("TOML file does not exist: " + tomlPath);
                }
                return Files.readString(tomlPath);
            case YAML_EDITOR:
                System.out.println("Converting YAML from editor to TOML");
                return PixiYamlImporter.convertYamlToToml(m_envYamlContent);
            case BUNDLING_ENVIRONMENT:
                System.out.println("Reading pixi.toml from bundled environment");
                if (m_bundledEnvironment == null || m_bundledEnvironment.isEmpty()) {
                    throw new IOException("No bundled environment selected");
                }
                try {
                    // Get the bundling root and construct path to environment directory
                    Path bundlingRoot = getBundlingRootPath();
                    Path bundledEnvDir = bundlingRoot.resolve(m_bundledEnvironment);
                    Path bundledTomlPath = bundledEnvDir.resolve("pixi.toml");
                    if (!Files.exists(bundledTomlPath)) {
                        throw new IOException("pixi.toml not found in bundled environment: " + bundledTomlPath);
                    }
                    return Files.readString(bundledTomlPath);
                } catch (Exception e) {
                    throw new IOException("Failed to read bundled environment: " + e.getMessage(), e);
                }
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

        @Widget(title = "Min version", description = "Minimum version (inclusive, optional)")
        String m_minVersion = "";

        @Widget(title = "Max version", description = "Maximum version (exclusive, optional)")
        String m_maxVersion = "";

        PackageSpec() {
        }

        PackageSpec(final String name, final PackageSource source, final String minVersion, final String maxVersion) {
            m_packageName = name;
            m_source = source;
            m_minVersion = minVersion;
            m_maxVersion = maxVersion;
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

        @ValueReference(TomlFileRef.class)
        FileSelection m_tomlFile;

        @ValueReference(YamlContentRef.class)
        String m_envYamlContent;

        @ValueReference(BundledEnvironmentRef.class)
        String m_bundledEnvironment;

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
                case TOML_FILE:
                    System.out.println("[TomlContentGetter] Reading TOML from file for lock generation");
                    try {
                        if (m_tomlFile == null || m_tomlFile.m_path == null) {
                            System.out.println("[TomlContentGetter] No file selected");
                            return "";
                        }
                        Path tomlPath = Path.of(m_tomlFile.m_path.getPath());
                        if (!Files.exists(tomlPath)) {
                            System.out.println("[TomlContentGetter] File does not exist: " + tomlPath);
                            return "";
                        }
                        String content = Files.readString(tomlPath);
                        System.out.println("[TomlContentGetter] Read TOML (" + content.length() + " chars)");
                        return content;
                    } catch (IOException e) {
                        System.out.println("[TomlContentGetter] Error reading file: " + e.getMessage());
                        return "";
                    }
                case YAML_EDITOR:
                    System.out.println("[TomlContentGetter] Converting YAML from editor to TOML");
                    try {
                        String toml = PixiYamlImporter.convertYamlToToml(m_envYamlContent);
                        System.out.println("[TomlContentGetter] Converted TOML (" + toml.length() + " chars)");
                        return toml;
                    } catch (Exception e) {
                        System.out.println("[TomlContentGetter] Error converting YAML: " + e.getMessage());
                        return "";
                    }
                case BUNDLING_ENVIRONMENT:
                    System.out.println("[TomlContentGetter] Reading TOML from bundled environment");
                    try {
                        if (m_bundledEnvironment == null || m_bundledEnvironment.isEmpty()) {
                            System.out.println("[TomlContentGetter] No bundled environment selected");
                            return "";
                        }
                        Path bundlingRoot = getBundlingRootPath();
                        Path bundledEnvDir = bundlingRoot.resolve(m_bundledEnvironment);
                        Path bundledTomlPath = bundledEnvDir.resolve("pixi.toml");
                        if (!Files.exists(bundledTomlPath)) {
                            System.out.println("[TomlContentGetter] TOML file does not exist: " + bundledTomlPath);
                            return "";
                        }
                        String content = Files.readString(bundledTomlPath);
                        System.out.println("[TomlContentGetter] Read TOML (" + content.length() + " chars)");
                        return content;
                    } catch (Exception e) {
                        System.out.println("[TomlContentGetter] Error reading bundled environment: " + e.getMessage());
                        return "";
                    }
                default:
                    System.out.println("[TomlContentGetter] Unknown input source: " + m_mainInputSource);
                    return "";
            }
        }
    }

    static final class PixiLockUpdateHandler extends CancelableActionHandler.UpdateHandler<String, TomlContentGetter> {
    }

    static final class PixiUpdateActionHandler
        extends PixiParameterUtils.AbstractPixiUpdateActionHandler<TomlContentGetter> {

        public PixiUpdateActionHandler() {
            super("[PythonEnvironmentProviderNode]");
        }

        @Override
        protected String getManifestContent(final TomlContentGetter contentGetter) {
            return contentGetter.getTomlContent();
        }

        @Override
        protected String prepareManifestContent(final String content) throws Exception {
            // Content is already TOML, no conversion needed
            return content;
        }
    }

    static final class PixiUpdateUpdateHandler extends CancelableActionHandler.UpdateHandler<String, TomlContentGetter> {
    }

    /**
     * Resets lock file to empty when any input content changes.
     */
    static final class ResetLockFileProvider implements StateProvider<String> {

        private Supplier<MainInputSource> m_inputSourceSupplier;
        private Supplier<PackageSpec[]> m_packagesSupplier;
        private Supplier<String> m_tomlContentSupplier;
        private Supplier<String> m_yamlContentSupplier;
        private Supplier<String> m_buttonFieldSupplier;

        private MainInputSource m_lastInputSource;
        private PackageSpec[] m_lastPackages;
        private String m_lastTomlContent;
        private String m_lastYamlContent;

        @Override
        public void init(final StateProviderInitializer initializer) {
            System.out.println("[ResetLockFileProvider] Initializing...");
            initializer.computeOnValueChange(MainInputSourceRef.class);
            initializer.computeOnValueChange(PackageArrayRef.class);
            initializer.computeOnValueChange(TomlContentRef.class);
            initializer.computeOnValueChange(YamlContentRef.class);
            initializer.computeOnValueChange(ButtonFieldRef.class);

            m_inputSourceSupplier = initializer.getValueSupplier(MainInputSourceRef.class);
            m_packagesSupplier = initializer.getValueSupplier(PackageArrayRef.class);
            m_tomlContentSupplier = initializer.getValueSupplier(TomlContentRef.class);
            m_yamlContentSupplier = initializer.getValueSupplier(YamlContentRef.class);
            m_buttonFieldSupplier = initializer.getValueSupplier(ButtonFieldRef.class);
        }

        @Override
        public String computeState(final NodeParametersInput context) {
            final MainInputSource currentInputSource = m_inputSourceSupplier.get();
            final PackageSpec[] currentPackages = m_packagesSupplier.get();
            final String currentTomlContent = m_tomlContentSupplier.get();
            final String currentYamlContent = m_yamlContentSupplier.get();
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

            if (currentInputSource == MainInputSource.YAML_EDITOR &&
                m_lastYamlContent != null && !m_lastYamlContent.equals(currentYamlContent)) {
                System.out.println("[ResetLockFileProvider] YAML content changed");
                contentChanged = true;
            }

            // Update last values
            m_lastInputSource = currentInputSource;
            m_lastPackages = currentPackages;
            m_lastTomlContent = currentTomlContent;
            m_lastYamlContent = currentYamlContent;

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

    // Helper method to build TOML from packages (using workspace structure for pixi)
    private static String buildPixiTomlFromPackages(final PackageSpec[] packages) {
        Config config = Config.inMemory();

        // [workspace] section (required by pixi)
        CommentedConfig workspace = CommentedConfig.inMemory();
        workspace.set("channels", Arrays.asList("knime", "conda-forge"));
        workspace.set("platforms", Arrays.asList("win-64", "linux-64", "osx-64", "osx-arm64"));
        config.set("workspace", workspace);

        // [dependencies] section for conda packages
        CommentedConfig dependencies = CommentedConfig.inMemory();
        for (PackageSpec pkg : packages) {
            if (pkg.m_packageName == null || pkg.m_packageName.isBlank() || pkg.m_source == PackageSource.PIP) {
                continue;
            }
            dependencies.set(pkg.m_packageName, formatVersionConstraint(pkg));
        }
        config.set("dependencies", dependencies);

        // [pypi-dependencies] section for pip packages
        CommentedConfig pypiDependencies = CommentedConfig.inMemory();
        boolean hasPipPackages = false;
        for (PackageSpec pkg : packages) {
            if (pkg.m_packageName != null && !pkg.m_packageName.isBlank() && pkg.m_source == PackageSource.PIP) {
                pypiDependencies.set(pkg.m_packageName, formatVersionConstraint(pkg));
                hasPipPackages = true;
            }
        }
        if (hasPipPackages) {
            config.set("pypi-dependencies", pypiDependencies);
        }

        // Write to string
        StringWriter writer = new StringWriter();
        TomlWriter tomlWriter = new TomlWriter();
        tomlWriter.setIndent(IndentStyle.SPACES_2);
        tomlWriter.setWriteTableInlinePredicate(path -> false); // Never write tables inline
        tomlWriter.write(config, writer);
        return writer.toString();
    }

    private static String formatVersionConstraint(final PackageSpec pkg) {
        boolean hasMin = pkg.m_minVersion != null && !pkg.m_minVersion.isBlank();
        boolean hasMax = pkg.m_maxVersion != null && !pkg.m_maxVersion.isBlank();

        if (hasMin && hasMax) {
            return ">="+ pkg.m_minVersion + ",<=" + pkg.m_maxVersion;
        } else if (hasMin) {
            return ">=" + pkg.m_minVersion;
        } else if (hasMax) {
            return "<=" + pkg.m_maxVersion;
        } else {
            return "*";
        }
    }

    /**
     * Custom persistor that doesn't persist the field - used for computed/display-only fields.
     */
    static final class DoNotPersist implements NodeParametersPersistor<String> {
        @Override
        public String load(final NodeSettingsRO settings) throws InvalidSettingsException {
            return null;
        }

        @Override
        public void save(final String obj, final NodeSettingsWO settings) {
            // Don't persist - this is a computed field
        }

        @Override
        public String[][] getConfigPaths() {
            return new String[0][];
        }
    }

    /**
     * Get the bundling root path using the same logic as BundlingRoot.
     * Helper method used by both the choices provider and TOML reading logic.
     */
    static Path getBundlingRootPath() throws Exception {
        // Check for KNIME_PYTHON_BUNDLING_PATH environment variable
        var bundlingPathFromVar = System.getenv("KNIME_PYTHON_BUNDLING_PATH");
        if (bundlingPathFromVar != null && !bundlingPathFromVar.isBlank()) {
            return Path.of(bundlingPathFromVar);
        }

        // Otherwise use installation_root/bundling
        Path installationRoot = getInstallationRoot();
        return installationRoot.resolve("bundling");
    }

    /**
     * Get the KNIME installation root directory.
     * Helper method used by getBundlingRootPath().
     */
    private static Path getInstallationRoot() throws Exception {
        var bundle = org.eclipse.core.runtime.Platform.getBundle("org.knime.pixi.nodes");
        String bundleLocationString = org.eclipse.core.runtime.FileLocator.getBundleFileLocation(bundle).orElseThrow().getAbsolutePath();
        Path bundleLocationPath = Path.of(bundleLocationString);
        return bundleLocationPath.getParent().getParent();
    }
}
