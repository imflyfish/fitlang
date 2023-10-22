// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package fit.jetbrains.jsonSchema.impl;


import com.intellij.notification.NotificationGroup;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.util.AstLoadingFilter;
import com.intellij.util.PairConsumer;
import com.intellij.util.containers.ContainerUtil;
import fit.jetbrains.jsonSchema.extension.JsonLikePsiWalker;
import fit.jetbrains.jsonSchema.extension.adapters.JsonArrayValueAdapter;
import fit.jetbrains.jsonSchema.extension.adapters.JsonObjectValueAdapter;
import fit.jetbrains.jsonSchema.extension.adapters.JsonPropertyAdapter;
import fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * @author Irina.Chernushina on 1/13/2017.
 */
public class JsonSchemaReader {
  private static final int MAX_SCHEMA_LENGTH = FileUtilRt.MEGABYTE;
  public static final Logger LOG = Logger.getInstance(JsonSchemaReader.class);
  public static final NotificationGroup ERRORS_NOTIFICATION = NotificationGroup.logOnlyGroup("JSON Schema");

  private final Map<String, JsonSchemaObject> myIds = new HashMap<>();
  private final ArrayDeque<Pair<JsonSchemaObject, fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter>> myQueue;

  private static final Map<String, MyReader> READERS_MAP = new HashMap<>();
  static {
    fillMap();
  }

  @Nullable private final VirtualFile myFile;

  public JsonSchemaReader(@Nullable VirtualFile file) {
    myFile = file;
    myQueue = new ArrayDeque<>();
  }

  @NotNull
  public static JsonSchemaObject readFromFile(@NotNull Project project, @NotNull VirtualFile file) throws Exception {
    if (!file.isValid()) {
      throw new Exception(String.format("Can not load JSON Schema file '%s'", file.getName()));
    }

    final PsiFile psiFile = PsiManager.getInstance(project).findFile(file);
    JsonSchemaObject object = psiFile == null ? null : new JsonSchemaReader(file).read(psiFile);
    if (object == null) {
      throw new Exception(String.format("Can not load code model for JSON Schema file '%s'", file.getName()));
    }
    return object;
  }

  @Nullable
  public static String checkIfValidJsonSchema(@NotNull final Project project, @NotNull final VirtualFile file) {
    final long length = file.getLength();
    final String fileName = file.getName();
    if (length > MAX_SCHEMA_LENGTH) {
      return String.format("JSON schema was not loaded from '%s' because it's too large (file size is %d bytes).", fileName, length);
    }
    if (length == 0) {
      return String.format("JSON schema was not loaded from '%s'. File is empty.", fileName);
    }
    try {
      readFromFile(project, file);
    } catch (Exception e) {
      final String message = String.format("JSON Schema not found or contain error in '%s': %s", fileName, e.getMessage());
      LOG.info(message);
      return message;
    }
    return null;
  }

  private static JsonSchemaObject enqueue(@NotNull Collection<Pair<JsonSchemaObject, fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter>> queue,
                                          @NotNull JsonSchemaObject schemaObject,
                                          @NotNull fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter container) {
    queue.add(Pair.create(schemaObject, container));
    return schemaObject;
  }

  @Nullable
  public JsonSchemaObject read(@NotNull PsiFile file) {
    fit.jetbrains.jsonSchema.extension.JsonLikePsiWalker walker = fit.jetbrains.jsonSchema.extension.JsonLikePsiWalker.getWalker(file, JsonSchemaObject.NULL_OBJ);
    if (walker == null) return null;
    PsiElement root = AstLoadingFilter.forceAllowTreeLoading(file, () -> ContainerUtil.getFirstItem(walker.getRoots(file)));
    return root == null ? null : read(root, walker);
  }

