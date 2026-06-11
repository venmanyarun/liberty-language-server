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
package io.openliberty.tools.langserver.completion.inline;

import org.eclipse.lsp4j.Position;

/**
 * Context information for inline completion requests.
 * Provides details about the current document state and cursor position.
 */
public class InlineCompletionContext {
    private final String documentUri;
    private final Position position;
    private final String partialText;
    private final String currentLine;
    
    /**
     * Create a new inline completion context.
     * 
     * @param documentUri URI of the document
     * @param position Cursor position
     * @param currentLine The current line content
     */
    public InlineCompletionContext(String documentUri, Position position, String currentLine) {
        this.documentUri = documentUri;
        this.position = position;
        this.currentLine = currentLine != null ? currentLine : "";
        this.partialText = extractPartialText(this.currentLine, position);
    }
    
    /**
     * Extract the text from the start of the line to the cursor position.
     */
    private String extractPartialText(String line, Position pos) {
        if (line == null || line.isEmpty()) {
            return "";
        }
        
        int charPos = pos.getCharacter();
        if (charPos > line.length()) {
            charPos = line.length();
        }
        
        // Get text up to cursor
        String textBeforeCursor = line.substring(0, charPos);
        
        // Find the start of the current word by going backwards from cursor
        // A word consists of letters, digits, and underscores
        int wordStart = charPos - 1;
        while (wordStart >= 0) {
            char c = textBeforeCursor.charAt(wordStart);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                break;
            }
            wordStart--;
        }
        wordStart++; // Move to the first character of the word
        
        return textBeforeCursor.substring(wordStart);
    }
    
    public String getDocumentUri() {
        return documentUri;
    }
    
    public Position getPosition() {
        return position;
    }
    
    public String getPartialText() {
        return partialText;
    }
    
    public String getCurrentLine() {
        return currentLine;
    }
    
    /**
     * Check if this is a server.env file.
     */
    public boolean isServerEnv() {
        return documentUri != null && documentUri.endsWith("server.env");
    }
    
    /**
     * Check if this is a bootstrap.properties file.
     */
    public boolean isBootstrapProperties() {
        return documentUri != null && documentUri.endsWith("bootstrap.properties");
    }
}