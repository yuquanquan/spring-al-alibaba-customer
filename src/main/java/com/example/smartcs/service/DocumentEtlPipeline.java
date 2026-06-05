package com.example.smartcs.service;

import com.example.smartcs.search.HybridSearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentTransformer;
import org.springframework.ai.reader.TextReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 文档 ETL 管道服务
 * <p>
 * ========================================================================
 * 【学习要点: ETL 管道 (Extract-Transform-Load)】
 * ========================================================================
 * ETL 是 RAG 系统的数据准备阶段，决定了知识库的质量。
 * <p>
 * 完整流程:
 * <pre>
 *   原始文档 → [Extract] → [去噪Denoise] → [切块Chunk] → [添加元数据] → [Load到向量库]
 * </pre>
 * <p>
 * ========================================================================
 * 【学习要点: 去噪 (Denoising)】
 * ========================================================================
 * 原始文档中通常包含大量"噪声"，会影响向量检索的精度：
 * - 页眉页脚、页码
 * - 多余空白、特殊字符
 * - 广告、导航栏文本
 * - 重复内容
 * <p>
 * 去噪策略:
 * 1. 正则替换: 移除页码、多余空白
 * 2. 模式匹配: 识别并移除噪声段落
 * 3. 内容过滤: 过滤过短的无意义片段
 * <p>
 * ========================================================================
 * 【学习要点: 切块 (Chunking)】
 * ========================================================================
 * 将长文档切分为适当大小的"块"（Chunk），是向量检索的基本单元。
 * <p>
 * 切块策略:
 * 1. 固定大小切块: 每 N 个 token 切一块（简单但可能截断语义）
 * 2. 语义切块: 按段落/章节切分（保留语义完整性）
 * 3. 滑动窗口: 相邻块有重叠（保证上下文连贯）
 * <p>
 * 关键参数:
 * - chunkSize: 每块的大小（token数），太大则检索精度低，太小则上下文不完整
 * - minChunkSize: 最小块大小，太小的块可能是噪声
 * - overlap: 重叠大小，保证跨块的语义连贯性
 * <p>
 * ========================================================================
 * 【学习要点: 索引选择 (Index Selection)】
 * ========================================================================
 * 向量索引决定了搜索的效率和精度。PgVector 支持两种索引：
 * <p>
 * 1. IVFFlat (Inverted File with Flat compression):
 *    - 将向量空间划分为多个簇
 *    - 查询时只搜索最近的几个簇
 *    - 优点: 构建快，内存占用小
 *    - 缺点: 数据量小时效果差
 *    - 适合: 数据量 > 100K
 * <p>
 * 2. HNSW (Hierarchical Navigable Small World):
 *    - 构建多层图结构
 *    - 查询时从顶层快速定位，逐层细化
 *    - 优点: 查询精度高，适合小数据量
 *    - 缺点: 构建慢，内存占用大
 *    - 适合: 数据量 < 100K（本项目选用此方案）
 */
@Slf4j
@Service
public class DocumentEtlPipeline {

    private final VectorStore vectorStore;
    private final HybridSearchService hybridSearchService;

    @Value("classpath:knowledge-base/*.md")
    private Resource[] knowledgeBaseResources;

    public DocumentEtlPipeline(VectorStore vectorStore, HybridSearchService hybridSearchService) {
        this.vectorStore = vectorStore;
        this.hybridSearchService = hybridSearchService;
    }

