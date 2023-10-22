// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package fit.jetbrains.jsonSchema.impl;

import fit.intellij.json.JsonBundle;
import fit.intellij.json.pointer.JsonPointerPosition;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiElement;
import com.intellij.util.SmartList;
import com.intellij.util.ThreeState;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.MultiMap;
import fit.jetbrains.jsonSchema.extension.JsonLikePsiWalker;
import fit.jetbrains.jsonSchema.extension.adapters.JsonArrayValueAdapter;
import fit.jetbrains.jsonSchema.extension.adapters.JsonObjectValueAdapter;
import fit.jetbrains.jsonSchema.extension.adapters.JsonPropertyAdapter;
import fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static fit.jetbrains.jsonSchema.impl.JsonSchemaVariantsTreeBuilder.doSingleStep;

/**
 * @author Irina.Chernushina on 4/25/2017.
 */
class JsonSchemaAnnotatorChecker {
  private static final Set<fit.jetbrains.jsonSchema.impl.JsonSchemaType> PRIMITIVE_TYPES =
    ContainerUtil.set(fit.jetbrains.jsonSchema.impl.JsonSchemaType._integer, fit.jetbrains.jsonSchema.impl.JsonSchemaType._number, fit.jetbrains.jsonSchema.impl.JsonSchemaType._boolean, fit.jetbrains.jsonSchema.impl.JsonSchemaType._string, fit.jetbrains.jsonSchema.impl.JsonSchemaType._null);
  private final Map<PsiElement, fit.jetbrains.jsonSchema.impl.JsonValidationError> myErrors;
  @NotNull private final Project myProject;
  @NotNull private final fit.jetbrains.jsonSchema.impl.JsonComplianceCheckerOptions myOptions;
  private boolean myHadTypeError;
  private static final String ENUM_MISMATCH_PREFIX = "Value should be one of: ";
  private static final String ACTUAL_PREFIX = "Actual: ";

  protected JsonSchemaAnnotatorChecker(@NotNull Project project, @NotNull fit.jetbrains.jsonSchema.impl.JsonComplianceCheckerOptions options) {
    myProject = project;
    myOptions = options;
    myErrors = new HashMap<>();
  }

  public Map<PsiElement, fit.jetbrains.jsonSchema.impl.JsonValidationError> getErrors() {
    return myErrors;
  }

  public boolean isHadTypeError() {
    return myHadTypeError;
  }

  public static JsonSchemaAnnotatorChecker checkByMatchResult(@NotNull Project project,
                                                              @NotNull fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter elementToCheck,
                                                              @NotNull final fit.jetbrains.jsonSchema.impl.MatchResult result,
                                                              @NotNull fit.jetbrains.jsonSchema.impl.JsonComplianceCheckerOptions options) {
    final List<JsonSchemaAnnotatorChecker> checkers = new ArrayList<>();
    if (result.myExcludingSchemas.isEmpty() && result.mySchemas.size() == 1) {
      final JsonSchemaAnnotatorChecker checker = new JsonSchemaAnnotatorChecker(project, options);
      checker.checkByScheme(elementToCheck, result.mySchemas.iterator().next());
      checkers.add(checker);
    }
    else {
      if (!result.mySchemas.isEmpty()) {
        checkers.add(processSchemasVariants(project, result.mySchemas, elementToCheck, false, options).getSecond());
      }
      if (!result.myExcludingSchemas.isEmpty()) {
        // we can have several oneOf groups, each about, for instance, a part of properties
        // - then we should allow properties from neighbour schemas (even if additionalProperties=false)
        final List<JsonSchemaAnnotatorChecker> list =
          ContainerUtil.map(result.myExcludingSchemas, group -> processSchemasVariants(project, group, elementToCheck, true, options).getSecond());
        checkers.add(mergeErrors(project, list, options, result.myExcludingSchemas));
      }
    }
    if (checkers.isEmpty()) return null;
    if (checkers.size() == 1) return checkers.get(0);

    return checkers.stream()
      .filter(checker -> !checker.isHadTypeError())
      .findFirst()
      .orElse(checkers.get(0));
  }

  private static JsonSchemaAnnotatorChecker mergeErrors(@NotNull Project project,
                                                        @NotNull List<JsonSchemaAnnotatorChecker> list,
                                                        @NotNull fit.jetbrains.jsonSchema.impl.JsonComplianceCheckerOptions options,
                                                        @NotNull List<Collection<? extends fit.jetbrains.jsonSchema.impl.JsonSchemaObject>> excludingSchemas) {
    final JsonSchemaAnnotatorChecker checker = new JsonSchemaAnnotatorChecker(project, options);

    for (JsonSchemaAnnotatorChecker ch: list) {
      for (Map.Entry<PsiElement, fit.jetbrains.jsonSchema.impl.JsonValidationError> element: ch.myErrors.entrySet()) {
        fit.jetbrains.jsonSchema.impl.JsonValidationError error = element.getValue();
        if (error.getFixableIssueKind() == fit.jetbrains.jsonSchema.impl.JsonValidationError.FixableIssueKind.ProhibitedProperty) {
          String propertyName = ((fit.jetbrains.jsonSchema.impl.JsonValidationError.ProhibitedPropertyIssueData)error.getIssueData()).propertyName;
          boolean skip = false;
          for (Collection<? extends fit.jetbrains.jsonSchema.impl.JsonSchemaObject> objects : excludingSchemas) {
            Set<String> keys = objects.stream()
              .filter(o -> !o.hasOwnExtraPropertyProhibition())
              .map(o -> o.getProperties().keySet()).flatMap(Set::stream).collect(Collectors.toSet());
            if (keys.contains(propertyName)) skip = true;
          }
          if (skip) continue;
        }
        checker.myErrors.put(element.getKey(), error);
      }
    }
    return checker;
  }

  private void error(final String error, final PsiElement holder,
                     JsonErrorPriority priority) {
    error(error, holder, fit.jetbrains.jsonSchema.impl.JsonValidationError.FixableIssueKind.None, null, priority);
  }

  private void error(final PsiElement newHolder, fit.jetbrains.jsonSchema.impl.JsonValidationError error) {
    error(error.getMessage(), newHolder, error.getFixableIssueKind(), error.getIssueData(), error.getPriority());
  }

  private void error(final String error, final PsiElement holder,
                     fit.jetbrains.jsonSchema.impl.JsonValidationError.FixableIssueKind fixableIssueKind,
                     fit.jetbrains.jsonSchema.impl.JsonValidationError.IssueData data,
                     JsonErrorPriority priority) {
    if (myErrors.containsKey(holder)) return;
    myErrors.put(holder, new fit.jetbrains.jsonSchema.impl.JsonValidationError(error, fixableIssueKind, data, priority));
  }