  @Nullable
  private JsonSchemaObject read(@NotNull final PsiElement object, @NotNull JsonLikePsiWalker walker) {
    final JsonSchemaObject root = new JsonSchemaObject(myFile, "/");
    fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter rootAdapter = walker.createValueAdapter(object);
    if (rootAdapter == null) return null;
    enqueue(myQueue, root, rootAdapter);
    while (!myQueue.isEmpty()) {
      final Pair<JsonSchemaObject, fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter> currentItem = myQueue.removeFirst();

      JsonSchemaObject currentSchema = currentItem.first;
      String pointer = currentSchema.getPointer();
      fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter adapter = currentItem.second;

      if (adapter instanceof fit.jetbrains.jsonSchema.extension.adapters.JsonObjectValueAdapter) {
        final List<fit.jetbrains.jsonSchema.extension.adapters.JsonPropertyAdapter> list = ((fit.jetbrains.jsonSchema.extension.adapters.JsonObjectValueAdapter)adapter).getPropertyList();
        for (fit.jetbrains.jsonSchema.extension.adapters.JsonPropertyAdapter property : list) {
          Collection<fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter> values = property.getValues();
          if (values.size() != 1) continue;
          String name = property.getName();
          if (name == null) continue;
          final MyReader reader = READERS_MAP.get(name);
          fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter value = values.iterator().next();
          if (reader != null) {
            reader.read(value, currentSchema, myQueue, myFile);
          }
          else {
            readSingleDefinition(name, value, currentSchema, pointer);
          }
        }
      }
      else if (adapter instanceof fit.jetbrains.jsonSchema.extension.adapters.JsonArrayValueAdapter) {
        List<fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter> values = ((fit.jetbrains.jsonSchema.extension.adapters.JsonArrayValueAdapter)adapter).getElements();
        for (int i = 0; i < values.size(); i++) {
          readSingleDefinition(String.valueOf(i), values.get(i), currentSchema, pointer);
        }
      }

      if (currentSchema.getId() != null) myIds.put(currentSchema.getId(), currentSchema);
      currentSchema.completeInitialization(adapter);
    }
    return root;
  }

  public Map<String, JsonSchemaObject> getIds() {
    return myIds;
  }

  private void readSingleDefinition(@NotNull String name,
                                    @NotNull fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter value,
                                    @NotNull JsonSchemaObject schema,
                                    String pointer) {
    String nextPointer = getNewPointer(name, pointer);
    final JsonSchemaObject defined = enqueue(myQueue, new JsonSchemaObject(myFile, nextPointer), value);
    Map<String, JsonSchemaObject> definitions = schema.getDefinitionsMap();
    if (definitions == null) schema.setDefinitionsMap(definitions = new HashMap<>());
    definitions.put(name, defined);
  }

  @NotNull
  private static String getNewPointer(@NotNull String name, String oldPointer) {
    return oldPointer.equals("/") ? oldPointer + name : oldPointer + "/" + name;
  }