    /**
     * 初始化知识库: 加载所有知识库文档到向量存储
     * <p>
     * 完整 ETL 流程:
     * 1. Extract: 从 Markdown 文件读取文本
     * 2. Transform: 去噪 → 切块 → 添加元数据
     * 3. Load: 存入 PgVector 向量存储
     *
     * @return 加载的文档块总数
     */
    public int initializeKnowledgeBase() {
        log.info("【ETL】开始初始化知识库...");

        List<Document> allDocuments = new ArrayList<>();

        for (Resource resource : knowledgeBaseResources) {
            try {
                log.info("【ETL-Extract】加载文档: {}", resource.getFilename());

                // ---- Step 1: 文档读取 (Extract) ----
                // TODO [企业级扩展] 当前仅支持 Markdown/纯文本。
                //  后续需要根据文件后缀路由到不同的 Reader:
                //  - .md/.txt  → TextReader（当前方案）
                //  - .pdf      → Apache PDFBox / Spring AI PdfReader（提取文本+页码）
                //  - .docx     → Apache POI / Spring AI DocxReader（保留段落结构）
                //  - .xlsx     → Apache POI（表格转结构化文本，按Sheet/行切分）
                //  - .pptx     → Apache POI（按Slide提取，每张Slide作为一个独立文档）
                //  - .jpg/.png → OCR（Tesseract / 通义千问VL多模态模型识别图片中文字）
                //  建议: 抽取 DocumentReaderFactory，根据文件类型返回对应的 Reader 实例
                TextReader reader = new TextReader(resource);
                List<Document> rawDocs = reader.read();
                log.info("【ETL】原始文档数: {} (来自 {})", rawDocs.size(), resource.getFilename());

                // ---- Step 2: 去噪 (Denoise) ----
                List<Document> denoisedDocs = denoise(rawDocs);
                log.info("【ETL-Denoise】去噪后文档数: {}", denoisedDocs.size());

                // ---- Step 3: 切块 (Chunking) ----
                List<Document> chunks = splitDocuments(denoisedDocs);
                log.info("【ETL-Chunk】切块后文档数: {}", chunks.size());

                // ---- Step 4: 添加元数据 (Metadata) ----
                addMetadata(chunks, resource.getFilename());

                allDocuments.addAll(chunks);

            } catch (Exception e) {
                log.error("【ETL】加载文档失败 {}: {}", resource.getFilename(), e.getMessage());
            }
        }

        // ---- Step 5: 加载到向量存储 (Load) ----
        if (!allDocuments.isEmpty()) {
            vectorStore.add(allDocuments);
            log.info("【ETL-Load】知识库加载完成，共 {} 个文档块", allDocuments.size());
            
            // 同步到 BM25 索引（用于混合检索）
            hybridSearchService.syncBM25Index(allDocuments);
        }

        return allDocuments.size();
    }