  private void typeError(final @NotNull PsiElement value, @Nullable fit.jetbrains.jsonSchema.impl.JsonSchemaType currentType, final @NotNull fit.jetbrains.jsonSchema.impl.JsonSchemaType... allowedTypes) {
    if (allowedTypes.length == 0) return;
    String currentTypeDesc = currentType == null ? "" : (" " + ACTUAL_PREFIX + currentType.getName() + ".");
    String prefix = "Incompatible types.\n";
    if (allowedTypes.length == 1) {
      error(String.format(prefix + " Required: %s.%s", allowedTypes[0].getName(), currentTypeDesc), value,
            fit.jetbrains.jsonSchema.impl.JsonValidationError.FixableIssueKind.ProhibitedType,
            new fit.jetbrains.jsonSchema.impl.JsonValidationError.TypeMismatchIssueData(allowedTypes),
            JsonErrorPriority.TYPE_MISMATCH);
    } else {
      final String typesText = Arrays.stream(allowedTypes)
                                     .map(fit.jetbrains.jsonSchema.impl.JsonSchemaType::getName)
                                     .distinct()
                                     .sorted(Comparator.naturalOrder())
                                     .collect(Collectors.joining(", "));
      error(String.format(prefix + " Required one of: %s.%s", typesText, currentTypeDesc), value,
            fit.jetbrains.jsonSchema.impl.JsonValidationError.FixableIssueKind.ProhibitedType,
            new fit.jetbrains.jsonSchema.impl.JsonValidationError.TypeMismatchIssueData(allowedTypes),
            JsonErrorPriority.TYPE_MISMATCH);
    }
    myHadTypeError = true;
  }

  public void checkByScheme(@NotNull fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter value, @NotNull fit.jetbrains.jsonSchema.impl.JsonSchemaObject schema) {
    final fit.jetbrains.jsonSchema.impl.JsonSchemaType type = fit.jetbrains.jsonSchema.impl.JsonSchemaType.getType(value);
    checkForEnum(value.getDelegate(), schema);
    boolean checkedNumber = false;
    boolean checkedString = false;
    boolean checkedArray = false;
    boolean checkedObject = false;
    if (type != null) {
      fit.jetbrains.jsonSchema.impl.JsonSchemaType schemaType = getMatchingSchemaType(schema, type);
      if (schemaType != null && !schemaType.equals(type)) {
        typeError(value.getDelegate(), value.substituteTypeForErrorMessage(type), schemaType);
      }
      else {
        if (fit.jetbrains.jsonSchema.impl.JsonSchemaType._string_number.equals(type)) {
          checkNumber(value.getDelegate(), schema, type);
          checkedNumber = true;
          checkString(value.getDelegate(), schema);
          checkedString = true;
        }
        else if (fit.jetbrains.jsonSchema.impl.JsonSchemaType._number.equals(type) || fit.jetbrains.jsonSchema.impl.JsonSchemaType._integer.equals(type)) {
          checkNumber(value.getDelegate(), schema, type);
          checkedNumber = true;
        }
        else if (fit.jetbrains.jsonSchema.impl.JsonSchemaType._string.equals(type)) {
          checkString(value.getDelegate(), schema);
          checkedString = true;
        }
        else if (fit.jetbrains.jsonSchema.impl.JsonSchemaType._array.equals(type)) {
          checkArray(value, schema);
          checkedArray = true;
        }
        else if (fit.jetbrains.jsonSchema.impl.JsonSchemaType._object.equals(type)) {
          checkObject(value, schema);
          checkedObject = true;
        }
      }
    }

    if ((!myHadTypeError || myErrors.isEmpty()) && !value.isShouldBeIgnored()) {
      PsiElement delegate = value.getDelegate();
      if (!checkedNumber && schema.hasNumericChecks() && value.isNumberLiteral()) {
        checkNumber(delegate, schema, fit.jetbrains.jsonSchema.impl.JsonSchemaType._number);
      }
      if (!checkedString && schema.hasStringChecks() && value.isStringLiteral()) {
        checkString(delegate, schema);
        checkedString = true;
      }
      if (!checkedArray && schema.hasArrayChecks() && value.isArray()) {
        checkArray(value, schema);
        checkedArray = true;
      }
      if (hasMinMaxLengthChecks(schema)) {
        if (value.isStringLiteral()) {
          if (!checkedString) {
            checkString(delegate, schema);
          }
        }
        else if (value.isArray()) {
          if (!checkedArray) {
            checkArray(value, schema);
          }
        }
      }
      if (!checkedObject && schema.hasObjectChecks() && value.isObject()) {
        checkObject(value, schema);
      }
    }

    if (schema.getNot() != null) {
      final fit.jetbrains.jsonSchema.impl.MatchResult result = new fit.jetbrains.jsonSchema.impl.JsonSchemaResolver(myProject, schema.getNot()).detailedResolve();
      if (result.mySchemas.isEmpty() && result.myExcludingSchemas.isEmpty()) return;

      // if 'not' uses reference to owning schema back -> do not check, seems it does not make any sense
      if (result.mySchemas.stream().anyMatch(s -> schema.equals(s)) ||
          result.myExcludingSchemas.stream().flatMap(Collection::stream)
            .anyMatch(s -> schema.equals(s))) return;

      final JsonSchemaAnnotatorChecker checker = checkByMatchResult(myProject, value, result, myOptions);
      if (checker == null || checker.isCorrect()) error("Validates against 'not' schema", value.getDelegate(), JsonErrorPriority.NOT_SCHEMA);
    }

    List<fit.jetbrains.jsonSchema.impl.IfThenElse> ifThenElseList = schema.getIfThenElse();
    if (ifThenElseList != null) {
      for (IfThenElse ifThenElse : ifThenElseList) {
        fit.jetbrains.jsonSchema.impl.MatchResult result = new fit.jetbrains.jsonSchema.impl.JsonSchemaResolver(myProject, ifThenElse.getIf()).detailedResolve();
        if (result.mySchemas.isEmpty() && result.myExcludingSchemas.isEmpty()) return;

        final JsonSchemaAnnotatorChecker checker = checkByMatchResult(myProject, value, result, myOptions);
        if (checker != null) {
          if (checker.isCorrect()) {
            fit.jetbrains.jsonSchema.impl.JsonSchemaObject then = ifThenElse.getThen();
            if (then != null) {
              checkObjectBySchemaRecordErrors(then, value);
            }
          }
          else {
            fit.jetbrains.jsonSchema.impl.JsonSchemaObject schemaElse = ifThenElse.getElse();
            if (schemaElse != null) {
              checkObjectBySchemaRecordErrors(schemaElse, value);
            }
          }
        }
      }
    }
  }

  private void checkObjectBySchemaRecordErrors(@NotNull fit.jetbrains.jsonSchema.impl.JsonSchemaObject schema, @NotNull fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter object) {
    final JsonSchemaAnnotatorChecker checker = checkByMatchResult(myProject, object, new fit.jetbrains.jsonSchema.impl.JsonSchemaResolver(myProject, schema).detailedResolve(), myOptions);
    if (checker != null) {
      myHadTypeError = checker.isHadTypeError();
      myErrors.putAll(checker.getErrors());
    }
  }

