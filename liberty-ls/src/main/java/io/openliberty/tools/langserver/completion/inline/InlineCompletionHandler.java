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

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.InlineCompletionItem;
import org.eclipse.lsp4j.InlineCompletionList;
import org.eclipse.lsp4j.InlineCompletionParams;
import org.eclipse.lsp4j.Position;

import io.openliberty.tools.langserver.completion.LibertyPropertiesCompletionProvider;
import io.openliberty.tools.langserver.ls.LibertyTextDocument;

/**
 * Handler for inline completion requests.
 * Reuses existing completion logic and transforms results into inline completion format.
 */
public class InlineCompletionHandler {
    
    private static final Logger LOGGER = Logger.getLogger(InlineCompletionHandler.class.getName());
    
    private final LibertyTextDocument document;
    private final CompletionToInlineAdapter adapter;
    
    /**
     * Create a new inline completion handler.
     * 
     * @param document The text document
     */
    public InlineCompletionHandler(LibertyTextDocument document) {
        this.document = document;
        this.adapter = new CompletionToInlineAdapter();
    }
    
    /**
     * Handle an inline completion request.
     * 
     * @param params The inline completion parameters
     * @return CompletableFuture with inline completion results
     */
    public CompletableFuture<InlineCompletionList> getInlineCompletions(InlineCompletionParams params) {
        LOGGER.info("Inline completion requested for: " + params.getTextDocument().getUri());
        
        try {
            // Create context
            Position position = params.getPosition();
            String currentLine = getCurrentLine(position);
            InlineCompletionContext context = new InlineCompletionContext(
                params.getTextDocument().getUri(),
                position,
                currentLine
            );
            
            // Get completions from existing provider
            LibertyPropertiesCompletionProvider completionProvider = 
                new LibertyPropertiesCompletionProvider(document);
            
            return completionProvider.getCompletions(position)
                .thenApply(completionItems -> {
                    // Transform to inline completions
                    List<InlineCompletionItem> inlineItems = 
                        adapter.transform(completionItems, context);
                    
                    LOGGER.info("Generated " + inlineItems.size() + " inline completion items");
                    
                    // Create and return inline completion list
                    InlineCompletionList result = new InlineCompletionList();
                    result.setItems(inlineItems);
                    return result;
                })
                .exceptionally(ex -> {
                    LOGGER.severe("Error generating inline completions: " + ex.getMessage());
                    InlineCompletionList emptyResult = new InlineCompletionList();
                    emptyResult.setItems(Collections.emptyList());
                    return emptyResult;
                });
                
        } catch (Exception e) {
            LOGGER.severe("Error in inline completion handler: " + e.getMessage());
            InlineCompletionList emptyResult = new InlineCompletionList();
            emptyResult.setItems(Collections.emptyList());
            return CompletableFuture.completedFuture(emptyResult);
        }
    }
    
    /**
     * Get the current line content at the given position.
     */
    private String getCurrentLine(Position position) {
        try {
            String content = document.getText();
            if (content == null || content.isEmpty()) {
                return "";
            }
            
            String[] lines = content.split("\n");
            int lineNumber = position.getLine();
            
            if (lineNumber >= 0 && lineNumber < lines.length) {
                return lines[lineNumber];
            }
            
            return "";
        } catch (Exception e) {
            LOGGER.warning("Error getting current line: " + e.getMessage());
            return "";
        }
    }
}