    /**
     * 去噪处理 (Denoising) - 企业级四层去噪架构
     * <p>
     * ====================================================================
     * TODO [企业级扩展] 多格式文档去噪适配
     * ====================================================================
     * 当前实现仅针对 Markdown 文本格式。企业级智能客服需要支持多种文档格式，
     * 每种格式的噪声特征和去噪策略不同:
     * <p>
     * PDF:
     * - 噪声: 页眉页脚、水印、扫描伪影、列排版导致的阅读顺序错乱
     * - 去噪: 基于坐标位置检测页眉页脚（Y坐标固定 = 页眉），多栏文本重排序
     * - 工具: Apache PDFBox（获取文字坐标）、LayoutLM（识别文档版面结构）
     * <p>
     * Word (.docx):
     * - 噪声: 修订标记、批注、隐藏文本、样式噪声
     * - 去噪: 接受所有修订后提取纯文本，忽略样式
     * - 工具: Apache POI（XWPFDocument）
     * <p>
     * Excel (.xlsx):
     * - 噪声: 空行空列、合并单元格、Sheet名噪声
     * - 去噪: 跳过空行空列，合并单元格内容拼接，Sheet名作为sectionTitle元数据
     * - 工具: Apache POI（XSSFWorkbook），按Sheet/行切分为独立Document
     * <p>
     * PPT (.pptx):
     * - 噪声: 母版文本（每页都有的公司名/日期）、演讲者备注
     * - 去噪: 移除母版占位符文本，可选保留/丢弃演讲者备注
     * - 工具: Apache POI（XMLSlideShow），每张Slide作为一个独立Document
     * <p>
     * 图片 (.jpg/.png):
     * - 噪声: OCR识别错误、水印、背景噪声
     * - 去噪: 先OCR提取文字，再做文本级去噪
     * - 工具: Tesseract OCR / 通义千问VL多模态模型（直接用VLM识别图片内容）
     * <p>
     * 建议架构: 抽取 DocumentDenoiser 接口，每种格式一个实现类:
     * <pre>
     * interface DocumentDenoiser { List&lt;Document&gt; denoise(List&lt;Document&gt; docs); }
     * class MarkdownDenoiser implements DocumentDenoiser { ... }  // 当前实现
     * class PdfDenoiser implements DocumentDenoiser { ... }
     * class ExcelDenoiser implements DocumentDenoiser { ... }
     * </pre>
     * <p>
     * ====================================================================
     * 【层次1: 溯源元数据提取】（必做，当前已实现）
     * ====================================================================
     * 原则: 任何从正文中移除的内容，都要先提取到 metadata 中。
     * 这样检索命中后，可以告诉用户"答案出自第几页、哪个章节"。
     * <p>
     * 提取内容:
     * - pageNumber: 文档中出现的页码（支持"第N页"和"Page N"两种格式）
     * - sectionTitle: 当前段落所属的章节标题（识别Markdown标题层级）
     * - docTitle: 文档首个一级标题作为文档标题
     * <p>
     * ====================================================================
     * 【层次2: 结构化清洗】（大多数项目会做，当前已实现）
     * ====================================================================
     * 不是盲目地用正则删除，而是先理解文档结构，再按结构清理。
     * <p>
     * 清洗策略:
     * - 页眉页脚检测: 识别跨页重复出现的文本（每页都有的就是页眉/页脚）
     * - 空白规范化: 合并连续空行、去除多余空格
     * - 表格/列表保护: 不破坏Markdown表格和有序/无序列表的结构
     * <p>
     * ====================================================================
     * 【层次3: 内容质量过滤】（按需，当前注释备用）
     * ====================================================================
     * 适用于数据源质量较差的场景（如OCR识别、网页爬虫）。
     * 如果输入是干净的Markdown/Word文档，通常不需要。
     * <p>
     * 可选策略:
     * - 信噪比评估: 计算有效文字占比（排除空白/标点），过低则标记为噪声
     * - 语言一致性: 检测是否是乱码（如"锟斤拷"、"\ufffd"等编码错误）
     * - 文档内重复检测: 同一文档内的重复段落（PDF页眉多次出现等）
     * - 最小信息量: 一段文字至少包含一个完整的句子/陈述
     * <p>
     * 实现示例（取消注释即可启用）:
     * <pre>
     * .filter(doc -> {
     *     String t = doc.getText();
     *     // 信噪比: 有效字符（非空白非标点）占比
     *     long effectiveChars = t.chars().filter(c -> !Character.isWhitespace(c)
     *         && !isPunctuation(c)).count();
     *     double snr = (double) effectiveChars / t.length();
     *     return snr > 0.3; // 有效字符占比超过30%
     * })
     * .filter(doc -> {
     *     // 乱码检测: 包含替换字符\ufffd或连续不可打印字符
     *     return !doc.getText().contains("\ufffd")
     *         && !doc.getText().matches(".*[\\x00-\\x08]{3,}.*");
     * })
     * </pre>
     * <p>
     * ====================================================================
     * 【层次4: 语义增强】（少数项目会做，当前注释备用）
     * ====================================================================
     * 成本较高（需要额外调用LLM），只有对精度要求极高的场景才使用。
     * <p>
     * 可选策略:
     * - LLM摘要: 对长段落用LLM生成摘要，作为chunk的补充embedding文本
     * - 实体提取: 提取产品名、人名、日期等关键实体，存入metadata用于精确过滤
     * - 关系标注: 标注"这段是上一段的续接"（解决切块导致的语义断裂）
     * - 质量评分: 用LLM对每个chunk打分，低分chunk降低检索权重
     * <p>
     * 实现思路（需注入ChatModel）:
     * <pre>
     * // 对每个chunk生成LLM摘要，存入metadata.summary字段
     * String summary = chatClient.prompt()
     *     .user("请用一句话概括以下内容的核心信息:\n" + chunk.getText())
     *     .call().content();
     * chunk.getMetadata().put("summary", summary);
     *
     * // 提取实体（产品名、人名、日期等）
     * String entities = chatClient.prompt()
     *     .user("提取以下文本中的关键实体（产品名、人名、日期），用逗号分隔:\n"
     *         + chunk.getText())
     *     .call().content();
     * chunk.getMetadata().put("entities", entities);
     * </pre>
     */
    private List<Document> denoise(List<Document> documents) {
        return documents.stream()
            .map(doc -> {
                String text = doc.getText();
                Map<String, Object> metadata = new HashMap<>(doc.getMetadata());

                // ============================================================
                // 层次1: 溯源元数据提取（在删除任何内容之前，先抢救结构信息）
                // ============================================================

                // 1a. 提取页码: 匹配 "第N页" 或 "Page N"，提取最大页码号
                List<String> pageNums = new ArrayList<>();
                Matcher cnPageMatcher = Pattern.compile("(?m)^第(\\d+)页\\s*$").matcher(text);
                while (cnPageMatcher.find()) pageNums.add(cnPageMatcher.group(1));
                Matcher enPageMatcher = Pattern.compile("(?m)^Page\\s+(\\d+)\\s*$").matcher(text);
                while (enPageMatcher.find()) pageNums.add(enPageMatcher.group(1));
                if (!pageNums.isEmpty()) {
                    // 存储页码范围（起始页-结束页），便于溯源
                    metadata.put("pageRange",
                        pageNums.get(0) + (pageNums.size() > 1 ? "-" + pageNums.get(pageNums.size() - 1) : ""));
                }

                // 1b. 提取章节标题: 识别Markdown标题层级（#、##、###）
                List<String> sections = new ArrayList<>();
                Matcher headingMatcher = Pattern.compile("(?m)^(#{1,3})\\s+(.+)$").matcher(text);
                while (headingMatcher.find()) {
                    sections.add(headingMatcher.group(2).trim());
                }
                if (!sections.isEmpty()) {
                    metadata.put("sectionTitle", sections.get(sections.size() - 1)); // 最后一个章节标题
                    if (text.startsWith("# ") || text.startsWith("# \t")) {
                        metadata.put("docTitle", sections.get(0)); // 第一个一级标题作为文档标题
                    }
                }

                // 1c. 提取所有结构信息（供后续扩展使用）
                metadata.put("headings", sections);

                // ============================================================
                // 层次2: 结构化清洗（先理解结构，再按结构清理）
                // ============================================================

                // 2a. 移除页码行（已从metadata中保存，现在可以从正文删除）
                text = text.replaceAll("(?m)^第\\d+页\\s*$", "");
                text = text.replaceAll("(?m)^Page\\s+\\d+\\s*$", "");

                // 2b. 检测并移除重复页眉页脚
                //     原理: 如果文本中存在完全相同的多行文本重复出现2次以上，
                //     大概率是页眉/页脚（如"XX公司 产品手册 V2.0"每页都有）
                List<String> repeatedLines = detectRepeatedLines(text);
                for (String line : repeatedLines) {
                    text = text.replace(line, "");
                    log.debug("【去噪-页眉页脚】移除重复行: {}", line.trim());
                }

                // 2c. 空白规范化（保护表格和列表结构）
                //     注意: 不盲目删除所有空行，因为Markdown的表格和列表依赖换行
                text = text.replaceAll("\n{3,}", "\n\n");  // 合并3个以上连续空行为2个
                text = text.replaceAll(" +", " ");          // 合并多余空格
                text = text.trim();

                return new Document(text, metadata);
            })
            .filter(doc -> doc.getText().length() > 20) // 过滤过短的噪声片段
            .collect(Collectors.toList());
    }

