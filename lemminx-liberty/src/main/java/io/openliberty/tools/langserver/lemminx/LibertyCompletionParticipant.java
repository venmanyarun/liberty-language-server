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
package io.openliberty.tools.langserver.lemminx;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import io.openliberty.tools.langserver.lemminx.models.feature.VariableLoc;
import org.eclipse.lemminx.commons.BadLocationException;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMElement;
import org.eclipse.lemminx.dom.DOMNode;
import org.eclipse.lemminx.services.extensions.completion.CompletionParticipantAdapter;
import org.eclipse.lemminx.services.extensions.completion.ICompletionRequest;
import org.eclipse.lemminx.services.extensions.completion.ICompletionResponse;
import org.eclipse.lemminx.services.extensions.inlinecompletion.IInlineCompletionParticipant;
import org.eclipse.lemminx.services.extensions.inlinecompletion.IInlineCompletionRequest;
import org.eclipse.lemminx.services.extensions.inlinecompletion.IInlineCompletionResponse;
import org.eclipse.lemminx.utils.XMLPositionUtility;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.InlineCompletionItem;
import org.eclipse.lsp4j.InsertReplaceEdit;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.CancelChecker;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import io.openliberty.tools.langserver.lemminx.data.LibertyRuntime;
import io.openliberty.tools.langserver.lemminx.models.feature.Feature;
import io.openliberty.tools.langserver.lemminx.services.FeatureService;
import io.openliberty.tools.langserver.lemminx.services.SettingsService;
import io.openliberty.tools.langserver.lemminx.util.LibertyConstants;
import io.openliberty.tools.langserver.lemminx.util.LibertyUtils;

public class LibertyCompletionParticipant extends CompletionParticipantAdapter implements IInlineCompletionParticipant {


    @Override
    public void onAttributeValue(String valuePrefix, ICompletionRequest request, ICompletionResponse response, CancelChecker cancelChecker) throws Exception {
        if (!LibertyUtils.isConfigXMLFile(request.getXMLDocument()))
            return;
        //show variable completion only if user uses "${"
        if(!valuePrefix.contains("${"))
            return;
        Properties variableProps = SettingsService.getInstance()
                .getVariablesForServerXml(request.getXMLDocument()
                        .getDocumentURI());
        LibertyUtils.checkAndAddNewVariables(request.getXMLDocument(), variableProps);
        //getting all existing variables in current completion prefix string
        List<VariableLoc> variables = LibertyUtils.getVariablesFromTextContent(request.getXMLDocument(), valuePrefix);
        String variablePrefix = "";
        String completionPrefix;

        if (!variables.isEmpty()) {
            // for multiple variables
            // find last variable last character and consider new variable completion from that location
            VariableLoc lastVar = variables.get(variables.size() - 1);
            // if last index of an existing variable is less than lastIndex of ${,
            // this means that completion variable is started with ${
            if (valuePrefix.lastIndexOf("${") > lastVar.getEndLoc()) {
                // get whatever is after last ${ as variablePrefix for Completion
                variablePrefix = valuePrefix.substring(valuePrefix.lastIndexOf("${") + 2);
                completionPrefix = valuePrefix.substring(0, valuePrefix.lastIndexOf("${"));
            } else {
                variablePrefix = valuePrefix.substring(lastVar.getEndLoc() + 1);
                // add char between last variable and start of completion variable to completionPrefix
                completionPrefix = valuePrefix.substring(0,lastVar.getEndLoc()+1);
            }
        } else {
            // extract variable name with whatever is after ${
            variablePrefix = valuePrefix.substring(valuePrefix.lastIndexOf("${") + 2);
            // extract completionPrefix with whatever is before ${
            completionPrefix = valuePrefix.substring(0, valuePrefix.lastIndexOf("${"));
        }
        String finalVariableName = variablePrefix.replace("${","").replace("}","");
        variableProps.entrySet().stream().filter(it -> it.getKey().toString().toLowerCase()
                        .contains(finalVariableName.toLowerCase()))
                .forEach(variableProp -> {
                    String varValue = String.format("%s${%s}", completionPrefix, variableProp.getKey());
                    Either<TextEdit, InsertReplaceEdit> edit = Either.forLeft(new
                            TextEdit(request.getReplaceRange(), varValue));
                    CompletionItem completionItem = new CompletionItem();
                    completionItem.setLabel(varValue);
                    completionItem.setTextEdit(edit);
                    completionItem.setFilterText(variableProp.getKey().toString());
                    completionItem.setKind(CompletionItemKind.Value);
                    completionItem.setDocumentation(String.format("%s = %s", variableProp.getKey(), variableProp.getValue()));
                    response.addCompletionItem(completionItem);
                });
    }

