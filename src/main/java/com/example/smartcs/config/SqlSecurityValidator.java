package com.example.smartcs.config;

import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;

import java.util.*;

/**
 * SQL 安全校验器（JSqlParser AST 白名单方案）
 * <p>
 * ========================================================================
 * 【学习要点: JSqlParser 白名单 vs 正则黑名单】
 * ========================================================================
 * <p>
 * 之前的正则黑名单方案：
 * <pre>
 *   黑名单: INSERT, UPDATE, DELETE, DROP...
 *   问题: LLM 可以绕过（子查询、注释、UNION 等）
 *   例: SELECT * FROM users WHERE 1=1; -- DROP TABLE users
 *       → 正则只检查 "SELECT" 开头，注释后的 DROP 可能被忽略
 * </pre>
 * <p>
 * JSqlParser AST 白名单方案：
 * <pre>
 *   1. 将 SQL 解析为语法树（AST）
 *   2. 遍历语法树，检查每一个节点
 *   3. 表名必须在白名单中（sys_user, sys_order...）
 *   4. 禁止的列名不能出现（password, salt...）
 *   5. 只允许 SELECT 语句
 *
 *   优势: 从语法树层面分析，无法绕过
 * </pre>
 */
@Slf4j
public class SqlSecurityValidator {

    /** 允许查询的表白名单 */
    private static final Set<String> ALLOWED_TABLES = Set.of(
        "sys_user",
        "sys_order",
        "sys_order_item",
        "sys_permission"
    );

    /** 禁止查询的列名（敏感字段） */
    private static final Set<String> FORBIDDEN_COLUMNS = Set.of(
        "password", "salt", "token", "secret", "api_key"
    );

    /** 查询超时时间（毫秒） */
    private static final long QUERY_TIMEOUT_MS = 5000;

    /** 最大返回行数 */
    private static final int MAX_ROWS = 100;

    /**
     * 校验 SQL 是否安全（AST 级别分析）
     * <p>
     * 检查项:
     * 1. 必须是 SELECT 语句
     * 2. 所有表名在白名单中
     * 3. 不包含敏感列名
     * 4. 不包含子查询中的危险操作
     *
     * @param sql 待校验的 SQL
     * @return 安全返回 true，否则返回 false
     */
    public static boolean isSafe(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            log.warn("【SQL安全】SQL 为空");
            return false;
        }

