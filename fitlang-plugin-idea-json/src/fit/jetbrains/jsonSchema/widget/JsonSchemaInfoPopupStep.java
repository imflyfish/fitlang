// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package fit.jetbrains.jsonSchema.widget;

import com.intellij.icons.AllIcons;
import fit.intellij.json.JsonBundle;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.ListPopupStepEx;
import com.intellij.openapi.ui.popup.ListSeparator;
import com.intellij.openapi.ui.popup.PopupStep;
import com.intellij.openapi.ui.popup.util.BaseListPopupStep;
import com.intellij.openapi.util.NlsContexts.PopupTitle;
import com.intellij.openapi.util.text.HtmlBuilder;
import com.intellij.openapi.util.text.HtmlChunk;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VfsUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.util.ui.EmptyIcon;
import com.intellij.util.ui.StatusText;
import fit.jetbrains.jsonSchema.ide.JsonSchemaService;
import fit.jetbrains.jsonSchema.JsonSchemaMappingsProjectConfiguration;
import fit.jetbrains.jsonSchema.UserDefinedJsonSchemaConfiguration;
import fit.jetbrains.jsonSchema.extension.JsonSchemaInfo;
import fit.jetbrains.jsonSchema.settings.mappings.JsonSchemaMappingsConfigurable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static com.intellij.openapi.util.NlsContexts.Tooltip;
import static fit.jetbrains.jsonSchema.widget.JsonSchemaStatusPopup.*;

public class JsonSchemaInfoPopupStep extends BaseListPopupStep<fit.jetbrains.jsonSchema.extension.JsonSchemaInfo> implements ListPopupStepEx<fit.jetbrains.jsonSchema.extension.JsonSchemaInfo> {
  private final Project myProject;
  @Nullable private final VirtualFile myVirtualFile;
  @NotNull private final JsonSchemaService myService;
  private static final Icon EMPTY_ICON = JBUIScale.scaleIcon(EmptyIcon.create(AllIcons.General.Add.getIconWidth()));

  public JsonSchemaInfoPopupStep(@NotNull List<fit.jetbrains.jsonSchema.extension.JsonSchemaInfo> allSchemas, @NotNull Project project, @Nullable VirtualFile virtualFile,
                                 @NotNull JsonSchemaService service, @Nullable @PopupTitle String title) {
    super(title, allSchemas);
    myProject = project;
    myVirtualFile = virtualFile;
    myService = service;
  }

  @NotNull
  @Override
  public String getTextFor(fit.jetbrains.jsonSchema.extension.JsonSchemaInfo value) {
    return value == null ? "" : value.getDescription();
  }

  @Override
  public Icon getIconFor(fit.jetbrains.jsonSchema.extension.JsonSchemaInfo value) {
    if (value == ADD_MAPPING) {
      return AllIcons.General.Add;
    }

    if (value == EDIT_MAPPINGS) {
      return AllIcons.Actions.Edit;
    }

    if (value == LOAD_REMOTE) {
      return AllIcons.Actions.Refresh;
    }

    if (value == IGNORE_FILE) {
      return AllIcons.Vcs.Ignore_file;
    }

    if (value == STOP_IGNORE_FILE) {
      return AllIcons.Actions.AddFile;
    }
    return EMPTY_ICON;
  }

  @Nullable
  @Override
  public ListSeparator getSeparatorAbove(fit.jetbrains.jsonSchema.extension.JsonSchemaInfo value) {
    List<fit.jetbrains.jsonSchema.extension.JsonSchemaInfo> values = getValues();
    int index = values.indexOf(value);
    if (index - 1 >= 0) {
      fit.jetbrains.jsonSchema.extension.JsonSchemaInfo info = values.get(index - 1);
      if (info == EDIT_MAPPINGS || info == ADD_MAPPING) {
        return new ListSeparator(JsonBundle.message("schema.widget.registered.schemas"));
      }
      if (value.getProvider() == null && info.getProvider() != null) {
        return new ListSeparator(JsonBundle.message("schema.widget.store.schemas"));
      }
    }
    return null;
  }

  @Override
  public PopupStep onChosen(fit.jetbrains.jsonSchema.extension.JsonSchemaInfo selectedValue, boolean finalChoice) {
    if (finalChoice) {
      if (selectedValue == EDIT_MAPPINGS || selectedValue == ADD_MAPPING) {
        return doFinalStep(() -> runSchemaEditorForCurrentFile());
      }
      else if (selectedValue == LOAD_REMOTE) {
        return doFinalStep(() -> myService.triggerUpdateRemote());
      }
      else if (selectedValue == IGNORE_FILE) {
        markIgnored(myVirtualFile, myProject);
        return doFinalStep(() -> myService.reset());
      }
      else if (selectedValue == STOP_IGNORE_FILE) {
        unmarkIgnored(myVirtualFile, myProject);
        return doFinalStep(() -> myService.reset());
      }
      else {
        setMapping(selectedValue, myVirtualFile, myProject);
        return doFinalStep(() -> myService.reset());
      }
    }
    return PopupStep.FINAL_CHOICE;
  }

