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
    private static final String GH_PAT = System.getenv("GH_PAT");
    private static final String DEEPSEEK_URL = "https://api.deepseek.com/v1/chat/completions";
    private static final int MAX_OUTPUT_TOKENS = 3200;
    private static final int MAX_HISTORY_TOPIC_SIZE = 200;
    private static final String GIST_FILENAME = "history_topics.json";
    private static final String OUTPUT_DIR = "output";
    private static final int TARGET_CONTENT_MIN = 1400;
    private static final int TARGET_CONTENT_MAX = 1800;
    /** 文章生成最大重试次数：JSON解析失败 / tags缺失 / 长度不达标都会重试 */
    private static final int ARTICLE_GENERATE_MAX_RETRY = 4;
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
            System.out.printf("历史选题加载，共%d条%n", usedTopics.size());
            String selectTopic = generateTopic(usedTopics);
            System.out.println("生成选题：" + selectTopic);
            // 带重试生成文章
            JSONObject articleJson = generateArticleWithRetry(selectTopic);
            String title = articleJson.getString("title");
            String content = articleJson.getString("content");
            JSONArray tags = safeGetJSONArray(articleJson, "tags");
            int contentLen = content != null ? content.length() : 0;
            System.out.println("爆款标题：" + title);
            System.out.println("正文长度：" + contentLen);
            saveMarkdownFile(title, content, tags);
            appendTopicToGist(usedTopics, selectTopic);
            try {
                sendFeishuMessage(title, content, tags);
                System.out.println("✅飞书推送成功");
            } catch (Exception e) {
                System.err.println("⚠️飞书推送异常：" + e.getMessage());
                e.printStackTrace();
            }
            System.out.println("=====任务执行完成=====");
        } catch (Exception e) {
            System.err.println("❌任务失败：" + e.getMessage());
            e.printStackTrace();
            // 发送飞书失败告警
            sendFeishuAlert("⚠️历史每日稿件任务执行失败！" + e.getMessage());
            System.exit(1);
        }
    }

    private static void checkEnv() {
        if (isBlank(DEEPSEEK_API_KEY)) throw new RuntimeException("缺少环境变量 DEEPSEEK_API_KEY");
        if (isBlank(FEISHU_WEBHOOK)) throw new RuntimeException("缺少环境变量 FEISHU_WEBHOOK");
        if (isBlank(GIST_ID)) throw new RuntimeException("缺少环境变量 GIST_ID");
        if (isBlank(GH_PAT)) throw new RuntimeException("缺少环境变量 GH_PAT");
    }

    private static void initLocalDir() throws IOException {
        Files.createDirectories(Paths.get(OUTPUT_DIR));
    }

    /**
     * 安全获取JSONArray，null返回空数组
     */
    private static JSONArray safeGetJSONArray(JSONObject obj, String key) {
        JSONArray arr = obj.getJSONArray(key);
        return arr == null ? new JSONArray() : arr;
    }

    private static JSONArray safeReadGistTopicList() {
        int maxRetry = 2;
        for (int r = 0; r < maxRetry; r++) {
            try {
                Request req = new Request.Builder()
                        .url("https://api.github.com/gists/" + GIST_ID)
                        .header("Authorization", "token " + GH_PAT)
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
                System.err.printf("Gist读取重试 %d, err:%s%n", r + 1, ex.getMessage());
                sleepMs(1000);
            }
        }
        return new JSONArray();
    }

    private static void appendTopicToGist(JSONArray list, String newTopic) {
        list.add(newTopic);
        while (list.size() > MAX_HISTORY_TOPIC_SIZE) {
            list.remove(0);
        }
        JSONObject body = new JSONObject();
        JSONObject filesWrap = new JSONObject();
        JSONObject fileItem = new JSONObject();
        fileItem.put("content", JSON.toJSONString(list));
        filesWrap.put(GIST_FILENAME, fileItem);
        body.put("files", filesWrap);
        int maxRetry = 2;
        for (int r = 0; r < maxRetry; r++) {
            try {
                RequestBody reqBody = RequestBody.create(body.toString(), MediaType.parse("application/json;charset=utf-8"));
                Request req = new Request.Builder()
                        .url("https://api.github.com/gists/" + GIST_ID)
                        .header("Authorization", "token " + GH_PAT)
                        .method("PATCH", reqBody)
                        .build();
                try (Response resp = HTTP_CLIENT.newCall(req).execute()) {
                    if (resp.isSuccessful()) {
                        System.out.println("✅Gist更新成功，已保存选题");
                        return;
                    }
                    System.err.println("Gist写入失败 code:" + resp.code());
                }
            } catch (Exception ex) {
                System.err.printf("Gist写入重试 %d err:%s%n", r + 1, ex.getMessage());
                sleepMs(1000);
            }
        }
        System.err.println("⚠️Gist写入全部重试失败");
    }

    private static String cleanJsonRaw(String raw) {
        if (isBlank(raw)) return "";
        String s = raw.trim();
        s = s.replaceAll("^```json", "");
        s = s.replaceAll("^```", "");
        s = s.replaceAll("```$", "");
        return s.trim();
    }

    private static String generateTopic(JSONArray historyTopics) throws IOException {
        String sysPromptTopic = """
                你是资深今日头条历史自媒体选题专家，擅长产出高完读、高评论的历史思辨选题。
                选题要求：
                1.优先做：人物抉择、历史反转、假设推演、反差对比、争议评价类，容易激发读者讨论欲。
                2.禁止：简单时间线、流水账、单纯科普介绍、事件平铺直叙。
                3.不要太冷门的小人物，尽量选择大众有一定认知的历史人物与事件。
                4.输出格式严格只返回JSON，不要解释、不要markdown、不要多余文字。
                输出模板：{"topic":"你的选题句子"}
                """;
        String userPrompt = "生成1条全新历史思辨选题，严格避开下面已经使用过的选题，不要重复：\n" + JSON.toJSONString(historyTopics);
        JSONObject respJson = callDeepSeekApi(sysPromptTopic, userPrompt);
        String raw = cleanJsonRaw(respJson.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"));
        if (isBlank(raw)) throw new RuntimeException("选题返回内容为空");
        JSONObject obj = JSONObject.parseObject(raw);
        if (obj == null) throw new RuntimeException("选题JSON解析返回null");
        return obj.getString("topic").trim();
    }

    /**
     * 带重试生成文章：JSON解析失败 / tags缺失 / 长度不达标 自动重试
     */
    private static JSONObject generateArticleWithRetry(String selectTopic) throws IOException {
        Exception lastErr = null;
        for (int i = 0; i < ARTICLE_GENERATE_MAX_RETRY; i++) {
            try {
                System.out.printf("📝开始生成文章，第%d/%d次尝试%n", i + 1, ARTICLE_GENERATE_MAX_RETRY);
                JSONObject articleJson = generateArticleOnce(selectTopic);
                String title = articleJson.getString("title");
                String content = articleJson.getString("content");
                JSONArray tags = safeGetJSONArray(articleJson, "tags");
                if (isBlank(title) || isBlank(content)) {
                    throw new RuntimeException("title或content为空");
                }
                if (tags.isEmpty()) {
                    throw new RuntimeException("tags数组为空");
                }
                int len = content.length();
                if (len < TARGET_CONTENT_MIN || len > TARGET_CONTENT_MAX) {
                    throw new RuntimeException("正文长度不达标，实际=" + len);
                }
                //全部校验通过返回
                return articleJson;
            } catch (Exception e) {
                lastErr = e;
                System.err.printf("⚠️文章生成校验失败：%s，准备重试%n", e.getMessage());
                sleepMs(2500);
            }
        }
        throw new IOException("多次生成文章全部失败", lastErr);
    }

    private static JSONObject generateArticleOnce(String selectTopic) throws IOException {
        String sysPromptArticle = """
                你是成熟的今日头条历史自媒体撰稿人，面向普通大众，追求高完读率、高评论互动。
                硬性写作规范：
                1.文章固定结构：悬念钩子开头 → 交代时代背景 → 多方人物立场与处境分析 → 关键事件转折 → 历史客观影响复盘 → 结尾感悟 + 开放式互动提问。
                2.语言：口语化，短句，段落切分要短，适合手机阅读；拒绝文言文堆砌，拒绝教科书式说教。
                3.史实：严格引用正史，禁止阴谋论、野史脑洞；观点客观中立，不强行站队。
                4.标题：要有冲突感、悬念感，适合自媒体传播。
                5.tags必须输出4个标签，以#开头，数组形式，不能为空。
                6.字符严格控制1400‑1800，严禁输出少于1400字符，如果内容不够，必须扩充细节、补充人物心理推演、丰富时代背景，直到达到字符下限，不要简写压缩。结尾必须留下开放式提问，引导读者评论。
                7.只返回JSON，禁止任何多余说明、禁止markdown代码块包裹输出。
                返回JSON模板：
                {"title":"","content":"换行使用\\n","tags":["#历史","#古代史","#历史解读","#人物"]}
                """;
        String userPrompt = "请根据下面选题写一篇自媒体文章：" + selectTopic +
                "\n硬性约束：正文1400‑1800字符，结尾带互动提问，tags字段必须返回非空数组，title、content、tags三个字段缺一不可。";
        JSONObject respJson = callDeepSeekApi(sysPromptArticle, userPrompt);
        String rawResp = cleanJsonRaw(respJson.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"));
        if (isBlank(rawResp)) {
            throw new RuntimeException("AI返回内容为空字符串");
        }
        JSONObject articleJson = JSONObject.parseObject(rawResp);
        if (articleJson == null) {
            throw new RuntimeException("fastjson2解析返回null，原始文本：" + rawResp);
        }
        return articleJson;
    }

    private static JSONObject callDeepSeekApi(String systemContent, String userContent) throws IOException {
        int retryTimes = 3;
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
                    JSONObject respJson = JSONObject.parseObject(respBody);
                    JSONArray choices = respJson.getJSONArray("choices");
                    if (choices == null || choices.isEmpty()) {
                        throw new RuntimeException("DeepSeek返回choices数组为空");
                    }
                    JSONObject choice0 = choices.getJSONObject(0);
                    JSONObject message = choice0.getJSONObject("message");
                    String aiContent = message.getString("content");
                    System.out.printf("[DEBUG] DeepSeek返回原始content长度:%d%n", aiContent != null ? aiContent.length() : 0);
                    if (isBlank(aiContent)) {
                        throw new RuntimeException("DeepSeek返回content是空字符串");
                    }
                    return respJson;
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
        sb.append(JSON.toJSONString(tags));
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
            if (!resp.isSuccessful()) {
                System.err.println("飞书调用异常 code:" + resp.code());
                String respBody = resp.body() != null ? resp.body().string() : "";
                System.err.println("feishu response body:" + respBody);
            }
        }
    }

    /**
     * 飞书告警消息，任务失败时调用
     */
    private static void sendFeishuAlert(String text) {
        try {
            JSONObject alertBody = new JSONObject();
            alertBody.put("msg_type", "text");
            alertBody.put("content", JSONObject.of("text", text));
            RequestBody body = RequestBody.create(alertBody.toString(), MediaType.parse("application/json;charset=utf-8"));
            Request req = new Request.Builder().url(FEISHU_WEBHOOK).post(body).build();
            HTTP_CLIENT.newCall(req).execute().close();
        } catch (Exception ignored) {
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
