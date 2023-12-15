package my.lang.action;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.DefaultActionGroup;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static fit.lang.plugin.json.ExecuteJsonNodeUtil.isJsonObjectText;

public abstract class FitLangPluginActionGroup extends DefaultActionGroup {

    @Override
    public void update(AnActionEvent event) {
        event.getPresentation().setEnabled(true);
    }

    AnAction[] children = new AnAction[0];

    boolean debug = false;

    protected abstract String getGroupName();

    JSONObject getGroupConfig(JSONObject pluginConfig) {
        JSONArray groups = pluginConfig.getJSONArray("groups");
        if (groups == null) {
            return null;
        }
        for (Object group : groups) {
            JSONObject groupObject = (JSONObject) group;
            if (getGroupName().equalsIgnoreCase(groupObject.getString("name"))) {
                if (!(groupObject.get("actions") instanceof JSONArray)) {
                    return null;
                }
                JSONObject groupConfig = new JSONObject();
                groupConfig.put("title", groupObject.getOrDefault("title", getGroupName()));
                groupConfig.put("actions", groupObject.get("actions"));
                return groupConfig;
            }
        }
        return null;
    }

    @Override
    public AnAction[] getChildren(AnActionEvent event) {
        if (children == null || children.length == 0 || debug) {
            String projectPath = event.getProject() == null ? "" : event.getProject().getBasePath();
            if (StrUtil.isBlank(projectPath)) {
                return children;
            }
            JSONObject pluginConfig = loadPluginRegisterConfig(projectPath);
            if (pluginConfig == null || pluginConfig.isEmpty()) {
                return children;
            }
            JSONObject groupConfig = getGroupConfig(pluginConfig);
            if (groupConfig == null) {
                return children;
            }
            JSONArray actions = groupConfig.getJSONArray("actions");
            if (actions == null || actions.isEmpty()) {
                event.getPresentation().setVisible(false);
                return children;
            }
            event.getPresentation().setText(groupConfig.getString("title"));
            event.getPresentation().setVisible(true);
            debug = Boolean.TRUE.equals(pluginConfig.getBoolean("debug"));
            List<AnAction> actionList = new ArrayList<>();
            Set<String> pluginNameSet = new HashSet<>();
            for (Object item : actions) {
                JSONObject plugin = (JSONObject) item;
                String name = plugin.getString("name");
                if (pluginNameSet.contains(name)) {
                    continue;
                }
                pluginNameSet.add(name);
                String title = plugin.getString("title");
                String script = plugin.getString("script");
                actionList.add(new FitLangPluginAction(name, title, script));
            }

            System.out.println("FitLang: loaded plugin: ".concat(pluginNameSet.toString()));

            children = actionList.toArray(new AnAction[0]);
        }
        return children;
    }

    JSONObject loadPluginRegisterConfig(String projectPath) {
        try {

            String pluginPath = projectPath + File.separator + "plugin.fit";
            System.out.println("FitLang: load plugin at ".concat(pluginPath));

            String pluginConfigJson = FileUtil.readUtf8String(pluginPath);

            if (isJsonObjectText(pluginConfigJson)) {
                return JSONObject.parse(pluginConfigJson);
            }
        } catch (Exception e) {
            //ignore
        }
        return null;
    }
}