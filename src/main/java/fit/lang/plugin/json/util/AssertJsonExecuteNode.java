package fit.lang.plugin.json.util;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import fit.lang.ExecuteNodeException;
import fit.lang.plugin.json.ExpressUtil;
import fit.lang.plugin.json.define.JsonExecuteNode;
import fit.lang.plugin.json.define.JsonExecuteNodeInput;
import fit.lang.plugin.json.define.JsonExecuteNodeOutput;

/**
 * 执行节点
 */
public class AssertJsonExecuteNode extends JsonExecuteNode {

    @Override
    public void execute(JsonExecuteNodeInput input, JsonExecuteNodeOutput output) {

        Boolean needToString = nodeJsonDefine.getBoolean("needToString");

        boolean success = true;
        Object assertResultObject = null;

        if (nodeJsonDefine.get("expected") != null) {
            Object expectedExpress = nodeJsonDefine.get("expected");
            assertResultObject = ExpressUtil.eval(expectedExpress, input.getInputParamAndContextParam());

            if (Boolean.TRUE.equals(needToString) && assertResultObject != null) {
                success = input.getData().toJSONString().equals(assertResultObject.toString());
            } else {
                success = input.getData().equals(assertResultObject);
            }
        }
        if (success && nodeJsonDefine.get("containField") != null) {
            JSONArray containField = nodeJsonDefine.getJSONArray("containField");
            assertResultObject = "contain fields: ".concat(ExpressUtil.eval(containField, input.getInputParamAndContextParam()).toString());

            success = true;
            for (Object field : containField) {
                if (!input.containsKey(field.toString())) {
                    success = false;
                    break;
                }
            }
        }
        if (success && nodeJsonDefine.get("containJson") != null) {
            JSONObject containJson = nodeJsonDefine.getJSONObject("containJson");
            assertResultObject = ExpressUtil.eval(containJson, input.getInputParamAndContextParam()).toJSONString();
            success = true;
            for (String field : containJson.keySet()) {
                if (!containJson.get(field).equals(input.get(field))) {
                    success = false;
                    break;
                }
            }
        }

        JSONObject result = new JSONObject();
        result.put("success", success);
        if (!success) {
            if (Boolean.TRUE.equals(needToString)) {
                result.put("actual", input.getData().toString());
            } else {
                result.put("actual", input.getData());
            }
            result.put("expected", assertResultObject);
            result.put("input", input.getData());
        }
        output.setData(result);
    }
}
