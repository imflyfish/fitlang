package fit.lang.plugin.json.flow;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import fit.lang.common.util.PrintExecuteNode;
import fit.lang.plugin.json.ExecuteJsonNodeUtil;
import fit.lang.plugin.json.define.JsonExecuteContext;
import fit.lang.plugin.json.define.JsonExecuteNodeInput;
import fit.lang.plugin.json.define.JsonExecuteNodeOutput;
import junit.framework.TestCase;
import org.junit.Assert;

public class JsonForeachExecuteNodeTest extends TestCase {

    public void testExecute1() {
        String flow = "{" +//
                "   'uni': 'foreach'," +
                "   'foreachField': 'list'," +
                "   'mixToItemField': 'times'," +
                "   'child': {" +
                "       'uni':'mix'," +
                "       'json':{" +
                "           'message':'mix'" +
                "       }" +
                "   }" +
                "}";

        String output = ExecuteJsonNodeUtil.executeCode("{'list':['a'],'times':0}", flow);

        System.out.println(output);

        Assert.assertEquals("{\"list\":[{\"data\":\"a\",\"times\":0,\"message\":\"mix\"}]}", output);
    }

}