  private void checkObject(@NotNull fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter value, @NotNull fit.jetbrains.jsonSchema.impl.JsonSchemaObject schema) {
    final fit.jetbrains.jsonSchema.extension.adapters.JsonObjectValueAdapter object = value.getAsObject();
    if (object == null) return;

    final List<fit.jetbrains.jsonSchema.extension.adapters.JsonPropertyAdapter> propertyList = object.getPropertyList();
    final Set<String> set = new HashSet<>();
    for (fit.jetbrains.jsonSchema.extension.adapters.JsonPropertyAdapter property : propertyList) {
      final String name = StringUtil.notNullize(property.getName());
      fit.jetbrains.jsonSchema.impl.JsonSchemaObject propertyNamesSchema = schema.getPropertyNamesSchema();
      if (propertyNamesSchema != null) {
        fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter nameValueAdapter = property.getNameValueAdapter();
        if (nameValueAdapter != null) {
          JsonSchemaAnnotatorChecker checker =
            checkByMatchResult(myProject, nameValueAdapter, new fit.jetbrains.jsonSchema.impl.JsonSchemaResolver(myProject, propertyNamesSchema).detailedResolve(),
                               myOptions);
          if (checker != null) {
            this.myErrors.putAll(checker.myErrors);
          }
        }
      }

      final JsonPointerPosition step = JsonPointerPosition.createSingleProperty(name);
      final Pair<ThreeState, fit.jetbrains.jsonSchema.impl.JsonSchemaObject> pair = doSingleStep(step, schema, false);
      if (ThreeState.NO.equals(pair.getFirst()) && !set.contains(name)) {
        error(JsonBundle.message("json.schema.annotation.not.allowed.property", name), property.getDelegate(),
              fit.jetbrains.jsonSchema.impl.JsonValidationError.FixableIssueKind.ProhibitedProperty,
              new fit.jetbrains.jsonSchema.impl.JsonValidationError.ProhibitedPropertyIssueData(name), JsonErrorPriority.LOW_PRIORITY);
      }
      else if (ThreeState.UNSURE.equals(pair.getFirst())) {
        for (fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter propertyValue : property.getValues()) {
          checkObjectBySchemaRecordErrors(pair.getSecond(), propertyValue);
        }
      }
      set.add(name);
    }

    if (object.shouldCheckIntegralRequirements()) {
      final Set<String> required = schema.getRequired();
      if (required != null) {
        HashSet<String> requiredNames = new LinkedHashSet<>(required);
        requiredNames.removeAll(set);
        if (!requiredNames.isEmpty()) {
          fit.jetbrains.jsonSchema.impl.JsonValidationError.MissingMultiplePropsIssueData data = createMissingPropertiesData(schema, requiredNames);
          error("Missing required " + data.getMessage(false), value.getDelegate(), fit.jetbrains.jsonSchema.impl.JsonValidationError.FixableIssueKind.MissingProperty, data,
                JsonErrorPriority.MISSING_PROPS);
        }
      }
      if (schema.getMinProperties() != null && propertyList.size() < schema.getMinProperties()) {
        error("Number of properties is less than " + schema.getMinProperties(), value.getDelegate(), JsonErrorPriority.LOW_PRIORITY);
      }
      if (schema.getMaxProperties() != null && propertyList.size() > schema.getMaxProperties()) {
        error("Number of properties is greater than " + schema.getMaxProperties(), value.getDelegate(), JsonErrorPriority.LOW_PRIORITY);
      }
      final Map<String, List<String>> dependencies = schema.getPropertyDependencies();
      if (dependencies != null) {
        for (Map.Entry<String, List<String>> entry : dependencies.entrySet()) {
          if (set.contains(entry.getKey())) {
            final List<String> list = entry.getValue();
            HashSet<String> deps = new HashSet<>(list);
            deps.removeAll(set);
            if (!deps.isEmpty()) {
              fit.jetbrains.jsonSchema.impl.JsonValidationError.MissingMultiplePropsIssueData data = createMissingPropertiesData(schema, deps);
              error("Dependency is violated: " + data.getMessage(false) + " must be specified, since '" + entry.getKey() + "' is specified",
                    value.getDelegate(),
                    fit.jetbrains.jsonSchema.impl.JsonValidationError.FixableIssueKind.MissingProperty,
                    data, JsonErrorPriority.MISSING_PROPS);
            }
          }
        }
      }
      final Map<String, fit.jetbrains.jsonSchema.impl.JsonSchemaObject> schemaDependencies = schema.getSchemaDependencies();
      if (schemaDependencies != null) {
        for (Map.Entry<String, fit.jetbrains.jsonSchema.impl.JsonSchemaObject> entry : schemaDependencies.entrySet()) {
          if (set.contains(entry.getKey())) {
            checkObjectBySchemaRecordErrors(entry.getValue(), value);
          }
        }
      }
    }
  }

  @Nullable
  private static Object getDefaultValueFromEnum(@NotNull fit.jetbrains.jsonSchema.impl.JsonSchemaObject propertySchema, @NotNull Ref<Integer> enumCount) {
    List<Object> enumValues = propertySchema.getEnum();
    if (enumValues != null) {
      enumCount.set(enumValues.size());
      if (enumValues.size() == 1) {
        Object defaultObject = enumValues.get(0);
        return defaultObject instanceof String ? StringUtil.unquoteString((String)defaultObject) : defaultObject;
      }
    }
    return null;
  }

  @NotNull
  private fit.jetbrains.jsonSchema.impl.JsonValidationError.MissingMultiplePropsIssueData createMissingPropertiesData(@NotNull fit.jetbrains.jsonSchema.impl.JsonSchemaObject schema,
                                                                                                                      HashSet<String> requiredNames) {
    List<fit.jetbrains.jsonSchema.impl.JsonValidationError.MissingPropertyIssueData> allProps = new ArrayList<>();
    for (String req: requiredNames) {
      fit.jetbrains.jsonSchema.impl.JsonSchemaObject propertySchema = resolvePropertySchema(schema, req);
      Object defaultValue = propertySchema == null ? null : propertySchema.getDefault();
      Ref<Integer> enumCount = Ref.create(0);

      fit.jetbrains.jsonSchema.impl.JsonSchemaType type = null;

      if (propertySchema != null) {
        MatchResult result = null;
        Object valueFromEnum = getDefaultValueFromEnum(propertySchema, enumCount);
        if (valueFromEnum != null) {
          defaultValue = valueFromEnum;
        }
        else {
          result = new fit.jetbrains.jsonSchema.impl.JsonSchemaResolver(myProject, propertySchema).detailedResolve();
          if (result.mySchemas.size() == 1) {
            valueFromEnum = getDefaultValueFromEnum(result.mySchemas.get(0), enumCount);
            if (valueFromEnum != null) {
              defaultValue = valueFromEnum;
            }
          }
        }
        type = propertySchema.getType();
        if (type == null) {
          if (result == null) {
            result = new fit.jetbrains.jsonSchema.impl.JsonSchemaResolver(myProject, propertySchema).detailedResolve();
          }
          if (result.mySchemas.size() == 1) {
            type = result.mySchemas.get(0).getType();
          }
        }
      }
      allProps.add(new fit.jetbrains.jsonSchema.impl.JsonValidationError.MissingPropertyIssueData(req,
                                                                    type,
                                                                    defaultValue,
                                                                    enumCount.get()));
    }

    return new fit.jetbrains.jsonSchema.impl.JsonValidationError.MissingMultiplePropsIssueData(allProps);
  }

