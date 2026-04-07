/*******************************************************************************
* Copyright (c) 2026 IBM Corporation and others.
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
package io.openliberty.tools.langserver.lemminx;

import io.openliberty.tools.common.plugins.config.VariableLocation;
import io.openliberty.tools.langserver.lemminx.models.feature.VariableLoc;
import io.openliberty.tools.langserver.lemminx.services.SettingsService;
import io.openliberty.tools.langserver.lemminx.util.LibertyConstants;
import io.openliberty.tools.langserver.lemminx.util.LibertyUtils;
import org.apache.xerces.impl.XMLEntityManager;
import org.eclipse.lemminx.dom.DOMAttr;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMElement;
import org.eclipse.lemminx.dom.DOMNode;
import org.eclipse.lemminx.dom.DOMParser;
import org.eclipse.lemminx.services.extensions.IDefinitionParticipant;
import org.eclipse.lemminx.services.extensions.IDefinitionRequest;
import org.eclipse.lemminx.utils.XMLPositionUtility;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Liberty Definition Participant for providing "Go to Definition" support for variables.
 * Allows users to Ctrl+Click on a variable reference (e.g., ${myVar}) to navigate to its definition.
 */
public class LibertyDefinitionParticipant implements IDefinitionParticipant {
    private static final Logger LOGGER = Logger.getLogger(LibertyDefinitionParticipant.class.getName());

    @Override
    public void findDefinition(IDefinitionRequest request, List<LocationLink> locations, CancelChecker cancelChecker) {
        if (!LibertyUtils.isConfigXMLFile(request.getXMLDocument())) {
            return;
        }

        DOMDocument document = request.getXMLDocument();
        DOMNode node = request.getNode();
        int offset = request.getOffset();
        
        if (node == null) {
            return;
        }

        // Check if we're in an attribute value that contains a variable
        if (node.isAttribute()) {
            DOMAttr attr = (DOMAttr) node;
            String attrValue = attr.getValue();
            if (attrValue != null && attrValue.contains("${")) {
                findVariableDefinition(document, attrValue, offset, attr.getNodeAttrValue().getStart(), locations);
            }
        } else if (node.isText()) {
            // Check if we're in text content that contains a variable
            String textContent = node.getTextContent();
            if (textContent != null && textContent.contains("${")) {
                findVariableDefinition(document, textContent, offset, node.getStart(), locations);
            }
        } else {
            // Try to find the attribute at the current offset
            DOMAttr attr = findAttributeAtOffset(document, offset);
            if (attr != null) {
                String attrValue = attr.getValue();
                if (attrValue != null && attrValue.contains("${")) {
                    findVariableDefinition(document, attrValue, offset, attr.getNodeAttrValue().getStart(), locations);
                }
            }
        }
    }
    
    /**
     * Find the attribute at the given offset.
     *
     * @param document The DOM document
     * @param offset   The cursor offset
     * @return The attribute at the offset, or null if not found
     */
    private DOMAttr findAttributeAtOffset(DOMDocument document, int offset) {
        DOMNode node = document.findNodeAt(offset);
        if (node == null) {
            return null;
        }
        
        if (node.isAttribute()) {
            return (DOMAttr) node;
        }
        
        if (node.isElement()) {
            DOMElement element = (DOMElement) node;
            List<DOMAttr> attributes = element.getAttributeNodes();
            if (attributes != null) {
                for (DOMAttr attr : attributes) {
                    if (attr.getNodeAttrValue() != null) {
                        int start = attr.getNodeAttrValue().getStart();
                        int end = attr.getNodeAttrValue().getEnd();
                        if (offset >= start && offset <= end) {
                            return attr;
                        }
                    }
                }
            }
        }
        
        return null;
    }

