package com.example.smartcs.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * 数据库查询工具集（Function Calling 薄代理层）
 * <p>
 * ========================================================================
 * 【学习要点: 薄代理架构 —— Tool 层零业务代码】
 * ========================================================================
 * <p>
 * 本类是纯粹的"适配器"，职责只有两个：
 * <pre>
 *   1. 用 @Tool / @ToolParam 注解告诉 LLM 有哪些工具可用
 *   2. 将 LLM 的调用请求委托给 DatabaseQueryService（业务逻辑层）
 *
 *   本类不包含：SQL 语句、JdbcTemplate、formatResults()、任何业务逻辑
 * </pre>
 * <p>
 * 为什么这样设计（面试题：Tool 和业务代码要写两遍吗？）：
 * <pre>
 *   ❌ 错误做法: Tool 层直接写 SQL → 和 Service 层重复
 *   ✅ 正确做法: Tool 层 = @Tool 注解 + 一行委托调用
 *
 *   架构分层：
 *     Controller (REST API)  ──┐
 *                              ├──→ DatabaseQueryService (业务逻辑) ──→ JdbcTemplate
 *     DatabaseTools (Tool层) ──┘
 *
 *   Tool 和 Controller 是平级的两个入口，都调用同一套 Service
 * </pre>
 * <p>
 * 扩展策略（当业务场景很多时）：
 * <pre>
 *   1. 按领域拆分: OrderTools、UserTools、ProductTools...
 *   2. 动态注册: 按用户角色只暴露有权限的工具
 *      List&lt;Object&gt; tools = new ArrayList&lt;&gt;();
 *      if (user.hasRole("ORDER")) tools.add(orderTools);
 *      chatClient.prompt().tools(tools.toArray())...
 * </pre>
 */
@Slf4j
@Component
public class DatabaseTools {

    private final DatabaseQueryService databaseQueryService;

    public DatabaseTools(DatabaseQueryService databaseQueryService) {
        this.databaseQueryService = databaseQueryService;
    }

    // ========================
    // 预定义查询工具（覆盖 80% 高频场景）
    // 每个方法只做: @Tool 注解 + 一行委托调用
    // ========================

    @Tool(description = "根据订单编号查询订单详情，包括订单金额、状态、所属用户等信息。当用户提到具体的订单号时使用此工具。")
    public String queryOrderByNo(
            @ToolParam(description = "订单编号，如 ORD2024001") String orderNo) {
        log.info("【Tool Calling】queryOrderByNo: {}", orderNo);
        return databaseQueryService.queryOrderByNo(orderNo);
    }

    @Tool(description = "根据用户名查询该用户的所有订单列表。当用户询问某个人的订单、购买记录时使用此工具。")
    public String queryOrdersByUser(
            @ToolParam(description = "用户名，如 zhangsan") String username) {
        log.info("【Tool Calling】queryOrdersByUser: {}", username);
        return databaseQueryService.queryOrdersByUser(username);
    }

    @Tool(description = "根据订单状态查询订单列表。可用状态值: PENDING(待支付), PAID(已支付), SHIPPED(已发货), COMPLETED(已完成), REFUNDING(退款中), REFUNDED(已退款), CANCELLED(已取消)")
    public String queryOrdersByStatus(
            @ToolParam(description = "订单状态: PENDING/PAID/SHIPPED/COMPLETED/REFUNDING/REFUNDED/CANCELLED") String status) {
        log.info("【Tool Calling】queryOrdersByStatus: {}", status);
        return databaseQueryService.queryOrdersByStatus(status);
    }

    @Tool(description = "查询系统中所有用户列表，包括用户名、邮箱、手机号、角色、状态等信息。当用户询问有哪些用户、查看用户列表时使用此工具。")
    public String queryAllUsers() {
        log.info("【Tool Calling】queryAllUsers");
        return databaseQueryService.queryAllUsers();
    }

    @Tool(description = "查询系统权限配置列表，包括菜单、按钮、接口类型的权限。当用户询问权限配置、角色权限时使用此工具。")
    public String queryPermissions() {
        log.info("【Tool Calling】queryPermissions");
        return databaseQueryService.queryPermissions();
    }

    // ========================
    // 受控 NL2SQL 工具（处理 20% 的复杂/探索性查询）
    // ========================

    @Tool(description = "执行自定义数据库查询。仅当上述预定义工具无法满足需求时使用（如统计、聚合、多表关联等复杂查询）。输入自然语言描述，系统会自动生成安全的SQL并执行。")
    public String securedNl2SqlQuery(
            @ToolParam(description = "自然语言描述的查询需求，如'上个月退货率最高的产品'") String question) {
        log.info("【Tool Calling】securedNl2SqlQuery: {}", question);
        return databaseQueryService.queryByNaturalLanguage(question);
    }
}
