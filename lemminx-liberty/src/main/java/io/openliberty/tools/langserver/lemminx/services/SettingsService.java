/*******************************************************************************
* Copyright (c) 2020, 2026 IBM Corporation and others.
*
* This program and the accompanying materials are made available under the
* terms of the Eclipse Public License v. 2.0 which is available at
* http://www.eclipse.org/legal/epl-2.0.
*
* SPDX-License-Identifier: EPL-2.0
*
* Contributors:
*     IBM Corporation - initial API and implementation
*******************************************************************************/
package io.openliberty.tools.langserver.lemminx.services;

import io.openliberty.tools.common.plugins.config.ServerConfigDocument;
import io.openliberty.tools.common.plugins.config.VariableLocation;
import io.openliberty.tools.langserver.lemminx.util.CommonLogger;
import io.openliberty.tools.langserver.lemminx.util.LibertyUtils;
import io.openliberty.tools.langserver.lemminx.util.ResourceBundleUtil;
import org.eclipse.lemminx.utils.JSONUtility;
import io.openliberty.tools.langserver.lemminx.models.settings.*;
import org.eclipse.lsp4j.InitializeParams;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Logger;

import static io.openliberty.tools.langserver.lemminx.util.LibertyUtils.findFileInWorkspace;
import static io.openliberty.tools.langserver.lemminx.util.ResourceBundleUtil.toLocale;

public class SettingsService {

    // Singleton so that only 1 Settings Service can be initialized and is
    // shared between all Lemminx Language Feature Participants

    private static SettingsService instance = new SettingsService();

    public static SettingsService getInstance() {
        return instance;
    }

    // default request delay is 10 seconds
    private static int DEFAULT_REQUEST_DELAY = 10;
    private static final Logger LOGGER = Logger.getLogger(SettingsService.class.getName());

    private SettingsService() {
    }

    private LibertySettings settings;

    private Map<String,Properties> variables;
    private Map<String, Map<String, List<VariableLocation>>> variableLocations;
    private Map<String, File> configDirectories; // Maps workspace URI to source config directory
    private Map<String, File> serverDirectories; // Maps workspace URI to target server directory
    private Locale currentLocale = Locale.getDefault();
    private boolean configCopiedToServer = false;
    private String latestRuntimeVersion;
    private Path featureJsonFilePath;

    /**
     * Takes the xml settings object and parses out the Liberty Settings
     * @param xmlSettings - All xml settings provided by the client
     */
    public void updateLibertySettings(Object xmlSettings) {
        AllSettings rootSettings = JSONUtility.toModel(xmlSettings, AllSettings.class);
        if (rootSettings != null) {
            settings = JSONUtility.toModel(rootSettings.getLiberty(), LibertySettings.class);
        }
    }

    public String getLibertyVersion() {
        return settings != null ? settings.getVersion() : null;
    }

    public String getLibertyRuntime() {
        return settings != null ? settings.getRuntime() : null;
    }

    public int getRequestDelay() {
        if (settings != null) {
            int requestDelay = settings.getRequestDelay();
            if (requestDelay > 0) {
                return requestDelay;
            }
        }

        return DEFAULT_REQUEST_DELAY;
    }

    /**
     * populate all variables for all available workspace folders
     *
     * @param workspaceFolders workspace folders
     */
    public void populateAllVariables(Collection<LibertyWorkspace> workspaceFolders) {
        variables = new HashMap<>();
        variableLocations = new HashMap<>();
        configDirectories = new HashMap<>();
        serverDirectories = new HashMap<>();
        for (LibertyWorkspace workspace : workspaceFolders) {
            populateVariablesForWorkspace(workspace);
        }
    }

