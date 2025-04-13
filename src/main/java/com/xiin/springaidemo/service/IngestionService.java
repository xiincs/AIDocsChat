package com.xiin.springaidemo.service;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.ParagraphPdfDocumentReader;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;


@Component
public class IngestionService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private final VectorStore vectorStore;

    @Value("classpath:/docs/星辰科技有限公司企业信息文档简版.pdf")
    private Resource enterpriseInfoPdf;
    
    @Value("${app.document.chunk-size:1000}")
    private int chunkSize;
    
    @Value("${app.document.chunk-overlap:200}")
    private int chunkOverlap;
    
    @Value("${app.document.use-structure:true}")
    private boolean useStructure;
    
    @Value("${app.document.use-ocr:false}")
    private boolean useOcr;
    
    @Value("${app.document.auto-detect:true}")
    private boolean autoDetect;
    
    @Value("${app.document.ocr-language:chi_sim+eng}")
    private String ocrLanguage;
    
    @Value("${app.document.ocr-dpi:300}")
    private int ocrDpi;

    @Value("${app.document.tessdata-path:#{null}}")
    private String tessdataPath;
    
    @Value("${app.document.min-text-length:100}")
    private int minTextLength;

    public IngestionService(VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }

    @Override
    public void run(String... args) throws Exception {
        // 创建临时目录用于处理
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "pdf_process_" + System.currentTimeMillis());
        tempDir.mkdirs();
        
        try {
            // 将PDF文件复制到临时目录
            File tempPdfFile = new File(tempDir, enterpriseInfoPdf.getFilename());
            org.springframework.util.FileCopyUtils.copy(
                enterpriseInfoPdf.getInputStream(),
                new java.io.FileOutputStream(tempPdfFile)
            );
            
            if (autoDetect) {
                // 自动检测PDF类型（文本PDF或扫描版PDF）
                boolean isScannedPdf = detectScannedPdf(tempPdfFile);
                
                if (isScannedPdf) {
                    log.info("检测到扫描版PDF，使用OCR处理");
                    processWithOcr(tempPdfFile);
                } else {
                    log.info("检测到文本PDF，直接提取文本");
                    if (useStructure) {
                        processWithStructure();
                    } else {
                        processWithBruteForce();
                    }
                }
            } else if (useOcr) {
                // 强制使用OCR模式
                log.info("使用OCR处理PDF文档，语言: {}", ocrLanguage);
                processWithOcr(tempPdfFile);
            } else if (useStructure) {
                // 使用结构化方式（基于目录和段落）处理PDF
                processWithStructure();
            } else {
                // 使用暴力分块方式处理PDF
                processWithBruteForce();
            }
        } finally {
            // 清理临时文件
            for (File file : tempDir.listFiles()) {
                file.delete();
            }
            tempDir.delete();
        }
        
        log.info("向量存储已加载数据!");
    }
    
    /**
     * 检测PDF是否为扫描版（图像版）PDF
     * 通过尝试提取文本并检查提取到的文本长度来判断
     */
    private boolean detectScannedPdf(File pdfFile) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            
            // 如果提取到的文本非常少，可能是扫描版PDF
            log.info("从PDF提取到{}个字符", text.length());
            return text.trim().length() < minTextLength;
        }
    }
    
    /**
     * 使用结构化方式处理PDF
     */
    private void processWithStructure() {
        log.info("使用结构化方式处理PDF (基于段落)");
        var pdfReader = new ParagraphPdfDocumentReader(enterpriseInfoPdf);
        // 使用默认分块器
        TextSplitter textSplitter = new TokenTextSplitter();
        vectorStore.accept(textSplitter.apply(pdfReader.get()));
    }
    
    /**
     * 使用暴力分块方式处理PDF
     */
    private void processWithBruteForce() {
        log.info("使用暴力分块方式处理PDF，块大小: {}, 重叠大小: {}", chunkSize, chunkOverlap);
        // 使用PagePdfDocumentReader按页读取PDF
        var pdfReader = new PagePdfDocumentReader(enterpriseInfoPdf);
        
        // 使用配置的TokenTextSplitter进行暴力分块
        TextSplitter textSplitter = new TokenTextSplitter(chunkSize, chunkOverlap, chunkSize / 10, chunkSize * 2, true);
        
        vectorStore.accept(textSplitter.apply(pdfReader.get()));
    }
    
    /**
     * 使用OCR处理PDF文档
     */
    private void processWithOcr(File pdfFile) throws IOException, TesseractException {
        // 使用Tesseract进行OCR处理
        ITesseract tesseract = new Tesseract();
        
        // 设置Tesseract数据目录，优先使用配置的路径
        if (tessdataPath != null && !tessdataPath.isEmpty()) {
            log.info("使用自定义Tesseract数据目录: {}", tessdataPath);
            tesseract.setDatapath(tessdataPath);
        } else {
            // 尝试查找当前工作目录下的tessdata目录
            File tessdataDir = new File("./tessdata");
            if (tessdataDir.exists() && tessdataDir.isDirectory()) {
                log.info("使用当前目录下的tessdata目录: {}", tessdataDir.getAbsolutePath());
                tesseract.setDatapath(tessdataDir.getAbsolutePath());
            } else {
                log.warn("未找到Tesseract数据目录，将使用系统默认设置，可能会导致OCR失败");
            }
        }
        
        tesseract.setLanguage(ocrLanguage);
        
        // 使用Tesseract直接处理PDF文件
        log.info("使用Tesseract OCR处理PDF: {}", pdfFile.getAbsolutePath());
        String ocrResult = tesseract.doOCR(pdfFile);
        
        // 将OCR结果创建为Document
        Document document = new Document(
            ocrResult,
            java.util.Map.of("source", enterpriseInfoPdf.getFilename())
        );
        
        // 进行文本分块
        TextSplitter textSplitter = new TokenTextSplitter(chunkSize, chunkOverlap, chunkSize / 10, chunkSize * 2, true);
        List<Document> documents = new ArrayList<>();
        documents.add(document);
        
        // 将处理后的文档加载到向量存储
        vectorStore.accept(textSplitter.apply(documents));
        
        log.info("OCR处理完成，文本长度: {} 字符", ocrResult.length());
    }
}
