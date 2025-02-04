// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package fit.intellij.jsonpath.psi;

import com.intellij.psi.tree.IElementType;
import fit.intellij.jsonpath.JsonPathLanguage;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

public final class JsonPathElementType extends IElementType {
  JsonPathElementType(@NotNull @NonNls String debugName) {
    super(debugName, JsonPathLanguage.INSTANCE);
  }
}
