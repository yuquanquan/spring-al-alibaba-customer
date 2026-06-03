package com.example.smartcs.service;

import com.itextpdf.text.*;
import com.itextpdf.text.pdf.BaseFont;
import com.itextpdf.text.pdf.PdfPCell;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfWriter;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTblWidth;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTblWidth;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;

/**
 * 文档生成服务
 * <p>
 * 生成两种格式的文档：
 * 1. Word (.docx): "订单说明书" - 使用 Apache POI
 * 2. PDF (.pdf): "退货说明书" (含图片) - 使用 iText
 */
@Slf4j
@Service
public class DocumentGenerator {

    @Value("${app.doc-output-dir:./output}")
    private String outputDir;

    // ========================
    // Word 文档生成 (Apache POI)
    // ========================

    /**
     * 生成"订单说明书" Word文档
     * <p>
     * 技术栈: Apache POI (XWPF)
     * 包含: 标题、正文、表格、页脚
     *
     * @return 生成的文件路径
     */
    public String generateOrderManualWord() throws IOException {
        log.info("【文档生成】开始生成订单说明书(Word)...");

        try (XWPFDocument document = new XWPFDocument()) {

            // ---- 标题 ----
            XWPFParagraph title = document.createParagraph();
            title.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun titleRun = title.createRun();
            titleRun.setText("智能客服系统 - 订单说明书");
            titleRun.setBold(true);
            titleRun.setFontSize(22);
            titleRun.setFontFamily("微软雅黑");

            // ---- 版本信息 ----
            addParagraph(document, "版本: V1.0", 12, false, ParagraphAlignment.CENTER);
            addParagraph(document, "日期: " + LocalDate.now(), 12, false, ParagraphAlignment.CENTER);
            addParagraph(document, "", 8, false, ParagraphAlignment.LEFT);

            // ---- 1. 概述 ----
            addHeading(document, "1. 概述", 16);
            addParagraph(document,
                "本文档详细介绍智能客服系统的订单管理功能，包括订单创建、查询、" +
                "修改、退款等操作流程，帮助用户全面了解订单管理能力。", 12, false, ParagraphAlignment.LEFT);

            // ---- 2. 订单状态说明 ----
            addHeading(document, "2. 订单状态说明", 16);
            addParagraph(document, "系统订单包含以下状态流转：", 12, false, ParagraphAlignment.LEFT);

            String[][] statusData = {
                {"状态", "说明", "允许操作"},
                {"PENDING（待支付）", "订单已创建，等待用户支付", "支付、取消"},
                {"PAID（已支付）", "用户已完成支付", "发货、退款"},
                {"SHIPPED（已发货）", "商品已发出", "确认收货、退货"},
                {"COMPLETED（已完成）", "订单已完成", "评价、售后"},
                {"REFUNDING（退款中）", "退款申请处理中", "等待审核"},
                {"REFUNDED（已退款）", "退款已完成", "无"},
                {"CANCELLED（已取消）", "订单已取消", "无"}
            };
            addTable(document, statusData);

            // ---- 3. 产品列表 ----
            addHeading(document, "3. 可购买的产品", 16);
            String[][] productData = {
                {"产品ID", "产品名称", "价格", "说明"},
                {"P001", "智能客服基础版", "¥99/月", "基础问答功能，适合个人使用"},
                {"P002", "智能客服企业版", "¥499/月", "完整功能+多坐席，适合企业"},
                {"P003", "云存储100GB", "¥199/年", "知识库文档存储空间"},
                {"P004", "自定义域名", "¥99/年", "绑定自己的域名"},
                {"P005", "多语言支持包", "¥299/年", "支持中英日韩四种语言"},
                {"P006", "私有化部署", "¥12999/次", "部署到客户自己的服务器"}
            };
            addTable(document, productData);

            // ---- 4. 常见问题 ----
            addHeading(document, "4. 订单常见问题(FAQ)", 16);

            String[][] faqData = {
                {"Q: 如何查看我的订单？", "A: 登录系统后，在「我的订单」页面可查看所有订单。也可以直接询问客服：\"查看我的订单\"。"},
                {"Q: 订单可以取消吗？", "A: 待支付状态的订单可以直接取消。已支付的订单需要走退款流程。"},
                {"Q: 退款多久到账？", "A: 退款申请通过后，1-5个工作日内原路退回。"},
                {"Q: 如何修改订单？", "A: 待支付订单可以修改商品信息，已支付订单无法修改，需退款后重新下单。"},
                {"Q: 支持哪些支付方式？", "A: 支持支付宝、微信支付、银行转账、企业对公转账。"}
            };

            for (String[] faq : faqData) {
                addParagraph(document, faq[0], 12, true, ParagraphAlignment.LEFT);
                addParagraph(document, "  " + faq[1], 11, false, ParagraphAlignment.LEFT);
            }

            // ---- 页脚 ----
            addParagraph(document, "", 8, false, ParagraphAlignment.LEFT);
            addParagraph(document, "如有疑问，请联系客服: support@smart-cs.com", 10, false, ParagraphAlignment.CENTER);

            // ---- 保存文件 ----
            Path outputPath = ensureOutputDir().resolve("订单说明书.docx");
            try (FileOutputStream fos = new FileOutputStream(outputPath.toFile())) {
                document.write(fos);
            }

            log.info("【文档生成】订单说明书已生成: {}", outputPath.toAbsolutePath());
            return outputPath.toAbsolutePath().toString();
        }
    }