  private static fit.jetbrains.jsonSchema.impl.JsonSchemaObject resolvePropertySchema(@NotNull fit.jetbrains.jsonSchema.impl.JsonSchemaObject schema, String req) {
    if (schema.getProperties().containsKey(req)) {
      return schema.getProperties().get(req);
    }
    else {
      fit.jetbrains.jsonSchema.impl.JsonSchemaObject propertySchema = schema.getMatchingPatternPropertySchema(req);
      if (propertySchema != null) {
        return propertySchema;
      }
      else {
        fit.jetbrains.jsonSchema.impl.JsonSchemaObject additionalPropertiesSchema = schema.getAdditionalPropertiesSchema();
        if (additionalPropertiesSchema != null) {
          return additionalPropertiesSchema;
        }
      }
    }
    return null;
  }

  private static boolean checkEnumValue(@NotNull Object object,
                                        @NotNull JsonLikePsiWalker walker,
                                        @Nullable fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter adapter,
                                        @NotNull String text,
                                        @NotNull BiFunction<String, String, Boolean> stringEq) {
    if (adapter != null && !adapter.shouldCheckAsValue()) return true;
    if (object instanceof fit.jetbrains.jsonSchema.impl.EnumArrayValueWrapper) {
      if (adapter instanceof fit.jetbrains.jsonSchema.extension.adapters.JsonArrayValueAdapter) {
        List<fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter> elements = ((fit.jetbrains.jsonSchema.extension.adapters.JsonArrayValueAdapter)adapter).getElements();
        Object[] values = ((EnumArrayValueWrapper)object).getValues();
        if (elements.size() == values.length) {
          for (int i = 0; i < values.length; i++) {
            if (!checkEnumValue(values[i], walker, elements.get(i), walker.getNodeTextForValidation(elements.get(i).getDelegate()), stringEq)) return false;
          }
          return true;
        }
      }
    }
    else if (object instanceof fit.jetbrains.jsonSchema.impl.EnumObjectValueWrapper) {
      if (adapter instanceof fit.jetbrains.jsonSchema.extension.adapters.JsonObjectValueAdapter) {
        List<fit.jetbrains.jsonSchema.extension.adapters.JsonPropertyAdapter> props = ((JsonObjectValueAdapter)adapter).getPropertyList();
        Map<String, Object> values = ((EnumObjectValueWrapper)object).getValues();
        if (props.size() == values.size()) {
          for (JsonPropertyAdapter prop : props) {
            if (!values.containsKey(prop.getName())) return false;
            for (fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter value : prop.getValues()) {
              if (!checkEnumValue(values.get(prop.getName()), walker, value, walker.getNodeTextForValidation(value.getDelegate()), stringEq)) return false;
            }
          }

          return true;
        }
      }
    }
    else {
      if (!walker.allowsSingleQuotes()) {
        if (stringEq.apply(object.toString(), text)) return true;
      }
      else {
        if (equalsIgnoreQuotes(object.toString(), text, walker.requiresValueQuotes(), stringEq)) return true;
      }
    }

    return false;
  }

  private void checkForEnum(PsiElement value, fit.jetbrains.jsonSchema.impl.JsonSchemaObject schema) {
    List<Object> enumItems = schema.getEnum();
    if (enumItems == null) return;
    final JsonLikePsiWalker walker = JsonLikePsiWalker.getWalker(value, schema);
    if (walker == null) return;
    final String text = StringUtil.notNullize(walker.getNodeTextForValidation(value));
    BiFunction<String, String, Boolean> eq = myOptions.isCaseInsensitiveEnumCheck() || schema.isForceCaseInsensitive()
                                             ? String::equalsIgnoreCase
                                             : String::equals;
    for (Object object : enumItems) {
      if (checkEnumValue(object, walker, walker.createValueAdapter(value), text, eq)) return;
    }
    error(ENUM_MISMATCH_PREFIX + StringUtil.join(enumItems, o -> o.toString(), ", "), value,
          fit.jetbrains.jsonSchema.impl.JsonValidationError.FixableIssueKind.NonEnumValue, null, JsonErrorPriority.MEDIUM_PRIORITY);
  }

  private static boolean equalsIgnoreQuotes(@NotNull final String s1,
                                            @NotNull final String s2,
                                            boolean requireQuotedValues,
                                            BiFunction<String, String, Boolean> eq) {
    final boolean quoted1 = StringUtil.isQuotedString(s1);
    final boolean quoted2 = StringUtil.isQuotedString(s2);
    if (requireQuotedValues && quoted1 != quoted2) return false;
    if (requireQuotedValues && !quoted1) return eq.apply(s1, s2);
    return eq.apply(StringUtil.unquoteString(s1), StringUtil.unquoteString(s2));
  }

  private void checkArray(fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter value, fit.jetbrains.jsonSchema.impl.JsonSchemaObject schema) {
    final JsonArrayValueAdapter asArray = value.getAsArray();
    if (asArray == null) return;
    final List<fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter> elements = asArray.getElements();
    if (schema.getMinLength() != null && elements.size() < schema.getMinLength()) {
      error("Array is shorter than " + schema.getMinLength(), value.getDelegate(), JsonErrorPriority.LOW_PRIORITY);
      return;
    }
    checkArrayItems(value, elements, schema);
  }

  @NotNull
  private static Pair<fit.jetbrains.jsonSchema.impl.JsonSchemaObject, JsonSchemaAnnotatorChecker> processSchemasVariants(
          @NotNull Project project, @NotNull final Collection<? extends fit.jetbrains.jsonSchema.impl.JsonSchemaObject> collection,
          @NotNull final fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter value, boolean isOneOf, JsonComplianceCheckerOptions options) {

    final JsonSchemaAnnotatorChecker checker = new JsonSchemaAnnotatorChecker(project, options);
    final fit.jetbrains.jsonSchema.impl.JsonSchemaType type = fit.jetbrains.jsonSchema.impl.JsonSchemaType.getType(value);
    fit.jetbrains.jsonSchema.impl.JsonSchemaObject selected = null;
    if (type == null) {
      if (!value.isShouldBeIgnored()) checker.typeError(value.getDelegate(), null, getExpectedTypes(collection));
    }
    else {
      final List<fit.jetbrains.jsonSchema.impl.JsonSchemaObject> filtered = new ArrayList<>(collection.size());
      for (fit.jetbrains.jsonSchema.impl.JsonSchemaObject schema: collection) {
        if (!areSchemaTypesCompatible(schema, type)) continue;
        filtered.add(schema);
      }
      if (filtered.isEmpty()) checker.typeError(value.getDelegate(), value.substituteTypeForErrorMessage(type), getExpectedTypes(collection));
      else if (filtered.size() == 1) {
        selected = filtered.get(0);
        checker.checkByScheme(value, selected);
      }
      else {
        if (isOneOf) {
          selected = checker.processOneOf(value, filtered);
        }
        else {
          selected = checker.processAnyOf(value, filtered);
        }
      }
    }
    return Pair.create(selected, checker);
  }

