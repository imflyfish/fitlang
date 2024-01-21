// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package fit.intellij.json.formatter;

import com.intellij.formatting.*;
import com.intellij.psi.codeStyle.CodeStyleSettings;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;
import fit.intellij.json.JsonElementTypes;
import fit.intellij.json.JsonLanguage;
import org.jetbrains.annotations.NotNull;

public class JsonFormattingBuilderModel implements FormattingModelBuilder {
    @Override
    public @NotNull FormattingModel createModel(@NotNull FormattingContext formattingContext) {
        CodeStyleSettings settings = formattingContext.getCodeStyleSettings();
        fit.intellij.json.formatter.JsonCodeStyleSettings customSettings = settings.getCustomSettings(fit.intellij.json.formatter.JsonCodeStyleSettings.class);
        SpacingBuilder spacingBuilder = createSpacingBuilder(settings);
        final fit.intellij.json.formatter.JsonBlock block =
                new JsonBlock(null, formattingContext.getNode(), customSettings, null, Indent.getSmartIndent(Indent.Type.CONTINUATION), null,
                        spacingBuilder);
        return FormattingModelProvider.createFormattingModelForPsiFile(formattingContext.getContainingFile(), block, settings);
    }

    @NotNull
    static SpacingBuilder createSpacingBuilder(CodeStyleSettings settings) {
        final fit.intellij.json.formatter.JsonCodeStyleSettings jsonSettings = settings.getCustomSettings(JsonCodeStyleSettings.class);
        final CommonCodeStyleSettings commonSettings = settings.getCommonSettings(JsonLanguage.INSTANCE);

        final int spacesBeforeComma = commonSettings.SPACE_BEFORE_COMMA ? 1 : 0;
        final int spacesBeforeColon = jsonSettings.SPACE_BEFORE_COLON ? 1 : 0;
        final int spacesAfterColon = jsonSettings.SPACE_AFTER_COLON ? 1 : 0;

        return new SpacingBuilder(settings, JsonLanguage.INSTANCE)
                .before(JsonElementTypes.COLON).spacing(spacesBeforeColon, spacesBeforeColon, 0, false, 0)
                .after(JsonElementTypes.COLON).spacing(spacesAfterColon, spacesAfterColon, 0, false, 0)
                .withinPair(JsonElementTypes.L_BRACKET, JsonElementTypes.R_BRACKET).spaceIf(commonSettings.SPACE_WITHIN_BRACKETS, true)
                .withinPair(JsonElementTypes.L_CURLY, JsonElementTypes.R_CURLY).spaceIf(commonSettings.SPACE_WITHIN_BRACES, true)
                .before(JsonElementTypes.COMMA).spacing(spacesBeforeComma, spacesBeforeComma, 0, false, 0)
                .after(JsonElementTypes.COMMA).spaceIf(commonSettings.SPACE_AFTER_COMMA);
    }
}
