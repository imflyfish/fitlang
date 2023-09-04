package fit.lang.plugin.json.tool;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.map.multi.ListValueMap;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.ContentType;
import cn.hutool.http.HttpUtil;
import cn.hutool.http.server.HttpServerRequest;
import cn.hutool.http.server.HttpServerResponse;
import cn.hutool.http.server.SimpleServer;
import cn.hutool.http.server.action.Action;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONWriter;
import fit.lang.plugin.json.ExecuteJsonNodeUtil;
import fit.lang.plugin.json.define.JsonExecuteContext;
import fit.lang.plugin.json.define.JsonExecuteNode;
import fit.lang.plugin.json.define.JsonExecuteNodeInput;
import fit.lang.plugin.json.define.JsonExecuteNodeOutput;
import fit.lang.plugin.json.tool.server.FitServerInstance;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static fit.lang.plugin.json.ExecuteJsonNodeUtil.isJsonText;

/**
 * 执行节点
 */
public class ServerJsonExecuteNode extends JsonExecuteNode {

    public static final String FIELD_NAEM_OF_HTTP_REQUEST = "httpRequest";
    /**
     * server启动文件的路径
     */
    public static String currentServerFilePath;

    /**
     * 默认服务器端口
     */
    public static final int DEFAULT_SERVER_PORT = 11111;
    public static final String REQUEST_PATH = "requestPath";
    public static final String SERVICE_PATH = "servicePath";

    static Map<Integer, FitServerInstance> serverMap = new HashMap<>();

    public static void setCurrentServerFilePath(String currentServerFilePath) {
        ServerJsonExecuteNode.currentServerFilePath = currentServerFilePath;
    }

    public static String getCurrentServerFilePath() {
        return currentServerFilePath;
    }

    public static String getServerFileDir() {
        if (currentServerFilePath == null) {
            return null;
        }
        int index = currentServerFilePath.lastIndexOf("/");
        if (index < 0) {
            index = currentServerFilePath.lastIndexOf("\\");
        }
        if (index < 0) {
            return null;
        }
        return convertPath(currentServerFilePath.substring(0, index));
    }

    @Override
    public void execute(JsonExecuteNodeInput input, JsonExecuteNodeOutput output) {

        Integer port = buildServerPort(input.getData(), nodeJsonDefine, DEFAULT_SERVER_PORT);
        FitServerInstance fitServer = serverMap.get(port);
        JSONObject result;
        if (fitServer == null) {
            fitServer = createFitServerInstance(port);
            result = load(fitServer);
            fitServer.getSimpleServer().start();
        } else {
            result = reload(fitServer, false);
        }

        output.setData(result);
    }

    private void setFileServer(FitServerInstance fitServer) {
        String rootPath = nodeJsonDefine.getString("root");

        if (rootPath == null) {
            rootPath = ServerJsonExecuteNode.getServerFileDir();
        }
        fitServer.getSimpleServer().setRoot(rootPath);
    }

    public JSONObject load(FitServerInstance fitServer) {
        return reload(fitServer, false);
    }

    public JSONObject reload(FitServerInstance fitServer, boolean isFirst) {

        JSONArray serviceList = fitServer.getServiceList();
        String serverFile = fitServer.getServerFile();
        String serviceDir = fitServer.getServiceDir();

        if (serverFile == null) {
            serverFile = getCurrentServerFilePath();
        } else if (!isFirst) {
            //支持重新加载文件，不支持修改端口 TODO
            nodeJsonDefine = JSONObject.parseObject(FileUtil.readUtf8String(serverFile));
        }
        fitServer.setServerFile(serverFile);

        serviceList.clear();

        JSONObject result = new JSONObject();

        setFileServer(fitServer);

        JSONObject stopDefine = addStopService(fitServer);
        serviceList.add(stopDefine);

        JSONObject registerDefine = addRegisterCloudService(fitServer);
        serviceList.add(registerDefine);

        JSONObject reloadDefine = addReloadService(fitServer);
        serviceList.add(reloadDefine);

        JSONObject serviceConfig = nodeJsonDefine.getJSONObject("service");
        if (serviceConfig != null && !serviceConfig.isEmpty()) {
            loadServiceNode(nodeJsonDefine.getJSONObject("service"), fitServer);
        }

        serviceList.add(serviceConfig);

        if (serviceDir == null) {
            serviceDir = getServerFileDir();
        }

        if (serviceDir == null) {
            serviceDir = nodeJsonDefine.getString("serviceDir");
        }

        if (serviceDir != null) {
            try {
                List<JSONObject> serviceListInServerNode = loadServiceDir(serviceDir, new File(serviceDir), fitServer.getSimpleServer());
                serviceList.addAll(serviceListInServerNode);
            } catch (Exception e) {
                System.out.println("loadServiceDir error: " + e);
            }
        }

        addRootService(fitServer);

        fitServer.setUrl(buildUrl(fitServer.getPort(), ""));

        result.put("message", "start server at port: " + fitServer.getPort());

        return result;
    }

