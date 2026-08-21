package com.wang.springboottemplate;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import okhttp3.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

public class HistoryArticleMain {

    private static final String DEEPSEEK_API_KEY = System.getenv("DEEPSEEK_API_KEY");
    private static final String FEISHU_WEBHOOK = System.getenv("FEISHU_WEBHOOK");
    private static final String GIST_ID = System.getenv("GIST_ID");
    private static final String GITHUB_PAT = System.getenv("GITHUB_PAT");

    private static final String DEEPSEEK_URL = "https://api.deepseek.com/v1/chat/completions";
    private static final String DEEPSEEK_MODEL = "deepseek-v4-flash";
    private static final int MAX_OUTPUT_TOKENS = 2200;
    private static final int MAX_HISTORY_TOPIC_SIZE = 200;
    private static final String GIST_FILENAME = "history_topics.json";
    private static final String OUTPUT_DIR = "output";

    private static final int TARGET_CONTENT_MIN = 1400;
    private static final int TARGET_CONTENT_MAX = 1800;

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    public static void main(String[] args) {
        try {
            checkEnv();
            initLocalDir();
            System.out.println("=====历史稿件生成任务启动=====");

            JSONArray usedTopics = safeReadGistTopicList();
            System.out.printf("历史选题加载：%d 条%n", usedTopics.size());

            String selectTopic = generateTopic(usedTopics);
            System.out.println("生成选题：" + selectTopic);

            JSONObject articleJson = generateArticle(selectTopic);
            String title = articleJson.getString("title");
            String content = articleJson.getString("content");
            JSONArray tags = articleJson.getJSONArray("tags");
            System.out.println("爆款标题：" + title);
            System.out.println("正文长度：" + content.length());

            saveMarkdownFile(title, content, tags);
            appendTopicToGist(usedTopics, selectTopic);

            try {
                sendFeishuMessage(title, content, tags);
                System.out.println("✅飞书推送成功");
            } catch (Exception e) {
                System.err.println("⚠️飞书推送异常：" + e.getMessage());
            }
            System.out.println("=====任务执行完成=====");
        } catch (Exception e) {
            System.err.println("❌任务失败：" + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void checkEnv() {
        if (isBlank(DEEPSEEK_API_KEY)) throw new RuntimeException("缺少环境变量 DEEPSEEK_API_KEY");
        if (isBlank(FEISHU_WEBHOOK)) throw new RuntimeException("缺少环境变量 FEISHU_WEBHOOK");
        if (isBlank(GIST_ID)) throw new RuntimeException("缺少环境变量 GIST_ID");
        if (isBlank(GITHUB_PAT)) throw new RuntimeException("缺少环境变量 GITHUB_PAT");
    }

    private static void initLocalDir() throws IOException {
        Files.createDirectories(Paths.get(OUTPUT_DIR));
    }

    private static JSONArray safeReadGistTopicList() {
        int maxRetry = 2;
        for (int r = 0; r < maxRetry; r++) {
            try {
                Request req = new Request.Builder()
                        .url("https://api.github.com/gists/" + GIST_ID)
                        .header("Authorization", "token " + GITHUB_PAT)
                        .get()
                        .build();
                try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
                    if (!resp.isSuccessful()) {
                        System.err.println("Gist读取失败 code:" + resp.code());
                        continue;
                    }
                    JSONObject json = JSON.parseObject(resp.body().string());
                    JSONObject fileObj = json.getJSONObject("files").getJSONObject(GIST_FILENAME);
                    String content = fileObj.getString("content");
                    return isBlank(content) ? new JSONArray() : JSON.parseArray(content);
                }
            } catch (Exception ex) {
                System.err.printf("Gist读取重试 %d 异常:%s%n", r+1, ex.getMessage());
                sleepMs(1200);
            }
        }
        return new JSONArray();
    }

    private static void appendTopicToGist(JSONArray list, String newTopic) {
        list.add(newTopic);
        while (list.size() > MAX_HISTORY_TOPIC_SIZE) list.remove(0);
        int maxRetry = 2;
        for (int r = 0; r < maxRetry; r++) {
            try {
                JSONObject gistBody = new JSONObject();
                JSONObject filesNode = new JSONObject();
                JSONObject fileItem = new JSONObject();
                fileItem.put("content", JSON.toJSONString(list, true));
                filesNode.put(GIST_FILENAME, fileItem);
                gistBody.put("files", filesNode);

                RequestBody body = RequestBody.create(gistBody.toString(), MediaType.parse("application/json;charset=utf-8"));
                Request req = new Request.Builder()
                        .url("https://api.github.com/gists/" + GIST_ID)
                        .header("Authorization", "token " + GITHUB_PAT)
                        .method("PATCH", body)
                        .build();
                try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
                    if(resp.isSuccessful()){
                        System.out.println("✅Gist更新成功，历史数量：" + list.size());
                        return;
                    }
                    System.err.println("Gist写入失败 code:"+resp.code());
                }
            } catch (Exception ex) {
                System.err.printf("Gist写入重试 %d 异常:%s%n", r+1, ex.getMessage());
                sleepMs(1200);
            }
        }
        System.err.println("⚠️Gist持久化失败，选题未保存");
    }

    /**清洗模型输出，剔除markdown代码块标记*/
    private static String cleanJsonRaw(String raw){
        String s = raw.trim();
        s = s.replaceAll("^```json", "");
        s = s.replaceAll("^```", "");
        s = s.replaceAll("```$", "");
        return s.trim();
    }

    private static String generateTopic(JSONArray historyTopics) throws IOException {
        String sysPromptTopic = """
                    你是今日头条历史自媒体选题专家，严格遵循Skill筛选规则。
                    【选题Skill规范】
                    1.优先产出：有反差、争议、反转、冷门真相、人性视角、结局复盘类思辨选题；
                    2.淘汰：纯时间线、流水账、教科书复述；
                    3.禁止：敏感近现代人物、恶意洗白抹黑、野史猎奇无考证、借古讽今；
                    4.输出1个事件思辨类选题，风格示例：朱棣为何执意迁都北京，安史之乱爆发的根本原因，诸葛亮北伐失败真实因素。
                    5.只输出JSON，禁止任何多余文字、解释、markdown。输出格式：{"topic":"选题文本"}
                    """;
        String userPrompt = """
                    生成1个历史思辨类选题，聚焦事件背后原因、决策逻辑、成败深层矛盾。
                    禁止使用已用过选题，语义高度相似也禁止输出：
                    %s
                    """.formatted(historyTopics.toJSONString());

        JSONObject respJson = callDeepSeekApi(sysPromptTopic, userPrompt);
        String raw = cleanJsonRaw(respJson.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"));
        JSONObject obj = JSON.parseObject(raw);
        return obj.getString("topic").trim();
    }

    private static JSONObject generateArticle(String selectTopic) throws IOException {
        String articleSysPrompt = """
                你是今日头条资深深度历史自媒体博主，对标平台成熟历史大号头条号文章写作风格，严格执行全套Skill。
                #【写作Skill总规范】
                ##1.稿件模板（历史事件深度思辨稿）
                悬念提问钩子开篇 →事件起因背景 →多重关键转折拆解 →多角度核心真相（制度约束、朝堂博弈、人物抉择、时代环境，拒绝单一归因）→事件造成的连锁历史影响 →结合现实视角的思考与启示。
                开篇必须抛出矛盾冲突，不要平铺直叙背景介绍。
                
                ##2.行文硬性要求
                1）口语通俗，自媒体叙事感，拒绝教科书式书面腔；人物事件辩证客观，拒绝非黑即白标签化；杜绝流水账。
                2）每一个分论点，补充对应时代细节、当事人处境、史料背景支撑；可以简要引用正史记载，野史必须标注。
                3）段落短小，适合手机长文阅读；适当反问，带动读者思考。
                4）结尾必须设置开放式互动提问，引导评论，输出有价值的思考感悟。
                5）价值观底线：不许洗白抹黑历史人物，不用现代标准苛责古人，拒绝阴谋论，不借古讽今。
                
                ##输出JSON全部字段，缺一不可：
                {
                "title":"标题",
                "content":"正文，换行使用\\n",
                "tags":["#历史","#古代史","#历史解析","#历史人物"]
                }
                
                硬性约束：
                1. 只返回完整JSON，禁止前置后置文字、markdown代码块、注释。
                2. 禁止使用空洞套话、重复语句来硬凑字符；篇幅依靠史实细节、多方视角扩充。
                3. title、content、tags字段全部必须存在。
                """;

        String articleUserPrompt = """
                        选题：%s
                        
                        写作硬性指标：
                        1、正文字符 %d‑%d，严禁使用废话、重复套话凑字数，依靠史实细节、多方立场视角丰富全文；不要仓促收尾。
                        2、严格执行系统给到的深度思辨稿全套Skill与模板。
                        3、多角度分析，同时写出事件里不同人物的处境与取舍，不要单一视角；每一部分要有史实支撑。
                        4、结尾带上开放式互动提问，引导读者评论。
                        """.formatted(selectTopic, TARGET_CONTENT_MIN, TARGET_CONTENT_MAX);

        JSONObject respJson = callDeepSeekApi(articleSysPrompt, articleUserPrompt);
        String rawResp = cleanJsonRaw(respJson.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"));
        return JSON.parseObject(rawResp);
    }

    private static JSONObject callDeepSeekApi(String systemContent, String userContent) throws IOException {
        int retryTimes = 2;
        Exception lastEx = null;
        for (int i = 0; i < retryTimes; i++) {
            try {
                JSONObject reqBody = new JSONObject();
                reqBody.put("model", DEEPSEEK_MODEL);
                reqBody.put("max_tokens", MAX_OUTPUT_TOKENS);
                reqBody.put("temperature", 0.7);
                JSONArray messages = new JSONArray();
                messages.add(JSONObject.of("role", "system", "content", systemContent));
                messages.add(JSONObject.of("role", "user", "content", userContent));
                reqBody.put("messages", messages);

                RequestBody body = RequestBody.create(reqBody.toString(), MediaType.parse("application/json; charset=utf-8"));
                Request request = new Request.Builder()
                        .url(DEEPSEEK_URL)
                        .header("Authorization", "Bearer " + DEEPSEEK_API_KEY)
                        .post(body)
                        .build();

                try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                    String respBody = response.body().string();
                    if (!response.isSuccessful()) {
                        throw new IOException("DeepSeek http code:"+response.code()+" body:"+respBody);
                    }
                    return JSON.parseObject(respBody);
                }
            } catch (Exception e) {
                lastEx = e;
                System.err.printf("DeepSeek调用失败，重试 %d/%d :%s%n",i+1,retryTimes,e.getMessage());
                sleepMs(2000);
            }
        }
        throw new IOException("DeepSeek接口多次重试失败", lastEx);
    }

    private static void saveMarkdownFile(String title, String content, JSONArray tags) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append("\n\n");
        sb.append(content).append("\n\n");
        sb.append(tags.toJSONString());
        Files.write(Paths.get(OUTPUT_DIR,"article.md"), sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("✅md文件已保存 output/article.md");
    }

    private static void sendFeishuMessage(String title, String article, JSONArray tags) throws IOException {
        int maxFeishuLen = 2200;
        String displayText = article.length()>maxFeishuLen
                ? article.substring(0,maxFeishuLen)+"\n\n> ⚠️内容过长，完整文章下载Action产物article.md"
                : article;

        JSONObject card = new JSONObject();
        card.put("msg_type","interactive");
        JSONObject cardBody = new JSONObject();
        cardBody.put("config", JSONObject.of("wide_screen_mode",true));
        JSONArray elements = new JSONArray();
        String tagStr = String.join(" ", tags.toList().stream().map(Object::toString).toList());
        elements.add(JSONObject.of("tag","div","text",
                JSONObject.of("tag","lark_md","content","**📜今日历史爆款标题："+title+"**\n"+tagStr+"\n\n"+displayText)));
        cardBody.put("elements",elements);
        card.put("card",cardBody);

        RequestBody body = RequestBody.create(card.toString(), MediaType.parse("application/json;charset=utf-8"));
        Request req = new Request.Builder().url(FEISHU_WEBHOOK).post(body).build();
        try(Response resp = HTTP_CLIENT.newCall(req).execute()){
            if(!resp.isSuccessful()) System.err.println("飞书调用异常 code:"+resp.code());
        }
    }

    private static boolean isBlank(String s){return s==null||s.isBlank();}
    private static void sleepMs(long ms){try{TimeUnit.MILLISECONDS.sleep(ms);}catch(InterruptedException ignored){}}
}