    /**
     * 检测文本中重复出现的行（用于识别页眉/页脚）
     * <p>
     * 原理: 将文本按行拆分，统计每行出现的次数。
     * 如果某一行（长度>3，非空白）出现了2次以上，认为是重复的页眉/页脚。
     * <p>
     * TODO [企业级改进] 当前方案是简化版，存在以下已知问题:
     * 1. 硬编码阈值: "出现3次以上"不适用于所有文档（2页文档的页眉只出现2次）
     * 2. 无位置感知: 正文中恰好重复出现的内容会被误删（如"注意事项"作为小标题出现3次）
     * 3. 不支持多行页眉: 连续2-3行的页眉/页脚无法识别
     * <p>
     * 企业级改进方向（学完整体架构后回来实现）:
     * - 位置感知: 只检测每页开头前2行和结尾后2行，中间的重复不算
     * - 比例感知: 重复次数/文档页数 > 0.5 才算页眉页脚
     * - TF-IDF统计: 对每行计算TF-IDF分数，高频+跨文档稀有的行 = 页眉页脚
     *   （纯统计算法，不需要模型。TF=行在本文档出现频率，IDF=log(全部文档数/包含该行的文档数)）
     * - 多行页眉检测: 连续N行在每页相同位置出现
     *
     * @param text 文档全文
     * @return 重复出现的行列表
     */
    private List<String> detectRepeatedLines(String text) {
        Map<String, Long> lineCounts = Arrays.stream(text.split("\n"))
            .map(String::trim)
            .filter(line -> line.length() > 3) // 忽略过短的行（如分隔线"---"）
            .collect(Collectors.groupingBy(line -> line, Collectors.counting()));

        return lineCounts.entrySet().stream()
            .filter(entry -> entry.getValue() >= 3) // 出现3次及以上视为页眉/页脚
            .map(Map.Entry::getKey)
            .collect(Collectors.toList());
    }

