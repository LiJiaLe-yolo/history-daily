package com.wang.springboottemplate;

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
                    JSONObject json = JSONObject.parseObject(resp.body().string());
                    JSONObject fileObj = json.getJSONObject("files").getJSONObject(GIST_FILENAME);
                    String content = fileObj.getString("content");
                    return isBlank(content) ? new JSONArray() : JSONArray.parseArray(content);
                }
            } catch (Exception ex) {
                System.err.printf("Gist读取重试 %d 异常:%s%n", r + 1, ex.getMessage());
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
                fileItem.put("content", JSONObject.toJSONString(list, true));
                filesNode.put(GIST_FILENAME, fileItem);
                gistBody.put("files", filesNode);

                RequestBody body = RequestBody.create(gistBody.toString(), MediaType.parse("application/json;charset=utf-8"));
                Request req = new Request.Builder()
                        .url("https://api.github.com/gists/" + GIST_ID)
                        .header("Authorization", "token " + GITHUB_PAT)
                        .method("PATCH", body)
                        .build();
                try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
                    if (resp.isSuccessful()) {
                        System.out.println("✅Gist更新成功，历史数量：" + list.size());
                        return;
                    }
                    System.err.println("Gist写入失败 code:" + resp.code());
                }
            } catch (Exception ex) {
                System.err.printf("Gist写入重试 %d 异常:%s%n", r + 1, ex.getMessage());
                sleepMs(1200);
            }
        }
        System.err.println("⚠️Gist持久化失败，选题未保存");
    }

    private static String cleanJsonRaw(String raw) {
        String s = raw.trim();
        s = s.replaceAll("^```json", "");
        s = s.replaceAll("^```", "");
        s = s.replaceAll("```$", "");
        return s.trim();
    }

    private static String generateTopic(JSONArray historyTopics) throws IOException {
        String sysPromptTopic = """
                    你是今日头条历史自媒体选题专家。
                    1.优先产出反差、争议、反转、人物抉择类思辨选题；
                    2.拒绝流水账、时间线类；
                    3.只输出json{"topic":"xxx"}，不要任何其他文字。
                    """;
        String userPrompt = "生成1条全新历史思辨选题，避开以下已使用选题：\n" + historyTopics.toJSONString();
        JSONObject respJson = callDeepSeekApi(sysPromptTopic, userPrompt);
        String raw = cleanJsonRaw(respJson.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"));
        JSONObject obj = JSONObject.parseObject(raw);
        return obj.getString("topic").trim();
    }

    private static JSONObject generateArticle(String selectTopic) throws IOException {
        String sysPromptArticle = """
                你是今日头条历史自媒体撰稿人。
                写作结构：悬念开头 → 时代背景 → 多方人物处境分析 → 事件转折 → 历史影响 → 结尾感悟+开放式提问。
                语言口语化适合手机阅读，段落简短。引用正史，拒绝阴谋论。
                返回严格JSON格式：{"title":"","content":"换行用\\n","tags":["#历史","#古代史","#历史解读","#人物"]}
                """;
        String userPrompt = "选题：" + selectTopic + "，正文控制1400-1800字符，结尾必须带互动提问。";
        JSONObject respJson = callDeepSeekApi(sysPromptArticle, userPrompt);
        String rawResp = cleanJsonRaw(respJson.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"));
        return JSONObject.parseObject(rawResp);
    }

    private static JSONObject callDeepSeekApi(String systemContent, String userContent) throws IOException {
        int retryTimes = 2;
        Exception lastEx = null;
        for (int i = 0; i < retryTimes; i++) {
            try {
                JSONObject reqBody = new JSONObject();
                reqBody.put("model", "deepseek-v4-flash");
                reqBody.put("max_tokens", MAX_OUTPUT_TOKENS);
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
                        throw new IOException("DeepSeek http code:" + response.code() + " body:" + respBody);
                    }
                    return JSONObject.parseObject(respBody);
                }
            } catch (Exception e) {
                lastEx = e;
                System.err.printf("DeepSeek调用失败，重试 %d/%d :%s%n", i + 1, retryTimes, e.getMessage());
                sleepMs(2000);
            }
        }
        throw new IOException("DeepSeek接口多次调用失败", lastEx);
    }

    private static void saveMarkdownFile(String title, String content, JSONArray tags) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append("\n\n");
        sb.append(content).append("\n\n");
        sb.append(tags.toJSONString());
        Files.write(Paths.get(OUTPUT_DIR, "article.md"), sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("✅md文件已保存 output/article.md");
    }

    private static void sendFeishuMessage(String title, String article, JSONArray tags) throws IOException {
        int maxFeishuLen = 2200;
        String displayText = article.length() > maxFeishuLen
                ? article.substring(0, maxFeishuLen) + "\n\n> ⚠️内容过长，完整文章下载Action产物article.md"
                : article;

        JSONObject card = new JSONObject();
        card.put("msg_type", "interactive");
        JSONObject cardBody = new JSONObject();
        cardBody.put("wide_screen_mode", true);
        JSONArray elements = new JSONArray();
        String tagStr = String.join(" ", tags.toJavaList(String.class));
        elements.add(JSONObject.of("tag", "div", "text",
                JSONObject.of("tag", "lark_md", "content", "**📜今日历史标题：" + title + "**\n" + tagStr + "\n\n" + displayText)));
        cardBody.put("elements", elements);
        card.put("card", cardBody);

        RequestBody body = RequestBody.create(card.toString(), MediaType.parse("application/json;charset=utf-8"));
        Request req = new Request.Builder().url(FEISHU_WEBHOOK).post(body).build();
        try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
            if (!resp.isSuccessful()) System.err.println("飞书调用异常 code:" + resp.code());
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static void sleepMs(long ms) {
        try {
            TimeUnit.MILLISECONDS.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
