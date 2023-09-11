package fit.server;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.CharsetUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import fit.lang.plugin.json.ExecuteJsonNodeUtil;
import fit.lang.plugin.json.tool.ServerJsonExecuteNode;

import java.io.File;

import static fit.lang.plugin.json.ExecuteJsonNodeUtil.isJsonText;
import static fit.lang.plugin.json.ExecuteJsonNodeUtil.readNodeDefineFile;

public class FitServerMain {

    public static void main(String[] args) {

        System.out.println("FitLang-0.4.13");
        String serverFilePath = "server.fit";
        String httpPrefix = "http://127.0.0.1";
        int port = 11111;

        if (args != null) {
            if (args.length > 0) {
                serverFilePath = args[0];
            }
            if (args.length > 1) {
                httpPrefix = args[1];
            }
        }
        File serverFile = new File(serverFilePath);

        if (!serverFile.exists()) {
            System.out.println("server file not existed: " + serverFile.getAbsoluteFile());
            return;
        }

        String code = readNodeDefineFile(serverFile);

        System.out.println("start server from " + serverFile.getAbsoluteFile());

        ServerJsonExecuteNode.setCurrentServerFilePath(serverFile.getAbsolutePath());
        ServerJsonExecuteNode.setHttpPrefix(httpPrefix);

        String result = ExecuteJsonNodeUtil.executeCode(code);
        System.out.println(result);

        System.out.println("start OK!");

        JSONObject resultJson;
        if (isJsonText(result)) {
            resultJson = JSON.parseObject(result);
            port = resultJson.getInteger("port");
            httpPrefix = resultJson.getString("httpPrefix");
        }

        System.out.println(httpPrefix + ":" + port);

    }
}
