package fit.lang.plugin.json.http;

import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.URLUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.http.Method;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import fit.lang.ExecuteNodeException;
import fit.lang.plugin.json.ExecuteJsonNodeUtil;
import fit.lang.plugin.json.ExpressUtil;
import fit.lang.plugin.json.define.JsonExecuteNode;
import fit.lang.plugin.json.define.JsonExecuteNodeInput;
import fit.lang.plugin.json.define.JsonExecuteNodeOutput;

import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;

import static fit.lang.plugin.json.ExecuteJsonNodeUtil.*;

/**
 * 执行节点
 */
public class HttpJsonExecuteNode extends JsonExecuteNode {

    @Override
    public void execute(JsonExecuteNodeInput input, JsonExecuteNodeOutput output) {
        String method = nodeJsonDefine.getString("method");
        if (StrUtil.isBlank(method)) {
            method = "POST";
        }
        request(input, output, nodeJsonDefine, Method.valueOf(method));
    }

    public static void request(JsonExecuteNodeInput input, JsonExecuteNodeOutput output, JSONObject nodeJsonDefine, Method method) {

        String url = ExecuteJsonNodeUtil.parseStringField("url", input, nodeJsonDefine);
        if (url == null) {
            throw new ExecuteNodeException("http node url field is required!");
        }

        //add query params
        url = buildUrlByQueryParams(nodeJsonDefine, url);

        HttpRequest request = HttpUtil.createRequest(method, url);

        setHttpHeader(nodeJsonDefine.getJSONObject("header"), request);
        setProxy(nodeJsonDefine.getJSONObject("proxy"), request);

        JSONObject httpParam = parseParam(nodeJsonDefine);

        Boolean isPostForm = nodeJsonDefine.getBoolean("isPostForm");
        if (method == Method.GET || (method == Method.POST && Boolean.TRUE.equals(isPostForm))) {
            parseHttpFormParam(input, request, httpParam);
        } else if (method == Method.POST || method == Method.PUT || method == Method.DELETE) {
            String httpBody;
            if (httpParam != null) {
                Object param = ExpressUtil.eval(httpParam, input.getInputParamAndContextParam());
                if (param != null) {
                    httpBody = param.toString();
                } else {
                    httpBody = "";
                }
            } else {
                httpBody = input.getData().toJSONString();
            }
            request.body(httpBody);
        }

        long timeBegin = System.currentTimeMillis();
        HttpResponse response = request.execute();
        long timeEnd = System.currentTimeMillis();

        JSONObject result = parseHttpResult(response);

        JSONObject out = result;

        if (Boolean.FALSE.equals(nodeJsonDefine.get("onlyBody")) || "postman".equals(nodeJsonDefine.getString("uni"))) {
            out = new JSONObject();
            out.put("cookie", parseCookie(response));
            out.put("header", parseHeader(response));
            out.put("status", response.getStatus());
            out.put("size", response.body().length());
            out.put("time", (timeEnd - timeBegin) + "ms");
            out.put("body", result);
        }
        output.setData(out);
    }

    static JSONArray parseCookie(HttpResponse response) {
        return JSONArray.copyOf(response.getCookies());
    }

    static JSONObject parseHeader(HttpResponse response) {
        Map<String, List<String>> headers = response.headers();
        JSONObject headerJson = new JSONObject();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            headerJson.put(entry.getKey(), entry.getValue().get(0));
        }
        return headerJson;
    }

    private static String buildUrlByQueryParams(JSONObject nodeJsonDefine, String url) {
        JSONObject query = nodeJsonDefine.getJSONObject("query");
        if (query != null && !query.isEmpty()) {
            String queryParams = URLUtil.buildQuery(query, Charset.defaultCharset());
            if (url.contains("?")) {
                url = url.concat("&").concat(queryParams);
            } else {
                url = url.concat("?").concat(queryParams);
            }
        }
        return url;
    }

    private static JSONObject parseParam(JSONObject nodeJsonDefine) {
        JSONObject httpParam = new JSONObject();
        String[] fields = {"param", "body"};
        for (String field : fields) {
            for (int i = 0; i < 10; i++) {
                String key = field + (i > 0 ? i + "" : "");
                Object param = nodeJsonDefine.get(key);
                if (param instanceof JSONObject) {
                    httpParam.putAll((JSONObject) param);
                }
            }
        }
        return httpParam;
    }
}