    // ========================
    // PDF 文档生成 (iText)
    // ========================

    /**
     * 生成"退货说明书" PDF文档（含图片）
     * <p>
     * 技术栈: iText 5 + iText-Asian (中文字体)
     * 包含: 标题、正文、表格、图片、流程图
     *
     * @return 生成的文件路径
     */
    public String generateReturnManualPdf() throws Exception {
        log.info("【文档生成】开始生成退货说明书(PDF)...");

        // 创建 PDF 文档
        Document document = new Document(PageSize.A4);
        Path outputPath = ensureOutputDir().resolve("退货说明书.pdf");

        PdfWriter writer = PdfWriter.getInstance(document, new FileOutputStream(outputPath.toFile()));
        document.open();

        // ---- 中文字体配置 ----
        // iText-Asian 提供的中文字体 (STSong-Light)
        BaseFont bfChinese = BaseFont.createFont("STSong-Light", "UniGB-UCS2-H", BaseFont.NOT_EMBEDDED);
        Font titleFont = new Font(bfChinese, 22, Font.BOLD);
        Font headingFont = new Font(bfChinese, 16, Font.BOLD);
        Font bodyFont = new Font(bfChinese, 12, Font.NORMAL);
        Font smallFont = new Font(bfChinese, 10, Font.NORMAL);
        Font boldFont = new Font(bfChinese, 12, Font.BOLD);

        // ---- 标题 ----
        Paragraph title = new Paragraph("智能客服系统 - 退货说明书", titleFont);
        title.setAlignment(Element.ALIGN_CENTER);
        title.setSpacingAfter(5f);
        document.add(title);

        // ---- 副标题 ----
        Paragraph subtitle = new Paragraph("版本: V1.0 | 日期: " + LocalDate.now(), smallFont);
        subtitle.setAlignment(Element.ALIGN_CENTER);
        subtitle.setSpacingAfter(20f);
        document.add(subtitle);

        // ---- 1. 退货政策概述 ----
        document.add(new Paragraph("1. 退货政策概述", headingFont));
        document.add(new Paragraph(" ", bodyFont));
        document.add(new Paragraph(
            "为保障消费者权益，智能客服系统提供完善的退货退款服务。" +
            "以下是我们的退货政策要点：", bodyFont));
        document.add(new Paragraph(" ", bodyFont));

        // 退货条件表格
        PdfPTable table = new PdfPTable(3);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{2f, 3f, 3f});

