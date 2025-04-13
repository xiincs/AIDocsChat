#!/bin/bash
echo "=================================="
echo "Tesseract训练数据下载工具"
echo "=================================="

# 创建tessdata目录
mkdir -p tessdata
cd tessdata

echo "正在下载简体中文训练数据 (chi_sim.traineddata)..."
curl -L -o chi_sim.traineddata https://github.com/tesseract-ocr/tessdata/raw/main/chi_sim.traineddata

echo "正在下载英文训练数据 (eng.traineddata)..."
curl -L -o eng.traineddata https://github.com/tesseract-ocr/tessdata/raw/main/eng.traineddata

echo "下载完成！"
echo "训练数据已保存至 $(pwd) 目录" 