  private final static fit.jetbrains.jsonSchema.impl.JsonSchemaType[] NO_TYPES = new fit.jetbrains.jsonSchema.impl.JsonSchemaType[0];
  private static fit.jetbrains.jsonSchema.impl.JsonSchemaType[] getExpectedTypes(final Collection<? extends fit.jetbrains.jsonSchema.impl.JsonSchemaObject> schemas) {
    final List<fit.jetbrains.jsonSchema.impl.JsonSchemaType> list = new ArrayList<>();
    for (fit.jetbrains.jsonSchema.impl.JsonSchemaObject schema : schemas) {
      final fit.jetbrains.jsonSchema.impl.JsonSchemaType type = schema.getType();
      if (type != null) {
        list.add(type);
      } else {
        final Set<fit.jetbrains.jsonSchema.impl.JsonSchemaType> variants = schema.getTypeVariants();
        if (variants != null) {
          list.addAll(variants);
        }
      }
    }
    return list.isEmpty() ? NO_TYPES : list.toArray(NO_TYPES);
  }

  public static boolean areSchemaTypesCompatible(@NotNull final fit.jetbrains.jsonSchema.impl.JsonSchemaObject schema, @NotNull final fit.jetbrains.jsonSchema.impl.JsonSchemaType type) {
    final fit.jetbrains.jsonSchema.impl.JsonSchemaType matchingSchemaType = getMatchingSchemaType(schema, type);
    if (matchingSchemaType != null) return matchingSchemaType.equals(type);
    if (schema.getEnum() != null) {
      return PRIMITIVE_TYPES.contains(type);
    }
    return true;
  }

  @Nullable
  private static fit.jetbrains.jsonSchema.impl.JsonSchemaType getMatchingSchemaType(@NotNull fit.jetbrains.jsonSchema.impl.JsonSchemaObject schema, @NotNull fit.jetbrains.jsonSchema.impl.JsonSchemaType input) {
    if (schema.getType() != null) {
      final fit.jetbrains.jsonSchema.impl.JsonSchemaType matchType = schema.getType();
      if (matchType != null) {
        if (fit.jetbrains.jsonSchema.impl.JsonSchemaType._integer.equals(input) && fit.jetbrains.jsonSchema.impl.JsonSchemaType._number.equals(matchType)) {
          return input;
        }
        if (fit.jetbrains.jsonSchema.impl.JsonSchemaType._string_number.equals(input) && (fit.jetbrains.jsonSchema.impl.JsonSchemaType._number.equals(matchType)
                                                            || fit.jetbrains.jsonSchema.impl.JsonSchemaType._integer.equals(matchType)
                                                            || fit.jetbrains.jsonSchema.impl.JsonSchemaType._string.equals(matchType))) {
          return input;
        }
        return matchType;
      }
    }
    if (schema.getTypeVariants() != null) {
      Set<fit.jetbrains.jsonSchema.impl.JsonSchemaType> matchTypes = schema.getTypeVariants();
      if (matchTypes.contains(input)) {
        return input;
      }
      if (fit.jetbrains.jsonSchema.impl.JsonSchemaType._integer.equals(input) && matchTypes.contains(fit.jetbrains.jsonSchema.impl.JsonSchemaType._number)) {
        return input;
      }
      if (fit.jetbrains.jsonSchema.impl.JsonSchemaType._string_number.equals(input) &&
          (matchTypes.contains(fit.jetbrains.jsonSchema.impl.JsonSchemaType._number)
           || matchTypes.contains(fit.jetbrains.jsonSchema.impl.JsonSchemaType._integer)
           || matchTypes.contains(fit.jetbrains.jsonSchema.impl.JsonSchemaType._string))) {
        return input;
      }
      //nothing matches, lets return one of the list so that other heuristics does not match
      return matchTypes.iterator().next();
    }
    if (!schema.getProperties().isEmpty() && fit.jetbrains.jsonSchema.impl.JsonSchemaType._object.equals(input)) return fit.jetbrains.jsonSchema.impl.JsonSchemaType._object;
    return null;
  }

  private void checkArrayItems(@NotNull fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter array, @NotNull final List<fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter> list, final fit.jetbrains.jsonSchema.impl.JsonSchemaObject schema) {
    if (schema.isUniqueItems()) {
      final MultiMap<String, fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter> valueTexts = new MultiMap<>();
      final JsonLikePsiWalker walker = JsonLikePsiWalker.getWalker(array.getDelegate(), schema);
      assert walker != null;
      for (fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter adapter : list) {
        valueTexts.putValue(walker.getNodeTextForValidation(adapter.getDelegate()), adapter);
      }

      for (Map.Entry<String, Collection<fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter>> entry: valueTexts.entrySet()) {
        if (entry.getValue().size() > 1) {
          for (fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter item: entry.getValue()) {
            if (!item.shouldCheckAsValue()) continue;
            error("Item is not unique", item.getDelegate(), JsonErrorPriority.TYPE_MISMATCH);
          }
        }
      }
    }
    if (schema.getContainsSchema() != null) {
      boolean match = false;
      for (fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter item: list) {
        final JsonSchemaAnnotatorChecker checker = checkByMatchResult(myProject, item, new JsonSchemaResolver(myProject, schema.getContainsSchema()).detailedResolve(), myOptions);
        if (checker == null || checker.myErrors.size() == 0 && !checker.myHadTypeError) {
          match = true;
          break;
        }
      }
      if (!match) {
        error("No match for 'contains' rule", array.getDelegate(), JsonErrorPriority.MEDIUM_PRIORITY);
      }
    }
    if (schema.getItemsSchema() != null) {
      for (fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter item : list) {
        checkObjectBySchemaRecordErrors(schema.getItemsSchema(), item);
      }
    }
    else if (schema.getItemsSchemaList() != null) {
      final Iterator<fit.jetbrains.jsonSchema.impl.JsonSchemaObject> iterator = schema.getItemsSchemaList().iterator();
      for (fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter arrayValue : list) {
        if (iterator.hasNext()) {
          checkObjectBySchemaRecordErrors(iterator.next(), arrayValue);
        }
        else {
          if (!Boolean.TRUE.equals(schema.getAdditionalItemsAllowed())) {
            error("Additional items are not allowed", arrayValue.getDelegate(), JsonErrorPriority.LOW_PRIORITY);
          }
          else if (schema.getAdditionalItemsSchema() != null) {
            checkObjectBySchemaRecordErrors(schema.getAdditionalItemsSchema(), arrayValue);
          }
        }
      }
    }
    if (schema.getMinItems() != null && list.size() < schema.getMinItems()) {
      error("Array is shorter than " + schema.getMinItems(), array.getDelegate(), JsonErrorPriority.LOW_PRIORITY);
    }
    if (schema.getMaxItems() != null && list.size() > schema.getMaxItems()) {
      error("Array is longer than " + schema.getMaxItems(), array.getDelegate(), JsonErrorPriority.LOW_PRIORITY);
    }
  }

  private static boolean hasMinMaxLengthChecks(fit.jetbrains.jsonSchema.impl.JsonSchemaObject schema) {
    return schema.getMinLength() != null || schema.getMaxLength() != null;
  }

