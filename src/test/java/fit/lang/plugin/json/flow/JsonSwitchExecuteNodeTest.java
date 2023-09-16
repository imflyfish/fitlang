package fit.lang.plugin.json.flow;

import com.alibaba.fastjson2.JSONObject;
import fit.lang.plugin.json.ExecuteJsonNodeUtil;
import junit.framework.TestCase;
import org.junit.Assert;

public class JsonSwitchExecuteNodeTest extends TestCase {

    public void testExecuteCase1() {

        testExecute("1", "message", "hello, world!");

    }

    public void testExecuteCase2() {

        testExecute("2", "who", "world");

    }

    public void testExecute(String type, String outputFieldName, String expect) {
        String flow = "{" +//
                "   'uni': 'switch'," +
                "   'switchField': 'type'," +
                "   'child': [" +
                "       {" +
                "           'case':'1'," +
                "           'uni':'hello'" +
                "       }," +
                "       {" +
                "           'case':'2'," +
                "           'uni':'echo'" +
                "       }" +
                "   ]" +
                "}";

        String output = ExecuteJsonNodeUtil.executeCode("{'type':'" + type + "'}", flow);

        System.out.println(output);

        JSONObject result = JSONObject.parse(output);
        Assert.assertEquals(expect, result.get(outputFieldName));
    }

}