    @Override
    public void onXMLContent(ICompletionRequest request, ICompletionResponse response, CancelChecker cancelChecker)
            throws IOException, BadLocationException {
        if (!LibertyUtils.isConfigXMLFile(request.getXMLDocument()))
            return;

        LibertyUtils.getLibertyRuntimeInfo(request.getXMLDocument());

        DOMElement parentElement = request.getParentElement();
        if (parentElement == null || parentElement.getTagName() == null)
            return;

        // if the parent element of cursor is a <feature>
        // provide the liberty features as completion options
        if (parentElement.getTagName().equals(LibertyConstants.FEATURE_ELEMENT)) {
            // narrow down completion list based on what was already entered
            DOMNode featureTextNode = (DOMNode) parentElement.getChildNodes().item(0);
            String featureName = featureTextNode != null ? featureTextNode.getTextContent() : null;

            // collect existing features
            List<String> existingFeatures = new ArrayList<String>();
            DOMNode featureMgrNode = null;
            if (parentElement.getParentNode() != null
                    && parentElement.getParentNode().getNodeName().equals(LibertyConstants.FEATURE_MANAGER_ELEMENT)) {
                featureMgrNode = parentElement.getParentNode();
                existingFeatures = FeatureService.getInstance().collectExistingFeatures(parentElement.getParentNode(), featureName);
            }

            List<CompletionItem> featureCompletionItems = buildCompletionItems(parentElement, request.getXMLDocument(),
                    existingFeatures, featureName, featureMgrNode);
            featureCompletionItems.stream().forEach(item -> response.addCompletionItem(item));
        } else if (parentElement.getTagName().equals(LibertyConstants.PLATFORM_ELEMENT)) {
            DOMNode platformTextNode = (DOMNode) parentElement.getChildNodes().item(0);
            String currentPlatformName = platformTextNode != null ? platformTextNode.getTextContent() : "";
            String currentPlatformNameWithoutVersion = LibertyUtils.stripVersion(currentPlatformName);
            // collect existing platforms
            List<String> existingPlatforms = FeatureService.getInstance()
                    .collectExistingPlatforms(request.getXMLDocument(),currentPlatformNameWithoutVersion);
            List<String> existingPlatformsWithoutVersion = existingPlatforms.stream().map(LibertyUtils::stripVersion).collect(Collectors.toList());
            this.buildPlatformCompletionItems(request, response, parentElement, existingPlatformsWithoutVersion);
        }
    }

    /**
     * build completion items for platforms
     *
     * @param request           request
     * @param response          response
     * @param parentElement     parent element xml dom
     * @param existingPlatforms
     */
    private void buildPlatformCompletionItems(ICompletionRequest request, ICompletionResponse response, DOMElement parentElement, List<String> existingPlatforms) {
        DOMNode platformTextNode = (DOMNode) parentElement.getChildNodes().item(0);
        String platformName = platformTextNode != null ? platformTextNode.getTextContent() : null;
        LibertyRuntime runtimeInfo = LibertyUtils.getLibertyRuntimeInfo(request.getXMLDocument());
        String libertyVersion =  runtimeInfo == null ? null : runtimeInfo.getRuntimeVersion();
        String libertyRuntime =  runtimeInfo == null ? null : runtimeInfo.getRuntimeType();

        final int requestDelay = SettingsService.getInstance().getRequestDelay();
        //get all platforms
        Set<String> platforms = FeatureService.getInstance().getAllPlatforms(libertyVersion, libertyRuntime, requestDelay,
                request.getXMLDocument().getDocumentURI());
        platforms.stream().filter(it->(Objects.isNull(platformName) || it.contains(platformName)))
                .filter(p -> !existingPlatforms.contains(LibertyUtils.stripVersion(p).toLowerCase()))
                .filter(p -> !existingPlatforms.contains(LibertyConstants.conflictingPlatforms.get(LibertyUtils.stripVersion(p).toLowerCase())))
                .forEach(platformItem -> {
                    Range range = XMLPositionUtility.createRange(parentElement.getStartTagCloseOffset() + 1,
                            parentElement.getEndTagOpenOffset(), request.getXMLDocument());
                    Either<TextEdit, InsertReplaceEdit> edit = Either.forLeft(new TextEdit(range, platformItem));
                    CompletionItem completionItem = new CompletionItem();
                    completionItem.setLabel(platformItem);
                    completionItem.setTextEdit(edit);
                    completionItem.setDocumentation(LibertyUtils.getPlatformDescription(platformItem));
                    response.addCompletionItem(completionItem);
                });
    }