  private void checkString(PsiElement propValue, fit.jetbrains.jsonSchema.impl.JsonSchemaObject schema) {
    String v = getValue(propValue, schema);
    if (v == null) return;
    final String value = StringUtil.unquoteString(v);
    if (schema.getMinLength() != null) {
      if (value.length() < schema.getMinLength()) {
        error("String is shorter than " + schema.getMinLength(), propValue, JsonErrorPriority.LOW_PRIORITY);
        return;
      }
    }
    if (schema.getMaxLength() != null) {
      if (value.length() > schema.getMaxLength()) {
        error("String is longer than " + schema.getMaxLength(), propValue, JsonErrorPriority.LOW_PRIORITY);
        return;
      }
    }
    if (schema.getPattern() != null) {
      if (schema.getPatternError() != null) {
        error("Can not check string by pattern because of error: " + StringUtil.convertLineSeparators(schema.getPatternError()),
              propValue, JsonErrorPriority.LOW_PRIORITY);
      }
      if (!schema.checkByPattern(value)) {
        error("String is violating the pattern: '" + StringUtil.convertLineSeparators(schema.getPattern()) + "'", propValue, JsonErrorPriority.LOW_PRIORITY);
      }
    }
    // I think we are not gonna to support format, there are a couple of RFCs there to check upon..
    /*
    if (schema.getFormat() != null) {
      LOG.info("Unsupported property used: 'format'");
    }*/
  }

  @Nullable
  private static String getValue(PsiElement propValue, fit.jetbrains.jsonSchema.impl.JsonSchemaObject schema) {
    final JsonLikePsiWalker walker = JsonLikePsiWalker.getWalker(propValue, schema);
    assert walker != null;
    fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter adapter = walker.createValueAdapter(propValue);
    if (adapter != null && !adapter.shouldCheckAsValue()) return null;
    return walker.getNodeTextForValidation(propValue);
  }

  private void checkNumber(PsiElement propValue, fit.jetbrains.jsonSchema.impl.JsonSchemaObject schema, fit.jetbrains.jsonSchema.impl.JsonSchemaType schemaType) {
    Number value;
    String valueText = getValue(propValue, schema);
    if (valueText == null) return;
    if (fit.jetbrains.jsonSchema.impl.JsonSchemaType._integer.equals(schemaType)) {
      value = fit.jetbrains.jsonSchema.impl.JsonSchemaType.getIntegerValue(valueText);
      if (value == null) {
        error("Integer value expected", propValue,
              fit.jetbrains.jsonSchema.impl.JsonValidationError.FixableIssueKind.TypeMismatch,
              new fit.jetbrains.jsonSchema.impl.JsonValidationError.TypeMismatchIssueData(new fit.jetbrains.jsonSchema.impl.JsonSchemaType[]{schemaType}), JsonErrorPriority.TYPE_MISMATCH);
        return;
      }
    }
    else {
      try {
        value = Double.valueOf(valueText);
      }
      catch (NumberFormatException e) {
        if (!fit.jetbrains.jsonSchema.impl.JsonSchemaType._string_number.equals(schemaType)) {
          error("Double value expected", propValue,
                fit.jetbrains.jsonSchema.impl.JsonValidationError.FixableIssueKind.TypeMismatch,
                new fit.jetbrains.jsonSchema.impl.JsonValidationError.TypeMismatchIssueData(new fit.jetbrains.jsonSchema.impl.JsonSchemaType[]{schemaType}), JsonErrorPriority.TYPE_MISMATCH);
        }
        return;
      }
    }
    final Number multipleOf = schema.getMultipleOf();
    if (multipleOf != null) {
      final double leftOver = value.doubleValue() % multipleOf.doubleValue();
      if (leftOver > 0.000001) {
        final String multipleOfValue = String.valueOf(Math.abs(multipleOf.doubleValue() - multipleOf.intValue()) < 0.000001 ?
                                                      multipleOf.intValue() : multipleOf);
        error("Is not multiple of " + multipleOfValue, propValue, JsonErrorPriority.LOW_PRIORITY);
        return;
      }
    }

    checkMinimum(schema, value, propValue, schemaType);
    checkMaximum(schema, value, propValue, schemaType);
  }

  private void checkMaximum(fit.jetbrains.jsonSchema.impl.JsonSchemaObject schema, Number value, PsiElement propertyValue,
                            @NotNull fit.jetbrains.jsonSchema.impl.JsonSchemaType propValueType) {

    Number exclusiveMaximumNumber = schema.getExclusiveMaximumNumber();
    if (exclusiveMaximumNumber != null) {
      if (fit.jetbrains.jsonSchema.impl.JsonSchemaType._integer.equals(propValueType)) {
        final int intValue = exclusiveMaximumNumber.intValue();
        if (value.intValue() >= intValue) {
          error("Greater than an exclusive maximum " + intValue, propertyValue, JsonErrorPriority.LOW_PRIORITY);
        }
      }
      else {
        final double doubleValue = exclusiveMaximumNumber.doubleValue();
        if (value.doubleValue() >= doubleValue) {
          error("Greater than an exclusive maximum " + exclusiveMaximumNumber, propertyValue, JsonErrorPriority.LOW_PRIORITY);
        }
      }
    }
    Number maximum = schema.getMaximum();
    if (maximum == null) return;
    boolean isExclusive = Boolean.TRUE.equals(schema.isExclusiveMaximum());
    if (fit.jetbrains.jsonSchema.impl.JsonSchemaType._integer.equals(propValueType)) {
      final int intValue = maximum.intValue();
      if (isExclusive) {
        if (value.intValue() >= intValue) {
          error("Greater than an exclusive maximum " + intValue, propertyValue, JsonErrorPriority.LOW_PRIORITY);
        }
      }
      else {
        if (value.intValue() > intValue) {
          error("Greater than a maximum " + intValue, propertyValue, JsonErrorPriority.LOW_PRIORITY);
        }
      }
    }
    else {
      final double doubleValue = maximum.doubleValue();
      if (isExclusive) {
        if (value.doubleValue() >= doubleValue) {
          error("Greater than an exclusive maximum " + maximum, propertyValue, JsonErrorPriority.LOW_PRIORITY);
        }
      }
      else {
        if (value.doubleValue() > doubleValue) {
          error("Greater than a maximum " + maximum, propertyValue, JsonErrorPriority.LOW_PRIORITY);
        }
      }
    }
  }