  private static void fillMap() {
    READERS_MAP.put("$id", createFromStringValue((object, s) -> object.setId(s)));
    READERS_MAP.put("id", createFromStringValue((object, s) -> object.setId(s)));
    READERS_MAP.put("$schema", createFromStringValue((object, s) -> object.setSchema(s)));
    READERS_MAP.put("description", createFromStringValue((object, s) -> object.setDescription(s)));
    // non-standard deprecation property used by VSCode
    READERS_MAP.put("deprecationMessage", createFromStringValue((object, s) -> object.setDeprecationMessage(s)));
    READERS_MAP.put(JsonSchemaObject.X_INTELLIJ_HTML_DESCRIPTION, createFromStringValue((object, s) -> object.setHtmlDescription(s)));
    READERS_MAP.put(JsonSchemaObject.X_INTELLIJ_LANGUAGE_INJECTION, createFromStringValue((object, s) -> object.setLanguageInjection(s)));
    READERS_MAP.put(JsonSchemaObject.X_INTELLIJ_CASE_INSENSITIVE, (element, object, queue, virtualFile) -> {
      if (element.isBooleanLiteral()) object.setForceCaseInsensitive(getBoolean(element));
    });
    READERS_MAP.put("title", createFromStringValue((object, s) -> object.setTitle(s)));
    READERS_MAP.put("$ref", createFromStringValue((object, s) -> object.setRef(s)));
    READERS_MAP.put("default", createDefault());
    READERS_MAP.put("format", createFromStringValue((object, s) -> object.setFormat(s)));
    READERS_MAP.put(JsonSchemaObject.DEFINITIONS, createDefinitionsConsumer());
    READERS_MAP.put(JsonSchemaObject.PROPERTIES, createPropertiesConsumer());
    READERS_MAP.put("multipleOf", createFromNumber((object, i) -> object.setMultipleOf(i)));
    READERS_MAP.put("maximum", createFromNumber((object, i) -> object.setMaximum(i)));
    READERS_MAP.put("minimum", createFromNumber((object, i) -> object.setMinimum(i)));
    READERS_MAP.put("exclusiveMaximum", (element, object, queue, virtualFile) -> {
      if (element.isBooleanLiteral()) object.setExclusiveMaximum(getBoolean(element));
      else if (element.isNumberLiteral()) object.setExclusiveMaximumNumber(getNumber(element));
    });
    READERS_MAP.put("exclusiveMinimum", (element, object, queue, virtualFile) -> {
      if (element.isBooleanLiteral()) object.setExclusiveMinimum(getBoolean(element));
      else if (element.isNumberLiteral()) object.setExclusiveMinimumNumber(getNumber(element));
    });
    READERS_MAP.put("maxLength", createFromInteger((object, i) -> object.setMaxLength(i)));
    READERS_MAP.put("minLength", createFromInteger((object, i) -> object.setMinLength(i)));
    READERS_MAP.put("pattern", createFromStringValue((object, s) -> object.setPattern(s)));
    READERS_MAP.put(JsonSchemaObject.ADDITIONAL_ITEMS, createAdditionalItems());
    READERS_MAP.put(JsonSchemaObject.ITEMS, createItems());
    READERS_MAP.put("contains", createContains());
    READERS_MAP.put("maxItems", createFromInteger((object, i) -> object.setMaxItems(i)));
    READERS_MAP.put("minItems", createFromInteger((object, i) -> object.setMinItems(i)));
    READERS_MAP.put("uniqueItems", (element, object, queue, virtualFile) -> {
      if (element.isBooleanLiteral()) object.setUniqueItems(getBoolean(element));
    });
    READERS_MAP.put("maxProperties", createFromInteger((object, i) -> object.setMaxProperties(i)));
    READERS_MAP.put("minProperties", createFromInteger((object, i) -> object.setMinProperties(i)));
    READERS_MAP.put("required", createRequired());
    READERS_MAP.put("additionalProperties", createAdditionalProperties());
    READERS_MAP.put("propertyNames", createFromObject("propertyNames", (object, schema) -> object.setPropertyNamesSchema(schema)));
    READERS_MAP.put("patternProperties", createPatternProperties());
    READERS_MAP.put("dependencies", createDependencies());
    READERS_MAP.put("enum", createEnum());
    READERS_MAP.put("const", (element, object, queue, virtualFile) -> object.setEnum(ContainerUtil.createMaybeSingletonList(readEnumValue(element))));
    READERS_MAP.put("type", createType());
    READERS_MAP.put("allOf", createContainer((object, members) -> object.setAllOf(members)));
    READERS_MAP.put("anyOf", createContainer((object, members) -> object.setAnyOf(members)));
    READERS_MAP.put("oneOf", createContainer((object, members) -> object.setOneOf(members)));
    READERS_MAP.put("not", createFromObject("not", (object, schema1) -> object.setNot(schema1)));
    READERS_MAP.put("if", createFromObject("if", (object, schema) -> object.setIf(schema)));
    READERS_MAP.put("then", createFromObject("then", (object, schema) -> object.setThen(schema)));
    READERS_MAP.put("else", createFromObject("else", (object, schema) -> object.setElse(schema)));
    READERS_MAP.put("instanceof", ((element, object, queue, virtualFile) -> object.setShouldValidateAgainstJSType()));
    READERS_MAP.put("typeof", ((element, object, queue, virtualFile) -> object.setShouldValidateAgainstJSType()));
  }

  private static MyReader createFromStringValue(PairConsumer<JsonSchemaObject, String> propertySetter) {
    return (element, object, queue, virtualFile) -> {
      if (element.isStringLiteral()) {
        propertySetter.consume(object, StringUtil.unquoteString(element.getDelegate().getText()));
      }
    };
  }

  private static MyReader createFromInteger(PairConsumer<JsonSchemaObject, Integer> propertySetter) {
    return (element, object, queue, virtualFile) -> {
      if (element.isNumberLiteral()) {
        propertySetter.consume(object, (int)getNumber(element));
      }
    };
  }