    /**
     * 文档切块 (Chunking) —— Parent-Child 父子分块策略
     * <p>
     * ========================================================================
     * 【学习要点: Chunk 切分方案对比】
     * ========================================================================
     *
     * 方案1: TokenTextSplitter（固定Token切分）
     * - 原理: 按 token 数量 + 分隔符优先级切分（\n\n > \n > 。 > ，）
     * - 优点: 简单通用，Spring AI 内置
     * - 缺点: 不考虑语义边界，可能把一段完整论述切断
     * - 适用: 原型验证、小项目
     *
     * 方案2: 语义分块 (Semantic Chunker)
     * - 原理: 用 embedding 相似度判断语义断点，相邻句子相似度低的地方切分
     * - 优点: 语义完整，不会切断上下文
     * - 缺点: 计算成本高（需要先对所有句子做 embedding）
     * - 适用: 长文档、技术文档
     *
     * 方案3: 结构化分块 (Structured Chunker)
     * - 原理: 按文档结构切分 — Markdown按标题、HTML按div、代码按函数、表格按行
     * - 优点: 保留文档逻辑层次，每个 chunk 语义自洽
     * - 缺点: 需要解析文档格式，不同格式需不同实现
     * - 适用: FAQ（按问答对）、政策文档（按章节）、代码（按函数）
     *
     * 方案4: 递归字符分块 (Recursive Character) — LangChain 默认方案
     * - 原理: 按优先级递归尝试分隔符: \n\n → \n → 。 → ，
     * - 优点: 通用性好，比固定 token 切分更尊重语义边界
     * - 缺点: 本质仍基于字符数，不真正理解语义
     * - 适用: 通用场景
     *
     * ★ 方案5: Parent-Child 父子分块（当前实现，企业级首选）
     * - 原理: 大块(2000token)给 LLM 阅读，小块(200token)做向量检索
     * - 优点:
     *   ✅ 检索精准: 小 chunk embedding 语义集中，不会被稀释
     *   ✅ 上下文完整: LLM 看到的是父级完整段落，减少幻觉
     *   ✅ 成本可控: 只给命中的 chunk 扩展为父级，不是所有都扩展
     * - 缺点:
     *   ❌ 存储翻倍: 每个 child 的 metadata 存一份 parent 文本
     *   ❌ 实现复杂: 需要维护父子关系，检索侧需适配
     * - 适用: 生产环境首选，尤其适合需要精准检索+完整上下文的场景
     *
     * 方案6: 滑动窗口重叠
     * - 原理: 大块 + 50% overlap，避免切断关键信息
     * - 优点: 几乎不丢上下文，简单有效
     * - 缺点: chunk 数量翻倍，存储和检索成本增加
     * - 适用: 法律合同、医疗报告（信息不能丢）
     *
     * ========================================================================
     * 【Parent-Child 分块流程】
     * ========================================================================
     * <pre>
     *   原始文档
     *      │
     *      ▼ TokenTextSplitter(2000 tokens)
     *   ┌──────────────────────────────────────────┐
     *   │  Parent Chunk 1 (~2000 tokens)            │
     *   │  ┌─────────┐ ┌─────────┐ ┌─────────┐    │
     *   │  │Child 1a │ │Child 1b │ │Child 1c │    │ ← 每个约 200 tokens
     *   │  └─────────┘ └─────────┘ └─────────┘    │
     *   └──────────────────────────────────────────┘
     *   ┌──────────────────────────────────────────┐
     *   │  Parent Chunk 2 (~2000 tokens)            │
     *   │  ┌─────────┐ ┌─────────┐                  │
     *   │  │Child 2a │ │Child 2b │                  │
     *   │  └─────────┘ └─────────┘                  │
     *   └──────────────────────────────────────────┘
     * </pre>
     * <p>
     * 检索流程:
     * 1. 用小 chunk 做向量检索（语义集中 → 精准命中）
     * 2. 命中后从 metadata 取 parentContent（完整上下文）
     * 3. 把 parent content 喂给 LLM（回答更完整，减少幻觉）
     *
     * @param documents 待切块的文档列表
     * @return 切块后的 child 文档列表（每个 child 的 metadata 包含 parentContent）
     */
    private List<Document> splitDocuments(List<Document> documents) {
        // ============================================================
        // Step 1: 切分父块（Parent Chunks）—— 大块，给 LLM 用
        // ============================================================
        // parentChunkSize=2000: 每块约 2000 token，保证段落完整性
        // parentMinChunkSizeChars=800: 每块最少 800 字符
        TokenTextSplitter parentSplitter = new TokenTextSplitter(
            2000,  // defaultChunkSize: 父块大小
            800,   // minChunkSizeChars: 最小字符数
            50,    // minChunkLengthToEmbed: 最短 50 字符才嵌入
            10000, // maxNumChunks: 单文档最多块数
            true   // keepSeparator: 保留分隔符
        );
        List<Document> parentChunks = parentSplitter.transform(documents);
        log.info("【Parent-Child】父块数: {}", parentChunks.size());

        // ============================================================
        // Step 2: 对每个父块切分子块（Child Chunks）—— 小块，做向量检索
        // ============================================================
        // childChunkSize=200: 每块约 200 token，语义集中 → 检索精准
        // childMinChunkSizeChars=50: 最小 50 字符
        TokenTextSplitter childSplitter = new TokenTextSplitter(
            200,   // defaultChunkSize: 子块大小
            50,    // minChunkSizeChars: 最小字符数
            50,    // minChunkLengthToEmbed: 最短 50 字符才嵌入
            10000, // maxNumChunks: 单文档最多块数
            true   // keepSeparator: 保留分隔符
        );

        List<Document> childChunks = new ArrayList<>();
        for (int parentIdx = 0; parentIdx < parentChunks.size(); parentIdx++) {
            Document parent = parentChunks.get(parentIdx);
            String parentId = "parent-" + UUID.randomUUID();
            String parentContent = parent.getText();

            // 对父块做子切分
            List<Document> children = childSplitter.transform(List.of(parent));

            for (int childIdx = 0; childIdx < children.size(); childIdx++) {
                Document child = children.get(childIdx);
                Map<String, Object> meta = new HashMap<>(child.getMetadata());

                // 关键: 在子块的 metadata 中存入父块完整内容
                meta.put("parentContent", parentContent);
                meta.put("parentId", parentId);
                meta.put("childIndex", childIdx);
                meta.put("parentIndex", parentIdx);

                // 创建新的 Document（Spring AI 1.1.x 的 id 不可变）
                childChunks.add(new Document(
                    UUID.randomUUID().toString(),
                    Objects.requireNonNull(child.getText()),
                    meta
                ));
            }
        }

        log.info("【Parent-Child】子块数: {}（平均每个父块 {} 个子块）",
            childChunks.size(),
            parentChunks.isEmpty() ? 0 : childChunks.size() / parentChunks.size());

        return childChunks;
    }