  private void checkMinimum(fit.jetbrains.jsonSchema.impl.JsonSchemaObject schema, Number value, PsiElement propertyValue,
                            @NotNull fit.jetbrains.jsonSchema.impl.JsonSchemaType schemaType) {
    // schema v6 - exclusiveMinimum is numeric now
    Number exclusiveMinimumNumber = schema.getExclusiveMinimumNumber();
    if (exclusiveMinimumNumber != null) {
      if (fit.jetbrains.jsonSchema.impl.JsonSchemaType._integer.equals(schemaType)) {
        final int intValue = exclusiveMinimumNumber.intValue();
        if (value.intValue() <= intValue) {
          error("Less than an exclusive minimum" + intValue, propertyValue, JsonErrorPriority.LOW_PRIORITY);
        }
      }
      else {
        final double doubleValue = exclusiveMinimumNumber.doubleValue();
        if (value.doubleValue() <= doubleValue) {
          error("Less than an exclusive minimum " + exclusiveMinimumNumber, propertyValue, JsonErrorPriority.LOW_PRIORITY);
        }
      }
    }

    Number minimum = schema.getMinimum();
    if (minimum == null) return;
    boolean isExclusive = Boolean.TRUE.equals(schema.isExclusiveMinimum());
    if (fit.jetbrains.jsonSchema.impl.JsonSchemaType._integer.equals(schemaType)) {
      final int intValue = minimum.intValue();
      if (isExclusive) {
        if (value.intValue() <= intValue) {
          error("Less than an exclusive minimum " + intValue, propertyValue, JsonErrorPriority.LOW_PRIORITY);
        }
      }
      else {
        if (value.intValue() < intValue) {
          error("Less than a minimum " + intValue, propertyValue, JsonErrorPriority.LOW_PRIORITY);
        }
      }
    }
    else {
      final double doubleValue = minimum.doubleValue();
      if (isExclusive) {
        if (value.doubleValue() <= doubleValue) {
          error("Less than an exclusive minimum " + minimum, propertyValue, JsonErrorPriority.LOW_PRIORITY);
        }
      }
      else {
        if (value.doubleValue() < doubleValue) {
          error("Less than a minimum " + minimum, propertyValue, JsonErrorPriority.LOW_PRIORITY);
        }
      }
    }
  }

  // returns the schema, selected for annotation
  private fit.jetbrains.jsonSchema.impl.JsonSchemaObject processOneOf(@NotNull fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter value, List<fit.jetbrains.jsonSchema.impl.JsonSchemaObject> oneOf) {
    final List<JsonSchemaAnnotatorChecker> candidateErroneousCheckers = new ArrayList<>();
    final List<fit.jetbrains.jsonSchema.impl.JsonSchemaObject> candidateErroneousSchemas = new ArrayList<>();
    final List<fit.jetbrains.jsonSchema.impl.JsonSchemaObject> correct = new SmartList<>();
    for (fit.jetbrains.jsonSchema.impl.JsonSchemaObject object : oneOf) {
      // skip it if something JS awaited, we do not process it currently
      if (object.isShouldValidateAgainstJSType()) continue;

      final JsonSchemaAnnotatorChecker checker = new JsonSchemaAnnotatorChecker(myProject, myOptions);
      checker.checkByScheme(value, object);

      if (checker.isCorrect()) {
        candidateErroneousCheckers.clear();
        candidateErroneousSchemas.clear();
        correct.add(object);
      }
      else {
        candidateErroneousCheckers.add(checker);
        candidateErroneousSchemas.add(object);
      }
    }
    if (correct.size() == 1) return correct.get(0);
    if (correct.size() > 0) {
      final fit.jetbrains.jsonSchema.impl.JsonSchemaType type = fit.jetbrains.jsonSchema.impl.JsonSchemaType.getType(value);
      if (type != null) {
        // also check maybe some currently not checked properties like format are different with schemes
        // todo note that JsonSchemaObject#equals is broken by design, so normally it shouldn't be used until rewritten
        //  but for now we use it here to avoid similar schemas being marked as duplicates
        if (new HashSet<>(correct).size() > 1 && !schemesDifferWithNotCheckedProperties(correct)) {
          error("Validates to more than one variant", value.getDelegate(), JsonErrorPriority.MEDIUM_PRIORITY);
        }
      }
      return ContainerUtil.getLastItem(correct);
    }

    return showErrorsAndGetLeastErroneous(candidateErroneousCheckers, candidateErroneousSchemas, true);
  }

  private static boolean schemesDifferWithNotCheckedProperties(@NotNull final List<fit.jetbrains.jsonSchema.impl.JsonSchemaObject> list) {
    return list.stream().anyMatch(s -> !StringUtil.isEmptyOrSpaces(s.getFormat()));
  }

  private enum AverageFailureAmount {
    Light,
    MissingItems,
    Medium,
    Hard,
    NotSchema
  }

  @NotNull
  private static AverageFailureAmount getAverageFailureAmount(@NotNull JsonSchemaAnnotatorChecker checker) {
    int lowPriorityCount = 0;
    boolean hasMedium = false;
    boolean hasMissing = false;
    boolean hasHard = false;
    Collection<fit.jetbrains.jsonSchema.impl.JsonValidationError> values = checker.getErrors().values();
    for (fit.jetbrains.jsonSchema.impl.JsonValidationError value: values) {
      switch (value.getPriority()) {
        case LOW_PRIORITY:
          lowPriorityCount++;
          break;
        case MISSING_PROPS:
          hasMissing = true;
          break;
        case MEDIUM_PRIORITY:
          hasMedium = true;
          break;
        case TYPE_MISMATCH:
          hasHard = true;
          break;
        case NOT_SCHEMA:
          return AverageFailureAmount.NotSchema;
      }
    }

    if (hasHard) {
      return AverageFailureAmount.Hard;
    }

    // missing props should win against other conditions
    if (hasMissing) {
      return AverageFailureAmount.MissingItems;
    }

    if (hasMedium) {
      return AverageFailureAmount.Medium;
    }

    return lowPriorityCount <= 3 ? AverageFailureAmount.Light : AverageFailureAmount.Medium;
  }

  // returns the schema, selected for annotation
  private fit.jetbrains.jsonSchema.impl.JsonSchemaObject processAnyOf(@NotNull JsonValueAdapter value, List<fit.jetbrains.jsonSchema.impl.JsonSchemaObject> anyOf) {
    final List<JsonSchemaAnnotatorChecker> candidateErroneousCheckers = new ArrayList<>();
    final List<fit.jetbrains.jsonSchema.impl.JsonSchemaObject> candidateErroneousSchemas = new ArrayList<>();

    for (fit.jetbrains.jsonSchema.impl.JsonSchemaObject object : anyOf) {
      final JsonSchemaAnnotatorChecker checker = new JsonSchemaAnnotatorChecker(myProject, myOptions);
      checker.checkByScheme(value, object);
      if (checker.isCorrect()) {
        return object;
      }
      // maybe we still find the correct schema - continue to iterate
      candidateErroneousCheckers.add(checker);
      candidateErroneousSchemas.add(object);
    }

    return showErrorsAndGetLeastErroneous(candidateErroneousCheckers, candidateErroneousSchemas, false);
  }

