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
 *   Jan 22, 2026 (Marc Lehner): created
 */
package org.knime.pixi.nodes;

import java.io.StringReader;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.knime.node.parameters.widget.message.TextMessage;
import org.knime.node.parameters.widget.message.TextMessage.MessageType;

import com.electronwill.nightconfig.core.Config;
import com.electronwill.nightconfig.toml.TomlParser;

/**
 * Utility class for validating pixi.toml manifests, especially platform configuration.
 *
 * @author Carsten Haubolt, KNIME GmbH, Konstanz, Germany
 * @author Marc Lehner, KNIME GmbH, Zurich, Switzerland
 * @since 5.11
 */
final class PixiTomlValidator {

    private static final Set<String> ALL_PLATFORMS = Set.of("win-64", "linux-64", "osx-64", "osx-arm64");

    private PixiTomlValidator() {
        // Utility class
    }

    /**
     * Validation result for TOML platform configuration.
     */
    enum ValidationResult {
            /** All platforms are correctly configured */
            ALL_PLATFORMS_PRESENT,
            /** No workspace section found */
            NO_WORKSPACE_SECTION,
            /** No platforms field found */
            NO_PLATFORMS_FIELD,
            /** Unknown platforms specified */
            UNKNOWN_PLATFORMS,
            /** Some platforms are missing */
            MISSING_PLATFORMS,
            /** TOML parsing error */
            PARSE_ERROR
    }

    /**
     * Result of platform validation containing the validation result and additional details.
     */
    record PlatformValidation(ValidationResult result, Set<String> missingPlatforms, Set<String> unknownPlatforms,
        String errorMessage) {

        static PlatformValidation allPlatformsPresent() {
            return new PlatformValidation(ValidationResult.ALL_PLATFORMS_PRESENT, Set.of(), Set.of(), null);
        }

        static PlatformValidation noWorkspaceSection() {
            return new PlatformValidation(ValidationResult.NO_WORKSPACE_SECTION, Set.of(), Set.of(), null);
        }

        static PlatformValidation noPlatformsField() {
            return new PlatformValidation(ValidationResult.NO_PLATFORMS_FIELD, Set.of(), Set.of(), null);
        }

        static PlatformValidation unknownPlatforms(final Set<String> unknownPlatforms) {
            return new PlatformValidation(ValidationResult.UNKNOWN_PLATFORMS, Set.of(), unknownPlatforms, null);
        }

        static PlatformValidation missingPlatforms(final Set<String> missingPlatforms) {
            return new PlatformValidation(ValidationResult.MISSING_PLATFORMS, missingPlatforms, Set.of(), null);
        }

        static PlatformValidation parseError(final String errorMessage) {
            return new PlatformValidation(ValidationResult.PARSE_ERROR, Set.of(), Set.of(), errorMessage);
        }

        /**
         * Returns true if this validation result represents a warning or error condition.
         */
        boolean isWarningOrError() {
            return result != ValidationResult.ALL_PLATFORMS_PRESENT;
        }
    }