    /**
     * Find the definition of a variable in the given text content.
     *
     * @param document    The DOM document
     * @param textContent The text content containing variables
     * @param offset      The cursor offset in the document
     * @param baseOffset  The starting offset of the text content in the document
     * @param locations   The list to add location links to
     */
    private void findVariableDefinition(DOMDocument document, String textContent, int offset, int baseOffset, List<LocationLink> locations) {
        // Get all variables from the text content - they have offsets relative to baseOffset
        List<VariableLoc> variables = LibertyUtils.getVariablesFromTextContent(document, textContent);
        
        if (variables.isEmpty()) {
            return;
        }

        // Find which variable the cursor is on
        VariableLoc targetVariable = null;
        for (VariableLoc variable : variables) {
            // Variable locations are relative to the text content start
            // We need to add baseOffset to get absolute document positions
            int varStart = baseOffset + variable.getStartLoc() - 2; // Account for ${
            int varEnd = baseOffset + variable.getEndLoc() + 1;     // Account for }
            
            if (offset >= varStart && offset <= varEnd) {
                targetVariable = variable;
                break;
            }
        }

        if (targetVariable == null) {
            return;
        }

        String variableName = targetVariable.getValue();
        
        // Adjust the variable location to absolute document offsets
        VariableLoc adjustedVariable = new VariableLoc(
            variableName,
            baseOffset + targetVariable.getStartLoc(),
            baseOffset + targetVariable.getEndLoc()
        );
        
        // Find the variable definition in the document
        LocationLink definitionLocation = findVariableDefinitionInDocument(document, variableName, adjustedVariable);
        
        if (definitionLocation != null) {
            locations.add(definitionLocation);
        }
    }