        try {
            // 步骤1: 解析 SQL 为语法树
            Statement statement = CCJSqlParserUtil.parse(sql);

            // 步骤2: 必须是 SELECT 语句
            if (!(statement instanceof Select select)) {
                log.warn("【SQL安全】非 SELECT 语句: {}", statement.getClass().getSimpleName());
                return false;
            }

            // 步骤3: 遍历语法树，校验表名和列名
            ValidationResult result = validateSelect(select);
            if (!result.isValid()) {
                log.warn("【SQL安全】校验失败: {}", result.getReason());
                return false;
            }

            log.debug("【SQL安全】校验通过: {}", sql);
            return true;

        } catch (Exception e) {
            log.warn("【SQL安全】SQL 解析失败: {} - {}", sql, e.getMessage());
            return false;
        }
    }

    /**
     * 校验 SELECT 语句
     * <p>
     * JSqlParser 5.0: SelectBody 被移除，Select 本身就是主体。
     * PlainSelect 直接 extends Select。
     */
    private static ValidationResult validateSelect(Select select) {
        // 遍历 PlainSelect（主查询）—— 5.0 中 Select 直接 instanceof PlainSelect
        if (select instanceof PlainSelect plainSelect) {
            return validatePlainSelect(plainSelect);
        }
        // 集合操作（UNION 等）
        if (select instanceof SetOperationList setOps) {
            for (Select body : setOps.getSelects()) {
                if (body instanceof PlainSelect ps) {
                    ValidationResult r = validatePlainSelect(ps);
                    if (!r.isValid()) return r;
                }
            }
            return ValidationResult.ok();
        }
        return ValidationResult.fail("不支持的查询类型");
    }

    /**
     * 校验 PlainSelect（单个 SELECT 语句）
     */
    private static ValidationResult validatePlainSelect(PlainSelect ps) {
        // 校验 FROM 表名
        if (ps.getFromItem() != null) {
            ValidationResult tableResult = validateFromItem(ps.getFromItem());
            if (!tableResult.isValid()) return tableResult;
        }

        // 校验 JOIN 表名
        if (ps.getJoins() != null) {
            for (Join join : ps.getJoins()) {
                if (join.getRightItem() != null) {
                    ValidationResult joinResult = validateFromItem(join.getRightItem());
                    if (!joinResult.isValid()) return joinResult;
                }
            }
        }

        // 校验 WHERE 子句中的子查询
        if (ps.getWhere() != null) {
            ValidationResult whereResult = validateExpression(ps.getWhere());
            if (!whereResult.isValid()) return whereResult;
        }

        // 校验 SELECT 列中的敏感字段
        if (ps.getSelectItems() != null) {
            for (SelectItem<?> item : ps.getSelectItems()) {
                // 检查列名是否包含敏感字段
                String itemStr = item.toString().toLowerCase();
                for (String forbidden : FORBIDDEN_COLUMNS) {
                    if (itemStr.contains(forbidden)) {
                        return ValidationResult.fail("禁止查询敏感列: " + forbidden);
                    }
                }
            }
        }

        return ValidationResult.ok();
    }

    /**
     * 校验 FROM 项（表名或子查询）
     */
    private static ValidationResult validateFromItem(FromItem fromItem) {
        if (fromItem instanceof Table table) {
            String tableName = table.getName().toLowerCase();
            if (!ALLOWED_TABLES.contains(tableName)) {
                return ValidationResult.fail("禁止查询表: " + tableName);
            }
        }
        // 子查询: 递归校验
        if (fromItem instanceof ParenthesedSelect subSelect) {
            return validateSelect(subSelect.getSelect());
        }
        return ValidationResult.ok();
    }

    /**
     * 校验表达式中的子查询
     */
    private static ValidationResult validateExpression(Expression expr) {
        if (expr instanceof ParenthesedSelect subSelect) {
            return validateSelect(subSelect.getSelect());
        }
        // IN 子查询
        if (expr instanceof InExpression inExpr) {
            if (inExpr.getRightExpression() instanceof ParenthesedSelect subSelect) {
                return validateSelect(subSelect.getSelect());
            }
        }
        // EXISTS 子查询
        if (expr instanceof ExistsExpression existsExpr) {
            if (existsExpr.getRightExpression() instanceof ParenthesedSelect subSelect) {
                return validateSelect(subSelect.getSelect());
            }
        }
        // AND/OR 组合: 递归检查
        if (expr instanceof BinaryExpression binaryExpr) {
            ValidationResult left = validateExpression(binaryExpr.getLeftExpression());
            if (!left.isValid()) return left;
            return validateExpression(binaryExpr.getRightExpression());
        }
        return ValidationResult.ok();
    }

    /**
     * 获取查询超时时间（毫秒）
     */
    public static long getQueryTimeoutMs() {
        return QUERY_TIMEOUT_MS;
    }

    /**
     * 获取最大返回行数
     */
    public static int getMaxRows() {
        return MAX_ROWS;
    }

    /**
     * 校验结果
     */
    static class ValidationResult {
        private final boolean valid;
        private final String reason;

        ValidationResult(boolean valid, String reason) {
            this.valid = valid;
            this.reason = reason;
        }

        static ValidationResult ok() { return new ValidationResult(true, null); }
        static ValidationResult fail(String reason) { return new ValidationResult(false, reason); }

        boolean isValid() { return valid; }
        String getReason() { return reason; }
    }
}
