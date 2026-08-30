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

    // [优化] 放宽校验区间，给AI更多容错空间，代码层做兜底截断
    private static final int TARGET_CONTENT_MIN = 1300;
    private static final int TARGET_CONTENT_MAX = 2000;
    // [优化] 截断目标长度，超长时截断到此长度
    private static final int TRUNCATE_TARGET = 1850;

    private static final int ARTICLE_GENERATE_MAX_RETRY = 4;

    // [优化] 提升超时时间，减少 Connection reset
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)   // 120s → 180s
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

    private static JSONArray safeGetJSONArray(JSONObject obj, String key) {
        JSONArray arr = obj.getJSONArray(key);
        return arr == null ? new JSONArray() : arr;
    }

    // ======================== Gist 操作 ========================

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

    // ======================== JSON 工具方法 ========================

    private static String cleanJsonRaw(String raw) {
        if (isBlank(raw)) return "";
        String s = raw.trim();
        s = s.replaceAll("^```json", "");
        s = s.replaceAll("^```", "");
        s = s.replaceAll("``` $ ", "");
        return s.trim();
    }

    // [优化] 新增：修复 JSON 中未转义的特殊字符，防止 fastjson2 解析报错
    private static String fixJsonEscapes(String json) {
        if (json == null) return "";
        StringBuilder sb = new StringBuilder(json.length());
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                sb.append(c);
                escaped = false;
                continue;
            }
            if (c == '\\') {
                sb.append(c);
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                sb.append(c);
                continue;
            }
            // 在 JSON 字符串值内部，修复未转义的控制字符
            if (inString) {
                switch (c) {
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    case '\b' -> sb.append("\\b");
                    case '\f' -> sb.append("\\f");
                    default -> {
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // [优化] 新增：安全截断文本，在句号/换行处断开，避免切断句子
    private static String truncateSafely(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) return text;
        // 从 maxLen 位置往前找最近的自然断句点
        String truncated = text.substring(0, maxLen);
        int lastNewline = truncated.lastIndexOf("\n\n");
        if (lastNewline > maxLen * 0.6) {
            return truncated.substring(0, lastNewline);
        }
        int lastPeriod = Math.max(
                truncated.lastIndexOf("。"),
                Math.max(truncated.lastIndexOf("？"), truncated.lastIndexOf("！"))
        );
        if (lastPeriod > maxLen * 0.6) {
            return truncated.substring(0, lastPeriod + 1);
        }
        return truncated;
    }

    // ======================== 选题生成 ========================

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

    // ======================== 文章生成（带智能重试） ========================

    /**
     * [优化] 带重试生成文章：
     * 1. 放宽校验区间 1300-2000
     * 2. 超长自动截断兜底
     * 3. 把上一次失败原因反馈给下一次尝试
     */
    private static JSONObject generateArticleWithRetry(String selectTopic) throws IOException {
        Exception lastErr = null;
        String feedback = ""; // [优化] 重试反馈信息

        for (int i = 0; i < ARTICLE_GENERATE_MAX_RETRY; i++) {
            try {
                System.out.printf("📝开始生成文章，第%d/%d次尝试%s%n", i + 1, ARTICLE_GENERATE_MAX_RETRY,
                        isBlank(feedback) ? "" : "（带修正反馈）");
                JSONObject articleJson = generateArticleOnce(selectTopic, feedback);
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

                // [优化] 下限校验
                if (len < TARGET_CONTENT_MIN) {
                    throw new RuntimeException("正文长度不足，实际=" + len + "，需>=" + TARGET_CONTENT_MIN);
                }

                // [优化] 超长自动截断兜底，不再直接报错
                if (len > TARGET_CONTENT_MAX) {
                    content = truncateSafely(content, TRUNCATE_TARGET);
                    // 确保截断后仍包含互动提问结尾
                    if (!content.contains("？") && !content.contains("?")) {
                        content += "\n\n你怎么看？欢迎评论区聊聊。";
                    }
                    articleJson.put("content", content);
                    System.out.printf("⚠️正文超长(%d→%d)，已自动截断%n", len, content.length());
                }

                System.out.printf("✅文章校验通过，正文长度=%d%n", content.length());
                return articleJson;
            } catch (Exception e) {
                lastErr = e;
                // [优化] 构建反馈信息，让下一次尝试有针对性修正
                feedback = "【上一次生成失败，请务必修正】" + e.getMessage()
                        + "。请特别注意正文长度控制在1400-1800字符之间。";
                System.err.printf("⚠️文章生成校验失败：%s，准备重试%n", e.getMessage());
                sleepMs(2500);
            }
        }
        throw new IOException("多次生成文章全部失败", lastErr);
    }

    /**
     * [优化] 新增 feedback 参数，重试时将失败原因传入 Prompt
     */
    private static JSONObject generateArticleOnce(String selectTopic, String feedback) throws IOException {
        String sysPromptArticle = """
                你是成熟的今日头条历史自媒体撰稿人，面向普通大众，追求高完读率、高评论互动。
                硬性写作规范：
                1.文章固定结构：悬念钩子开头 → 交代时代背景 → 多方人物立场与处境分析 → 关键事件转折 → 历史客观影响复盘 → 结尾感悟 + 开放式互动提问。
                2.语言：口语化，短句，段落切分要短，适合手机阅读；拒绝文言文堆砌，拒绝教科书式说教。
                3.史实：严格引用正史，禁止阴谋论、野史脑洞；观点客观中立，不强行站队。
                4.标题：要有冲突感、悬念感，适合自媒体传播。
                5.tags必须输出4个标签，以#开头，数组形式，不能为空。
                6.【篇幅铁律】正文目标1500-1700字，这是最重要的约束：
                   - 内容单薄时，必须增加：人物心理博弈细节、同时期横向对比、后世史家争议观点来扩充。
                   - 不要为凑字数重复废话，每段要有新信息量。
                   - 结尾互动提问是正文的一部分，计入字数。
                7.只返回JSON，禁止任何多余说明、禁止markdown代码块包裹输出。
                8.返回的JSON必须合法：字符串中的换行用\\n表示，禁止出现真实换行符；双引号用\\"转义。
                返回JSON模板：
                {"title":"","content":"换行使用\\n","tags":["#历史","#古代史","#历史解读","#人物"]}
                """;

        // [优化] userPrompt 加入重试反馈信息
        String userPrompt = "请根据下面选题写一篇自媒体文章：" + selectTopic
                + "\n硬性约束：正文1400-1800字符，结尾带互动提问，tags字段必须返回非空数组，title、content、tags三个字段缺一不可。"
                + (isBlank(feedback) ? "" : "\n\n" + feedback);

        JSONObject respJson = callDeepSeekApi(sysPromptArticle, userPrompt);
        String rawResp = cleanJsonRaw(respJson.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"));
        if (isBlank(rawResp)) {
            throw new RuntimeException("AI返回内容为空字符串");
        }

        // [优化] 修复 JSON 转义问题后再解析
        rawResp = fixJsonEscapes(rawResp);

        JSONObject articleJson = JSONObject.parseObject(rawResp);
        if (articleJson == null) {
            throw new RuntimeException("fastjson2解析返回null，原始文本：" + rawResp);
        }
        return articleJson;
    }

    // ======================== DeepSeek API 调用 ========================

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

                    // [优化] 检查 finish_reason，如果是 length 说明被截断了
                    String finishReason = choice0.getString("finish_reason");
                    if ("length".equals(finishReason)) {
                        System.err.println("⚠️DeepSeek输出被max_tokens截断(finish_reason=length)，建议增大MAX_OUTPUT_TOKENS");
                    }

                    JSONObject message = choice0.getJSONObject("message");
                    String aiContent = message.getString("content");
                    System.out.printf("[DEBUG] DeepSeek返回原始content长度:%d, finish_reason:%s%n",
                            aiContent != null ? aiContent.length() : 0, finishReason);
                    if (isBlank(aiContent)) {
                        throw new RuntimeException("DeepSeek返回content是空字符串");
                    }
                    return respJson;
                }
            } catch (Exception e) {
                lastEx = e;
                // [优化] 指数退避重试：2s → 4s → 8s
                long waitMs = 2000L * (1L << i);
                System.err.printf("DeepSeek调用失败，重试 %d/%d :%s，等待%ds%n", i + 1, retryTimes, e.getMessage(), waitMs / 1000);
                sleepMs(waitMs);
            }
        }
        throw new IOException("DeepSeek接口多次调用失败", lastEx);
    }

    // ======================== 文件保存 ========================

    private static void saveMarkdownFile(String title, String content, JSONArray tags) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title).append("\n\n");
        sb.append(content).append("\n\n");
        sb.append("---\n\n");
        sb.append("**标签：** ").append(String.join(" ", tags.toJavaList(String.class))).append("\n");
        Files.write(Paths.get(OUTPUT_DIR, "article.md"), sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("✅md文件已保存 output/article.md");
    }

    // ======================== 飞书推送 ========================

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

    // ======================== 工具方法 ========================

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