    private String getHttpPrefix() {
        String httpPrefix = nodeJsonDefine.getString("httpPrefix");
        if (StrUtil.isBlank(httpPrefix)) {
            httpPrefix = "http://127.0.0.1";
        }
        return httpPrefix;
    }

    public static Integer buildServerPort(JSONObject input, JSONObject nodeDefine, int defaultPort) {
        Integer inputPort = input.getInteger("port");
        Integer serverPort = inputPort;
        if (serverPort == null) {
            serverPort = nodeDefine.getInteger("port");
        }
        if (serverPort == null) {
            serverPort = defaultPort;
        }
        return serverPort;
    }

    private void addRootService(FitServerInstance fitServerInstance) {

        clearContext(fitServerInstance.getSimpleServer(), "/_menu");
        fitServerInstance.getSimpleServer().addAction("/_menu", new Action() {
            @Override
            public void doAction(HttpServerRequest request, HttpServerResponse response) {
                JSONObject welcome = getWelcomeJson(fitServerInstance);
                response.write(welcome.toJSONString(JSONWriter.Feature.PrettyFormat), ContentType.JSON.getValue());
            }
        });
    }

    private JSONObject getWelcomeJson(FitServerInstance fitServerInstance) {
        String welcomeMessage = "hello, fit server!";
        if (nodeJsonDefine.getString("welcome") != null) {
            welcomeMessage = nodeJsonDefine.getString("welcome");
        }
        JSONObject welcome = new JSONObject();
        welcome.put("message", welcomeMessage);
        welcome.put("server", getServerDisplay(serverMap));
        welcome.put("service", getServicesDisplay(fitServerInstance));
        return welcome;
    }

    JSONArray getServerDisplay(Map<Integer, FitServerInstance> serverMap) {
        JSONArray displayList = new JSONArray();
        for (Map.Entry<Integer, FitServerInstance> serverEntry : serverMap.entrySet()) {
            FitServerInstance instance = serverEntry.getValue();
            displayList.add(instance.getDisplayInfo());
        }
        return displayList;
    }

    JSONArray getServicesDisplay(FitServerInstance fitServerInstance) {
        JSONArray display = new JSONArray();
        for (Object define : fitServerInstance.getServiceList()) {
            if (define == null) {
                continue;
            }
            JSONObject defineJson = (JSONObject) define;
            JSONObject serviceDisplay = new JSONObject();
            String servicePath = defineJson.getString("path");
            if (servicePath == null) {
                continue;
            }
            serviceDisplay.put("path", servicePath);
            serviceDisplay.put("url", buildUrl(fitServerInstance.getPort(), servicePath));
            serviceDisplay.put("loadType", defineJson.getString("loadType"));
            serviceDisplay.put("description", defineJson.get("description"));
            display.add(serviceDisplay);
        }
        return display;
    }

    private void loadServiceNode(JSONObject service, FitServerInstance fitServer) {
        if (service != null) {
            for (Map.Entry<String, Object> entry : service.entrySet()) {
                String servicePath = entry.getKey();
                JSONObject serviceFlow = (JSONObject) entry.getValue();
                if (serviceFlow == null || serviceFlow.isEmpty()) {
                    continue;
                }
                JSONObject serviceDefine = buildStandardServiceDefine(serviceFlow);
                serviceDefine.put("path", servicePath);
                serviceDefine.put("loadType", "serverNode");
                registerService(fitServer.getSimpleServer(), servicePath, serviceDefine);
                fitServer.getServiceList().add(serviceDefine);
            }
        }
    }

    static String convertPath(String path) {
        return path.replace("\\", "/");
    }

    JSONObject buildStandardServiceDefine(JSONObject serviceDefine) {
        JSONObject standardServiceDefine = serviceDefine;
        if (!serviceDefine.containsKey("flow")) {
            standardServiceDefine = new JSONObject();
            standardServiceDefine.put("flow", serviceDefine);
            standardServiceDefine.put("input", new JSONObject());
        }
        return standardServiceDefine;
    }