    /**
     * Find the variable definition across all sources.
     * For XML files: Search directly in the DOM (has accurate line numbers)
     * For other files: Use SettingsService locations (bootstrap.properties, server.env, variables directory)
     *
     * @param document       The DOM document
     * @param variableName   The name of the variable to find
     * @param referenceVar   The variable reference location
     * @return LocationLink to the variable definition, or null if not found
     */
    private LocationLink findVariableDefinitionInDocument(DOMDocument document, String variableName, VariableLoc referenceVar) {
        try {
            // First, try to find in the current server.xml (has accurate line numbers)
            LocationLink xmlLocation = findVariableInServerXml(document, variableName, referenceVar);
            if (xmlLocation != null) {
                return xmlLocation;
            }
            
            // If not found in XML, search in properties files and variables directory
            String documentURI = document.getDocumentURI();
            Map<String, List<VariableLocation>> variableLocations =
                SettingsService.getInstance().getVariableLocationsForServerXml(documentURI);
            
            if (variableLocations == null || !variableLocations.containsKey(variableName)) {
                LOGGER.fine("Variable '" + variableName + "' not found in variable locations");
                return null;
            }
            
            // Get all locations for this variable (there may be multiple definitions)
            List<VariableLocation> locations = variableLocations.get(variableName);
            if (locations == null || locations.isEmpty()) {
                return null;
            }
            
            // Filter out XML files (line number = 0) and use the first non-XML location
            for (VariableLocation varLocation : locations) {
                if (varLocation.getLineNumber() > 0) {
                    // This is from a properties file or variables directory
                    return createLocationLink(document, varLocation, referenceVar);
                }
            }
            
            return null;
            
        } catch (Exception e) {
            LOGGER.severe("Error finding variable definition: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * Find variable definition in the server.xml document and its includes.
     * This method searches the DOM directly to get accurate line numbers.
     *
     * @param document       The DOM document
     * @param variableName   The name of the variable to find
     * @param referenceVar   The variable reference location
     * @return LocationLink to the variable definition, or null if not found
     */
    private LocationLink findVariableInServerXml(DOMDocument document, String variableName, VariableLoc referenceVar) {
        // First search in the current document
        LocationLink result = searchVariableInDocument(document, variableName, referenceVar);
        if (result != null) {
            return result;
        }
        
        // If not found, search in included files
        return searchVariableInIncludes(document, variableName, referenceVar);
    }
    
    /**
     * Search for a variable in a specific DOM document.
     *
     * @param document       The DOM document to search
     * @param variableName   The name of the variable to find
     * @param referenceVar   The variable reference location
     * @return LocationLink to the variable definition, or null if not found
     */
    private LocationLink searchVariableInDocument(DOMDocument document, String variableName, VariableLoc referenceVar) {
        DOMElement root = document.getDocumentElement();
        if (root == null) {
            return null;
        }

        List<DOMNode> children = root.getChildren();
        for (DOMNode child : children) {
            if (child.isElement() && "variable".equals(child.getNodeName())) {
                DOMElement varElement = (DOMElement) child;
                String nameAttr = varElement.getAttribute("name");
                
                if (variableName.equals(nameAttr)) {
                    // Found the variable definition
                    Range targetRange = XMLPositionUtility.createRange(
                        varElement.getStart(),
                        varElement.getEnd(),
                        document
                    );
                    
                    // Create a more precise target range for the name attribute
                    DOMAttr nameAttrNode = varElement.getAttributeNode("name");
                    Range targetSelectionRange = null;
                    if (nameAttrNode != null) {
                        targetSelectionRange = XMLPositionUtility.createRange(
                            nameAttrNode.getNodeAttrValue().getStart(),
                            nameAttrNode.getNodeAttrValue().getEnd(),
                            document
                        );
                    } else {
                        targetSelectionRange = targetRange;
                    }
                    
                    // Origin range is the variable reference
                    Range originRange = XMLPositionUtility.createRange(
                        referenceVar.getStartLoc() - 2,  // Include ${
                        referenceVar.getEndLoc() + 1,     // Include }
                        document
                    );
                    
                    return new LocationLink(
                        document.getDocumentURI(),
                        targetRange,
                        targetSelectionRange,
                        originRange
                    );
                }
            }
        }

        return null;
    }
    
    /**
     * Search for a variable in included XML files.
     *
     * @param document       The source DOM document
     * @param variableName   The name of the variable to find
     * @param referenceVar   The variable reference location
     * @return LocationLink to the variable definition, or null if not found
     */
    private LocationLink searchVariableInIncludes(DOMDocument document, String variableName, VariableLoc referenceVar) {
        try {
            DOMElement root = document.getDocumentElement();
            if (root == null) {
                return null;
            }
            
            List<DOMNode> children = root.getChildren();
            for (DOMNode child : children) {
                if (child.isElement() && LibertyConstants.INCLUDE_ELEMENT.equals(child.getNodeName())) {
                    DOMAttr includeAttr = child.getAttributeNode("location");
                    if (includeAttr == null) {
                        continue;
                    }
                    
                    String location = includeAttr.getValue();
                    if (location == null || !location.endsWith(".xml") || location.startsWith("http") || location.contains("$")) {
                        // Skip non-XML, HTTP, or variable-based includes
                        continue;
                    }
                    
                    // Resolve the include location relative to the current document
                    String resolvedLocation = XMLEntityManager.expandSystemId(location, document.getDocumentURI(), false);
                    File includeFile = new File(java.net.URI.create(resolvedLocation));
                    
                    if (!includeFile.exists()) {
                        continue;
                    }
                    
                    // Parse the included file
                    String content = new String(Files.readAllBytes(includeFile.toPath()));
                    DOMDocument includedDoc = DOMParser.getInstance().parse(content, resolvedLocation, null);
                    
                    // Search for the variable in the included document
                    LocationLink result = searchVariableInDocument(includedDoc, variableName, referenceVar);
                    if (result != null) {
                        return result;
                    }
                    
                    // Recursively search includes in the included file
                    result = searchVariableInIncludes(includedDoc, variableName, referenceVar);
                    if (result != null) {
                        return result;
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.warning("Error searching included files: " + e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Create a LocationLink from a VariableLocation.
     * Maps target paths to source paths when possible.
     *
     * @param document       The source DOM document
     * @param varLocation    The variable location from ServerConfigDocument
     * @param referenceVar   The variable reference location
     * @return LocationLink to the variable definition
     */
    private LocationLink createLocationLink(DOMDocument document, VariableLocation varLocation, VariableLoc referenceVar) {
        try {
            String targetPath = varLocation.getFilePath();
            
            // Map target path to source path (e.g., target/liberty/... -> src/main/liberty/config/...)
            String sourcePath = SettingsService.getInstance().mapTargetPathToSource(
                document.getDocumentURI(),
                targetPath
            );
            
            String targetUri = Paths.get(sourcePath).toUri().toString();
            int lineNumber = varLocation.getLineNumber();
            
            // Create target range - the entire line where the variable is defined
            Position targetStart = new Position(lineNumber - 1, 0); // LSP uses 0-based line numbers
            Position targetEnd = new Position(lineNumber - 1, Integer.MAX_VALUE); // End of line
            Range targetRange = new Range(targetStart, targetEnd);
            
            // For selection range, try to be more precise
            // For properties files, we could highlight just the key
            // For now, use the same range as target
            Range targetSelectionRange = targetRange;
            
            // Origin range is the variable reference in the source document
            Range originRange = XMLPositionUtility.createRange(
                referenceVar.getStartLoc() - 2,  // Include ${
                referenceVar.getEndLoc() + 1,     // Include }
                document
            );
            
            return new LocationLink(targetUri, targetRange, targetSelectionRange, originRange);
            
        } catch (Exception e) {
            LOGGER.severe("Error creating LocationLink: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
}
