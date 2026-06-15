package com.example.smartcs.controller;

import com.example.smartcs.service.DocumentEtlPipeline;
import com.example.smartcs.service.DocumentGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * 文档管理控制器
 * <p>
 * 提供知识库管理和文档生成相关的 REST API：
 * 1. 知识库初始化: 加载示例文档到向量库
 * 2. 文档导入: 导入自定义文档
 * 3. 文档搜索: 搜索知识库内容（测试用）
 * 4. 文档生成: 生成 Word/PDF 格式的说明书
 * 5. 文档下载: 下载生成的文档
 */
@Slf4j
@RestController
@RequestMapping("/api/docs")
public class DocumentController {

    private final DocumentEtlPipeline etlPipeline;
    private final DocumentGenerator documentGenerator;

    public DocumentController(DocumentEtlPipeline etlPipeline,
                              DocumentGenerator documentGenerator) {
        this.etlPipeline = etlPipeline;
        this.documentGenerator = documentGenerator;
    }

    /**
     * 初始化知识库（首次调用 = 全量加载，后续调用 = 自动增量同步）
     * <p>
     * 首次：加载 classpath:knowledge-base/ 下所有 Markdown 文档 + 建立索引快照
     * 后续：对比源文件 hash，只处理新增/修改/删除的文档
     */
    @PostMapping("/init")
    public Map<String, Object> initKnowledgeBase() {
        int count = etlPipeline.initializeKnowledgeBase();
        return Map.of(
            "status", "success",
            "message", count > 0 ? "知识库初始化完成" : "知识库已是最新，无需更新",
            "documentChunks", count
        );
    }

    /**
     * 增量同步知识库（显式触发）
     * <p>
     * 对比磁盘文件与已索引快照的 hash 值：
     * - 新增文档 → 向量化 + 入库
     * - 修改文档 → 删除旧向量 + 重新向量化
     * - 删除文档 → 清理旧向量
     * - 未变化文档 → 跳过（省 embedding 成本）
     */
    @PostMapping("/sync")
    public Map<String, Object> syncKnowledgeBase() {
        Map<String, Integer> stats = etlPipeline.syncKnowledgeBase();
        return Map.of(
            "status", "success",
            "message", "增量同步完成",
            "stats", stats
        );
    }

    /**
     * 导入自定义文档
     *
     * @param filePath 文档的本地文件路径
     */
    @PostMapping("/import")
    public Map<String, Object> importDocument(@RequestParam String filePath) {
        int count = etlPipeline.importDocument(filePath);
        return Map.of(
            "status", count > 0 ? "success" : "failed",
            "documentChunks", count
        );
    }

    /**
     * 搜索知识库文档
     * <p>
     * 用于测试和调试知识库检索效果。
     *
     * @param query 搜索关键词
     * @param topK 返回结果数量（默认5）
     * @param docType 文档类型过滤（可选，如 faq/return-policy/product-manual）
     */
    @GetMapping("/search")
    public List<Map<String, Object>> searchDocuments(
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int topK,
            @RequestParam(required = false) String docType) {
        return etlPipeline.searchDocuments(query, topK, docType);
    }

    /**
     * 获取知识库统计信息
     */
    @GetMapping("/stats")
    public Map<String, Object> getStats() {
        return etlPipeline.getStats();
    }

    // ========================
    // 文档生成与下载
    // ========================

    /**
     * 生成并下载"订单说明书" (Word .docx)
     * <p>
     * 调用 Apache POI 生成包含标题、正文、表格的 Word 文档
     */
    @GetMapping("/download/order-manual")
    public ResponseEntity<Resource> downloadOrderManual() {
        try {
            String filePath = documentGenerator.generateOrderManualWord();
            return downloadFile(filePath, "订单说明书.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        } catch (Exception e) {
            log.error("生成订单说明书失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 生成并下载"退货说明书" (PDF，含图片)
     * <p>
     * 调用 iText 生成包含中文、表格、流程图、示意图的 PDF 文档
     */
    @GetMapping("/download/return-manual")
    public ResponseEntity<Resource> downloadReturnManual() {
        try {
            String filePath = documentGenerator.generateReturnManualPdf();
            return downloadFile(filePath, "退货说明书.pdf", "application/pdf");
        } catch (Exception e) {
            log.error("生成退货说明书失败", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 一键生成所有文档
     */
    @PostMapping("/generate-all")
    public Map<String, Object> generateAllDocuments() {
        try {
            String orderManual = documentGenerator.generateOrderManualWord();
            String returnManual = documentGenerator.generateReturnManualPdf();
            return Map.of(
                "status", "success",
                "orderManual", orderManual,
                "returnManual", returnManual
            );
        } catch (Exception e) {
            log.error("文档生成失败", e);
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    /**
     * 文件下载辅助方法
     */
    private ResponseEntity<Resource> downloadFile(String filePath, String fileName, String contentType) {
        File file = new File(filePath);
        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }

        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(contentType))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"" + java.net.URLEncoder.encode(fileName) + "\"")
            .body(resource);
    }
}