    private String buildUrl(Integer serverPort, String servicePath) {
        return getHttpPrefix() + ":" + serverPort + servicePath;
    }

    static JSONObject addStopService(FitServerInstance fitServer) {
        String stopPath = "/_stop";
        clearContext(fitServer.getSimpleServer(), stopPath);
        fitServer.getSimpleServer().addAction(stopPath, new Action() {
            @Override
            public void doAction(HttpServerRequest request, HttpServerResponse response) {
                int stopPort = fitServer.getSimpleServer().getAddress().getPort();
                String port = request.getParam("port");
                if (port != null) {
                    if (NumberUtil.isInteger(port)) {
                        stopPort = Integer.parseInt(port);
                    } else {
                        response.write("{\"message\":\"port must be a int number, but found: " + port + "!\"}");
                        return;
                    }
                }

                FitServerInstance server = getFitServerInstance(stopPort);
                if (server == null) {
                    response.write("{\"message\":\"count found server at port: " + port + "!\"}");
                    return;
                }
                response.write("{\"message\":\"stop " + stopPort + " OK!\"}");
                server.getSimpleServer().getRawServer().stop(1);
                serverMap.remove(stopPort);
                fitServer.setRunning(false);
            }
        });
        JSONObject stopDefine = new JSONObject();
        stopDefine.put("path", stopPath);
        stopDefine.put("description", "stop this server");
        return stopDefine;
    }