  /**
   * Filters schema validation results to get the result with the "minimal" amount of errors.
   * This is needed in case of oneOf or anyOf conditions, when there exist no match.
   * I.e., when we have multiple schema candidates, but none is applicable.
   * In this case we need to show the most "suitable" error messages
   *   - by detecting the most "likely" schema corresponding to the current entity
   */
  @Nullable
  private fit.jetbrains.jsonSchema.impl.JsonSchemaObject showErrorsAndGetLeastErroneous(@NotNull List<JsonSchemaAnnotatorChecker> candidateErroneousCheckers,
                                                                                        @NotNull List<fit.jetbrains.jsonSchema.impl.JsonSchemaObject> candidateErroneousSchemas,
                                                                                        boolean isOneOf) {
    fit.jetbrains.jsonSchema.impl.JsonSchemaObject current = null;
    JsonSchemaObject currentWithMinAverage = null;
    Optional<AverageFailureAmount> minAverage = candidateErroneousCheckers.stream()
                                                                          .map(c -> getAverageFailureAmount(c))
                                                                          .min(Comparator.comparingInt(c -> c.ordinal()));
    int min = minAverage.orElse(AverageFailureAmount.Hard).ordinal();

    int minErrorCount = candidateErroneousCheckers.stream().map(c -> c.getErrors().size()).min(Integer::compareTo).orElse(Integer.MAX_VALUE);

    MultiMap<PsiElement, fit.jetbrains.jsonSchema.impl.JsonValidationError> errorsWithMinAverage = MultiMap.create();
    MultiMap<PsiElement, fit.jetbrains.jsonSchema.impl.JsonValidationError> allErrors = MultiMap.create();
    for (int i = 0; i < candidateErroneousCheckers.size(); i++) {
      JsonSchemaAnnotatorChecker checker = candidateErroneousCheckers.get(i);
      final boolean isMoreThanMinErrors = checker.getErrors().size() > minErrorCount;
      final boolean isMoreThanAverage = getAverageFailureAmount(checker).ordinal() > min;
      if (!isMoreThanMinErrors) {
        if (isMoreThanAverage) {
          currentWithMinAverage = candidateErroneousSchemas.get(i);
        }
        else {
          current = candidateErroneousSchemas.get(i);
        }

        for (Map.Entry<PsiElement, fit.jetbrains.jsonSchema.impl.JsonValidationError> entry: checker.getErrors().entrySet()) {
          (isMoreThanAverage ? errorsWithMinAverage : allErrors).putValue(entry.getKey(), entry.getValue());
        }
      }
    }

    if (allErrors.isEmpty()) allErrors = errorsWithMinAverage;

    for (Map.Entry<PsiElement, Collection<fit.jetbrains.jsonSchema.impl.JsonValidationError>> entry : allErrors.entrySet()) {
      Collection<fit.jetbrains.jsonSchema.impl.JsonValidationError> value = entry.getValue();
      if (value.size() == 0) continue;
      if (value.size() == 1) {
        error(entry.getKey(), value.iterator().next());
        continue;
      }
      fit.jetbrains.jsonSchema.impl.JsonValidationError error = tryMergeErrors(value, isOneOf);
      if (error != null) {
        error(entry.getKey(), error);
      }
      else {
        for (fit.jetbrains.jsonSchema.impl.JsonValidationError validationError : value) {
          error(entry.getKey(), validationError);
        }
      }
    }

    if (current == null) {
      current = currentWithMinAverage;
    }
    if (current == null) {
      current = ContainerUtil.getLastItem(candidateErroneousSchemas);
    }

    return current;
  }

  @Nullable
  private static fit.jetbrains.jsonSchema.impl.JsonValidationError tryMergeErrors(@NotNull Collection<fit.jetbrains.jsonSchema.impl.JsonValidationError> errors, boolean isOneOf) {
    fit.jetbrains.jsonSchema.impl.JsonValidationError.FixableIssueKind commonIssueKind = null;
    for (fit.jetbrains.jsonSchema.impl.JsonValidationError error : errors) {
      fit.jetbrains.jsonSchema.impl.JsonValidationError.FixableIssueKind currentIssueKind = error.getFixableIssueKind();
      if (currentIssueKind == fit.jetbrains.jsonSchema.impl.JsonValidationError.FixableIssueKind.None) return null;
      else if (commonIssueKind == null) commonIssueKind = currentIssueKind;
      else if (currentIssueKind != commonIssueKind) return null;
    }

    if (commonIssueKind == fit.jetbrains.jsonSchema.impl.JsonValidationError.FixableIssueKind.NonEnumValue) {
      return new fit.jetbrains.jsonSchema.impl.JsonValidationError(ENUM_MISMATCH_PREFIX
                                     + errors
                                       .stream()
                                       .map(e -> StringUtil.trimStart(e.getMessage(), ENUM_MISMATCH_PREFIX))
                                       .map(e -> StringUtil.split(e, ", "))
                                       .flatMap(e -> e.stream())
                                       .distinct()
                                       .collect(Collectors.joining(", ")), commonIssueKind, null, errors.iterator().next().getPriority());
    }

    if (commonIssueKind == fit.jetbrains.jsonSchema.impl.JsonValidationError.FixableIssueKind.MissingProperty) {
      String prefix = isOneOf ? "One of the following property sets is required: " : "Should have at least one of the following property sets: ";
      return new fit.jetbrains.jsonSchema.impl.JsonValidationError(prefix +
                                     errors.stream().map(e -> (fit.jetbrains.jsonSchema.impl.JsonValidationError.MissingMultiplePropsIssueData)e.getIssueData())
                                    .map(d -> d.getMessage(false)).collect(Collectors.joining(", or ")),
                                     isOneOf ? fit.jetbrains.jsonSchema.impl.JsonValidationError.FixableIssueKind.MissingOneOfProperty : fit.jetbrains.jsonSchema.impl.JsonValidationError.FixableIssueKind.MissingAnyOfProperty,
                                     new fit.jetbrains.jsonSchema.impl.JsonValidationError.MissingOneOfPropsIssueData(
                                       ContainerUtil.map(errors, e -> (fit.jetbrains.jsonSchema.impl.JsonValidationError.MissingMultiplePropsIssueData)e.getIssueData())), errors.iterator().next().getPriority());
    }

    if (commonIssueKind == fit.jetbrains.jsonSchema.impl.JsonValidationError.FixableIssueKind.ProhibitedType) {
      final Set<fit.jetbrains.jsonSchema.impl.JsonSchemaType> allTypes = errors.stream().map(e -> (fit.jetbrains.jsonSchema.impl.JsonValidationError.TypeMismatchIssueData)e.getIssueData())
        .flatMap(d -> Arrays.stream(d.expectedTypes)).collect(Collectors.toSet());

      if (allTypes.size() == 1) return errors.iterator().next();

      List<String> actualInfos = errors.stream().map(e -> e.getMessage()).map(JsonSchemaAnnotatorChecker::fetchActual).distinct().collect(Collectors.toList());
      String actualInfo = actualInfos.size() == 1 ? (" " + ACTUAL_PREFIX + actualInfos.get(0) + ".") : "";
      String commonTypeMessage = "Incompatible types.\n Required one of: " + allTypes.stream().map(t -> t.getDescription()).sorted().collect(Collectors.joining(", ")) + "." + actualInfo;
      return new fit.jetbrains.jsonSchema.impl.JsonValidationError(commonTypeMessage, fit.jetbrains.jsonSchema.impl.JsonValidationError.FixableIssueKind.TypeMismatch,
                                     new JsonValidationError.TypeMismatchIssueData(ContainerUtil.toArray(allTypes, JsonSchemaType[]::new)),
                                     errors.iterator().next().getPriority());
    }

    return null;
  }

  private static String fetchActual(String message) {
    int actual = message.indexOf(ACTUAL_PREFIX);
    if (actual == -1) return null;
    String substring = message.substring(actual + ACTUAL_PREFIX.length());
    return StringUtil.trimEnd(substring, ".");
  }

  public boolean isCorrect() {
    return myErrors.isEmpty();
  }
}