    /**
     * 为文档块添加元数据 (Metadata)
     * <p>
     * 元数据分两类，职责完全不同:
     * <p>
     * 【索引型元数据】- 用于检索时的过滤条件，参与SQL查询:
     * - source: 文档来源（文件名）
     * - docType: 文档类型（faq/policy/manual）
     * <p>
     * 【溯源型元数据】- 用于回答后展示来源，不参与过滤:
     * - pageRange: 页码范围（从去噪阶段提取）
     * - sectionTitle: 所属章节标题（从去噪阶段提取）
     * - docTitle: 文档标题（从去噪阶段提取）
     * - chunkIndex: 块序号
     * - totalChunks: 总块数
     * <p>
     * 注意: PgVector的metadata是JSONB字段，多存几个字段不影响向量检索性能
     * （向量检索只看embedding向量，不看metadata）。metadata只在过滤阶段被查询。
     */
    private void addMetadata(List<Document> documents, String sourceFile) {
        String docType = determineDocType(sourceFile);
        int totalChunks = documents.size();

        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            // 索引型元数据（用于检索过滤）
            doc.getMetadata().put("source", sourceFile);
            doc.getMetadata().put("docType", docType);
            doc.getMetadata().put("chunkIndex", i);
            doc.getMetadata().put("totalChunks", totalChunks);
            // 溯源型元数据（pageRange、sectionTitle、docTitle）
            // 已在denoise阶段提取到metadata中，由父块→子块自动继承
            // 如果某些chunk缺少溯源信息，补充默认值
            doc.getMetadata().putIfAbsent("pageRange", "unknown");
            doc.getMetadata().putIfAbsent("sectionTitle", "unknown");
            // 设置唯一ID: Spring AI 1.1.x Document的id是不可变的，
            // 需要通过构造函数 Document(id, text, metadata) 重新创建
            // 注意: parentContent 已在 splitDocuments() 的 Parent-Child 分块中写入 metadata
            String uniqueId = UUID.randomUUID().toString();
            documents.set(i, new Document(uniqueId, Objects.requireNonNull(doc.getText()), doc.getMetadata()));
        }
    }

    /**
     * 根据文件名推断文档类型
     * 用于元数据过滤时的分类检索
     */
    private String determineDocType(String filename) {
        if (filename == null) return "general";
        String lower = filename.toLowerCase();
        if (lower.contains("faq")) return "faq";
        if (lower.contains("return") || lower.contains("退货")) return "return-policy";
        if (lower.contains("order") || lower.contains("订单")) return "order-guide";
        if (lower.contains("manual") || lower.contains("手册")) return "product-manual";
        return "general";
    }

    /**
     * 导入自定义文档到知识库
     * <p>
     * 支持导入任意文本文件到向量存储。
     * 在生产环境中可以扩展支持 PDF、Word、HTML 等格式。
     */
    public int importDocument(String filePath) {
        try {
            TextReader reader = new TextReader(new org.springframework.core.io.FileSystemResource(filePath));
            List<Document> docs = reader.read();
            docs = denoise(docs);
            docs = splitDocuments(docs);
            addMetadata(docs, filePath);
            vectorStore.add(docs);
            log.info("【ETL】文档导入成功: {} ({} 个文档块)", filePath, docs.size());
            return docs.size();
        } catch (Exception e) {
            log.error("【ETL】文档导入失败: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 搜索知识库文档（带元数据过滤）
     * <p>
     * 提供 API 级别的搜索能力，可用于测试和管理知识库。
     */
    public List<Map<String, Object>> searchDocuments(String query, int topK, String docType) {
        org.springframework.ai.vectorstore.SearchRequest.Builder builder =
            org.springframework.ai.vectorstore.SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(0.3);

        if (docType != null && !docType.isEmpty()) {
            org.springframework.ai.vectorstore.filter.FilterExpressionBuilder fb =
                new org.springframework.ai.vectorstore.filter.FilterExpressionBuilder();
            builder.filterExpression(fb.eq("docType", docType).build());
        }

        List<Document> docs = vectorStore.similaritySearch(builder.build());

        return docs.stream().map(doc -> {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", doc.getId());
            // 优先展示 parentContent（Parent-Child 分块时的父块完整内容）
            String displayText = (String) doc.getMetadata().getOrDefault("parentContent", doc.getText());
            result.put("content", displayText.substring(0, Math.min(200, displayText.length())) + "...");
            result.put("metadata", doc.getMetadata());
            return result;
        }).collect(Collectors.toList());
    }

    /**
     * 获取知识库统计信息
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("knowledgeBaseFiles", knowledgeBaseResources != null ? knowledgeBaseResources.length : 0);
        stats.put("status", "loaded");
        return stats;
    }
}