    /**
     * read all variables from workspace directories
     *
     * @param workspace workspace
     */
    public void populateVariablesForWorkspace(LibertyWorkspace workspace) {
        Properties variablesForWorkspace = new Properties();
        Map<String, List<VariableLocation>> locationsForWorkspace = new HashMap<>();
        Path pluginConfigFilePath = findFileInWorkspace(workspace, Paths.get("liberty-plugin-config.xml"));
        if (pluginConfigFilePath != null) {
            File installDirectory = LibertyUtils.getFileFromLibertyPluginXml(pluginConfigFilePath, "installDirectory");
            File serverDirectory = LibertyUtils.getFileFromLibertyPluginXml(pluginConfigFilePath, "serverDirectory");
            File userDirectory = LibertyUtils.getFileFromLibertyPluginXml(pluginConfigFilePath, "userDirectory");
            File serverOutputDirectory = LibertyUtils.getFileFromLibertyPluginXml(pluginConfigFilePath, "serverOutputDirectory");
            File configDirectory = LibertyUtils.getFileFromLibertyPluginXml(pluginConfigFilePath, "configDirectory");
            
            if (serverDirectory != null && installDirectory != null && userDirectory != null && serverOutputDirectory !=null) {
                try {
                    ServerConfigDocument serverConfigDocument = new ServerConfigDocument(
                            new CommonLogger(LOGGER), null, installDirectory, userDirectory, serverDirectory, serverOutputDirectory);
                    variablesForWorkspace.putAll(serverConfigDocument.getDefaultProperties());
                    variablesForWorkspace.putAll(serverConfigDocument.getProperties());
                    
                    // Get variable locations from ServerConfigDocument
                    Map<String, List<VariableLocation>> configLocations = serverConfigDocument.getVariableLocations();
                    if (configLocations != null) {
                        locationsForWorkspace.putAll(configLocations);
                    }
                    
                    // Store directory mappings for path translation
                    if (configDirectory != null) {
                        configDirectories.put(workspace.getWorkspaceString(), configDirectory);
                    }
                    serverDirectories.put(workspace.getWorkspaceString(), serverDirectory);
                    
                    LOGGER.finest("Populated variables for workspace: " + workspace.getWorkspaceString() + ". Number of variables found: " + variablesForWorkspace.size());
                } catch (Exception e) {
                    LOGGER.warning("Variable resolution is not available because the necessary directory locations were not found in the liberty-plugin-config.xml file.");
                    LOGGER.info("Exception received: " + e.getMessage());
                }
            }
        } else {
            LOGGER.warning("Could not find liberty-plugin-config.xml in workspace URI " + workspace.getWorkspaceString() + ". Variable resolution cannot be performed");
        }
        variables.put(workspace.getWorkspaceString(), variablesForWorkspace);
        variableLocations.put(workspace.getWorkspaceString(), locationsForWorkspace);
    }

    /**
     * Get variables list for a workspace server xml file
     *
     * @param serverXmlURI serverXmlURI
     * @return variables
     */
    public Properties getVariablesForServerXml(String serverXmlURI) {
        LibertyWorkspace workspace = LibertyProjectsManager.getInstance().getWorkspaceFolder(serverXmlURI);
        Properties variableProps = new Properties();
        if (workspace == null) {
            LOGGER.warning("Could not find workspace for server xml URI %s. Variable resolution cannot be performed.".formatted(serverXmlURI));
        } else if (variables != null && variables.containsKey(workspace.getWorkspaceString())) {
            variableProps = variables.get(workspace.getWorkspaceString());
        } else {
            LOGGER.warning("Could not find variable mapping for workspace URI %s. Variable resolution cannot be performed.".formatted(workspace.getWorkspaceString()));
        }
        return variableProps;
    }

    /**
     * Get variable locations for a workspace server xml file
     *
     * @param serverXmlURI serverXmlURI
     * @return variable locations map (variable name -> list of locations)
     */
    public Map<String, List<VariableLocation>> getVariableLocationsForServerXml(String serverXmlURI) {
        LibertyWorkspace workspace = LibertyProjectsManager.getInstance().getWorkspaceFolder(serverXmlURI);
        Map<String, List<VariableLocation>> locations = new HashMap<>();
        if (workspace == null) {
            LOGGER.warning("Could not find workspace for server xml URI %s. Variable location resolution cannot be performed.".formatted(serverXmlURI));
        } else if (variableLocations != null && variableLocations.containsKey(workspace.getWorkspaceString())) {
            locations = variableLocations.get(workspace.getWorkspaceString());
        } else {
            LOGGER.warning("Could not find variable location mapping for workspace URI %s. Variable location resolution cannot be performed.".formatted(workspace.getWorkspaceString()));
        }
        return locations;
    }