    /**
     * Validate the platform configuration in a pixi.toml manifest.
     *
     * @param tomlContent the pixi.toml content to validate
     * @return the validation result
     */
    static PlatformValidation validatePlatforms(final String tomlContent) {
        if (tomlContent == null || tomlContent.isBlank()) {
            return PlatformValidation.allPlatformsPresent();
        }

        try {
            TomlParser parser = new TomlParser();
            Config config = parser.parse(new StringReader(tomlContent));

            // Try to get the workspace section
            Config workspace = config.get("workspace");
            if (workspace == null) {
                return PlatformValidation.noWorkspaceSection();
            }

            // Try to get the platforms list
            List<?> platformsList = workspace.get("platforms");
            if (platformsList == null || platformsList.isEmpty()) {
                return PlatformValidation.noPlatformsField();
            }

            // Convert to set of strings
            Set<String> platforms = platformsList.stream().map(Object::toString).collect(Collectors.toSet());

            // Check if all platforms are present
            if (platforms.equals(ALL_PLATFORMS)) {
                return PlatformValidation.allPlatformsPresent();
            }

            // Check whether all platforms are known
            Set<String> unknownPlatforms = new HashSet<>(platforms);
            unknownPlatforms.removeAll(ALL_PLATFORMS);

            if (!unknownPlatforms.isEmpty()) {
                return PlatformValidation.unknownPlatforms(unknownPlatforms);
            }

            // Check which platforms are missing
            Set<String> missingPlatforms = new HashSet<>(ALL_PLATFORMS);
            missingPlatforms.removeAll(platforms);

            if (!missingPlatforms.isEmpty()) {
                return PlatformValidation.missingPlatforms(missingPlatforms);
            }

            return PlatformValidation.allPlatformsPresent();

        } catch (Exception ex) {
            return PlatformValidation.parseError(ex.getMessage());
        }
    }

    /**
     * Convert a platform validation result to a user-facing message.
     *
     * @param validation the validation result
     * @return the text message, or empty if validation passed
     */
    static Optional<TextMessage.Message> toMessage(final PlatformValidation validation) {
        switch (validation.result()) {
//            case ALL_PLATFORMS_PRESENT:
//                return Optional.of(new TextMessage.Message("Platform configuration",
//                    "Pixi TOML is prepared for all operating systems.", MessageType.INFO));

            case NO_WORKSPACE_SECTION:
                return Optional.of(new TextMessage.Message("Platform configuration",
                    "No '[workspace]' section found. Environment will only be checked for the current platform.",
                    MessageType.WARNING));

            case NO_PLATFORMS_FIELD:
                return Optional.of(new TextMessage.Message("Platform configuration",
                    "No 'platforms' field found in workspace section. Environment will only be checked for the current platform. Consider adding \" platforms = [\"win-64\", \"linux-64\", \"osx-64\", \"osx-arm64\"]\" to the workspace part of your TOML.",
                    MessageType.WARNING));

            case UNKNOWN_PLATFORMS:
                String unknownOSes = validation.unknownPlatforms().stream().collect(Collectors.joining(", "));
                String knownOSes = ALL_PLATFORMS.stream().collect(Collectors.joining(", "));
                return Optional.of(new TextMessage.Message("Platform configuration",
                    "Unknown platform(s): " + unknownOSes + ". Platforms should be one of: " + knownOSes + ".",
                    MessageType.WARNING));

            case MISSING_PLATFORMS:
                String missingOs = validation.missingPlatforms().stream().map(PixiTomlValidator::platformDisplayName)
                    .collect(Collectors.joining(", "));
                String missingOSsStringToAdd = validation.missingPlatforms().stream().map(os -> "\"" + os + "\"")
                    .collect(Collectors.joining(", "));
                return Optional.of(new TextMessage.Message("Platform configuration",
                    "Missing platform(s): " + missingOs
                        + ". Environment may not work on all operating systems. Consider adding "
                        + missingOSsStringToAdd + " to the platforms field in the workspace part of your TOML.",
                    MessageType.WARNING));

            case PARSE_ERROR:
                return Optional.of(new TextMessage.Message("TOML Parse Error",
                    "Could not parse TOML content: " + validation.errorMessage(), MessageType.ERROR));

            default:
                return Optional.empty();
        }
    }

    /**
     * Convert a platform identifier to a user-friendly display name.
     */
    private static String platformDisplayName(final String platformIdentifier) {
        if (platformIdentifier.startsWith("win-")) {
            return "Windows";
        }
        if (platformIdentifier.startsWith("linux-")) {
            return "Linux (and KNIME Hub)";
        }
        if (platformIdentifier.equals("osx-64")) {
            return "macOS (Intel)";
        }
        if (platformIdentifier.equals("osx-arm64")) {
            return "macOS (Apple Silicon)";
        }
        return platformIdentifier;
    }
}
