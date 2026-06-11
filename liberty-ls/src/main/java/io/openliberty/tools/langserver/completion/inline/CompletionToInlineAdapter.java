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
        // If there's partial text, the completion should start with it
        if (!partialText.isEmpty()) {
            return label.toLowerCase().startsWith(partialText.toLowerCase());
        }
        
        return true;
    }
    
    /**
     * Convert a CompletionItem to an InlineCompletionItem.
     */
    private InlineCompletionItem convertToInlineCompletion(CompletionItem item,
                                                           InlineCompletionContext context) {
        InlineCompletionItem inlineItem = new InlineCompletionItem();
        
        // Extract the insert text
        String insertText = getInsertText(item);
        
        // Remove the partial text that's already typed
        String partialText = context.getPartialText();
        if (!partialText.isEmpty() && insertText.toLowerCase().startsWith(partialText.toLowerCase())) {
            insertText = insertText.substring(partialText.length());
        }
        
        // Set the insert text as Either<String, StringValue>
        inlineItem.setInsertText(Either.forLeft(insertText));
        
        // Set the range if available from the completion item
        Range range = getRange(item, context);
        if (range != null) {
            inlineItem.setRange(range);
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
     * Extract the range from a CompletionItem.
     * For inline completions, the range should start at the current cursor position,
     * not at the beginning of the word being completed.
     */
    private Range getRange(CompletionItem item, InlineCompletionContext context) {
        Position currentPos = context.getPosition();
        String partialText = context.getPartialText();
        String insertText = getInsertText(item);
        
        // Calculate where the completion text will end
        // If we removed partial text from insertText, add it back to get full length
        int fullLength = insertText.length();
        if (!partialText.isEmpty() && getInsertText(item).toLowerCase().startsWith(partialText.toLowerCase())) {
            fullLength += partialText.length();
        }
        
        // Range starts at current cursor and extends by the length of remaining text
        Position endPos = new Position(
            currentPos.getLine(),
            currentPos.getCharacter() + insertText.length()
        );
        
        return new Range(currentPos, endPos);
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