    public boolean isConfigCopiedToServer() {
        return configCopiedToServer;
    }

    public void setConfigCopiedToServer(boolean configCopiedToServer) {
        this.configCopiedToServer = configCopiedToServer;
    }

    public Locale getCurrentLocale() {
        return currentLocale;
    }

    /**
     * Initialize locale from IDE extension initialization params
     *
     * @param initializeParams initialization params
     */
    public void initializeLocale(InitializeParams initializeParams) {
        if (initializeParams != null && initializeParams.getLocale() != null && !initializeParams.getLocale().trim().isEmpty()) {
            Locale userLocale = toLocale(initializeParams.getLocale());
            Locale matchingLocale = ResourceBundleUtil.findBestMatchingLocale(userLocale);
            if (matchingLocale != null) {
                this.currentLocale = matchingLocale;
                LOGGER.info("Locale set as %s for locale string %s".formatted(SettingsService.getInstance().getCurrentLocale(), initializeParams.getLocale()));
            } else {
                LOGGER.warning("Setting locale as en as initializeParams locale " + initializeParams.getLocale() + "is not matching with available locales");
                this.currentLocale = Locale.ENGLISH;
            }
        } else {
            LOGGER.warning("Setting locale as en as initializeParams locale is null");
            this.currentLocale = Locale.ENGLISH;
        }
    }

    /**
     * Set locale directly
     * @param locale
     */
    public void setLocale(Locale locale) {
        this.currentLocale = locale;
    }

    // Check if the liberty-plugin-config.xml is copied to server or not
    public boolean isLibertyPluginConfigAvailableInServer(LibertyWorkspace libertyWorkspace) {
        if (libertyWorkspace != null) {
            Path pluginConfigFilePath = LibertyUtils.findFileInWorkspace(libertyWorkspace, Paths.get("liberty-plugin-config.xml"));
            return pluginConfigFilePath != null;
        }
        return false;
    }

    public String getLatestRuntimeVersion() {
        return latestRuntimeVersion;
    }

    public void setLatestRuntimeVersion(String latestRuntimeVersion) {
        this.latestRuntimeVersion = latestRuntimeVersion;
    }

    public Path getFeatureJsonFilePath() {
        return featureJsonFilePath;
    }

    public void setFeatureJsonFilePath(Path featureJsonFilePath) {
        this.featureJsonFilePath = featureJsonFilePath;
    }

    /**
     * Map a target file path to its source equivalent.
     * Dev mode copies files from src/main/liberty/config to target/liberty/wlp/usr/servers/serverName.
     * This method maps the target path back to the source path for "Go to Definition".
     *
     * @param serverXmlURI The server.xml URI to determine the workspace
     * @param targetPath   The path in the target directory
     * @return The corresponding source path, or the original path if mapping fails
     */
    public String mapTargetPathToSource(String serverXmlURI, String targetPath) {
        try {
            LibertyWorkspace workspace = LibertyProjectsManager.getInstance().getWorkspaceFolder(serverXmlURI);
            if (workspace == null) {
                return targetPath;
            }

            String workspaceKey = workspace.getWorkspaceString();
            File configDir = configDirectories != null ? configDirectories.get(workspaceKey) : null;
            File serverDir = serverDirectories != null ? serverDirectories.get(workspaceKey) : null;

            if (configDir == null || serverDir == null) {
                return targetPath;
            }

            // Convert to canonical paths for comparison
            String targetCanonical = new File(targetPath).getCanonicalPath();
            String serverDirCanonical = serverDir.getCanonicalPath();
            String configDirCanonical = configDir.getCanonicalPath();

            // If the target path is under the server directory, map it to config directory
            if (targetCanonical.startsWith(serverDirCanonical)) {
                String relativePath = targetCanonical.substring(serverDirCanonical.length());
                String sourcePath = configDirCanonical + relativePath;
                
                // Check if the source file exists
                File sourceFile = new File(sourcePath);
                if (sourceFile.exists()) {
                    LOGGER.fine("Mapped target path " + targetPath + " to source path " + sourcePath);
                    return sourcePath;
                }
            }

            return targetPath;
        } catch (Exception e) {
            LOGGER.warning("Error mapping target path to source: " + e.getMessage());
            return targetPath;
        }
    }
}
