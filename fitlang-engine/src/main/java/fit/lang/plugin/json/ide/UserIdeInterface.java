package fit.lang.plugin.json.ide;

import com.alibaba.fastjson2.JSONObject;
import com.intellij.openapi.project.Project;

import java.io.File;
import java.util.List;

/**
 * 用户IDE接口
 */
public interface UserIdeInterface {
    /**
     * 读取当前编辑器内容
     *
     * @return
     */
    String readEditorContent();

    /**
     * 写入当前内容
     *
     * @param content
     */
    void writeEditorContent(String content);

    int showCheckboxOkCancelDialog(String title, String message, String checkboxText);

    void showErrorDialog(String title, String message);

    String showInputDialog(String title, String message);

    int showOkCancelDialog(String title, String message, String okText, String cancelText);

    void openWebPage(String url, JSONObject option, JSONObject context);

    void showNodeConfig(JSONObject config, Project project);

    JSONObject getNodeConfig();

    void showInfoMessage(String title, String message);

    String showPasswordDialog(String title, String message);

    List<File> chooseFiles(JSONObject config);
}
