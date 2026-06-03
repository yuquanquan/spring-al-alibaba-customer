package com.example.smartcs.service;

import com.example.smartcs.config.PromptTemplates;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 数据库查询服务 (NL2SQL)
 * <p>
 * ========================================================================
 * 【学习要点: 自然语言转SQL (NL2SQL)】
 * ========================================================================
 * NL2SQL 是将用户的自然语言问题自动转换为 SQL 查询的技术。
 * <p>
 * 实现流程:
 * 1. 将表结构（DDL）+ 用户问题 注入 Prompt
 * 2. LLM 生成 SQL 语句
 * 3. 安全校验（只允许 SELECT，防止注入）
 * 4. 执行 SQL 获取结果
 * 5. 将结果传回 LLM 生成自然语言回答
 * <p>
 * 安全考虑:
 * - 只允许 SELECT 语句
 * - SQL 注入检测
 * - 查询超时限制
 * - 结果行数限制
 * <p>
 * 进阶方向:
 * - 使用 Function Calling / Tool Use 让 LLM 直接调用查询方法
 * - 预定义查询模板（更安全，但灵活性较低）
 * - 混合方案：常见查询用模板，复杂查询用 NL2SQL
 */
@Slf4j
@Service
public class DatabaseQueryService {

    private final JdbcTemplate jdbcTemplate;
    private final ChatModel chatModel;

    /** SQL安全黑名单: 禁止这些操作 */
    private static final List<String> SQL_BLACKLIST = List.of(
        "INSERT", "UPDATE", "DELETE", "DROP", "ALTER",
        "CREATE", "TRUNCATE", "EXEC", "GRANT", "REVOKE"
    );

    public DatabaseQueryService(JdbcTemplate jdbcTemplate, ChatModel chatModel) {
        this.jdbcTemplate = jdbcTemplate;
        this.chatModel = chatModel;
    }

    /**
     * 自然语言查询业务数据
     * <p>
     * 完整链路: 用户问题 → LLM生成SQL → 安全校验 → 执行查询 → 格式化结果
     *
     * @param question 用户的自然语言问题
     * @return 查询结果字符串
     */
    public String queryByNaturalLanguage(String question) {
        log.info("【NL2SQL】用户问题: {}", question);

        // 步骤1: 让 LLM 生成 SQL
        String sql = generateSql(question);
        log.info("【NL2SQL】生成的SQL: {}", sql);

        // 步骤2: 安全校验
        if (!isSafeSql(sql)) {
            log.warn("【NL2SQL】SQL安全检查未通过: {}", sql);
            return "抱歉，生成的查询语句未通过安全检查，请换一种方式描述您的问题。";
        }

        // 步骤3: 执行查询
        try {
            List<Map<String, Object>> results = jdbcTemplate.queryForList(sql);
            log.info("【NL2SQL】查询返回 {} 条记录", results.size());

            if (results.isEmpty()) {
                return "未找到符合条件的数据。";
            }

            return formatResults(results);

        } catch (Exception e) {
            log.error("【NL2SQL】SQL执行失败: {}", e.getMessage());
            return "查询执行失败: " + e.getMessage() + "。请尝试用更清晰的方式描述您的问题。";
        }
    }

    /**
     * 使用 LLM 生成 SQL 查询语句
     * <p>
     * 将表结构信息和用户问题一起注入 Prompt，
     * 让 LLM 理解数据模型后生成准确的 SQL。
     */
    private String generateSql(String question) {
        ChatClient client = ChatClient.builder(chatModel).build();

        String prompt = PromptTemplates.NL2SQL.replace("{question}", question);

        String response = client.prompt()
            .user(prompt)
            .call()
            .content();

        // 清理 SQL（移除代码块标记和多余空白）
        return response
            .replaceAll("```sql\\s*", "")
            .replaceAll("```\\s*", "")
            .trim();
    }

    /**
     * SQL 安全校验
     * <p>
     * 只允许 SELECT 查询，禁止任何数据修改操作。
     * 生产环境还应该:
     * - 使用 SQL Parser 做更严格的安全分析
     * - 限制可查询的表
     * - 设置查询超时
     * - 限制返回行数
     */
    private boolean isSafeSql(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return false;
        }

        String upperSql = sql.toUpperCase().trim();

        // 必须以 SELECT 开头
        if (!upperSql.startsWith("SELECT")) {
            return false;
        }

        // 检查黑名单
        for (String keyword : SQL_BLACKLIST) {
            // 使用正则确保是独立关键词（避免误判如 "SELECTED"）
            if (upperSql.matches(".*\\b" + keyword + "\\b.*")) {
                return false;
            }
        }

        return true;
    }

    /**
     * 格式化查询结果为可读的文本
     */
    private String formatResults(List<Map<String, Object>> results) {
        StringBuilder sb = new StringBuilder();

        // 添加表头
        if (!results.isEmpty()) {
            sb.append("查询到 ").append(results.size()).append(" 条记录:\n\n");
            // 列名
            sb.append("| ").append(String.join(" | ", results.get(0).keySet())).append(" |\n");
            sb.append("|").append("-".repeat(results.get(0).size() * 12)).append("|\n");
        }

        // 添加数据行
        for (Map<String, Object> row : results) {
            sb.append("| ");
            for (Object value : row.values()) {
                sb.append(value != null ? value.toString() : "NULL").append(" | ");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    // ========================
    // 直接查询方法（供 Function Calling 使用）
    // ========================

    /**
     * 按订单号查询订单
     * 可以直接被 LLM Function Calling 调用
     */
    public String queryOrderByNo(String orderNo) {
        List<Map<String, Object>> results = jdbcTemplate.queryForList(
            "SELECT o.*, u.username FROM sys_order o LEFT JOIN sys_user u ON o.user_id = u.id WHERE o.order_no = ?",
            orderNo
        );
        return results.isEmpty() ? "未找到订单: " + orderNo : formatResults(results);
    }

    /**
     * 按用户名查询订单
     */
    public String queryOrdersByUser(String username) {
        List<Map<String, Object>> results = jdbcTemplate.queryForList(
            "SELECT o.order_no, o.total_amount, o.status, o.description, o.create_time " +
            "FROM sys_order o JOIN sys_user u ON o.user_id = u.id WHERE u.username = ?",
            username
        );
        return results.isEmpty() ? "用户 " + username + " 暂无订单" : formatResults(results);
    }

    /**
     * 按状态查询订单
     */
    public String queryOrdersByStatus(String status) {
        List<Map<String, Object>> results = jdbcTemplate.queryForList(
            "SELECT o.order_no, u.username, o.total_amount, o.status, o.description " +
            "FROM sys_order o JOIN sys_user u ON o.user_id = u.id WHERE o.status = ?",
            status
        );
        return results.isEmpty() ? "没有状态为 " + status + " 的订单" : formatResults(results);
    }

    /**
     * 查询所有用户
     */
    public String queryAllUsers() {
        List<Map<String, Object>> results = jdbcTemplate.queryForList(
            "SELECT id, username, email, phone, role, status, create_time FROM sys_user"
        );
        return formatResults(results);
    }

    /**
     * 查询权限列表
     */
    public String queryPermissions() {
        List<Map<String, Object>> results = jdbcTemplate.queryForList(
            "SELECT id, name, code, type, parent_id FROM sys_permission ORDER BY parent_id, id"
        );
        return formatResults(results);
    }
}
