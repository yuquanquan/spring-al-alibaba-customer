package com.example.smartcs.service;

import com.example.smartcs.config.PromptTemplates;
import com.example.smartcs.config.SqlSecurityValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 数据库查询服务 (NL2SQL —— 受控版本)
 * <p>
 * ========================================================================
 * 【学习要点: 受控 NL2SQL —— JSqlParser AST 白名单校验】
 * ========================================================================
 * <p>
 * 本类是 Function Calling 的降级方案：
 * <pre>
 *   用户问 "张三的订单" → DatabaseTools.queryOrdersByUser()   ← 预定义工具（安全）
 *   用户问 "上月退货率最高的产品" → 本类.queryByNaturalLanguage() ← NL2SQL（灵活）
 * </pre>
 * <p>
 * 安全升级（对比旧版正则黑名单）：
 * <pre>
 *   旧: 正则黑名单 → SELECT * FROM users; -- DROP TABLE   ← 可能绕过
 *   新: JSqlParser AST 白名单 → 解析语法树，只允许白名单表和列 ← 无法绕过
 * </pre>
 */
@Slf4j
@Service
public class DatabaseQueryService {

    private final JdbcTemplate jdbcTemplate;
    private final ChatModel chatModel;

    public DatabaseQueryService(JdbcTemplate jdbcTemplate, ChatModel chatModel) {
        this.jdbcTemplate = jdbcTemplate;
        this.chatModel = chatModel;
    }

    /**
     * 自然语言查询业务数据（受控 NL2SQL）
     * <p>
     * 完整链路: 用户问题 → LLM生成SQL → JSqlParser白名单校验 → 执行查询 → 格式化结果
     *
     * @param question 用户的自然语言问题
     * @return 查询结果字符串
     */
    public String queryByNaturalLanguage(String question) {
        log.info("【NL2SQL】用户问题: {}", question);

        // 步骤1: 让 LLM 生成 SQL
        String sql = generateSql(question);
        log.info("【NL2SQL】生成的SQL: {}", sql);

        // 步骤2: JSqlParser AST 白名单校验（替代旧的正则黑名单）
        if (!SqlSecurityValidator.isSafe(sql)) {
            log.warn("【NL2SQL】SQL安全校验未通过: {}", sql);
            return "抱歉，生成的查询语句未通过安全检查，请换一种方式描述您的问题。";
        }

        // 步骤3: 添加 LIMIT（如果没有）
        sql = ensureLimit(sql, SqlSecurityValidator.getMaxRows());

        // 步骤4: 执行查询（带超时保护）
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
     */
    private String generateSql(String question) {
        ChatClient client = ChatClient.builder(chatModel).build();

        String prompt = PromptTemplates.NL2SQL.replace("{question}", question);

        String response = client.prompt()
            .user(prompt)
            .call()
            .content();

        return response
            .replaceAll("```sql\\s*", "")
            .replaceAll("```\\s*", "")
            .trim();
    }

    /**
     * 确保 SQL 有 LIMIT 限制（防止返回过多数据）
     */
    private String ensureLimit(String sql, int maxRows) {
        String upper = sql.toUpperCase().trim();
        if (!upper.contains("LIMIT")) {
            return sql + " LIMIT " + maxRows;
        }
        return sql;
    }

    // ========================
    // 预定义查询方法（业务逻辑层 —— 唯一的业务代码位置）
    // DatabaseTools 的 @Tool 方法通过调用这些方法实现薄代理
    // ========================

    /**
     * 按订单号查询订单详情
     */
    public String queryOrderByNo(String orderNo) {
        log.info("【订单查询】按订单号: {}", orderNo);
        List<Map<String, Object>> results = jdbcTemplate.queryForList(
            "SELECT o.order_no, o.total_amount, o.status, o.description, o.create_time, " +
            "u.username, u.email " +
            "FROM sys_order o LEFT JOIN sys_user u ON o.user_id = u.id " +
            "WHERE o.order_no = ?",
            orderNo
        );
        return results.isEmpty()
            ? "未找到订单号为 " + orderNo + " 的订单。请确认订单号是否正确。"
            : formatResults(results);
    }

    /**
     * 按用户名查询订单列表
     */
    public String queryOrdersByUser(String username) {
        log.info("【订单查询】按用户名: {}", username);
        List<Map<String, Object>> results = jdbcTemplate.queryForList(
            "SELECT o.order_no, o.total_amount, o.status, o.description, o.create_time " +
            "FROM sys_order o JOIN sys_user u ON o.user_id = u.id " +
            "WHERE u.username = ? ORDER BY o.create_time DESC LIMIT 50",
            username
        );
        return results.isEmpty()
            ? "用户 " + username + " 暂无订单记录。"
            : formatResults(results);
    }

    /**
     * 按状态查询订单列表
     */
    public String queryOrdersByStatus(String status) {
        log.info("【订单查询】按状态: {}", status);
        List<Map<String, Object>> results = jdbcTemplate.queryForList(
            "SELECT o.order_no, u.username, o.total_amount, o.status, o.description, o.create_time " +
            "FROM sys_order o JOIN sys_user u ON o.user_id = u.id " +
            "WHERE o.status = ? ORDER BY o.create_time DESC LIMIT 50",
            status.toUpperCase()
        );
        return results.isEmpty()
            ? "没有状态为 " + status + " 的订单。"
            : formatResults(results);
    }

    /**
     * 查询所有用户
     */
    public String queryAllUsers() {
        log.info("【用户查询】查询全部用户");
        List<Map<String, Object>> results = jdbcTemplate.queryForList(
            "SELECT id, username, email, phone, role, status, create_time FROM sys_user ORDER BY id LIMIT 100"
        );
        return results.isEmpty() ? "系统中暂无用户数据。" : formatResults(results);
    }

    /**
     * 查询权限列表
     */
    public String queryPermissions() {
        log.info("【权限查询】查询全部权限配置");
        List<Map<String, Object>> results = jdbcTemplate.queryForList(
            "SELECT id, name, code, type, parent_id FROM sys_permission ORDER BY parent_id, id"
        );
        return results.isEmpty() ? "系统中暂无权限配置。" : formatResults(results);
    }

    // ========================
    // 内部工具方法
    // ========================

    /**
     * 格式化查询结果为可读的文本（public 供 Tool 层复用）
     */
    public String formatResults(List<Map<String, Object>> results) {
        StringBuilder sb = new StringBuilder();

        if (!results.isEmpty()) {
            sb.append("查询到 ").append(results.size()).append(" 条记录:\n\n");
            sb.append("| ").append(String.join(" | ", results.get(0).keySet())).append(" |\n");
            sb.append("|").append("-".repeat(results.get(0).size() * 12)).append("|\n");
        }

        for (Map<String, Object> row : results) {
            sb.append("| ");
            for (Object value : row.values()) {
                sb.append(value != null ? value.toString() : "NULL").append(" | ");
            }
            sb.append("\n");
        }

        return sb.toString();
    }
}
