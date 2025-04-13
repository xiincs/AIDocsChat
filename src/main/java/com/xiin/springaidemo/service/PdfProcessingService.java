package com.xiin.springaidemo.service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TextSplitter;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import net.sourceforge.tess4j.ITesseract;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;

@Service
public class PdfProcessingService {

    private static final Logger log = LoggerFactory.getLogger(PdfProcessingService.class);

    @Value("${app.document.auto-detect:true}")
    private boolean autoDetect;

    @Value("${app.document.min-text-length:100}")
    private int minTextLength;

    @Value("${app.document.ocr-language:chi_sim+eng}")
    private String ocrLanguage;

    @Value("${app.document.tessdata-path:#{null}}")
    private String tessdataPath;
    
    @Value("${app.document.chunk-size:1000}")
    private int chunkSize;
    
    @Value("${app.document.chunk-overlap:200}")
    private int chunkOverlap;
    
    @Value("${app.document.auto-vectorize:true}")
    private boolean autoVectorize;

    @Autowired
    private VectorStore vectorStore;

    /**
     * 处理上传的PDF文件，根据内容自动选择处理方式
     *
     * @param file 上传的PDF文件
     * @return 处理结果，包含提取的文本和处理方法
     * @throws IOException 处理过程中的IO异常
     */
    public ProcessingResult processPdf(MultipartFile file) throws IOException {
        // 创建临时文件
        Path tempFilePath = Files.createTempFile("upload_", ".pdf");
        File tempFile = tempFilePath.toFile();
        
        try (FileOutputStream fos = new FileOutputStream(tempFile)) {
            fos.write(file.getBytes());
            
            ProcessingResult result;
            
            if (autoDetect) {
                // 自动检测PDF类型
                boolean isScannedPdf = detectScannedPdf(tempFile);
                
                if (isScannedPdf) {
                    // 扫描版PDF，使用OCR处理
                    log.info("检测到扫描版PDF ({}), 使用OCR处理", file.getOriginalFilename());
                    result = processWithOcr(tempFile);
                } else {
                    // 文本PDF，直接提取文本
                    log.info("检测到文本PDF ({}), 直接提取文本", file.getOriginalFilename());
                    result = processWithPdfBox(tempFile);
                }
            } else {
                // 不自动检测，直接使用PDFBox提取文本
                result = processWithPdfBox(tempFile);
            }
            
            // 如果配置了自动向量化，则处理文本并存入向量数据库
            if (autoVectorize && result != null && result.getExtractedText() != null && !result.getExtractedText().isEmpty()) {
                vectorizeText(result.getExtractedText(), file.getOriginalFilename());
                result.setVectorized(true);
            }
            
            return result;
            
        } catch (Exception e) {
            log.error("处理PDF时出错: {}", e.getMessage(), e);
            throw new IOException("处理PDF时出错: " + e.getMessage(), e);
        } finally {
            // 删除临时文件
            boolean deleted = tempFile.delete();
            if (!deleted) {
                log.warn("无法删除临时文件: {}", tempFile.getAbsolutePath());
                tempFile.deleteOnExit();
            }
        }
    }
    
    /**
     * 处理文本并存入向量数据库
     * 
     * @param text 提取的文本
     * @param filename 文件名
     */
    private void vectorizeText(String text, String filename) {
        try {
            log.info("开始对文本进行分块并存入向量数据库, 文件: {}", filename);
            
            // 创建文档对象
            Document document = new Document(text, Map.of("source", filename));
            List<Document> documents = new ArrayList<>();
            documents.add(document);
            
            // 使用TextSplitter对文本进行分块
            TextSplitter textSplitter = new TokenTextSplitter(
                chunkSize,       // 块大小
                chunkOverlap,    // 块重叠
                chunkSize / 10,  // 最小块大小
                chunkSize * 2,   // 最大块大小
                true             // 保留分隔符
            );
            
            // 对文本进行分块
            List<Document> chunks = textSplitter.apply(documents);
            
            log.info("文本已分割成{}个块", chunks.size());
            
            // 将分块后的文档存入向量数据库
            vectorStore.accept(chunks);
            
            log.info("文本成功存入向量数据库");
            
        } catch (Exception e) {
            log.error("将文本存入向量数据库时出错: {}", e.getMessage(), e);
        }
    }

    /**
     * 检测PDF是否为扫描版（图像版）PDF
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
     * 使用PDFBox直接提取文本
     */
    private ProcessingResult processWithPdfBox(File pdfFile) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            
            return new ProcessingResult("TEXT_EXTRACTION", text);
        }
    }

    /**
     * 使用OCR处理PDF
     */
    private ProcessingResult processWithOcr(File pdfFile) throws IOException {
        try {
            // 使用Tesseract进行OCR处理
            ITesseract tesseract = new Tesseract();
            
            // 设置Tesseract数据目录
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
                    log.warn("未找到Tesseract数据目录，将使用系统默认设置");
                }
            }
            
            tesseract.setLanguage(ocrLanguage);
            
            // 执行OCR识别
            String ocrResult = tesseract.doOCR(pdfFile);
            return new ProcessingResult("OCR", ocrResult);
        } catch (TesseractException e) {
            log.error("OCR处理失败: {}", e.getMessage(), e);
            throw new IOException("OCR处理失败: " + e.getMessage(), e);
        }
    }

    /**
     * PDF处理结果类
     */
    public static class ProcessingResult {
        private final String processingMethod;
        private final String extractedText;
        private boolean vectorized = false;
        private int chunkCount = 0;

        public ProcessingResult(String processingMethod, String extractedText) {
            this.processingMethod = processingMethod;
            this.extractedText = extractedText;
        }

        public String getProcessingMethod() {
            return processingMethod;
        }

        public String getExtractedText() {
            return extractedText;
        }
        
        public boolean isVectorized() {
            return vectorized;
        }
        
        public void setVectorized(boolean vectorized) {
            this.vectorized = vectorized;
        }
        
        public int getChunkCount() {
            return chunkCount;
        }
        
        public void setChunkCount(int chunkCount) {
            this.chunkCount = chunkCount;
        }
    }
} 