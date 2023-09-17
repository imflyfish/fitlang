package fit.lang.plugin.json.monitor;

import cn.hutool.system.oshi.OshiUtil;
import com.alibaba.fastjson2.JSONObject;
import fit.lang.plugin.json.define.JsonExecuteNode;
import fit.lang.plugin.json.define.JsonExecuteNodeInput;
import fit.lang.plugin.json.define.JsonExecuteNodeOutput;
import oshi.hardware.CentralProcessor;

import static fit.lang.plugin.json.ExecuteJsonNodeUtil.covertToG;

/**
 * 获取监控信息
 */
public class GetMonitorDataJsonExecuteNode extends JsonExecuteNode {

    @Override
    public void execute(JsonExecuteNodeInput input, JsonExecuteNodeOutput output) {

        int second = parseIntField("second", input, 500);

        JSONObject result = new JSONObject();
        result.put("cpuPoints", StartMonitorJsonExecuteNode.getGatherList(second));
        result.put("memoryPoints", StartMonitorJsonExecuteNode.getMemoryGatherList(second));
        CentralProcessor centralProcessor = OshiUtil.getHardware().getProcessor();
        result.put("cpuTotal", centralProcessor.getPhysicalProcessorCount() + " X " + covertToG(centralProcessor.getMaxFreq()) + "G");
        result.put("second", second);


        output.setData(result);
    }

}