        addPdfTableHeader(table, "条件", "说明", "备注", bfChinese);
        addPdfTableRow(table, "退货时限", "购买后7天内", "超过7天需走售后流程", bfChinese);
        addPdfTableRow(table, "商品状态", "未使用、未拆封（实体商品）", "虚拟商品不支持退货", bfChinese);
        addPdfTableRow(table, "退款方式", "原路退回", "1-5个工作日到账", bfChinese);
        addPdfTableRow(table, "运费承担", "质量问题卖家承担", "非质量问题买家承担", bfChinese);

        document.add(table);
        document.add(new Paragraph(" ", bodyFont));

        // ---- 2. 退货流程 ----
        document.add(new Paragraph("2. 退货流程", headingFont));
        document.add(new Paragraph(" ", bodyFont));

        // 流程步骤表格
        PdfPTable flowTable = new PdfPTable(2);
        flowTable.setWidthPercentage(100);
        flowTable.setWidths(new float[]{1f, 4f});

        addPdfTableHeader(flowTable, "步骤", "详细说明", bfChinese);
        addPdfTableRow(flowTable, "第1步", "联系客服：通过在线客服或电话提交退货申请", bfChinese);
        addPdfTableRow(flowTable, "第2步", "填写退货单：在系统中填写退货原因和商品信息", bfChinese);
        addPdfTableRow(flowTable, "第3步", "商家审核：1-3个工作日内审核退货申请", bfChinese);
        addPdfTableRow(flowTable, "第4步", "寄回商品：审核通过后，按退货地址寄回商品", bfChinese);
        addPdfTableRow(flowTable, "第5步", "退款处理：收到退货商品后，1-5个工作日退款", bfChinese);

        document.add(flowTable);
        document.add(new Paragraph(" ", bodyFont));

        // ---- 3. 退货流程图 (可视化) ----
        document.add(new Paragraph("3. 退货流程图", headingFont));
        document.add(new Paragraph(" ", bodyFont));

        // 使用 iText 绘制简易流程图
        PdfPTable flowChart = new PdfPTable(1);
        flowChart.setWidthPercentage(80);
        flowChart.setHorizontalAlignment(Element.ALIGN_CENTER);

        String[] steps = {
            "┌─────────────────────────┐\n│   提交退货申请           │\n└────────────┬────────────┘",
            "              ▼",
            "┌─────────────────────────┐\n│   填写退货表单           │\n└────────────┬────────────┘",
            "              ▼",
            "┌─────────────────────────┐\n│   商家审核 (1-3工作日)   │\n└──────┬──────────┬───────┘",
            "     通过 ▼        ▼ 拒绝",
            "┌───────────┐  ┌──────────────┐\n│ 寄回商品   │  │ 联系客服协商 │\n└─────┬─────┘  └──────────────┘",
            "      ▼",
            "┌─────────────────────────┐\n│   退款到账 (1-5工作日)   │\n└─────────────────────────┘"
        };

        for (String step : steps) {
            PdfPCell cell = new PdfPCell(new Phrase(step, new Font(bfChinese, 10)));
            cell.setBorder(Rectangle.NO_BORDER);
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            cell.setPadding(3f);
            flowChart.addCell(cell);
        }
        document.add(flowChart);
        document.add(new Paragraph(" ", bodyFont));

        // ---- 4. 退货示意图 ----
        document.add(new Paragraph("4. 退货注意事项（图示）", headingFont));
        document.add(new Paragraph(" ", bodyFont));

        // 生成一张简易示意图并嵌入PDF
        Path imagePath = generateReturnFlowImage();
        if (imagePath != null && Files.exists(imagePath)) {
            try {
                Image img = Image.getInstance(imagePath.toAbsolutePath().toString());
                img.scaleToFit(450f, 300f);
                img.setAlignment(Element.ALIGN_CENTER);
                document.add(img);
                document.add(new Paragraph("图: 退货流程示意图", smallFont));
            } catch (Exception e) {
                log.warn("【文档生成】图片嵌入失败: {}", e.getMessage());
                document.add(new Paragraph("（图片加载失败，请参考上方文字说明）", bodyFont));
            }
        }
        document.add(new Paragraph(" ", bodyFont));

