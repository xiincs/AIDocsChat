package com.xiin.springaidemo.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class WebController {

    /**
     * 根路径重定向到首页
     */
    @GetMapping("/")
    public String index() {
        return "redirect:/index.html";
    }
} 