Note:
We cannot depend on snakeyaml provided by the target platform. The `org.knime.conda.envinstall` plugin needs to be free of dependencies that are upgraded to prevent changes to the active plugins when the action is loaded for the installation of Python-based extensions.
See AP-24536 for details.
