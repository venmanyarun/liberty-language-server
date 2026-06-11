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

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.InlineCompletionItem;
import org.eclipse.lsp4j.InsertReplaceEdit;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

/**
 * Adapter that transforms CompletionItem results into InlineCompletionItem format.
 * This allows reuse of existing completion logic for inline completions.
 */
public class CompletionToInlineAdapter {
    
    private static final int MAX_INLINE_SUGGESTIONS = 10;
    
    /**
     * Transform a list of CompletionItems into InlineCompletionItems.
     * 
     * @param completionItems The completion items from the existing provider
     * @param context The inline completion context
     * @return List of inline completion items
     */
    public List<InlineCompletionItem> transform(List<CompletionItem> completionItems, 
                                                 InlineCompletionContext context) {
        if (completionItems == null || completionItems.isEmpty()) {
            return new ArrayList<>();
        }
        
        return completionItems.stream()
            .filter(item -> isRelevantForInline(item, context))
            .limit(MAX_INLINE_SUGGESTIONS)
            .map(item -> convertToInlineCompletion(item, context))
            .collect(Collectors.toList());
    }
    
    /**
     * Check if a completion item is relevant for inline completion.
     * Filters out items that don't match the current context.
     */
    private boolean isRelevantForInline(CompletionItem item, InlineCompletionContext context) {
        if (item == null || item.getLabel() == null) {
            return false;
        }
        
        String label = item.getLabel();
        String partialText = context.getPartialText();
        if(partialText.contains("=")){
            partialText=partialText.substring(partialText.indexOf("=")+1);
        }
        // Use substring matching to match behavior of regular completions
        if (!partialText.isEmpty()) {
            return label.toLowerCase().contains(partialText.toLowerCase());
        }
        
        return true;
    }
    
    /**
     * Convert a CompletionItem to an InlineCompletionItem.
     */
    private InlineCompletionItem convertToInlineCompletion(CompletionItem item,
                                                           InlineCompletionContext context) {
        InlineCompletionItem inlineItem = new InlineCompletionItem();
        
        // Extract the full insert text from the completion item
        String fullInsertText = getInsertText(item);
        String partialText = context.getPartialText();
        
        // For inline completions, always provide the FULL completion text
        // and use a range to replace what the user has typed
        String insertText = fullInsertText;
        
        // Set the insert text
        inlineItem.setInsertText(Either.forLeft(insertText));
        
        // Always set a range to replace the partial text with the full completion
        // This ensures VSCode shows the complete text, not fragments starting with special chars
        if (!partialText.isEmpty()) {
            Range range = createRangeForReplacement(context, partialText, fullInsertText);
            if (range != null) {
                inlineItem.setRange(range);
            }
        }
        
        // Set filter text (used for ranking)
        if (item.getFilterText() != null) {
            inlineItem.setFilterText(item.getFilterText());
        } else {
            inlineItem.setFilterText(item.getLabel());
        }
        
        return inlineItem;
    }
    
    /**
     * Create a range that replaces the typed text with the full completion.
     * This works for both prefix and substring matches.
     */
    private Range createRangeForReplacement(InlineCompletionContext context, String partialText, String fullText) {
        Position currentPos = context.getPosition();
        int line = currentPos.getLine();
        int cursorChar = currentPos.getCharacter();
        
        // Start position: beginning of the typed partial text
        int startChar = cursorChar - partialText.length();
        if (startChar < 0) startChar = 0;
        
        // End position: where the full completion will end
        int endChar = startChar + fullText.length();
        
        return new Range(
            new Position(line, startChar),
            new Position(line, endChar)
        );
    }
    
    
    /**
     * Extract the insert text from a CompletionItem.
     */
    private String getInsertText(CompletionItem item) {
        // Try textEdit first
        if (item.getTextEdit() != null) {
            Either<TextEdit, InsertReplaceEdit> textEdit = item.getTextEdit();
            if (textEdit.isLeft()) {
                return textEdit.getLeft().getNewText();
            } else if (textEdit.isRight()) {
                return textEdit.getRight().getNewText();
            }
        }
        
        // Fall back to insertText
        if (item.getInsertText() != null) {
            return item.getInsertText();
        }
        
        // Fall back to label
        return item.getLabel();
    }
    
}