  protected void runSchemaEditorForCurrentFile() {
    assert myVirtualFile != null: "override this method to do without a virtual file!";
    ShowSettingsUtil.getInstance().showSettingsDialog(myProject, JsonSchemaMappingsConfigurable.class, (configurable) -> {
      // For some reason, JsonSchemaMappingsConfigurable.reset is called right after this callback, leading to resetting the customization.
      // Workaround: move this logic inside JsonSchemaMappingsConfigurable.reset.
      configurable.setInitializer(() -> {
        fit.jetbrains.jsonSchema.JsonSchemaMappingsProjectConfiguration mappings = fit.jetbrains.jsonSchema.JsonSchemaMappingsProjectConfiguration.getInstance(myProject);
        fit.jetbrains.jsonSchema.UserDefinedJsonSchemaConfiguration configuration = mappings.findMappingForFile(myVirtualFile);
        if (configuration == null) {
          configuration = configurable.addProjectSchema();
          String relativePath = VfsUtilCore.getRelativePath(myVirtualFile, myProject.getBaseDir());
          configuration.patterns.add(new fit.jetbrains.jsonSchema.UserDefinedJsonSchemaConfiguration.Item(
            relativePath == null ? myVirtualFile.getUrl() : relativePath, false, false));
        }
        configurable.selectInTree(configuration);
      });
    });
  }

  @Override
  public boolean isSpeedSearchEnabled() {
    return true;
  }

  @Nullable
  @Override
  public @Tooltip String getTooltipTextFor(fit.jetbrains.jsonSchema.extension.JsonSchemaInfo value) {
    return getDoc(value);
  }

  @Nullable
  private static @Tooltip String getDoc(fit.jetbrains.jsonSchema.extension.JsonSchemaInfo schema) {
    if (schema == null) return null;
    if (schema.getName() == null) return schema.getDocumentation();
    if (schema.getDocumentation() == null) return schema.getName();
    return new HtmlBuilder()
      .append(HtmlChunk.tag("b").addText(schema.getName()))
      .append(HtmlChunk.br())
      .appendRaw(schema.getDocumentation()).toString();
  }

  @Override
  public void setEmptyText(@NotNull StatusText emptyText) {
  }

  private static void markIgnored(@Nullable VirtualFile virtualFile, @NotNull Project project) {
    fit.jetbrains.jsonSchema.JsonSchemaMappingsProjectConfiguration configuration = fit.jetbrains.jsonSchema.JsonSchemaMappingsProjectConfiguration.getInstance(project);
    configuration.markAsIgnored(virtualFile);
  }

  private static void unmarkIgnored(@Nullable VirtualFile virtualFile, @NotNull Project project) {
    fit.jetbrains.jsonSchema.JsonSchemaMappingsProjectConfiguration configuration = fit.jetbrains.jsonSchema.JsonSchemaMappingsProjectConfiguration.getInstance(project);
    if (!configuration.isIgnoredFile(virtualFile)) return;
    configuration.unmarkAsIgnored(virtualFile);
  }
  protected void setMapping(@Nullable JsonSchemaInfo selectedValue, @Nullable VirtualFile virtualFile, @NotNull Project project) {
    assert virtualFile != null: "override this method to do without a virtual file!";
    fit.jetbrains.jsonSchema.JsonSchemaMappingsProjectConfiguration configuration = JsonSchemaMappingsProjectConfiguration.getInstance(project);

    VirtualFile projectBaseDir = project.getBaseDir();

    fit.jetbrains.jsonSchema.UserDefinedJsonSchemaConfiguration mappingForFile = configuration.findMappingForFile(virtualFile);
    if (mappingForFile != null) {
      for (fit.jetbrains.jsonSchema.UserDefinedJsonSchemaConfiguration.Item pattern : mappingForFile.patterns) {
        if (Objects.equals(VfsUtil.findRelativeFile(projectBaseDir, pattern.getPathParts()), virtualFile)
              || virtualFile.getUrl().equals(fit.jetbrains.jsonSchema.UserDefinedJsonSchemaConfiguration.Item.neutralizePath(pattern.getPath()))) {
          mappingForFile.patterns.remove(pattern);
          if (mappingForFile.patterns.size() == 0 && mappingForFile.isApplicationDefined()) {
            configuration.removeConfiguration(mappingForFile);
          }
          else {
            mappingForFile.refreshPatterns();
          }
          break;
        }
      }
    }

    if (selectedValue == null) return;

    String path = projectBaseDir == null ? null : VfsUtilCore.getRelativePath(virtualFile, projectBaseDir);
    if (path == null) {
      path = virtualFile.getUrl();
    }

    fit.jetbrains.jsonSchema.UserDefinedJsonSchemaConfiguration existing = configuration.findMappingBySchemaInfo(selectedValue);
    fit.jetbrains.jsonSchema.UserDefinedJsonSchemaConfiguration.Item item = new fit.jetbrains.jsonSchema.UserDefinedJsonSchemaConfiguration.Item(path, false, false);
    if (existing != null) {
      if (!existing.patterns.contains(item)) {
        existing.patterns.add(item);
        existing.refreshPatterns();
      }
    }
    else {
      configuration.addConfiguration(new UserDefinedJsonSchemaConfiguration(selectedValue.getDescription(),
                                                                            selectedValue.getSchemaVersion(),
                                                                            selectedValue.getUrl(project),
                                                                            true,
                                                                            Collections.singletonList(item)));
    }
  }
}
