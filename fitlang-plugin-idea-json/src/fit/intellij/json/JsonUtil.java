// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package fit.intellij.json;

import com.intellij.openapi.fileTypes.FileType;
import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.util.ObjectUtils;
import fit.intellij.json.psi.JsonFile;
import fit.intellij.json.psi.JsonValue;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * @author Mikhail Golubev
 */
public class JsonUtil {
  private JsonUtil() {
    // empty
  }

  /**
   * Clone of C# "as" operator.
   * Checks if expression has correct type and casts it if it has. Returns null otherwise.
   * It saves coder from "instanceof / cast" chains.
   *
   * Copied from PyCharm's {@code PyUtil}.
   *
   * @param expression expression to check
   * @param cls        class to cast
   * @param <T>        class to cast
   * @return expression casted to appropriate type (if could be casted). Null otherwise.
   */
  @Nullable
  @SuppressWarnings("unchecked")
  public static <T> T as(@Nullable final Object expression, @NotNull final Class<T> cls) {
    if (expression == null) {
      return null;
    }
    if (cls.isAssignableFrom(expression.getClass())) {
      return (T)expression;
    }
    return null;
  }

  @Nullable
  public static <T extends fit.intellij.json.psi.JsonElement> T getPropertyValueOfType(@NotNull final fit.intellij.json.psi.JsonObject object, @NotNull final String name,
                                                                                       @NotNull final Class<T> clazz) {
    final fit.intellij.json.psi.JsonProperty property = object.findProperty(name);
    if (property == null) return null;
    return ObjectUtils.tryCast(property.getValue(), clazz);
  }

  public static boolean isArrayElement(@NotNull PsiElement element) {
    return element instanceof fit.intellij.json.psi.JsonValue && element.getParent() instanceof fit.intellij.json.psi.JsonArray;
  }

  public static int getArrayIndexOfItem(@NotNull PsiElement e) {
    PsiElement parent = e.getParent();
    if (!(parent instanceof fit.intellij.json.psi.JsonArray)) return -1;
    List<JsonValue> elements = ((fit.intellij.json.psi.JsonArray)parent).getValueList();
    for (int i = 0; i < elements.size(); i++) {
      if (e == elements.get(i)) {
        return i;
      }
    }
    return -1;
  }

  @Contract(value = "null -> null")
  @Nullable
  public static fit.intellij.json.psi.JsonObject getTopLevelObject(@Nullable JsonFile jsonFile) {
    return jsonFile != null ? ObjectUtils.tryCast(jsonFile.getTopLevelValue(), fit.intellij.json.psi.JsonObject.class) : null;
  }

  public static boolean isJsonFile(@NotNull VirtualFile file) {
    FileType type = file.getFileType();
    return type instanceof LanguageFileType && ((LanguageFileType)type).getLanguage() instanceof JsonLanguage;
  }
}
