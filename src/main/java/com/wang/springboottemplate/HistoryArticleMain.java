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

    // ✅ 切换为官方推荐稳定模型，flash系列当前长文生成不稳定
    private static final String MODEL_NAME = "deepseek-v4-flash";
    private static final int MAX_OUTPUT_TOKENS = 4096;
    private static final int MAX_HISTORY_TOPIC_SIZE = 200;
    private static final String GIST_FILENAME = "history_topics.json";
    private static final String OUTPUT_DIR = "output";

    private static final int TARGET_CONTENT_MIN = 1300;
    private static final int TARGET_CONTENT_MAX = 2000;
    private static final int TRUNCATE_TARGET = 1850;
    // ✅ 降级接受阈值：JSON修复后内容>=此值且含互动提问则允许通过
    private static final int FALLBACK_CONTENT_MIN = 800;

    // ✅ 外层重试增至6次，给服务端更多恢复窗口
    private static final int ARTICLE_GENERATE_MAX_RETRY = 6;

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    public static void main(String[] args) {
        try {
            checkEnv();
            initLocalDir();
            System.out.println("=====历史稿件生成任务启动=====");

            // ✅ 模型健康预检，避免无效重试浪费
            System.out.println("🔍 预检模型可用性...");
            try {
                callDeepSeekApi("回复OK", "测试连通性");
                System.out.println("✅ 模型预检通过");
            } catch (IOException e) {
                throw new RuntimeException("模型预检失败，请检查MODEL_NAME或API状态: " + e.getMessage());
            }

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
        s = s.replaceAll("^```json\\s*", "");
        s = s.replaceAll("^```\\s*", "");
        s = s.replaceAll("\\s*```\\s* $ ", "");
        return s.trim();
    }

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

    private static boolean isCompleteJson(String json) {
        if (isBlank(json)) return false;
        String trimmed = json.trim();
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) return false;
        int quoteCount = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            if (trimmed.charAt(i) == '"' && (i == 0 || trimmed.charAt(i - 1) != '\\')) {
                quoteCount++;
            }
        }
        return quoteCount % 2 == 0;
    }

    private static String tryRepairTruncatedJson(String json) {
        try {
            int contentIdx = json.indexOf("\"content\":\"");
            if (contentIdx < 0) return null;

            int valueStart = contentIdx + "\"content\":\"".length();
            String truncatedContent = json.substring(valueStart);

            int lastValidEnd = -1;
            for (int i = truncatedContent.length() - 1; i >= 0; i--) {
                char c = truncatedContent.charAt(i);
                if (c == '。' || c == '？' || c == '！' || c == '\n') {
                    lastValidEnd = i;
                    break;
                }
            }

            if (lastValidEnd < 100) return null;

            String validContent = truncatedContent.substring(0, lastValidEnd + 1);
            String title = extractPartialTitle(json);

            JSONObject repaired = new JSONObject();
            repaired.put("title", title);
            repaired.put("content", validContent);
            repaired.put("tags", new JSONArray().fluentAdd("#历史").fluentAdd("#历史解读"));
            return repaired.toJSONString();
        } catch (Exception e) {
            System.err.println("⚠️JSON修复失败：" + e.getMessage());
            return null;
        }
    }

    private static String extractPartialTitle(String json) {
        try {
            int start = json.indexOf("\"title\":\"") + "\"title\":\"".length();
            int end = json.indexOf("\"", start);
            if (start > 0 && end > start) return json.substring(start, end);
        } catch (Exception ignored) {
        }
        return "历史文章";
    }

    private static String truncateSafely(String text, int maxLen) {
        if (text == null || text.length() <= maxLen) return text;
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

    // ✅ 从错误消息中提取实际字数
    private static int extractActualLength(String msg) {
        try {
            int idx = msg.indexOf("实际=");
            if (idx >= 0) {
                String numStr = msg.substring(idx + 3).replaceAll("[^0-9].*", "");
                return Integer.parseInt(numStr);
            }
        } catch (Exception ignored) {
        }
        return 0;
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

        if (!raw.startsWith("{")) {
            System.err.println("⚠️ 选题返回非JSON格式: " + raw.substring(0, Math.min(200, raw.length())));
            throw new RuntimeException("选题生成失败：AI未返回合法JSON");
        }

        JSONObject obj = JSONObject.parseObject(raw);
        if (obj == null) throw new RuntimeException("选题JSON解析返回null");
        return obj.getString("topic").trim();
    }

    // ======================== 文章生成（带智能重试） ========================

    private static JSONObject generateArticleWithRetry(String selectTopic) throws IOException {
        Exception lastErr = null;
        String feedback = "";

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

                // ✅ 降级接受：修复后内容>=800字且含互动提问则允许通过
                boolean hasQuestion = content.contains("？") || content.contains("?");
                boolean isFallback = len >= FALLBACK_CONTENT_MIN && len < TARGET_CONTENT_MIN && hasQuestion;

                if (len < TARGET_CONTENT_MIN && !isFallback) {
                    throw new RuntimeException("正文长度不足，实际=" + len + "，需>=" + TARGET_CONTENT_MIN);
                }
                if (isFallback) {
                    System.out.printf("⚠️使用降级内容（%d字），已满足最低可读标准%n", len);
                }

                if (len > TARGET_CONTENT_MAX) {
                    content = truncateSafely(content, TRUNCATE_TARGET);
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
                String msg = e.getMessage();

                // ✅ 分级反馈策略：根据重试次数给出不同强度的修正指令
                if (msg != null && msg.contains("长度不足")) {
                    int actualLen = extractActualLength(msg);
                    if (i == 0) {
                        feedback = "【正文略短】请扩充至1400-1600字符。建议增加：①关键人物的心理博弈细节 ②同时期其他势力的横向对比 ③后世史家的争议评价。保持原有结构不变，仅充实内容。";
                    } else {
                        feedback = "⚠️【严重警告】上一次正文仅" + actualLen + "字，远低于1300字最低要求。" +
                                "本次必须输出≥1400字符！禁止删减任何段落，禁止概括性叙述。" +
                                "每个历史论点必须配具体史料案例+白话解读+读者共鸣点三层展开。" +
                                "直接输出完整JSON，不要解释。";
                    }
                } else if (msg != null && (msg.contains("截断") || msg.contains("EOI") || msg.contains("不完整"))) {
                    feedback = "【输出被截断】请将正文控制在1300-1500字符内确保JSON完整。优先保证核心论述+互动提问完整，可精简修饰性语句但不要删减史实分析。";
                } else {
                    feedback = "【生成失败】" + msg + "。请确保正文1300-1600字符，JSON合法完整。";
                }

                System.err.printf("⚠️文章生成校验失败：%s，准备重试%n", msg);
                sleepMs(2500);
            }
        }
        throw new IOException("多次生成文章全部失败", lastErr);
    }

    private static JSONObject generateArticleOnce(String selectTopic, String feedback) throws IOException {
        // ✅ 结构化字数分配Prompt，降低模型规划难度
        String sysPromptArticle = """
                你是成熟的今日头条历史自媒体撰稿人，面向普通大众，追求高完读率、高评论互动。
                硬性写作规范：
                1.文章固定结构：悬念钩子开头 → 交代时代背景 → 多方人物立场与处境分析 → 关键事件转折 → 历史客观影响复盘 → 结尾感悟 + 开放式互动提问。
                2.语言：口语化，短句，段落切分要短，适合手机阅读；拒绝文言文堆砌，拒绝教科书式说教。
                3.史实：严格引用正史，禁止阴谋论、野史脑洞；观点客观中立，不强行站队。
                4.标题：要有冲突感、悬念感，适合自媒体传播。
                5.tags必须输出4个标签，以#开头，数组形式，不能为空。
                6.【篇幅铁律】正文总长1300-1600字符，按以下结构分配：
                   - 悬念钩子开头：150-200字符
                   - 时代背景交代：200-250字符
                   - 多方立场分析：350-450字符（核心段落，必须展开）
                   - 关键事件转折：200-250字符
                   - 历史影响复盘：200-250字符
                   - 结尾感悟+互动提问：150-200字符
                   ⚠️ 若某部分写短了，必须在后续段落补充细节，不可跳过。
                7.只返回纯JSON，禁止任何多余说明、禁止markdown代码块包裹、禁止输出思考过程。
                8.返回的JSON必须合法且完整闭合：字符串中的换行用\\n表示，双引号用\\"转义。
                返回JSON模板：
                {"title":"","content":"换行使用\\n","tags":["#历史","#古代史","#历史解读","#人物"]}
                """;

        String userPrompt = "请根据下面选题写一篇自媒体文章：" + selectTopic
                + "\n硬性约束：正文1300-1600字符，严格按6段结构分配字数，结尾带互动提问，tags字段必须返回非空数组，title、content、tags三个字段缺一不可。"
                + (isBlank(feedback) ? "" : "\n\n" + feedback);

        JSONObject respJson = callDeepSeekApi(sysPromptArticle, userPrompt);
        String rawResp = cleanJsonRaw(respJson.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content"));
        if (isBlank(rawResp)) {
            throw new RuntimeException("AI返回内容为空字符串");
        }

        rawResp = fixJsonEscapes(rawResp);

        // ✅ 截断修复增强：修复后自动补全互动提问，支持降级接受
        if (!isCompleteJson(rawResp)) {
            String finishReason = respJson.getJSONArray("choices").getJSONObject(0).getString("finish_reason");
            if ("length".equals(finishReason)) {
                System.err.println("⚠️JSON被token截断，尝试自动修复...");
                String repaired = tryRepairTruncatedJson(rawResp);
                if (repaired != null) {
                    JSONObject repairedJson = JSONObject.parseObject(repaired);
                    String repairedContent = repairedJson.getString("content");
                    boolean hasQuestion = repairedContent.contains("？") || repairedContent.contains("?");

                    if (repairedContent.length() >= FALLBACK_CONTENT_MIN && hasQuestion) {
                        System.out.printf("✅JSON修复成功（降级模式），长度=%d，含互动提问%n", repairedContent.length());
                        rawResp = repaired;
                    } else if (repairedContent.length() >= FALLBACK_CONTENT_MIN) {
                        repairedContent += "\n\n这段历史你怎么看？欢迎评论区聊聊。";
                        repairedJson.put("content", repairedContent);
                        System.out.printf("✅JSON修复成功（补全互动），长度=%d%n", repairedContent.length());
                        rawResp = repairedJson.toJSONString();
                    } else {
                        throw new RuntimeException("AI输出被token限制截断且修复后内容过短(" + repairedContent.length() + "字)，请重试");
                    }
                } else {
                    throw new RuntimeException("AI输出被token限制截断且无法自动修复，请重试");
                }
            } else {
                throw new RuntimeException("JSON格式不完整且非length截断，原始文本前200字符：" + rawResp.substring(0, Math.min(200, rawResp.length())));
            }
        }

        JSONObject articleJson = JSONObject.parseObject(rawResp);
        if (articleJson == null) {
            throw new RuntimeException("fastjson2解析返回null，原始文本前200字符：" + rawResp.substring(0, Math.min(200, rawResp.length())));
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
                reqBody.put("model", MODEL_NAME);
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

                    if (respJson.containsKey("error")) {
                        throw new IOException("DeepSeek API Error: " + respJson.getJSONObject("error").toString());
                    }

                    JSONArray choices = respJson.getJSONArray("choices");
                    if (choices == null || choices.isEmpty()) {
                        System.err.println("⚠️ choices为空，原始响应体: " + respBody);
                        throw new IOException("DeepSeek返回choices数组为空");
                    }
                    JSONObject choice0 = choices.getJSONObject(0);

                    String finishReason = choice0.getString("finish_reason");
                    JSONObject message = choice0.getJSONObject("message");
                    String aiContent = message.getString("content");

                    System.out.printf("[DEBUG] DeepSeek返回原始content长度:%d, finish_reason:%s%n",
                            aiContent != null ? aiContent.length() : 0, finishReason);

                    if (isBlank(aiContent) && "length".equals(finishReason)) {
                        throw new IOException("DeepSeek返回空内容且finish_reason=length（服务端瞬时故障）");
                    }

                    if ("length".equals(finishReason)) {
                        System.err.println("⚠️DeepSeek输出被max_tokens截断(finish_reason=length)");
                    }

                    if (isBlank(aiContent)) {
                        throw new RuntimeException("DeepSeek返回content是空字符串");
                    }
                    return respJson;
                }
            } catch (Exception e) {
                lastEx = e;
                // ✅ 退避上限30s，避免指数爆炸导致等待过长
                long waitMs = Math.min(3000L * (1L << i), 30000L);
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
            long jitter = (long) (ms * 0.2 * (Math.random() * 2 - 1));
            TimeUnit.MILLISECONDS.sleep(ms + jitter);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
