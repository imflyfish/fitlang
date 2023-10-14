package fit.lang.plugin.json.util;

import com.alibaba.fastjson2.JSONArray;
import fit.lang.plugin.json.ExpressUtil;
import fit.lang.plugin.json.define.JsonExecuteNode;
import fit.lang.plugin.json.define.JsonExecuteNodeInput;
import fit.lang.plugin.json.define.JsonExecuteNodeOutput;

import static fit.lang.plugin.json.ExecuteJsonNodeUtil.getConfigFields;

/**
 * 执行节点
 */
public class SetJsonExecuteNode extends JsonExecuteNode {

    @Override
    public void execute(JsonExecuteNodeInput input, JsonExecuteNodeOutput output) {
        output.setData(input.getData());

        String key = nodeJsonDefine.getString("key");
        Object value = nodeJsonDefine.get("value");

        Object newValue = ExpressUtil.eval(value, input.getInputParamAndContextParam());
        input.getNodeContext().setAttribute(key, newValue);
        output.getData().putIfAbsent(key, newValue);

        JSONArray fields = getConfigFields(nodeJsonDefine, "copyField");

        for (Object field : fields) {
            input.getNodeContext().setAttribute(field.toString(), input.get(field.toString()));
            output.getData().putIfAbsent(field.toString(), input.get(field.toString()));
        }
    }
}
