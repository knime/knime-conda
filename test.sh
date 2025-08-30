#!/bin/bash

# Arguments:
# $1: Path to the root of a KNIME installation (../KNIME.app/Contents/Eclipse/ for MacOS)
if [ -z "$1" ]; then
    echo "Error: Path to the KNIME installation is required as the first argument."
    exit 1
fi
KNIME_ROOT=$1

# Build the project
mvn clean package

# Create a temporary directory for the Eclipse installation
DEST=$(mktemp -d)

# Use the p2 director to create a minimal Eclipse installation
java -jar "${KNIME_ROOT}/plugins/org.eclipse.equinox.launcher_1.6.700.v20240213-1244.jar" \
    -application org.eclipse.equinox.p2.director \
    -nosplash -consolelog \
    -repository 'https://jenkins.devops.knime.com/p2/knime/composites/master' \
    -installIU org.knime.minimal.product \
    -destination ${DEST}

# Install the test extension from the local build
java -jar "${DEST}/plugins/org.eclipse.equinox.launcher_1.6.700.v20240213-1244.jar" \
    -application org.eclipse.equinox.p2.director \
    -nosplash -consolelog \
    -repository "file://$(pwd)/org.knime.update.conda/target/repository,https://jenkins.devops.knime.com/p2-browse/knime/composites/master" \
    -installIU org.knime.features.conda.envinstall.testext.feature.group

# Ask if the user wants to delete the temporary directory
echo "Do you want to delete the temporary directory ${DEST}? (y/n)"
read -r answer
if [[ $answer == "y" || $answer == "Y" ]]; then
    rm -rf "${DEST}"
    echo "Temporary directory ${DEST} deleted."
else
    echo "Temporary directory ${DEST} not deleted."
fi
