// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package fit.jetbrains.jsonSchema.impl;

import com.intellij.codeInspection.LocalInspectionToolSession;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemsHolder;
import fit.intellij.json.pointer.JsonPointerPosition;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.containers.ContainerUtil;
import fit.jetbrains.jsonSchema.extension.JsonLikePsiWalker;
import fit.jetbrains.jsonSchema.extension.adapters.JsonPropertyAdapter;
import fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class JsonSchemaComplianceChecker {
  private static final Key<Set<PsiElement>> ANNOTATED_PROPERTIES = Key.create("FitJsonSchema.Properties.Annotated");

  @NotNull private final JsonSchemaObject myRootSchema;
  @NotNull private final ProblemsHolder myHolder;
  @NotNull private final fit.jetbrains.jsonSchema.extension.JsonLikePsiWalker myWalker;
  private final LocalInspectionToolSession mySession;
  @NotNull private final fit.jetbrains.jsonSchema.impl.JsonComplianceCheckerOptions myOptions;
  @Nullable private final String myMessagePrefix;

  public JsonSchemaComplianceChecker(@NotNull JsonSchemaObject rootSchema,
                                     @NotNull ProblemsHolder holder,
                                     @NotNull fit.jetbrains.jsonSchema.extension.JsonLikePsiWalker walker,
                                     @NotNull LocalInspectionToolSession session,
                                     @NotNull fit.jetbrains.jsonSchema.impl.JsonComplianceCheckerOptions options) {
    this(rootSchema, holder, walker, session, options, null);
  }

  public JsonSchemaComplianceChecker(@NotNull JsonSchemaObject rootSchema,
                                     @NotNull ProblemsHolder holder,
                                     @NotNull fit.jetbrains.jsonSchema.extension.JsonLikePsiWalker walker,
                                     @NotNull LocalInspectionToolSession session,
                                     @NotNull JsonComplianceCheckerOptions options,
                                     @Nullable String messagePrefix) {
    myRootSchema = rootSchema;
    myHolder = holder;
    myWalker = walker;
    mySession = session;
    myOptions = options;
    myMessagePrefix = messagePrefix;
  }

  public void annotate(@NotNull final PsiElement element) {
    Project project = element.getProject();
    final fit.jetbrains.jsonSchema.extension.adapters.JsonPropertyAdapter firstProp = myWalker.getParentPropertyAdapter(element);
    if (firstProp != null) {
      final JsonPointerPosition position = myWalker.findPosition(firstProp.getDelegate(), true);
      if (position == null || position.isEmpty()) return;
      final MatchResult result = new JsonSchemaResolver(project, myRootSchema, position).detailedResolve();
      for (fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter value : firstProp.getValues()) {
        createWarnings(JsonSchemaAnnotatorChecker.checkByMatchResult(project, value, result, myOptions));
      }
    }
    checkRoot(element, firstProp);
  }

  private void checkRoot(@NotNull PsiElement element, @Nullable JsonPropertyAdapter firstProp) {
    fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter rootToCheck;
    if (firstProp == null) {
      rootToCheck = findTopLevelElement(myWalker, element);
    } else {
      rootToCheck = firstProp.getParentObject();
      if (rootToCheck == null || !myWalker.isTopJsonElement(rootToCheck.getDelegate().getParent())) {
        return;
      }
    }
    if (rootToCheck != null) {
      Project project = element.getProject();
      final MatchResult matchResult = new JsonSchemaResolver(project, myRootSchema).detailedResolve();
      createWarnings(JsonSchemaAnnotatorChecker.checkByMatchResult(project, rootToCheck, matchResult, myOptions));
    }
  }

  private void createWarnings(@Nullable JsonSchemaAnnotatorChecker checker) {
    if (checker == null || checker.isCorrect()) return;
    // compute intersecting ranges - we'll solve warning priorities based on this information
    List<TextRange> ranges = new ArrayList<>();
    List<List<Map.Entry<PsiElement, JsonValidationError>>> entries = new ArrayList<>();
    for (Map.Entry<PsiElement, JsonValidationError> entry : checker.getErrors().entrySet()) {
      TextRange range = entry.getKey().getTextRange();
      boolean processed = false;
      for (int i = 0; i < ranges.size(); i++) {
        TextRange currRange = ranges.get(i);
        if (currRange.intersects(range)) {
          ranges.set(i, new TextRange(Math.min(currRange.getStartOffset(), range.getStartOffset()), Math.max(currRange.getEndOffset(), range.getEndOffset())));
          entries.get(i).add(entry);
          processed = true;
          break;
        }
      }
      if (processed) continue;

      ranges.add(range);
      entries.add(ContainerUtil.newArrayList(entry));
    }

    // for each set of intersecting ranges, compute the best errors to show
    for (List<Map.Entry<PsiElement, JsonValidationError>> entryList : entries) {
      int min = entryList.stream().map(v -> v.getValue().getPriority().ordinal()).min(Integer::compareTo).orElse(Integer.MAX_VALUE);
      for (Map.Entry<PsiElement, JsonValidationError> entry : entryList) {
        JsonValidationError validationError = entry.getValue();
        PsiElement psiElement = entry.getKey();
        if (validationError.getPriority().ordinal() > min) {
          continue;
        }
        TextRange range = myWalker.adjustErrorHighlightingRange(psiElement);
        range = range.shiftLeft(psiElement.getTextRange().getStartOffset());
        registerError(psiElement, range, validationError);
      }
    }
  }

  private void registerError(@NotNull PsiElement psiElement, @NotNull TextRange range, @NotNull JsonValidationError validationError) {
    if (checkIfAlreadyProcessed(psiElement)) return;
    String value = validationError.getMessage();
    if (myMessagePrefix != null) value = myMessagePrefix + value;
    LocalQuickFix[] fix = validationError.createFixes(myWalker.getSyntaxAdapter(myHolder.getProject()));
    PsiElement element = range.isEmpty() ? psiElement.getContainingFile() : psiElement;
    if (fix.length == 0) {
      myHolder.registerProblem(element, range, value);
    }
    else {
      myHolder.registerProblem(element, range, value, fix);
    }
  }

  private static JsonValueAdapter findTopLevelElement(@NotNull JsonLikePsiWalker walker, @NotNull PsiElement element) {
    final Ref<PsiElement> ref = new Ref<>();
    PsiTreeUtil.findFirstParent(element, el -> {
      final boolean isTop = walker.isTopJsonElement(el);
      if (!isTop) ref.set(el);
      return isTop;
    });
    return ref.isNull() ? (walker.acceptsEmptyRoot() ? walker.createValueAdapter(element) : null) : walker.createValueAdapter(ref.get());
  }

  private boolean checkIfAlreadyProcessed(@NotNull PsiElement property) {
    Set<PsiElement> data = mySession.getUserData(ANNOTATED_PROPERTIES);
    if (data == null) {
      data = new HashSet<>();
      mySession.putUserData(ANNOTATED_PROPERTIES, data);
    }
    if (data.contains(property)) return true;
    data.add(property);
    return false;
  }
}