  private static MyReader createFromNumber(PairConsumer<JsonSchemaObject, Number> propertySetter) {
    return (element, object, queue, virtualFile) -> {
      if (element.isNumberLiteral()) {
        propertySetter.consume(object, getNumber(element));
      }
    };
  }

  private static MyReader createFromObject(String prop, PairConsumer<JsonSchemaObject, JsonSchemaObject> propertySetter) {
    return (element, object, queue, virtualFile) -> {
      if (element.isObject()) {
        propertySetter.consume(object, enqueue(queue, new JsonSchemaObject(virtualFile, getNewPointer(prop, object.getPointer())), element));
      }
    };
  }

  private static MyReader createContainer(@NotNull final PairConsumer<JsonSchemaObject, List<JsonSchemaObject>> delegate) {
    return (element, object, queue, virtualFile) -> {
      if (element instanceof fit.jetbrains.jsonSchema.extension.adapters.JsonArrayValueAdapter) {
        final List<fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter> list = ((fit.jetbrains.jsonSchema.extension.adapters.JsonArrayValueAdapter)element).getElements();
        final List<JsonSchemaObject> members = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
          fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter value = list.get(i);
          if (!(value.isObject())) continue;
          members.add(enqueue(queue, new JsonSchemaObject(virtualFile, getNewPointer(String.valueOf(i), object.getPointer())), value));
        }
        delegate.consume(object, members);
      }
    };
  }

  private static MyReader createType() {
    return (element, object, queue, virtualFile) -> {
      if (element.isStringLiteral()) {
        final fit.jetbrains.jsonSchema.impl.JsonSchemaType type = parseType(StringUtil.unquoteString(element.getDelegate().getText()));
        if (type != null) object.setType(type);
      } else if (element instanceof fit.jetbrains.jsonSchema.extension.adapters.JsonArrayValueAdapter) {
        final Set<fit.jetbrains.jsonSchema.impl.JsonSchemaType> typeList = ((fit.jetbrains.jsonSchema.extension.adapters.JsonArrayValueAdapter)element).getElements().stream()
          .filter(notEmptyString()).map(el -> parseType(StringUtil.unquoteString(el.getDelegate().getText())))
          .filter(el -> el != null).collect(Collectors.toSet());
        if (!typeList.isEmpty()) object.setTypeVariants(typeList);
      }
    };
  }

  @Nullable
  private static fit.jetbrains.jsonSchema.impl.JsonSchemaType parseType(@NotNull final String typeString) {
    try {
      return JsonSchemaType.valueOf("_" + typeString);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  @Nullable
  private static Object readEnumValue(fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter value) {
    if (value.isStringLiteral()) {
      return "\"" + StringUtil.unquoteString(value.getDelegate().getText()) + "\"";
    } else if (value.isNumberLiteral()) {
      return getNumber(value);
    } else if (value.isBooleanLiteral()) {
      return getBoolean(value);
    } else if (value.isNull()) {
      return "null";
    } else if (value instanceof fit.jetbrains.jsonSchema.extension.adapters.JsonArrayValueAdapter) {
      return new EnumArrayValueWrapper(((fit.jetbrains.jsonSchema.extension.adapters.JsonArrayValueAdapter)value).getElements().stream().map(v -> readEnumValue(v)).filter(v -> v != null).toArray());
    } else if (value instanceof fit.jetbrains.jsonSchema.extension.adapters.JsonObjectValueAdapter) {
      return new EnumObjectValueWrapper(((fit.jetbrains.jsonSchema.extension.adapters.JsonObjectValueAdapter)value).getPropertyList().stream()
        .filter(p -> p.getValues().size() == 1)
        .map(p -> Pair.create(p.getName(), readEnumValue(p.getValues().iterator().next())))
        .filter(p -> p.second != null)
        .collect(Collectors.toMap(p -> p.first, p -> p.second)));
    }
    return null;
  }

  private static MyReader createEnum() {
    return (element, object, queue, virtualFile) -> {
      if (element instanceof fit.jetbrains.jsonSchema.extension.adapters.JsonArrayValueAdapter) {
        final List<Object> objects = new ArrayList<>();
        final List<fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter> list = ((fit.jetbrains.jsonSchema.extension.adapters.JsonArrayValueAdapter)element).getElements();
        for (fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter value : list) {
          Object enumValue = readEnumValue(value);
          if (enumValue == null) return; // don't validate if we have unsupported entity kinds
          objects.add(enumValue);
        }
        object.setEnum(objects);
      }
    };
  }

  private static boolean getBoolean(@NotNull fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter value) {
    return Boolean.parseBoolean(value.getDelegate().getText());
  }

  @NotNull
  private static Number getNumber(@NotNull fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter value) {
    Number numberValue;
    try {
      numberValue = Integer.parseInt(value.getDelegate().getText());
    } catch (NumberFormatException e) {
      try {
        numberValue = Double.parseDouble(value.getDelegate().getText());
      }
      catch (NumberFormatException e2) {
        return -1;
      }
    }
    return numberValue;
  }

  private static MyReader createDependencies() {
    return (element, object, queue, virtualFile) -> {
      if (element instanceof fit.jetbrains.jsonSchema.extension.adapters.JsonObjectValueAdapter) {
        final HashMap<String, List<String>> propertyDependencies = new HashMap<>();
        final HashMap<String, JsonSchemaObject> schemaDependencies = new HashMap<>();

        final List<fit.jetbrains.jsonSchema.extension.adapters.JsonPropertyAdapter> list = ((fit.jetbrains.jsonSchema.extension.adapters.JsonObjectValueAdapter)element).getPropertyList();
        for (fit.jetbrains.jsonSchema.extension.adapters.JsonPropertyAdapter property : list) {
          Collection<fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter> values = property.getValues();
          if (values.size() != 1) continue;
          fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter value = values.iterator().next();
          if (value == null) continue;
          if (value instanceof fit.jetbrains.jsonSchema.extension.adapters.JsonArrayValueAdapter) {
            final List<String> dependencies = ((fit.jetbrains.jsonSchema.extension.adapters.JsonArrayValueAdapter)value).getElements().stream()
              .filter(notEmptyString())
              .map(el -> StringUtil.unquoteString(el.getDelegate().getText())).collect(Collectors.toList());
            if (!dependencies.isEmpty()) propertyDependencies.put(property.getName(), dependencies);
          } else if (value.isObject()) {
            String newPointer = getNewPointer("dependencies/" + property.getName(), object.getPointer());
            schemaDependencies.put(property.getName(), enqueue(queue, new JsonSchemaObject(virtualFile, newPointer), value));
          }
        }

        object.setPropertyDependencies(propertyDependencies);
        object.setSchemaDependencies(schemaDependencies);
      }
    };
  }

  @NotNull
  private static Predicate<fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter> notEmptyString() {
    return el -> el.isStringLiteral() && !StringUtil.isEmptyOrSpaces(el.getDelegate().getText());
  }

  private static MyReader createPatternProperties() {
    return (element, object, queue, virtualFile) -> {
      if (element.isObject()) {
        object.setPatternProperties(readInnerObject(getNewPointer("patternProperties", object.getPointer()), element, queue, virtualFile));
      }
    };
  }

  private static MyReader createAdditionalProperties() {
    return (element, object, queue, virtualFile) -> {
      if (element.isBooleanLiteral()) {
        object.setAdditionalPropertiesAllowed(getBoolean(element));
      } else if (element.isObject()) {
        object.setAdditionalPropertiesSchema(enqueue(queue, new JsonSchemaObject(virtualFile,
                                                                                 getNewPointer("additionalProperties", object.getPointer())),
                                                     element));
      }
    };
  }

  private static MyReader createRequired() {
    return (element, object, queue, virtualFile) -> {
      if (element instanceof fit.jetbrains.jsonSchema.extension.adapters.JsonArrayValueAdapter) {
        object.setRequired(new LinkedHashSet<>(((fit.jetbrains.jsonSchema.extension.adapters.JsonArrayValueAdapter)element).getElements().stream()
                                                 .filter(notEmptyString())
                                                 .map(el -> StringUtil.unquoteString(el.getDelegate().getText()))
                                                 .collect(Collectors.toList())));
      }
    };
  }

  private static MyReader createItems() {
    return (element, object, queue, virtualFile) -> {
      if (element.isObject()) {
        object.setItemsSchema(enqueue(queue, new JsonSchemaObject(virtualFile, getNewPointer("items", object.getPointer())), element));
      } else if (element instanceof fit.jetbrains.jsonSchema.extension.adapters.JsonArrayValueAdapter) {
        final List<JsonSchemaObject> list = new ArrayList<>();
        final List<fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter> values = ((JsonArrayValueAdapter)element).getElements();
        for (int i = 0; i < values.size(); i++) {
          fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter value = values.get(i);
          if (value.isObject()) {
            list.add(enqueue(queue, new JsonSchemaObject(virtualFile, getNewPointer("items/"+i, object.getPointer())), value));
          }
        }
        object.setItemsSchemaList(list);
      }
    };
  }

  private static MyReader createDefinitionsConsumer() {
    return (element, object, queue, virtualFile) -> {
      if (element.isObject()) {
        object.setDefinitionsMap(readInnerObject(getNewPointer("definitions", object.getPointer()), element, queue, virtualFile));
      }
    };
  }

  private static MyReader createContains() {
    return (element, object, queue, virtualFile) -> {
      if (element.isObject()) {
        object.setContainsSchema(enqueue(queue, new JsonSchemaObject(virtualFile, getNewPointer("contains", object.getPointer())), element));
      }
    };
  }

  private static MyReader createAdditionalItems() {
    return (element, object, queue, virtualFile) -> {
      if (element.isBooleanLiteral()) {
        object.setAdditionalItemsAllowed(getBoolean(element));
      } else if (element.isObject()) {
        object.setAdditionalItemsSchema(enqueue(queue, new JsonSchemaObject(virtualFile,
                                                                            getNewPointer("additionalItems", object.getPointer())), element));
      }
    };
  }

  private static MyReader createPropertiesConsumer() {
    return (element, object, queue, virtualFile) -> {
      if (element.isObject()) {
        object.setProperties(readInnerObject(getNewPointer("properties", object.getPointer()), element, queue, virtualFile));
      }
    };
  }

  @NotNull
  private static Map<String, JsonSchemaObject> readInnerObject(String parentPointer, @NotNull fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter element,
                                                               @NotNull Collection<Pair<JsonSchemaObject, fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter>> queue,
                                                               VirtualFile virtualFile) {
    final Map<String, JsonSchemaObject> map = new HashMap<>();
    if (!(element instanceof fit.jetbrains.jsonSchema.extension.adapters.JsonObjectValueAdapter)) return map;
    final List<fit.jetbrains.jsonSchema.extension.adapters.JsonPropertyAdapter> properties = ((JsonObjectValueAdapter)element).getPropertyList();
    for (JsonPropertyAdapter property : properties) {
      Collection<fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter> values = property.getValues();
      if (values.size() != 1) continue;
      fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter value = values.iterator().next();
      String propertyName = property.getName();
      if (propertyName == null) continue;
      if (value.isBooleanLiteral()) {
        // schema v7: `propName: true` is equivalent to `propName: {}`
        map.put(propertyName, new JsonSchemaObject(virtualFile, getNewPointer(propertyName, parentPointer)));
        continue;
      }
      if (!value.isObject()) continue;
      map.put(propertyName, enqueue(queue, new JsonSchemaObject(virtualFile, getNewPointer(propertyName, parentPointer)), value));
    }
    return map;
  }

  private static MyReader createDefault() {
    return (element, object, queue, virtualFile) -> {
      if (element.isObject()) {
        object.setDefault(enqueue(queue, new JsonSchemaObject(virtualFile, getNewPointer("default", object.getPointer())), element));
      } else if (element.isStringLiteral()) {
        object.setDefault(StringUtil.unquoteString(element.getDelegate().getText()));
      } else if (element.isNumberLiteral()) {
        object.setDefault(getNumber(element));
      } else if (element.isBooleanLiteral()) {
        object.setDefault(getBoolean(element));
      }
    };
  }

  private interface MyReader {
    void read(@NotNull fit.jetbrains.jsonSchema.extension.adapters.JsonValueAdapter source,
              @NotNull JsonSchemaObject target,
              @NotNull Collection<Pair<JsonSchemaObject, JsonValueAdapter>> processingQueue,
              @Nullable VirtualFile file);
  }
}
