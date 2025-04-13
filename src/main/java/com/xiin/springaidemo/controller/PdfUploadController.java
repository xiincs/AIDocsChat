package com.xiin.springaidemo.controller;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.xiin.springaidemo.service.PdfProcessingService;

@RestController
@RequestMapping("/api/pdf")
public class PdfUploadController {

    @Autowired
    private PdfProcessingService pdfProcessingService;

    /**
     * 上传PDF文件并提取文本
     * 
     * @param file 要上传的PDF文件
     * @return 提取的文本内容和处理信息
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadPdf(
            @RequestParam("file") MultipartFile file) {
        
        Map<String, Object> response = new HashMap<>();
        
        // 检查是否为PDF文件
        if (file.isEmpty()) {
            response.put("success", false);
            response.put("message", "请选择一个文件");
            return ResponseEntity.badRequest().body(response);
        }
        
        String contentType = file.getContentType();
        if (contentType == null || !contentType.equals("application/pdf")) {
            response.put("success", false);
            response.put("message", "只支持PDF文件");
            return ResponseEntity.badRequest().body(response);
        }
        
        try {
            // 处理PDF文件并提取文本
            PdfProcessingService.ProcessingResult result = pdfProcessingService.processPdf(file);
            
            response.put("success", true);
            response.put("fileName", file.getOriginalFilename());
            response.put("fileSize", file.getSize());
            response.put("processingMethod", result.getProcessingMethod());
            response.put("textLength", result.getExtractedText().length());
            response.put("text", result.getExtractedText());
            response.put("vectorized", result.isVectorized());
            
            return ResponseEntity.ok(response);
            
        } catch (IOException e) {
            response.put("success", false);
            response.put("message", "处理文件时出错: " + e.getMessage());
            return ResponseEntity.internalServerError().body(response);
        }
    }
} 