    /**
     * 注册到云服务中： 使用http的长连接实现
     *
     * @param fitServer
     * @return
     */
    private JSONObject addRegisterCloudService(FitServerInstance fitServer) {
        String reloadPath = "/_register";
        clearContext(fitServer.getSimpleServer(), reloadPath);
        fitServer.getSimpleServer().addAction(reloadPath, new Action() {
            @Override
            public void doAction(HttpServerRequest request, HttpServerResponse response) {
                System.out.println("server come: ".concat(request.getClientIP()));
                InputStream inputStream = request.getBodyStream();
                response.send(200, Long.MAX_VALUE);
                PrintWriter writer = response.getWriter();
                while (fitServer.isRunning()) {

                    System.out.println("send to server begin!");
                    writer.write("{'uni':'hello'}");

                    writer.write("{$?!}");
                    writer.flush();
                    System.out.println("send to server end!");

                    byte[] data = new byte[1024];
                    StringBuilder stringBuilder = new StringBuilder();
                    while (true) {
                        try {
                            int length = inputStream.read(data);
                            System.out.println("read end!");
                            if (length == -1) {
                                break;
                            }
                            stringBuilder.append(new String(data, 0, length));
                            int endIndex = stringBuilder.indexOf("}{$?!}");
                            if (endIndex > -1) {
                                String code = stringBuilder.substring(0, endIndex + 1);

                                System.out.println("result: " + code);

                                stringBuilder = new StringBuilder(stringBuilder.substring(endIndex + 6));
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    System.out.println(stringBuilder);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        });
        JSONObject reloadDefine = new JSONObject();
        reloadDefine.put("path", reloadPath);
        reloadDefine.put("description", "reload this server");
        return reloadDefine;
    }

    private static void clearContext(SimpleServer simpleServer, String stopPath) {
        try {
            simpleServer.getRawServer().removeContext(stopPath);
        } catch (Exception e) {
            //todo ignore
        }
    }

    private JSONObject addReloadService(FitServerInstance fitServer) {
        String reloadPath = "/_reload";
        clearContext(fitServer.getSimpleServer(), reloadPath);
        fitServer.getSimpleServer().addAction(reloadPath, new Action() {
            @Override
            public void doAction(HttpServerRequest request, HttpServerResponse response) {
                reload(fitServer, false);
                JSONObject welcome = getWelcomeJson(fitServer);
                response.write(welcome.toJSONString(JSONWriter.Feature.PrettyFormat), ContentType.JSON.getValue());
            }
        });
        JSONObject reloadDefine = new JSONObject();
        reloadDefine.put("path", reloadPath);
        reloadDefine.put("description", "reload this server");
        return reloadDefine;
    }

    /**
     * 注册service
     *
     * @param simpleServer
     * @param servicePath
     * @param serviceConfig
     */
    private void registerService(SimpleServer simpleServer, String servicePath, JSONObject serviceConfig) {
        if (StrUtil.isBlank(servicePath) || serviceConfig == null || serviceConfig.isEmpty()) {
            return;
        }
        clearContext(simpleServer, servicePath);
        simpleServer.addAction(servicePath, new Action() {
            @Override
            public void doAction(HttpServerRequest request, HttpServerResponse response) {

                JSONObject input = buildInput(request, serviceConfig);

                JSONObject contextParam = new JSONObject();
                contextParam.put(REQUEST_PATH, request.getPath());
                contextParam.put(SERVICE_PATH, servicePath);
                try {
                    JsonExecuteContext jsonExecuteContext = new JsonExecuteContext();
                    String output = ExecuteJsonNodeUtil.executeCode(input, contextParam, jsonExecuteContext);
                    JSONObject serviceFlow = input.getJSONObject("flow");
                    if (isWebNode(serviceFlow)) {
                        JSONObject header = serviceFlow.getJSONObject("header");
                        String contextType = null;
                        if (header != null) {
                            contextType = header.getString("contextType");
                        }
                        if (StrUtil.isNotBlank(contextType)) {
                            response.write(output, contextType);
                        } else {
                            response.write(output);
                        }
                    } else {//默认json类型
                        response.write(output, ContentType.JSON.getValue());
                    }
                } catch (Exception e) {
                    JSONObject result = new JSONObject();
                    result.put("message", "inner error: ".concat(e.getMessage()));
                    response.write(result.toJSONString(), ContentType.JSON.getValue());
                }
            }
        });
    }

    private JSONObject buildInput(HttpServerRequest request, JSONObject serviceConfig) {
        JSONObject input = new JSONObject(0);

        String requestBody = request.getBody();
        if (StrUtil.isBlank(requestBody)) {
            requestBody = "{}";
        }
        JSONObject inputJson = new JSONObject();
        JSONObject serviceFlow = serviceConfig;
        if (serviceConfig.containsKey("input") && serviceConfig.containsKey("flow")) {
            input = serviceConfig;
            inputJson = serviceConfig.getJSONObject("input");
        } else {
            input.put("input", inputJson);
            input.put("flow", serviceFlow);
        }

        if (isJsonText(requestBody)) {
            inputJson.putAll(JSONObject.parseObject(requestBody));
        }

        ListValueMap<String, String> listValueMap = request.getParams();
        for (Map.Entry<String, List<String>> entry : listValueMap.entrySet()) {
            inputJson.put(entry.getKey(), entry.getValue().get(0));
        }
        return input;
    }

    List<JSONObject> loadServiceDir(String serviceRootDir, File serviceFile, SimpleServer simpleServer) {

        List<JSONObject> serviceDefineList = new ArrayList<>();
        if (serviceFile.isDirectory()) {
            File[] subFiles = serviceFile.listFiles();
            for (File subFile : subFiles) {
                List<JSONObject> subDefineList = loadServiceDir(serviceRootDir, subFile, simpleServer);
                serviceDefineList.addAll(subDefineList);
            }
        } else if (serviceFile.getName().endsWith(".fit") || serviceFile.getName().endsWith(".fit.json")) {
            String serviceDefine = FileUtil.readUtf8String(serviceFile);
            JSONObject serviceDefineJson = buildStandardServiceDefine(JSONObject.parseObject(serviceDefine));
            String servicePath = convertPath(serviceFile.getAbsolutePath().substring(serviceRootDir.length()));
            serviceDefineJson.put("path", servicePath);
            serviceDefineJson.put("loadType", "fileSystem");
            registerService(simpleServer, servicePath, serviceDefineJson);
            serviceDefineList.add(serviceDefineJson);
        }
        return serviceDefineList;
    }

    /**
     * 是否web节点，有特殊逻辑处理
     *
     * @param flow
     * @return
     */
    public static boolean isWebNode(JSONObject flow) {
        return "web".equals(flow.getString("uni"));
    }

    public static FitServerInstance createFitServerInstance(int port) {
        FitServerInstance fitServerInstance = new FitServerInstance();
        SimpleServer simpleServer = HttpUtil.createServer(port);
        fitServerInstance.setSimpleServer(simpleServer);
        serverMap.put(port, fitServerInstance);
        fitServerInstance.setRunning(true);
        return fitServerInstance;
    }

    public static FitServerInstance getFitServerInstance(int port) {
        return serverMap.get(port);
    }
}