    private CompletionItem buildFeatureCompletionItem(Feature feature, DOMElement featureElement,
            DOMDocument document) {
        String featureName = feature.getWlpInformation().getShortName();

        // Build a text edit to replace whatever is inside <feature></feature>
        // with the completion result
        Range range = XMLPositionUtility.createRange(featureElement.getStartTagCloseOffset() + 1,
                featureElement.getEndTagOpenOffset(), document);
        Either<TextEdit, InsertReplaceEdit> edit = Either.forLeft(new TextEdit(range, featureName));

        // Build the completion item to return to the client
        CompletionItem item = new CompletionItem();
        item.setTextEdit(edit);
        item.setLabel(featureName);
        item.setDocumentation(Either.forLeft(feature.getShortDescription()));
        return item;
    }

    private List<CompletionItem> buildCompletionItems(DOMElement featureElement, DOMDocument domDocument,
            List<String> existingFeatures, String featureName, DOMNode featureMgrNode) {

        LibertyRuntime runtimeInfo = LibertyUtils.getLibertyRuntimeInfo(domDocument);
        String libertyVersion =  runtimeInfo == null ? null : runtimeInfo.getRuntimeVersion();
        String libertyRuntime =  runtimeInfo == null ? null : runtimeInfo.getRuntimeType();

        final int requestDelay = SettingsService.getInstance().getRequestDelay();

        boolean checkFeatureName = featureName != null && !featureName.isBlank();

        if (checkFeatureName) {
            String featureNameLowerCase = featureName.toLowerCase();

            // strip off version number after the - so that we can provide all possible valid versions of a feature for completion
            String featureNameToCompare = featureNameLowerCase.contains("-") ? featureNameLowerCase.substring(0, featureNameLowerCase.lastIndexOf("-")+1) : featureNameLowerCase;

            List<Feature> completionFeatures = FeatureService.getInstance().getFeatureReplacements(featureNameToCompare, featureMgrNode, libertyVersion, libertyRuntime, requestDelay, domDocument.getDocumentURI());
            return getFeatureCompletionItems(featureElement, domDocument, completionFeatures);
        } else {
            List<Feature> features = FeatureService.getInstance().getFeaturesAndPlatforms(libertyVersion, libertyRuntime, requestDelay, domDocument.getDocumentURI()).getPublicFeatures();
            return getUniqueFeatureCompletionItems(featureElement, domDocument, features, existingFeatures);
        }
    }

     private List<CompletionItem> getFeatureCompletionItems(DOMElement featureElement, DOMDocument domDocument, List<Feature> completionFeatures) {
        List<CompletionItem> uniqueFeatureCompletionItems = new ArrayList<CompletionItem>();

        for (Feature nextFeature : completionFeatures) {
            CompletionItem ci = buildFeatureCompletionItem(nextFeature, featureElement, domDocument);
            uniqueFeatureCompletionItems.add(ci);
        }

        return uniqueFeatureCompletionItems;
    }

    private List<CompletionItem> getUniqueFeatureCompletionItems(DOMElement featureElement, DOMDocument domDocument, List<Feature> allFeatures, List<String> existingFeatureNames) {
        List<CompletionItem> uniqueFeatureCompletionItems = new ArrayList<CompletionItem>();
        // collect features without version
        // existing features are already in lower case
        Set<String> existingFeatureNamesWithoutVersion = existingFeatureNames.stream()
                .map(s->LibertyUtils.stripVersion(s).toLowerCase())
                .collect(Collectors.toSet());

        for (Feature nextFeature : allFeatures) {
            String nextFeatureName = nextFeature.getWlpInformation().getShortName().toLowerCase();
            String nextFeatureNameWithoutVersion = LibertyUtils.stripVersion(nextFeatureName);
            // exclude other versions of all included features
            if (!existingFeatureNames.contains(nextFeatureName)
                    && !existingFeatureNamesWithoutVersion.contains(nextFeatureNameWithoutVersion)) {
                CompletionItem ci = buildFeatureCompletionItem(nextFeature, featureElement, domDocument);
                uniqueFeatureCompletionItems.add(ci);
            }
        }

        return uniqueFeatureCompletionItems;
    }