        // ---- 5. 注意事项 ----
        document.add(new Paragraph("5. 注意事项", headingFont));
        document.add(new Paragraph(" ", bodyFont));

        String[] notes = {
            "1. 请保留商品原包装和配件，退货时需一并寄回",
            "2. 退货商品需保持完好，不影响二次销售",
            "3. 定制类商品和虚拟商品不支持退货",
            "4. 退货运费建议使用有追踪号的快递方式",
            "5. 退款将在收到退货商品并验收后处理",
            "6. 如对退货进度有疑问，可随时联系在线客服查询"
        };

        for (String note : notes) {
            document.add(new Paragraph("• " + note, bodyFont));
            document.add(new Paragraph(" ", smallFont));
        }

        // ---- 页脚 ----
        document.add(new Paragraph(" ", bodyFont));
        Paragraph footer = new Paragraph("如有疑问，请联系客服: support@smart-cs.com | 电话: 400-888-8888", smallFont);
        footer.setAlignment(Element.ALIGN_CENTER);
        document.add(footer);

        // ---- 关闭文档 ----
        document.close();
        writer.close();

        log.info("【文档生成】退货说明书已生成: {}", outputPath.toAbsolutePath());
        return outputPath.toAbsolutePath().toString();
    }

    /**
     * 生成退货流程示意图（简易 PNG）
     * 使用 Java AWT 绘制流程图
     */
    private Path generateReturnFlowImage() {
        try {
            int width = 500, height = 280;
            java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g2d = image.createGraphics();

            // 背景
            g2d.setColor(java.awt.Color.WHITE);
            g2d.fillRect(0, 0, width, height);

            // 绘制流程框
            g2d.setColor(new java.awt.Color(70, 130, 180));
            g2d.setFont(new java.awt.Font("SimHei", java.awt.Font.BOLD, 13));

            int boxW = 120, boxH = 36, gap = 30;
            int startX = 30, y = 30;

            String[] labels = {"提交申请", "商家审核", "寄回商品", "退款到账"};
            int[] xs = {startX, startX + boxW + gap, startX + (boxW + gap) * 2, startX + (boxW + gap) * 3};

            for (int i = 0; i < labels.length; i++) {
                // 圆角矩形
                g2d.fillRoundRect(xs[i], y, boxW, boxH, 10, 10);
                // 白色文字
                g2d.setColor(java.awt.Color.WHITE);
                int textWidth = g2d.getFontMetrics().stringWidth(labels[i]);
                g2d.drawString(labels[i], xs[i] + (boxW - textWidth) / 2, y + boxH / 2 + 5);
                g2d.setColor(new java.awt.Color(70, 130, 180));

                // 箭头
                if (i < labels.length - 1) {
                    int arrowX = xs[i] + boxW;
                    int arrowY = y + boxH / 2;
                    g2d.drawLine(arrowX, arrowY, arrowX + gap, arrowY);
                    g2d.fillPolygon(
                        new int[]{arrowX + gap - 8, arrowX + gap, arrowX + gap - 8},
                        new int[]{arrowY - 5, arrowY, arrowY + 5}, 3);
                }
            }

            // 第二行: 分支
            int y2 = y + boxH + 60;
            g2d.setColor(new java.awt.Color(220, 80, 80));
            g2d.fillRoundRect(xs[1], y2, boxW, boxH, 10, 10);
            g2d.setColor(java.awt.Color.WHITE);
            String rejectLabel = "审核拒绝";
            int tw = g2d.getFontMetrics().stringWidth(rejectLabel);
            g2d.drawString(rejectLabel, xs[1] + (boxW - tw) / 2, y2 + boxH / 2 + 5);

            // 连接线
            g2d.setColor(new java.awt.Color(220, 80, 80));
            g2d.drawLine(xs[1] + boxW / 2, y + boxH, xs[1] + boxW / 2, y2);

            // 说明文字
            g2d.setColor(new java.awt.Color(100, 100, 100));
            g2d.setFont(new java.awt.Font("SimSun", java.awt.Font.PLAIN, 11));
            g2d.drawString("审核拒绝 → 联系客服协商", xs[1] + boxW + 10, y2 + boxH / 2 + 4);

            // 标题
            g2d.setColor(new java.awt.Color(50, 50, 50));
            g2d.setFont(new java.awt.Font("SimHei", java.awt.Font.BOLD, 16));
            g2d.drawString("退货流程示意图", width / 2 - 70, height - 20);

            g2d.dispose();

            // 保存图片
            Path imagePath = ensureOutputDir().resolve("return-flow.png");
            javax.imageio.ImageIO.write(image, "png", imagePath.toFile());
            return imagePath;

        } catch (Exception e) {
            log.warn("【文档生成】流程图生成失败: {}", e.getMessage());
            return null;
        }
    }

    // ========================
    // Word 辅助方法
    // ========================

    private void addHeading(XWPFDocument doc, String text, int fontSize) {
        XWPFParagraph heading = doc.createParagraph();
        heading.setSpacingBefore(200);
        XWPFRun run = heading.createRun();
        run.setText(text);
        run.setBold(true);
        run.setFontSize(fontSize);
        run.setFontFamily("微软雅黑");
    }

    private void addParagraph(XWPFDocument doc, String text, int fontSize, boolean bold,
                              ParagraphAlignment alignment) {
        XWPFParagraph para = doc.createParagraph();
        para.setAlignment(alignment);
        XWPFRun run = para.createRun();
        run.setText(text);
        run.setFontSize(fontSize);
        run.setBold(bold);
        run.setFontFamily("微软雅黑");
    }

    private void addTable(XWPFDocument doc, String[][] data) {
        XWPFTable table = doc.createTable(data.length, data[0].length);

        // 设置表格宽度为页面宽度
        CTTblWidth tableWidth = table.getCTTbl().addNewTblPr().addNewTblW();
        tableWidth.setType(STTblWidth.PCT);
        tableWidth.setW(java.math.BigInteger.valueOf(5000));

        for (int i = 0; i < data.length; i++) {
            for (int j = 0; j < data[i].length; j++) {
                XWPFTableCell cell = table.getRow(i).getCell(j);
                cell.setText(data[i][j]);

                // 表头加粗 + 背景色
                if (i == 0) {
                    cell.setColor("D9E2F3");
                    for (XWPFParagraph p : cell.getParagraphs()) {
                        for (XWPFRun r : p.getRuns()) {
                            r.setBold(true);
                        }
                    }
                }
            }
        }
    }

    // ========================
    // PDF 辅助方法
    // ========================

    private void addPdfTableHeader(PdfPTable table, String[] headers, BaseFont bf) {
        Font headerFont = new Font(bf, 12, Font.BOLD, BaseColor.WHITE);
        for (String header : headers) {
            PdfPCell cell = new PdfPCell(new Phrase(header, headerFont));
            cell.setBackgroundColor(new BaseColor(70, 130, 180));
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            cell.setPadding(6f);
            table.addCell(cell);
        }
    }

    private void addPdfTableRow(PdfPTable table, String[] values, BaseFont bf) {
        Font bodyFont = new Font(bf, 11, Font.NORMAL);
        for (String value : values) {
            PdfPCell cell = new PdfPCell(new Phrase(value, bodyFont));
            cell.setPadding(5f);
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            table.addCell(cell);
        }
    }

    // ========================
    // 公共辅助方法
    // ========================

    private Path ensureOutputDir() throws IOException {
        Path dir = Paths.get(outputDir);
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
        return dir;
    }
}