    @Override
    public void onInlineCompletion(IInlineCompletionRequest request, IInlineCompletionResponse response,
            CancelChecker cancelChecker) {
        if (!LibertyUtils.isConfigXMLFile(request.getXMLDocument())) {
            return;
        }

        List<CompletionItem> completionItems = new ArrayList<>();
        CompletionCollector collector = new CompletionCollector(completionItems);
        DOMElement parentElement = request.getParentElement();

        try {
            if (parentElement != null && LibertyConstants.FEATURE_ELEMENT.equals(parentElement.getTagName())) {
                collectFeatureInlineCompletions(request, completionItems);
            } else if (parentElement != null && LibertyConstants.PLATFORM_ELEMENT.equals(parentElement.getTagName())) {
                collectPlatformInlineCompletions(request, collector, parentElement);
            } else {
                collectVariableInlineCompletions(request, collector, cancelChecker);
            }

            System.out.println("[Liberty inline completion] parent="
                    + (parentElement != null ? parentElement.getTagName() : "<none>")
                    + ", completionItems=" + completionItems.size()
                    + ", uri=" + request.getXMLDocument().getDocumentURI());

            completionItems.stream()
                    .map(this::toInlineCompletionItem)
                    .filter(Objects::nonNull)
                    .forEach(response::addInlineCompletionItem);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void collectFeatureInlineCompletions(IInlineCompletionRequest request, List<CompletionItem> completionItems) {
        DOMElement parentElement = request.getParentElement();
        DOMNode featureTextNode = (DOMNode) parentElement.getChildNodes().item(0);
        String featureName = featureTextNode != null ? featureTextNode.getTextContent() : null;

        List<String> existingFeatures = new ArrayList<>();
        DOMNode featureMgrNode = null;
        if (parentElement.getParentNode() != null
                && LibertyConstants.FEATURE_MANAGER_ELEMENT.equals(parentElement.getParentNode().getNodeName())) {
            featureMgrNode = parentElement.getParentNode();
            existingFeatures = FeatureService.getInstance().collectExistingFeatures(parentElement.getParentNode(),
                    featureName);
        }

        completionItems.addAll(buildCompletionItems(parentElement, request.getXMLDocument(), existingFeatures,
                featureName, featureMgrNode));
    }

    private void collectPlatformInlineCompletions(IInlineCompletionRequest request, ICompletionResponse response,
            DOMElement parentElement) {
        DOMNode platformTextNode = (DOMNode) parentElement.getChildNodes().item(0);
        String currentPlatformName = platformTextNode != null ? platformTextNode.getTextContent() : "";
        String currentPlatformNameWithoutVersion = LibertyUtils.stripVersion(currentPlatformName);
        List<String> existingPlatforms = FeatureService.getInstance()
                .collectExistingPlatforms(request.getXMLDocument(), currentPlatformNameWithoutVersion);
        List<String> existingPlatformsWithoutVersion = existingPlatforms.stream()
                .map(LibertyUtils::stripVersion)
                .collect(Collectors.toList());
        buildPlatformCompletionItems(new InlineToCompletionRequest(request), response, parentElement,
                existingPlatformsWithoutVersion);
    }

    private void collectVariableInlineCompletions(IInlineCompletionRequest request, ICompletionResponse response,
            CancelChecker cancelChecker) throws Exception {
        String valuePrefix = getAttributeValuePrefix(request);
        if (valuePrefix != null && valuePrefix.contains("${")) {
            onAttributeValue(valuePrefix, new InlineToCompletionRequest(request), response, cancelChecker);
        }
    }

    private String getAttributeValuePrefix(IInlineCompletionRequest request) {
        DOMNode node = request.getNode();
        if (node == null) {
            return null;
        }
        String textContent = node.getTextContent();
        if (textContent == null) {
            return null;
        }
        int prefixLength = Math.max(0, request.getOffset() - node.getStart());
        return textContent.substring(0, Math.min(prefixLength, textContent.length()));
    }

    private InlineCompletionItem toInlineCompletionItem(CompletionItem item) {
        String insertText = getInsertText(item);
        if (insertText == null || insertText.isEmpty()) {
            return null;
        }

        InlineCompletionItem inlineItem = new InlineCompletionItem();
        inlineItem.setInsertText(insertText);
        inlineItem.setFilterText(item.getFilterText() != null ? item.getFilterText() : item.getLabel());

        Range range = getRange(item);
        if (range != null) {
            inlineItem.setRange(range);
        }
        return inlineItem;
    }

    private Range getRange(CompletionItem item) {
        if (item.getTextEdit() == null) {
            return null;
        }
        if (item.getTextEdit().isLeft()) {
            return item.getTextEdit().getLeft().getRange();
        }
        return item.getTextEdit().getRight().getReplace();
    }

    private String getInsertText(CompletionItem item) {
        if (item.getTextEdit() != null) {
            if (item.getTextEdit().isLeft()) {
                return item.getTextEdit().getLeft().getNewText();
            }
            return item.getTextEdit().getRight().getNewText();
        }
        if (item.getInsertText() != null) {
            return item.getInsertText();
        }
        return item.getLabel();
    }

    private static final class CompletionCollector implements ICompletionResponse {

        private final List<CompletionItem> items;

        private CompletionCollector(List<CompletionItem> items) {
            this.items = items;
        }

        @Override
        public void addCompletionItem(CompletionItem completionItem, boolean comingFromGrammar) {
            items.add(completionItem);
        }

        @Override
        public void addCompletionItem(CompletionItem completionItem) {
            items.add(completionItem);
        }

        @Override
        public boolean hasSomeItemFromGrammar() {
            return false;
        }

        @Override
        public boolean hasAttribute(String attribute) {
            return false;
        }

        @Override
        public void addCompletionAttribute(CompletionItem completionItem) {
            items.add(completionItem);
        }
    }

    private static final class InlineToCompletionRequest implements ICompletionRequest {

        private final IInlineCompletionRequest request;

        private InlineToCompletionRequest(IInlineCompletionRequest request) {
            this.request = request;
        }

        @Override
        public DOMDocument getXMLDocument() {
            return request.getXMLDocument();
        }

        @Override
        public org.eclipse.lemminx.dom.DOMNode getNode() {
            return request.getNode();
        }

        @Override
        public DOMElement getParentElement() {
            return request.getParentElement();
        }

        @Override
        public int getOffset() {
            return request.getOffset();
        }

        @Override
        public org.eclipse.lsp4j.Position getPosition() {
            return request.getPosition();
        }

        @Override
        public String getCurrentTag() {
            return null;
        }

        @Override
        public org.eclipse.lemminx.dom.DOMAttr getCurrentAttribute() {
            return null;
        }

        @Override
        public String getCurrentAttributeName() {
            return null;
        }

        @Override
        public org.eclipse.lemminx.dom.LineIndentInfo getLineIndentInfo() throws BadLocationException {
            throw new BadLocationException("Inline completion request does not support line indent info");
        }

        @Override
        public <T> T getComponent(Class clazz) {
            return request.getComponent(clazz);
        }

        @Override
        public org.eclipse.lemminx.settings.SharedSettings getSharedSettings() {
            return request.getSharedSettings();
        }

        @Override
        public boolean canSupportMarkupKind(String kind) {
            return request.canSupportMarkupKind(kind);
        }

        @Override
        public Range getReplaceRange() {
            return XMLPositionUtility.createRange(request.getOffset(), request.getOffset(), request.getXMLDocument());
        }

        @Override
        public Range getReplaceRangeForTagName() {
            return getReplaceRange();
        }

        @Override
        public org.eclipse.lemminx.extensions.contentmodel.utils.XMLGenerator getXMLGenerator() throws BadLocationException {
            throw new BadLocationException("Inline completion request does not support XML generator");
        }

        @Override
        public String getFilterForStartTagName(String tagName) {
            return tagName;
        }

        @Override
        public String getInsertAttrValue(String value) {
            return value;
        }

        @Override
        public boolean isCompletionSnippetsSupported() {
            return false;
        }

        @Override
        public boolean isAutoCloseTags() {
            return false;
        }

        @Override
        public org.eclipse.lsp4j.InsertTextFormat getInsertTextFormat() {
            return org.eclipse.lsp4j.InsertTextFormat.PlainText;
        }

        @Override
        public boolean isResolveDocumentationSupported() {
            return false;
        }

        @Override
        public boolean isResolveAdditionalTextEditsSupported() {
            return false;
        }
    }
}
